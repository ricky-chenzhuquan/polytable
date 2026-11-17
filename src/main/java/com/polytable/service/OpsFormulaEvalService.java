package com.polytable.service;

import com.polytable.repository.OpsFieldConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polytable.formula.FormulaAst;
import com.polytable.formula.FormulaEvaluator;
import com.polytable.formula.FormulaEvaluationContext;
import com.polytable.formula.FormulaFunctionRegistry;
import com.polytable.formula.FormulaParser;
import com.polytable.formula.FormulaValue;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文备注：协作表公式只读求值（与 ops/index.html evalFormula* 对齐的子集）。
 * 列表响应填充 {@code formulaValues}；含快路径 / 表达式编译缓存 / 大行数并行 enrich。
 */
@Service
public class OpsFormulaEvalService {

    private static final Pattern INNERMOST_CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern BITABLE_REF = Pattern.compile("bitable::\\$t\\[\"([^\"]+)\"]");
    private static final Pattern FIELD_REF = Pattern.compile("\\[([^\\]]+)]");
    /** 中文备注：字段替换后常见 `" + "-" + "番茄"`，须允许引号与 + 之间有空格（对齐前端 looksUnevaluated）。 */
    private static final Pattern UNEVALUATED_STRING_CONCAT = Pattern.compile("\"\\s*\\+|\\+\\s*\"");
    /** 中文备注：≥512 行并行 enrich，小表单线程避免 fork/join 开销。 */
    private static final int PARALLEL_ROW_THRESHOLD = 512;

    private static final Map<String, String> SYSTEM_FIELD_LABELS = Map.ofEntries(
            Map.entry("剧目类型", "dramaTypeLabel"),
            Map.entry("版权", "copyrightOwner"),
            Map.entry("book_id", "bookId"),
            Map.entry("bookId", "bookId"),
            Map.entry("预估发布时间", "publishTime"),
            Map.entry("总集数", "episodeAmount"),
            Map.entry("最终评级", "finalRating"),
            Map.entry("素材数", "materialCount"),
            Map.entry("网盘链接", "cloudDiskUrl"),
            Map.entry("优先级", "priorityLevel"),
            Map.entry("更新时间", "crawlUpdateTime")
    );

    private final OpsFieldConfigRepository fieldConfigRepository;
    private volatile List<FormulaFieldSpec> formulaFieldCache = List.of();
    private volatile long formulaFieldCacheAtMs = 0L;
    private volatile EnrichContext enrichContextCache = EnrichContext.EMPTY;
    private static final long FORMULA_FIELD_CACHE_TTL_MS = 60_000L;
    private static final FormulaParser FORMULA_PARSER = new FormulaParser();
    private static final FormulaFunctionRegistry FORMULA_FUNCTIONS = new FormulaFunctionRegistry();
    private static final FormulaEvaluator FORMULA_EVALUATOR = new FormulaEvaluator(FORMULA_FUNCTIONS);
    private static final List<Map<String, Object>> FORMULA_FUNCTION_CATALOG = loadFormulaFunctionCatalog();

    public OpsFormulaEvalService(OpsFieldConfigRepository fieldConfigRepository) {
        this.fieldConfigRepository = fieldConfigRepository;
    }

    /** 为每行写入 formulaValues（fieldKey → 展示字符串），前端只读展示、滚动不算公式。 */
    /** 中文备注：行级公式物化——结果按 (公式版本|上下文|行标识) 缓存,引用输入未变的行直接复用,
     *  只有输入变化的行才重算。写入序 LRU,上限 15 万行条目(约 40MB)。 */
    /** 中文备注：并发安全 + 容量超限整体清空——并行流下避免全局锁竞争(access-order LRU 的 get 也是写)。 */
    private final Map<String, CachedRowFormulas> rowFormulaResultCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int ROW_FORMULA_CACHE_MAX_ENTRIES = 150_000;

    private volatile String formulaVersionMemo = "";

    private String formulaVersionCached() {
        String v = formulaVersionMemo;
        if (v == null || v.isBlank()) {
            v = formulaMetadataVersion();
            formulaVersionMemo = v;
        }
        return v;
    }

    /** 引用字段键(含公式间引用的传递展开):fastPath 取 sourceFieldKey,表达式取 [引用] 解析后的键。 */
    private java.util.Set<String> referencedRowKeys(EnrichContext ctx) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (CompiledFormula f : ctx.formulas()) {
            queue.add(f.fieldKey());
        }
        java.util.Set<String> seenFormulas = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (key == null || !seenFormulas.add(key)) continue;
            CompiledFormula f = ctx.compiledByKey().get(key);
            if (f != null && f.fastPath() != FastPathKind.NONE) {
                if (!f.sourceFieldKey().isBlank()) out.add(f.sourceFieldKey());
                continue;
            }
            String expr = f == null ? "" : f.normalizedExpr();
            java.util.regex.Matcher m = FIELD_REF.matcher(expr == null ? "" : expr);
            while (m.find()) {
                String ref = resolveFieldKey(m.group(1), ctx.titleToKey());
                if (ref == null || ref.isBlank()) continue;
                if (ctx.compiledByKey().containsKey(ref)) {
                    queue.add(ref); // 公式间引用:展开其输入
                } else {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    private static String rowIdentity(Map<String, Object> row) {
        for (String k : new String[]{"bookId", "opsKey", "rowId", "row_id"}) {
            Object v = row.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }

    private static String rowFingerprint(Map<String, Object> row, java.util.Set<String> refKeys) {
        StringBuilder sb = new StringBuilder(64);
        for (String k : refKeys) {
            Object v = row.get(k);
            sb.append(k).append('=').append(v == null ? "\u0000" : String.valueOf(v)).append('\u0001');
        }
        return sb.toString();
    }

    private void enrichRowCached(Map<String, Object> row, EnrichContext ctx,
                                 java.util.Set<String> refKeys, String version, int ctxSig) {
        String id = rowIdentity(row);
        if (id == null) {
            enrichRow(row, ctx);
            return;
        }
        String fp = rowFingerprint(row, refKeys);
        String key = version + "|" + ctxSig + "|" + id;
        CachedRowFormulas cached = rowFormulaResultCache.get(key);
        if (cached != null && cached.fingerprint().equals(fp)) {
            row.put("formulaValues", cached.values());
            return;
        }
        enrichRow(row, ctx);
        Object values = row.get("formulaValues");
        if (values instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            if (rowFormulaResultCache.size() >= ROW_FORMULA_CACHE_MAX_ENTRIES) {
                rowFormulaResultCache.clear();
            }
            rowFormulaResultCache.put(key, new CachedRowFormulas(fp, copy));
        }
    }

    private void enrichRowsCached(List<Map<String, Object>> rows, EnrichContext ctx) {
        if (ctx.formulas().isEmpty()) {
            rows.forEach(row -> { if (row != null) row.put("formulaValues", Map.of()); });
            return;
        }
        java.util.Set<String> refKeys = referencedRowKeys(ctx);
        String version = formulaVersionCached();
        int ctxSig = ctx.formulas().hashCode();
        if (rows.size() >= PARALLEL_ROW_THRESHOLD) {
            rows.parallelStream().forEach(row -> enrichRowCached(row, ctx, refKeys, version, ctxSig));
        } else {
            for (Map<String, Object> row : rows) {
                enrichRowCached(row, ctx, refKeys, version, ctxSig);
            }
        }
    }

    public void enrichFormulaValues(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        enrichRowsCached(rows, enrichContext());
    }

    /**
     * 为协作表按本表字段定义计算公式；这些字段定义来自当前请求的元数据，公式仍只在服务端求值。
     */
    public void enrichFormulaValues(List<Map<String, Object>> rows,
                                    List<? extends Map<String, ?>> fieldMetas) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        enrichRowsCached(rows, contextFromFieldMetas(fieldMetas));
    }

    /** 服务端公式预览使用与列表相同的解析器和字段引用规则。 */
    public String previewFormula(String expression,
                                 String fieldKey,
                                 Map<String, Object> sampleRow,
                                 List<? extends Map<String, ?>> fieldMetas) {
        String key = trimToEmpty(fieldKey);
        String title = key;
        Map<String, String> titleToKey = new LinkedHashMap<>(SYSTEM_FIELD_LABELS);
        List<FormulaFieldSpec> specs = new ArrayList<>();
        boolean previewIncluded = false;
        if (fieldMetas != null) {
            for (Map<String, ?> field : fieldMetas) {
                if (field == null) continue;
                String fieldKeyValue = trimToEmpty(field.containsKey("fieldKey") ? field.get("fieldKey") : field.get("key"));
                String fieldTitle = trimToEmpty(field.containsKey("fieldTitle") ? field.get("fieldTitle") : field.get("title"));
                String type = trimToEmpty(field.containsKey("fieldType") ? field.get("fieldType") : field.get("type"));
                String expr = trimToEmpty(field.containsKey("defaultValue") ? field.get("defaultValue") : field.get("formula"));
                if (!fieldKeyValue.isBlank()) {
                    titleToKey.putIfAbsent(fieldKeyValue, fieldKeyValue);
                    if (!fieldTitle.isBlank()) titleToKey.putIfAbsent(fieldTitle, fieldKeyValue);
                }
                if (fieldKeyValue.equals(key)) {
                    if (!fieldTitle.isBlank()) title = fieldTitle;
                    specs.add(new FormulaFieldSpec(key, title, expression));
                    previewIncluded = true;
                } else if ("formula".equalsIgnoreCase(type) && !fieldKeyValue.isBlank() && !expr.isBlank()) {
                    specs.add(new FormulaFieldSpec(fieldKeyValue, fieldTitle, expr));
                }
            }
        }
        if (!previewIncluded) specs.add(new FormulaFieldSpec(key, title, expression));
        EnrichContext ctx = EnrichContext.compile(specs, Map.copyOf(titleToKey));
        Map<String, String> cache = new HashMap<>();
        return evalExpression(expression, sampleRow == null ? Map.of() : sampleRow,
                key, ctx, List.of(), cache);
    }

    private EnrichContext contextFromFieldMetas(List<? extends Map<String, ?>> fieldMetas) {
        if (fieldMetas == null || fieldMetas.isEmpty()) return EnrichContext.EMPTY;
        Map<String, String> titleToKey = new LinkedHashMap<>(SYSTEM_FIELD_LABELS);
        List<FormulaFieldSpec> specs = new ArrayList<>();
        for (Map<String, ?> field : fieldMetas) {
            if (field == null) continue;
            String key = trimToEmpty(field.containsKey("fieldKey") ? field.get("fieldKey") : field.get("key"));
            String title = trimToEmpty(field.containsKey("fieldTitle") ? field.get("fieldTitle") : field.get("title"));
            String type = trimToEmpty(field.containsKey("fieldType") ? field.get("fieldType") : field.get("type"));
            String expr = trimToEmpty(field.containsKey("defaultValue") ? field.get("defaultValue") : field.get("formula"));
            if (!key.isBlank()) {
                titleToKey.putIfAbsent(key, key);
                if (!title.isBlank()) titleToKey.putIfAbsent(title, key);
            }
            if ("formula".equalsIgnoreCase(type) && !key.isBlank() && !expr.isBlank()) {
                specs.add(new FormulaFieldSpec(key, title, expr));
            }
        }
        return EnrichContext.compile(specs, Map.copyOf(titleToKey));
    }

    /** Sorts already-computed formula values on the server; blank values remain last in either direction. */
    public boolean sortRowsByFormulaField(List<Map<String, Object>> rows, String fieldKey, String direction) {
        if (rows == null || rows.isEmpty() || fieldKey == null || fieldKey.isBlank()) {
            return false;
        }
        boolean hasFormulaField = rows.stream().anyMatch(row -> formulaValues(row).containsKey(fieldKey));
        if (!hasFormulaField) {
            return false;
        }
        if (rows.size() < 2) {
            return true;
        }

        boolean ascending = !"desc".equalsIgnoreCase(direction);
        List<FormulaSortRow> decorated = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object rawValue = formulaValues(row).get(fieldKey);
            String text = rawValue == null ? "" : String.valueOf(rawValue).trim();
            boolean blank = text.isBlank();
            decorated.add(new FormulaSortRow(
                    row,
                    blank,
                    blank ? null : parseFormulaNumber(text),
                    text));
        }
        decorated.sort((left, right) -> {
            if (left.blank() != right.blank()) {
                return left.blank() ? 1 : -1;
            }
            if (left.blank()) {
                return 0;
            }
            if (left.number() != null && right.number() != null) {
                return ascending
                        ? left.number().compareTo(right.number())
                        : right.number().compareTo(left.number());
            }
            return ascending
                    ? left.text().compareToIgnoreCase(right.text())
                    : right.text().compareToIgnoreCase(left.text());
        });
        for (int i = 0; i < decorated.size(); i++) {
            rows.set(i, decorated.get(i).row());
        }
        return true;
    }

    private static Map<?, ?> formulaValues(Map<String, Object> row) {
        if (row == null || !(row.get("formulaValues") instanceof Map<?, ?> values)) {
            return Map.of();
        }
        return values;
    }

    private static BigDecimal parseFormulaNumber(String value) {
        try {
            String normalized = value.replace(",", "").trim();
            return normalized.isEmpty() ? null : new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void enrichRow(Map<String, Object> row, EnrichContext ctx) {
        if (row == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> fieldCache = new HashMap<>(ctx.compiledByKey().size() * 2 + 1);
        for (CompiledFormula formula : ctx.formulas()) {
            String val = evalCompiled(formula, row, ctx, List.of(), fieldCache);
            fieldCache.put(formula.fieldKey(), val == null ? "" : val);
            values.put(formula.fieldKey(), val == null ? "" : val);
        }
        row.put("formulaValues", values);
    }

    private String evalCompiled(CompiledFormula formula,
                                Map<String, Object> row,
                                EnrichContext ctx,
                                List<String> stack,
                                Map<String, String> fieldCache) {
        if (formula.fastPath() != FastPathKind.NONE) {
            return formatNumber(parseDisplayPlayCount(rowFieldRawValue(row, formula.sourceFieldKey())));
        }
        return evalExpression(
                formula.normalizedExpr(),
                row,
                formula.fieldKey(),
                ctx,
                stack,
                fieldCache);
    }

    EnrichContext enrichContext() {
        cachedFormulaFields();
        return enrichContextCache;
    }

    /** Stable content version for full query snapshot keys; hashes formulas without exposing their text. */
    public String formulaMetadataVersion() {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(cachedFormulaFields().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Function names exported to the existing formula editor from the evaluator registry. */
    public List<String> formulaFunctionNames() {
        return FORMULA_FUNCTIONS.names();
    }

    public List<Map<String, Object>> formulaFunctionCatalog() {
        return FORMULA_FUNCTION_CATALOG;
    }

    private static List<Map<String, Object>> loadFormulaFunctionCatalog() {
        try (var input = OpsFormulaEvalService.class.getResourceAsStream("/formula/bitable-formula-catalog.json")) {
            if (input == null) return List.of();
            JsonNode root = new ObjectMapper().readTree(input);
            Map<String, JsonNode> byName = new HashMap<>();
            root.path("functions").forEach(function -> byName.put(function.path("name").asText().toUpperCase(Locale.ROOT), function));
            List<Map<String, Object>> catalog = new ArrayList<>();
            for (String name : FORMULA_FUNCTIONS.names()) {
                JsonNode entry = byName.get(name.toUpperCase(Locale.ROOT));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                if (entry == null && "CONTAINS".equals(name)) {
                    item.put("signatures", List.of("CONTAINS(text, substring, …)"));
                    item.put("returnType", "boolean");
                    item.put("description", "兼容现有业务公式的文本子串匹配别名；官方函数请使用 CONTAINTEXT。 ");
                    item.put("arguments", "text：待查找文本；substring：一个或多个待查找子串");
                    item.put("sourceType", "project-compatibility-alias");
                    item.put("examples", List.of("CONTAINS(\"106.9w\", \"w\")"));
                    catalog.add(Map.copyOf(item));
                    continue;
                }
                if (entry == null) continue;
                item.put("signatures", entry.path("signatures"));
                item.put("returnType", entry.path("returnType").asText("unknown"));
                item.put("description", entry.path("description").asText(""));
                item.put("arguments", entry.path("arguments").asText(""));
                item.put("examples", entry.path("examples"));
                item.put("sourceType", "official-catalog");
                catalog.add(Map.copyOf(item));
            }
            return List.copyOf(catalog);
        } catch (Exception failure) {
            throw new IllegalStateException("公式函数目录加载失败", failure);
        }
    }

    List<FormulaFieldSpec> cachedFormulaFields() {
        long now = System.currentTimeMillis();
        if (now - formulaFieldCacheAtMs < FORMULA_FIELD_CACHE_TTL_MS && !formulaFieldCache.isEmpty()) {
            return formulaFieldCache;
        }
        synchronized (this) {
            if (now - formulaFieldCacheAtMs < FORMULA_FIELD_CACHE_TTL_MS && !formulaFieldCache.isEmpty()) {
                return formulaFieldCache;
            }
            formulaFieldCache = loadFormulaFieldsFromDb();
            formulaFieldCacheAtMs = now;
            if (formulaFieldCache.isEmpty()) {
                enrichContextCache = EnrichContext.EMPTY;
            } else {
                Map<String, String> titleToKey = buildTitleToKeyIndex(formulaFieldCache);
                enrichContextCache = EnrichContext.compile(formulaFieldCache, titleToKey);
            }
            return formulaFieldCache;
        }
    }

    public void invalidateFormulaFieldCache() {
        formulaFieldCacheAtMs = 0L;
        enrichContextCache = EnrichContext.EMPTY;
        rowFormulaResultCache.clear();
        formulaVersionMemo = "";
    }

    private List<FormulaFieldSpec> loadFormulaFieldsFromDb() {
        if (fieldConfigRepository == null) {
            return List.of();
        }
        List<FormulaFieldSpec> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : fieldConfigRepository.listAll().entrySet()) {
            Map<String, Object> cfg = entry.getValue();
            if (cfg == null) {
                continue;
            }
            String type = String.valueOf(cfg.getOrDefault("fieldType", "")).trim().toLowerCase(Locale.ROOT);
            if (!"formula".equals(type)) {
                continue;
            }
            String key = trimToEmpty(entry.getKey());
            String expr = trimToEmpty((String) cfg.get("defaultValue"));
            if (key.isBlank() || expr.isBlank()) {
                continue;
            }
            String title = trimToEmpty((String) cfg.get("fieldTitle"));
            out.add(new FormulaFieldSpec(key, title, expr));
        }
        return List.copyOf(out);
    }

    private Map<String, String> buildTitleToKeyIndex(List<FormulaFieldSpec> specs) {
        Map<String, String> index = new ConcurrentHashMap<>(SYSTEM_FIELD_LABELS);
        for (FormulaFieldSpec spec : specs) {
            if (!spec.fieldTitle().isBlank()) {
                index.putIfAbsent(spec.fieldTitle(), spec.fieldKey());
            }
            index.putIfAbsent(spec.fieldKey(), spec.fieldKey());
        }
        return index;
    }

    static CompiledFormula compileFormula(FormulaFieldSpec spec, Map<String, String> titleToKey) {
        String normalized = normalizeFormulaExpression(spec.expr(), true);
        FastPathKind fastPath = detectPlayCountFastPath(normalized, titleToKey);
        String sourceField = fastPath == FastPathKind.NONE ? "" : playCountSourceField(normalized, titleToKey);
        return new CompiledFormula(spec.fieldKey(), normalized, fastPath, sourceField);
    }

    /**
     * 中文备注：常见「亿/w/万 → 数字」数量公式；命中则单行 O(1) parse，不走 IF 树。
     */
    static FastPathKind detectPlayCountFastPath(String normalizedExpr, Map<String, String> titleToKey) {
        if (normalizedExpr == null || normalizedExpr.isBlank()) {
            return FastPathKind.NONE;
        }
        String source = playCountSourceField(normalizedExpr, titleToKey);
        if (source == null) {
            return FastPathKind.NONE;
        }
        String compact = normalizedExpr.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!compact.contains("IF(") || !compact.contains("CONTAINS(")
                || !compact.contains("SUBSTITUTE(") || !compact.contains("VALUE(")) {
            return FastPathKind.NONE;
        }
        if ("playCountAdd".equals(source)) {
            return FastPathKind.PLAY_COUNT_ADD;
        }
        if ("playCount".equals(source)) {
            return FastPathKind.PLAY_COUNT_TOTAL;
        }
        return FastPathKind.NONE;
    }

    static String playCountSourceField(String normalizedExpr, Map<String, String> titleToKey) {
        Matcher m = FIELD_REF.matcher(normalizedExpr);
        String only = null;
        while (m.find()) {
            String key = resolveFieldKey(m.group(1), titleToKey);
            if (!"playCountAdd".equals(key) && !"playCount".equals(key)) {
                return null;
            }
            if (only == null) {
                only = key;
            } else if (!only.equals(key)) {
                return null;
            }
        }
        return only;
    }

    String evalExpression(String expr,
                          Map<String, Object> row,
                          String selfKey,
                          List<FormulaFieldSpec> allSpecs,
                          Map<String, String> titleToKey,
                          List<String> stack) {
        EnrichContext context = EnrichContext.compile(allSpecs, titleToKey);
        return evalExpression(expr, row, selfKey, context, stack, new HashMap<>());
    }

    private String evalExpression(String expr,
                                  Map<String, Object> row,
                                  String selfKey,
                                  EnrichContext ctx,
                                  List<String> stack,
                                  Map<String, String> fieldCache) {
        String s = normalizeFormulaExpression(expr, true);
        if (s.isBlank()) {
            return "";
        }
        if (selfKey != null && !selfKey.isBlank() && stack.contains(selfKey)) {
            return "#CIRCLE!";
        }
        // Cross-table legacy tokens remain on the compatibility path until their record
        // references are represented by FormulaDataSource batches in the list service.
        if (BITABLE_REF.matcher(s).find()) {
            return evalExpressionLegacy(s, row, selfKey, ctx, stack, fieldCache);
        }
        try {
            FormulaAst.Expression expression = FORMULA_PARSER.parse(s);
            Map<String, FormulaValue> values = new LinkedHashMap<>();
            collectTypedFields(expression, row, selfKey, ctx, stack, fieldCache, values);
            FormulaValue result = FORMULA_EVALUATOR.evaluate(expression,
                    new FormulaEvaluationContext(values, Map.of(), java.time.ZoneId.of("Asia/Shanghai"),
                            java.time.Clock.system(java.time.ZoneId.of("Asia/Shanghai"))));
            return displayFormulaValue(result);
        } catch (RuntimeException parseOrEvaluationFailure) {
            return "#VALUE!";
        }
    }

    private void collectTypedFields(FormulaAst.Expression expression,
                                    Map<String, Object> row,
                                    String selfKey,
                                    EnrichContext ctx,
                                    List<String> stack,
                                    Map<String, String> fieldCache,
                                    Map<String, FormulaValue> values) {
        if (expression instanceof FormulaAst.FieldReference reference) {
            String key = resolveFieldKey(reference.name(), ctx.titleToKey());
            String display = resolveFieldValue(row, key, ctx, selfKey == null ? stack : appendStack(stack, selfKey), fieldCache);
            FormulaValue value = display.startsWith("#") && display.endsWith("!")
                    ? FormulaValue.error(display, "formula reference error")
                    : display.startsWith("#") && display.endsWith("?")
                    ? FormulaValue.error(display, "formula reference error")
                    : FormulaValue.text(display);
            values.put(reference.name(), value);
            values.put(key, value);
        } else if (expression instanceof FormulaAst.Unary unary) {
            collectTypedFields(unary.operand(), row, selfKey, ctx, stack, fieldCache, values);
        } else if (expression instanceof FormulaAst.Binary binary) {
            collectTypedFields(binary.left(), row, selfKey, ctx, stack, fieldCache, values);
            collectTypedFields(binary.right(), row, selfKey, ctx, stack, fieldCache, values);
        } else if (expression instanceof FormulaAst.Call call) {
            collectTypedFields(call.callee(), row, selfKey, ctx, stack, fieldCache, values);
            call.arguments().forEach(argument -> collectTypedFields(argument, row, selfKey, ctx, stack, fieldCache, values));
        } else if (expression instanceof FormulaAst.Member member) {
            collectTypedFields(member.target(), row, selfKey, ctx, stack, fieldCache, values);
        }
    }

    private static String displayFormulaValue(FormulaValue value) {
        return switch (value.kind()) {
            case BLANK -> "";
            case TEXT -> value.asText();
            case DECIMAL -> value.asDecimal().stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(value.rawValue());
            case DATE_TIME, LIST, RECORD_REFERENCE -> String.valueOf(value.rawValue());
            case ERROR -> value.errorCode();
        };
    }

    private String evalExpressionLegacy(String expr,
                                        Map<String, Object> row,
                                        String selfKey,
                                        EnrichContext ctx,
                                        List<String> stack,
                                        Map<String, String> fieldCache) {
        String s = expr;
        if (s.isBlank()) {
            return "";
        }
        if (selfKey != null && !selfKey.isBlank() && stack.contains(selfKey)) {
            return "#CIRCLE!";
        }
        List<String> nextStack = selfKey == null || selfKey.isBlank()
                ? new ArrayList<>(stack)
                : appendStack(stack, selfKey);
        Matcher bitableRef = BITABLE_REF.matcher(s);
        StringBuffer bitableOut = new StringBuffer();
        while (bitableRef.find()) {
            String quoted = quote(resolveFieldValue(row, bitableRef.group(1), ctx, nextStack, fieldCache));
            bitableRef.appendReplacement(bitableOut, Matcher.quoteReplacement(quoted));
        }
        bitableRef.appendTail(bitableOut);
        s = bitableOut.toString();
        Matcher fieldRef = FIELD_REF.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (fieldRef.find()) {
            String key = resolveFieldKey(fieldRef.group(1), ctx.titleToKey());
            String replacement = quote(resolveFieldValue(row, key, ctx, nextStack, fieldCache));
            fieldRef.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        fieldRef.appendTail(sb);
        s = sb.toString().replace("&", "+");
        s = evalFormulaFunctions(s);
        Object branch = evalFormulaBranch(s);
        String branchText = branch == null ? "" : String.valueOf(branch);
        boolean looksUnevaluated = UNEVALUATED_STRING_CONCAT.matcher(branchText).find();
        if (!branchText.isBlank() && !looksUnevaluated
                && !INNERMOST_CALL.matcher(branchText).find()) {
            return branchText;
        }
        try {
            String withoutStrings = branchText.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
            if (!withoutStrings.matches("^[\\s\\d.+\\-*/()A-Za-z_\"]+$")) {
                return "";
            }
            Object out = evalFormulaArithmetic(branchText);
            return out == null || String.valueOf(out).isBlank() ? "" : String.valueOf(out);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String resolveFieldValue(Map<String, Object> row,
                                     String nameOrKey,
                                     EnrichContext ctx,
                                     List<String> stack,
                                     Map<String, String> fieldCache) {
        String key = resolveFieldKey(nameOrKey, ctx.titleToKey());
        if (key.isBlank()) {
            return "";
        }
        if (fieldCache.containsKey(key)) {
            return fieldCache.get(key);
        }
        CompiledFormula nested = ctx.compiledByKey().get(key);
        String out;
        if (nested != null) {
            if (stack.contains(key)) {
                out = "#CIRCLE!";
            } else {
                out = evalCompiled(nested, row, ctx, stack, fieldCache);
            }
        } else {
            out = rowFieldRawValue(row, key);
        }
        fieldCache.put(key, out);
        return out;
    }

    static String rowFieldRawValue(Map<String, Object> row, String key) {
        if (row == null || key == null || key.isBlank()) {
            return "";
        }
        if ("seriesName".equals(key)) {
            return trimToEmpty(row.get("seriesName"));
        }
        if ("bookId".equals(key)) {
            if ("adx".equals(trimToEmpty(row.get("dramaType")))) {
                return trimToEmpty(row.get("bookId"));
            }
            String book = trimToEmpty(row.get("bookId"));
            if (!book.isBlank()) {
                return book;
            }
            return trimToEmpty(row.get("opsKey"));
        }
        if ("playCountAdd".equals(key)) {
            String text = trimToEmpty(row.get("playCountAddText"));
            if (!text.isBlank()) {
                return text;
            }
            return trimToEmpty(row.get("playCountAdd"));
        }
        if ("playCount".equals(key)) {
            String text = trimToEmpty(row.get("playCountText"));
            if (!text.isBlank()) {
                return text;
            }
            return trimToEmpty(row.get("playCount"));
        }
        Object v = row.get(key);
        return v == null ? "" : trimToEmpty(v);
    }

    static String resolveFieldKey(String nameOrKey, Map<String, String> titleToKey) {
        String label = trimToEmpty(nameOrKey);
        if (label.isBlank()) {
            return "";
        }
        if (titleToKey.containsKey(label)) {
            return titleToKey.get(label);
        }
        return label;
    }

    static String normalizeFormulaExpression(String expr, boolean adxPlayCountDefault) {
        String s = trimToEmpty(expr);
        if (s.isBlank()) {
            return "";
        }
        if (s.startsWith("=")) {
            s = s.substring(1).trim();
        }
        s = repairBrokenFormulaFieldRefs(s, adxPlayCountDefault);
        s = s.replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u201e', '"').replace('\u2033', '"').replace('\u2036', '"')
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\uFF08', '(').replace('\uFF09', ')')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (!s.contains("[") && adxPlayCountDefault) {
            String play = "[playCountAdd]";
            s = s.replaceAll("(?i)CONTAINS\\s*\\(\\s*,", "CONTAINS(" + play + ",")
                    .replaceAll("(?i)SUBSTITUTE\\s*\\(\\s*,", "SUBSTITUTE(" + play + ",")
                    .replaceAll("(?i)VALUE\\s*\\(\\s*\\)", "VALUE(" + play + ")");
        }
        return s;
    }

    private static String repairBrokenFormulaFieldRefs(String expr, boolean adxPlayCountDefault) {
        String s = expr == null ? "" : expr;
        if (s.contains("[")) {
            return s;
        }
        String play = adxPlayCountDefault ? "[playCountAdd]" : "[playCount]";
        s = s.replaceAll("(?i)CONTAINS\\s*\\(\\s*(?:[\\r\\n]|[\\s,])*\\s*,", "CONTAINS(" + play + ",")
                .replaceAll("(?i)SUBSTITUTE\\s*\\(\\s*(?:[\\r\\n]|[\\s,])*\\s*,", "SUBSTITUTE(" + play + ",")
                .replaceAll("(?i)VALUE\\s*\\(\\s*(?:[\\r\\n]|[\\s,])*\\s*\\)", "VALUE(" + play + ")");
        return s;
    }

    static double parseDisplayPlayCount(Object value) {
        String text = trimToEmpty(value)
                .replace(",", "")
                .replace("，", "")
                .replace(" ", "")
                .replace("+", "");
        if (text.isBlank()) {
            return 0D;
        }
        long multiplier = 1L;
        if (text.matches(".*[wW万]$")) {
            multiplier = 10_000L;
            text = text.substring(0, text.length() - 1);
        } else if (text.matches(".*[kK]$")) {
            multiplier = 1_000L;
            text = text.substring(0, text.length() - 1);
        } else if (text.matches(".*[mM]$")) {
            multiplier = 1_000_000L;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("亿")) {
            multiplier = 100_000_000L;
            text = text.substring(0, text.length() - 1);
        }
        try {
            BigDecimal n = new BigDecimal(text);
            BigDecimal product = n.multiply(BigDecimal.valueOf(multiplier));
            if (multiplier == 1L) {
                return product.doubleValue();
            }
            return product.setScale(0, RoundingMode.HALF_UP).doubleValue();
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private static String formatNumber(double n) {
        if (!Double.isFinite(n)) {
            return "";
        }
        if (n == Math.rint(n)) {
            return String.valueOf((long) n);
        }
        return String.valueOf(n);
    }

    private static String evalFormulaFunctions(String expr) {
        String s = expr;
        for (int i = 0; i < 500; i++) {
            CallHit hit = findInnermostFormulaCall(s);
            if (hit == null) {
                break;
            }
            Object val = evalFormulaCall(hit.name, hit.argsStr);
            s = s.substring(0, hit.start) + formatFormulaResult(val) + s.substring(hit.end);
        }
        return s;
    }

    private static Object evalFormulaCall(String name, String argsStr) {
        List<Object> args = splitFormulaCallArgs(argsStr).stream().map(OpsFormulaEvalService::parseFormulaLiteral).toList();
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "IF" -> {
                if (args.size() < 3) {
                    yield "";
                }
                yield formulaTruthy(args.get(0)) ? evalFormulaBranch(String.valueOf(args.get(1))) : evalFormulaBranch(String.valueOf(args.get(2)));
            }
            case "CONTAINS" -> {
                if (args.size() < 2) {
                    yield false;
                }
                yield String.valueOf(args.get(0)).contains(String.valueOf(args.get(1)));
            }
            case "SUBSTITUTE" -> {
                if (args.size() < 3) {
                    yield args.isEmpty() ? "" : args.get(0);
                }
                yield String.valueOf(args.get(0)).replace(String.valueOf(args.get(1)), String.valueOf(args.get(2)));
            }
            case "VALUE" -> parseDisplayPlayCount(args.isEmpty() ? "" : args.get(0));
            default -> "";
        };
    }

    private static Object evalFormulaBranch(String token) {
        String t = trimToEmpty(token);
        if (t.isBlank()) {
            return "";
        }
        String withoutStrings = t.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        if (withoutStrings.matches("^[\\s\\d.+\\-*/()\"]+$")) {
            Object out = evalFormulaArithmetic(t);
            return out == null ? "" : out;
        }
        return parseFormulaLiteral(t);
    }

    private static Object evalFormulaArithmetic(String source) {
        String t = trimToEmpty(source);
        if (t.isBlank()) {
            return "";
        }
        if (isSingleQuotedLiteral(t)) {
            return unescapeJsonString(t.substring(1, t.length() - 1));
        }
        try {
            if (t.matches("^-?\\d+(\\.\\d+)?$")) {
                if (t.contains(".")) {
                    return new BigDecimal(t).doubleValue();
                }
                return Long.parseLong(t);
            }
            return evalSimpleArithmeticExpression(t);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Object evalSimpleArithmeticExpression(String expr) {
        return new SimpleArithParser(expr).parse();
    }

    private static Object parseFormulaLiteral(String token) {
        String t = trimToEmpty(token);
        if (t.isBlank()) {
            return "";
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return unescapeJsonString(t.substring(1, t.length() - 1));
        }
        if (t.matches("^-?\\d+(\\.\\d+)?$")) {
            return t.contains(".") ? Double.parseDouble(t) : Long.parseLong(t);
        }
        if ("TRUE".equalsIgnoreCase(t)) {
            return true;
        }
        if ("FALSE".equalsIgnoreCase(t)) {
            return false;
        }
        return t;
    }

    private static boolean formulaTruthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0D && Double.isFinite(n.doubleValue());
        }
        String s = trimToEmpty(v);
        if (s.isBlank()) {
            return false;
        }
        return !"false".equalsIgnoreCase(s) && !"0".equals(s);
    }

    private static String formatFormulaResult(Object val) {
        if (val instanceof Number n && Double.isFinite(n.doubleValue())) {
            return formatNumber(n.doubleValue());
        }
        if (val instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return quote(val == null ? "" : String.valueOf(val));
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 整段表达式是否仅为一个引号字符串（勿把 `"a" + "b"` 误判成单字面量）。 */
    private static boolean isSingleQuotedLiteral(String t) {
        if (!t.startsWith("\"") || t.length() < 2) {
            return false;
        }
        boolean esc = false;
        for (int i = 1; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (ch == '\\') {
                esc = true;
                continue;
            }
            if (ch == '"') {
                return i == t.length() - 1;
            }
        }
        return false;
    }

    private static CallHit findInnermostFormulaCall(String expr) {
        Matcher m = INNERMOST_CALL.matcher(expr);
        CallHit best = null;
        while (m.find()) {
            int openParen = expr.indexOf('(', m.end() - 1);
            if (openParen < 0) {
                continue;
            }
            int close = findMatchingCloseParen(expr, openParen);
            if (close < 0) {
                continue;
            }
            String argsStr = expr.substring(openParen + 1, close);
            String innerBare = argsStr.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "");
            if (INNERMOST_CALL.matcher(innerBare).find()) {
                continue;
            }
            best = new CallHit(m.group(1).toUpperCase(Locale.ROOT), m.start(), close + 1, argsStr);
        }
        return best;
    }

    private static int findMatchingCloseParen(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = openIdx; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitFormulaCallArgs(String inner) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (inStr) {
                cur.append(ch);
                if (esc) {
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    inStr = false;
                }
                continue;
            }
            if (ch == '"') {
                inStr = true;
                cur.append(ch);
                continue;
            }
            if (ch == '(') {
                depth++;
                cur.append(ch);
                continue;
            }
            if (ch == ')') {
                depth--;
                cur.append(ch);
                continue;
            }
            if (ch == ',' && depth == 0) {
                args.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().trim().isBlank()) {
            args.add(cur.toString().trim());
        }
        return args;
    }

    private static String unescapeJsonString(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static List<String> appendStack(List<String> stack, String key) {
        List<String> next = new ArrayList<>(stack);
        next.add(key);
        return next;
    }

    private static String trimToEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    enum FastPathKind {
        NONE,
        PLAY_COUNT_ADD,
        PLAY_COUNT_TOTAL
    }

    record FormulaFieldSpec(String fieldKey, String fieldTitle, String expr) {
    }

    record CompiledFormula(String fieldKey, String normalizedExpr, FastPathKind fastPath, String sourceFieldKey) {
    }

    /** 行级公式物化条目:引用输入指纹 + 结果集。 */
    record CachedRowFormulas(String fingerprint, Map<String, Object> values) {
    }

    private record FormulaSortRow(Map<String, Object> row, boolean blank, BigDecimal number, String text) {
    }

    record EnrichContext(List<CompiledFormula> formulas,
                         List<FormulaFieldSpec> rawSpecs,
                         Map<String, String> titleToKey,
                         Map<String, CompiledFormula> compiledByKey) {
        static final EnrichContext EMPTY = new EnrichContext(List.of(), List.of(), Map.of(), Map.of());

        static EnrichContext compile(List<FormulaFieldSpec> specs, Map<String, String> titleToKey) {
            if (specs == null || specs.isEmpty()) {
                return new EnrichContext(List.of(), List.of(), titleToKey == null ? Map.of() : titleToKey, Map.of());
            }
            List<CompiledFormula> compiled = new ArrayList<>(specs.size());
            Map<String, CompiledFormula> byKey = new LinkedHashMap<>();
            for (FormulaFieldSpec spec : specs) {
                CompiledFormula c = compileFormula(spec, titleToKey);
                compiled.add(c);
                byKey.put(spec.fieldKey(), c);
            }
            return new EnrichContext(List.copyOf(compiled), specs, titleToKey, Map.copyOf(byKey));
        }
    }

    private record CallHit(String name, int start, int end, String argsStr) {
    }

    private static final class SimpleArithParser {
        private final String input;
        private int pos;

        SimpleArithParser(String input) {
            this.input = input == null ? "" : input;
        }

        Object parse() {
            Object v = parseAddSub();
            skipWs();
            if (pos < input.length()) {
                throw new IllegalArgumentException("trailing");
            }
            return v;
        }

        private Object parseAddSub() {
            Object left = parseMulDiv();
            for (;;) {
                skipWs();
                if (pos >= input.length()) {
                    return left;
                }
                char op = input.charAt(pos);
                if (op != '+' && op != '-') {
                    return left;
                }
                pos++;
                Object right = parseMulDiv();
                left = combineAddSub(left, right, op);
            }
        }

        private Object parseMulDiv() {
            Object left = parseUnary();
            for (;;) {
                skipWs();
                if (pos >= input.length()) {
                    return left;
                }
                char op = input.charAt(pos);
                if (op != '*' && op != '/') {
                    return left;
                }
                pos++;
                Object right = parseUnary();
                left = combineMulDiv(left, right, op);
            }
        }

        private Object parseUnary() {
            skipWs();
            if (pos < input.length() && input.charAt(pos) == '+') {
                pos++;
                return parseUnary();
            }
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                Object v = parseUnary();
                if (v instanceof Number n) {
                    return -n.doubleValue();
                }
                throw new IllegalArgumentException("unary");
            }
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++;
                Object inner = parseAddSub();
                skipWs();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("paren");
                }
                pos++;
                return inner;
            }
            if (pos < input.length() && input.charAt(pos) == '"') {
                pos++;
                StringBuilder sb = new StringBuilder();
                boolean esc = false;
                while (pos < input.length()) {
                    char ch = input.charAt(pos++);
                    if (esc) {
                        sb.append(ch);
                        esc = false;
                        continue;
                    }
                    if (ch == '\\') {
                        esc = true;
                        continue;
                    }
                    if (ch == '"') {
                        return sb.toString();
                    }
                    sb.append(ch);
                }
                throw new IllegalArgumentException("string");
            }
            int start = pos;
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (Character.isDigit(ch) || ch == '.') {
                    pos++;
                    continue;
                }
                break;
            }
            if (start == pos) {
                throw new IllegalArgumentException("num");
            }
            String num = input.substring(start, pos);
            return num.contains(".") ? Double.parseDouble(num) : Long.parseLong(num);
        }

        private static Object combineAddSub(Object left, Object right, char op) {
            if (left instanceof String || right instanceof String) {
                String l = String.valueOf(left);
                String r = String.valueOf(right);
                return op == '+' ? l + r : l;
            }
            double a = ((Number) left).doubleValue();
            double b = ((Number) right).doubleValue();
            double out = op == '+' ? a + b : a - b;
            return (out == Math.rint(out)) ? (long) out : out;
        }

        private static Object combineMulDiv(Object left, Object right, char op) {
            double a = ((Number) left).doubleValue();
            double b = ((Number) right).doubleValue();
            double out = op == '*' ? a * b : a / b;
            return (out == Math.rint(out)) ? (long) out : out;
        }

        private void skipWs() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
