package com.polytable.service;

import com.polytable.repository.OpsFieldConfigRepository;
import com.polytable.repository.OpsFieldOptionRepository;
import com.polytable.repository.OpsSheetFieldRepository;
import com.polytable.repository.OpsSheetRowRepository;
import com.polytable.repository.OpsTableRegistryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 空白协作表「查找引用」：选源表+源字段，按引用表筛选条件匹配，计算方式默认原值；结果元素超 1000 拒绝执行。
 */
@Service
public class OpsSheetLookupService {

    public static final int MAX_SOURCE_ROWS = 2000;
    public static final int MAX_RESULT_ELEMENTS = 1000;
    public static final String LIMIT_MESSAGE = "公式计算结果中的元素数量已超出上限1000个，请重新筛选条件。";
    /** 引用表筛选条件：源表字段 + 运算符 + 固定值（同视图筛选，非本表字段比对）。 */
    public record LookupCondition(String sourceFieldKey, String op, String value) {
    }

    public record LookupConfig(
            String sourceTableId,
            String sourceTableLabel,
            String sourceFieldKey,
            String sourceFieldLabel,
            String calcMethod,
            String fieldFormat,
            List<LookupCondition> conditions
    ) {
        static LookupConfig empty() {
            return new LookupConfig("", "", "", "", "raw", "auto", List.of());
        }

        public boolean enabled() {
            return sourceTableId != null && !sourceTableId.isBlank()
                    && sourceFieldKey != null && !sourceFieldKey.isBlank();
        }
    }

    public static class LookupLimitExceededException extends RuntimeException {
        public LookupLimitExceededException(String message) {
            super(message);
        }
    }

    /** 本表用于逐行匹配的列（对齐「名称列 + 编号列」约定；协作表按 key/标题识别）。 */
    public record MatchColumns(String seriesNameFieldKey, String bookIdFieldKey) {
    }

    private final OpsSheetRowRepository sheetRowRepository;
    private final OpsSheetFieldRepository sheetFieldRepository;
    private final OpsTableRegistryRepository tableRegistryRepository;
    private final OpsFieldConfigRepository fieldConfigRepository;
    private final OpsFieldOptionRepository fieldOptionRepository;
    private final ObjectMapper objectMapper;

    public OpsSheetLookupService(OpsSheetRowRepository sheetRowRepository,
                                 OpsSheetFieldRepository sheetFieldRepository,
                                 OpsTableRegistryRepository tableRegistryRepository,
                                 OpsFieldConfigRepository fieldConfigRepository,
                                 OpsFieldOptionRepository fieldOptionRepository,
                                 ObjectMapper objectMapper) {
        this.sheetRowRepository = sheetRowRepository;
        this.sheetFieldRepository = sheetFieldRepository;
        this.tableRegistryRepository = tableRegistryRepository;
        this.fieldConfigRepository = fieldConfigRepository;
        this.fieldOptionRepository = fieldOptionRepository;
        this.objectMapper = objectMapper;
    }

    /** 中文备注：系统表 lookup 列来自 ops_field_config，供 enrich 与 validate 复用。 */
    public List<Map<String, Object>> buildLookupFieldMetasFromConfigs() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> configs = fieldConfigRepository.listAll();
        if (configs == null || configs.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Map<String, Object>> e : configs.entrySet()) {
            Map<String, Object> cfg = e.getValue();
            if (cfg == null) {
                continue;
            }
            if (!"lookup".equalsIgnoreCase(trim(String.valueOf(cfg.getOrDefault("fieldType", ""))))) {
                continue;
            }
            String masterLinkRaw = String.valueOf(cfg.getOrDefault("masterLinkJson", ""));
            LookupConfig lookup = parseConfig(masterLinkRaw);
            if (!lookup.enabled()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            String fieldKey = trim(e.getKey());
            meta.put("fieldKey", fieldKey);
            meta.put("key", fieldKey);
            meta.put("fieldType", "lookup");
            meta.put("type", "lookup");
            String title = trim(String.valueOf(cfg.getOrDefault("fieldTitle", "")));
            meta.put("fieldTitle", title.isBlank() ? fieldKey : title);
            meta.put("label", meta.get("fieldTitle"));
            meta.put("masterLinkJson", masterLinkRaw.isBlank() || "null".equals(masterLinkRaw) ? null : masterLinkRaw);
            Map<String, Object> lookupMeta = new LinkedHashMap<>();
            lookupMeta.put("kind", "lookup");
            lookupMeta.put("sourceTableId", lookup.sourceTableId());
            lookupMeta.put("sourceTableLabel", lookup.sourceTableLabel());
            lookupMeta.put("sourceFieldKey", lookup.sourceFieldKey());
            lookupMeta.put("sourceFieldLabel", lookup.sourceFieldLabel());
            lookupMeta.put("calcMethod", lookup.calcMethod());
            lookupMeta.put("fieldFormat", lookup.fieldFormat());
            lookupMeta.put("conditions", lookup.conditions().stream().map(c -> Map.of(
                    "sourceFieldKey", c.sourceFieldKey(),
                    "op", c.op(),
                    "value", c.value()
            )).toList());
            meta.put("lookup", lookupMeta);
            out.add(meta);
        }
        return out;
    }

    public List<Map<String, String>> listLinkableTables(String currentTableId) {
        List<Map<String, String>> out = new ArrayList<>();
        String current = trim(currentTableId);
        for (Map<String, Object> row : tableRegistryRepository.listActive()) {
            if (row == null) continue;
            if (!"blank".equalsIgnoreCase(trim(String.valueOf(row.getOrDefault("kind", ""))))) continue;
            String tableId = trim(String.valueOf(row.getOrDefault("tableId", "")));
            if (tableId.isBlank() || tableId.equals(current)) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("tableId", tableId);
            item.put("label", trim(String.valueOf(row.getOrDefault("label", tableId))));
            item.put("kind", "blank");
            out.add(item);
        }
        return out;
    }

    public List<Map<String, Object>> listFieldsForTable(String tableId) {
        String tid = trim(tableId);
        if (tid.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> field : sheetFieldRepository.listActive(tid)) {
            if (field == null) continue;
            String key = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
            if (key.isBlank()) continue;
            String type = trim(String.valueOf(field.getOrDefault("fieldType", "text")));
            if ("lookup".equals(type)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fieldKey", key);
            item.put("label", trim(String.valueOf(field.getOrDefault("fieldTitle", key))));
            item.put("fieldType", type);
            List<Map<String, String>> options = parseFieldOptions(String.valueOf(field.getOrDefault("optionsJson", "")));
            if (!options.isEmpty()) {
                item.put("options", options);
            }
            enrichLookupFieldMeta(item);
            out.add(item);
        }
        return out;
    }

    private void enrichLookupFieldMeta(Map<String, Object> item) {
        if (item == null) return;
        String key = trim(String.valueOf(item.getOrDefault("fieldKey", "")));
        if (key.isBlank()) return;
        String type = trim(String.valueOf(item.getOrDefault("fieldType", "")));
        if (type.isBlank()) {
            type = resolveLookupFieldType(key);
            if (!type.isBlank()) item.put("fieldType", type);
        }
        if (!needsLookupOptions(type)) return;
        Object rawOpts = item.get("options");
        if (rawOpts instanceof List<?> list && !list.isEmpty()) return;
        List<Map<String, String>> options = loadEffectiveLookupOptions(key);
        if (!options.isEmpty()) item.put("options", options);
    }

    private static boolean needsLookupOptions(String fieldType) {
        String t = trim(fieldType).toLowerCase(Locale.ROOT);
        return "select".equals(t) || "multi".equals(t) || "checkbox".equals(t);
    }

    private String resolveLookupFieldType(String fieldKey) {
        String key = trim(fieldKey);
        if (key.isBlank()) return "text";
        Map<String, Object> cfg = fieldConfigRepository == null ? null : fieldConfigRepository.find(key);
        if (cfg != null) {
            String fromCfg = trim(String.valueOf(cfg.getOrDefault("fieldType", "")));
            if (!fromCfg.isBlank()) return fromCfg;
        }
        return "text";
    }

    private List<Map<String, String>> loadEffectiveLookupOptions(String fieldKey) {
        List<String> values = new ArrayList<>();
        List<String> fromConfig = optionsFromFieldConfig(fieldKey);
        if (fromConfig != null) {
            values.addAll(fromConfig);
        } else {
            values.addAll(OpsFieldOptionDefaults.seedOptions(fieldKey));
            if (fieldOptionRepository != null) {
                try {
                    for (String value : fieldOptionRepository.listActive(fieldKey)) {
                        String v = trim(value);
                        if (!v.isBlank() && !values.contains(v)) values.add(v);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            Map<String, String> opt = new LinkedHashMap<>();
            opt.put("value", value);
            opt.put("label", value);
            out.add(opt);
        }
        return out;
    }

    private List<String> optionsFromFieldConfig(String fieldKey) {
        if (fieldConfigRepository == null) return null;
        try {
            Map<String, Object> row = fieldConfigRepository.find(fieldKey);
            if (row == null) return null;
            Object raw = row.get("optionsJson");
            if (raw == null || String.valueOf(raw).isBlank()) return null;
            List<Map<String, String>> options = parseFieldOptions(String.valueOf(raw));
            if (options.isEmpty()) return null;
            List<String> values = new ArrayList<>();
            for (Map<String, String> opt : options) {
                String v = trim(opt.get("value"));
                if (!v.isBlank() && !values.contains(v)) values.add(v);
            }
            return values.isEmpty() ? null : values;
        } catch (Exception e) {
            return null;
        }
    }

    public LookupConfig parseConfig(Object raw) {
        if (raw == null) return LookupConfig.empty();
        Map<String, Object> map;
        if (raw instanceof Map<?, ?> m) {
            map = new LinkedHashMap<>();
            m.forEach((k, v) -> map.put(String.valueOf(k), v));
        } else {
            try {
                map = objectMapper.readValue(String.valueOf(raw), new TypeReference<>() {
                });
            } catch (Exception e) {
                return LookupConfig.empty();
            }
        }
        if (map == null || map.isEmpty()) return LookupConfig.empty();
        String kind = trim(String.valueOf(map.getOrDefault("kind", "")));
        if (!"lookup".equalsIgnoreCase(kind) && !map.containsKey("sourceTableId")) {
            return LookupConfig.empty();
        }
        String sourceTableId = trim(String.valueOf(map.getOrDefault("sourceTableId", "")));
        String sourceTableLabel = trim(String.valueOf(map.getOrDefault("sourceTableLabel", "")));
        String sourceFieldKey = trim(String.valueOf(map.getOrDefault("sourceFieldKey", "")));
        String sourceFieldLabel = trim(String.valueOf(map.getOrDefault("sourceFieldLabel", "")));
        String calcMethod = trim(String.valueOf(map.getOrDefault("calcMethod", "raw")));
        if (calcMethod.isBlank()) calcMethod = "raw";
        String fieldFormat = trim(String.valueOf(map.getOrDefault("fieldFormat", "auto")));
        if (fieldFormat.isBlank()) fieldFormat = "auto";
        List<LookupCondition> conditions = new ArrayList<>();
        Object rawConds = map.get("conditions");
        if (rawConds instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> cm)) continue;
                String op = trim(String.valueOf(cm.get("op") == null ? "eq" : cm.get("op")));
                if (op.isBlank()) op = "eq";
                String sourceKey = trim(String.valueOf(cm.get("sourceFieldKey")));
                if (sourceKey.isBlank()) continue;
                String value = trim(String.valueOf(cm.get("value")));
                if (value.isBlank() && !isNoValueOp(op)) continue;
                conditions.add(new LookupCondition(sourceKey, op, value));
            }
        }
        return new LookupConfig(sourceTableId, sourceTableLabel, sourceFieldKey, sourceFieldLabel,
                calcMethod, fieldFormat, conditions);
    }

    public String serializeConfig(LookupConfig config) {
        if (config == null || !config.enabled()) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", "lookup");
        map.put("sourceTableId", config.sourceTableId());
        if (config.sourceTableLabel() != null && !config.sourceTableLabel().isBlank()) {
            map.put("sourceTableLabel", config.sourceTableLabel());
        }
        map.put("sourceFieldKey", config.sourceFieldKey());
        if (config.sourceFieldLabel() != null && !config.sourceFieldLabel().isBlank()) {
            map.put("sourceFieldLabel", config.sourceFieldLabel());
        }
        map.put("calcMethod", config.calcMethod() == null || config.calcMethod().isBlank() ? "raw" : config.calcMethod());
        map.put("fieldFormat", config.fieldFormat() == null || config.fieldFormat().isBlank() ? "auto" : config.fieldFormat());
        List<Map<String, String>> conds = new ArrayList<>();
        for (LookupCondition c : config.conditions()) {
            if (c == null || c.sourceFieldKey().isBlank()) continue;
            if (!isNoValueOp(c.op()) && (c.value() == null || c.value().isBlank())) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("sourceFieldKey", c.sourceFieldKey());
            item.put("op", c.op() == null || c.op().isBlank() ? "eq" : c.op());
            item.put("value", c.value() == null ? "" : c.value());
            conds.add(item);
        }
        map.put("conditions", conds);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    public int countResultElements(LookupConfig config,
                                   List<Map<String, Object>> fieldMetas,
                                   List<Map<String, Object>> localRows) {
        if (config == null || !config.enabled()) {
            return 0;
        }
        List<Map<String, Object>> sourceRows = loadSourceRows(config.sourceTableId(), config.conditions());
        List<Map<String, Object>> hits = filterSourceHits(config.sourceTableId(), sourceRows, config.conditions());
        MatchColumns match = detectMatchColumns(fieldMetas);
        if (canPerRowLookup(match)) {
            SourceIndex index = SourceIndex.fromHits(hits);
            int count = 0;
            for (Map<String, Object> local : localRows == null ? List.<Map<String, Object>>of() : localRows) {
                Map<String, Object> hit = index.match(
                        match,
                        cellText(local, match.seriesNameFieldKey()),
                        cellText(local, match.bookIdFieldKey()));
                if (hit == null) continue;
                String value = resolveSourceFieldValue(hit, config.sourceFieldKey());
                if (!value.isBlank()) count++;
            }
            return count;
        }
        return hits.size();
    }

    public void validateOrThrow(LookupConfig config,
                                List<Map<String, Object>> fieldMetas,
                                List<Map<String, Object>> localRows) {
        if (config == null || !config.enabled()) return;
        int count = countResultElements(config, fieldMetas, localRows);
        if (count > MAX_RESULT_ELEMENTS) {
            throw new LookupLimitExceededException(LIMIT_MESSAGE);
        }
    }

    public List<Map<String, Object>> enrichSheetLookupFields(String tableId,
                                                             String operator,
                                                             List<Map<String, Object>> fieldMetas,
                                                             List<Map<String, Object>> rows) {
        return enrichSheetLookupFields(tableId, operator, fieldMetas, rows, Set.of());
    }

    /** 读表时按查找引用配置填充列值并落库，切换表/刷新后仍保留。返回各列同步状态供前端提示。 */
    public List<Map<String, Object>> enrichSheetLookupFields(String tableId,
                                                             String operator,
                                                             List<Map<String, Object>> fieldMetas,
                                                             List<Map<String, Object>> rows,
                                                             Set<String> forceFillFieldKeys) {
        List<Map<String, Object>> syncOut = new ArrayList<>();
        if (fieldMetas == null || fieldMetas.isEmpty() || rows == null) {
            return syncOut;
        }
        Set<String> forceKeys = forceFillFieldKeys == null ? Set.of() : forceFillFieldKeys;
        MatchColumns match = detectMatchColumns(fieldMetas);
        boolean perRow = canPerRowLookup(match);
        List<Map<String, Object>> lookupFields = new ArrayList<>();
        for (Map<String, Object> field : fieldMetas) {
            if (!"lookup".equalsIgnoreCase(trim(String.valueOf(field.getOrDefault("fieldType", ""))))) {
                continue;
            }
            LookupConfig config = parseLookupConfigFromField(field);
            if (config.enabled()) {
                lookupFields.add(field);
            }
        }
        if (lookupFields.isEmpty()) {
            return syncOut;
        }
        boolean anyForce = lookupFields.stream().anyMatch(f -> {
            String k = trim(String.valueOf(f.getOrDefault("fieldKey", "")));
            return !k.isBlank() && forceKeys.contains(k);
        });
        if (!anyForce && !hasUnfilledLookupCells(lookupFields, rows, tableId)) {
            return syncOut;
        }
        for (Map<String, Object> field : lookupFields) {
            LookupConfig config = parseLookupConfigFromField(field);
            String localKey = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
            if (localKey.isBlank()) {
                continue;
            }
            boolean forceFill = forceKeys.contains(localKey);
            if (!forceFill && !hasUnfilledForField(rows, localKey, tableId)) {
                continue;
            }
            try {
                validateOrThrow(config, fieldMetas, rows);
            } catch (LookupLimitExceededException e) {
                syncOut.add(buildLookupLimitMeta(field, e.getMessage()));
                continue;
            }
            List<Map<String, Object>> sourceRows = loadSourceRows(config.sourceTableId(), config.conditions());
            List<Map<String, Object>> hits = filterSourceHits(config.sourceTableId(), sourceRows, config.conditions());
            if (perRow) {
                SourceIndex index = SourceIndex.fromHits(hits);
                int filled = 0;
                int matchedRows = 0;
                String sample = "";
                for (Map<String, Object> local : rows) {
                    if (local == null) continue;
                    Map<String, Object> hit = index.match(
                            match,
                            cellText(local, match.seriesNameFieldKey()),
                            cellText(local, match.bookIdFieldKey()));
                    if (hit == null) continue;
                    matchedRows++;
                    String value = resolveSourceFieldValue(hit, config.sourceFieldKey());
                    if (value.isBlank()) continue;
                    if (sample.isBlank()) sample = value;
                    if (applyLookupFill(tableId, operator, local, localKey, value, forceFill)) {
                        filled++;
                    }
                }
                syncOut.add(buildLookupSyncMeta(field, config, sourceRows, hits, filled, matchedRows, true, sample, match));
            } else {
                String value = resolveCalcValue(config, hits);
                int filled = 0;
                if (!value.isBlank()) {
                    for (Map<String, Object> local : rows) {
                        if (local == null) continue;
                        if (applyLookupFill(tableId, operator, local, localKey, value, forceFill)) {
                            filled++;
                        }
                    }
                }
                syncOut.add(buildLookupSyncMeta(field, config, sourceRows, hits, filled, hits.size(), false, value, match));
            }
        }
        return syncOut;
    }

    /** 中文备注：在本表列里识别逐行匹配列——名称列（key/标题=seriesName/剧名）与编号列（bookId/book_id）。 */
    private MatchColumns detectMatchColumns(List<Map<String, Object>> fieldMetas) {
        String seriesKey = "";
        String bookKey = "";
        for (Map<String, Object> field : fieldMetas == null ? List.<Map<String, Object>>of() : fieldMetas) {
            if (field == null) continue;
            String key = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
            String title = trim(String.valueOf(field.getOrDefault("fieldTitle",
                    field.getOrDefault("label", ""))));
            if (seriesKey.isBlank() && ("seriesName".equalsIgnoreCase(key) || "剧名".equals(title))) {
                seriesKey = key;
            }
            if (bookKey.isBlank() && ("bookId".equalsIgnoreCase(key) || "book_id".equalsIgnoreCase(key)
                    || "bookId".equalsIgnoreCase(title) || "book_id".equalsIgnoreCase(title))) {
                bookKey = key;
            }
        }
        return new MatchColumns(seriesKey, bookKey);
    }

    private static boolean canPerRowLookup(MatchColumns match) {
        return match != null
                && (!trim(match.seriesNameFieldKey()).isBlank() || !trim(match.bookIdFieldKey()).isBlank());
    }

    private static final class SourceIndex {
        private final Map<String, Map<String, Object>> byBookId = new LinkedHashMap<>();
        private final Map<String, List<Map<String, Object>>> bySeriesName = new LinkedHashMap<>();
        static SourceIndex fromHits(List<Map<String, Object>> hits) {
            SourceIndex index = new SourceIndex();
            if (hits == null) return index;
            for (Map<String, Object> source : hits) {
                if (source == null) continue;
                String bookId = normalizeCompare(cellText(source, "bookId"));
                if (bookId.isBlank()) {
                    bookId = normalizeCompare(cellText(source, "opsKey"));
                }
                if (!bookId.isBlank() && !index.byBookId.containsKey(bookId)) {
                    index.byBookId.put(bookId, source);
                }
                String seriesName = normalizeCompare(cellText(source, "seriesName"));
                if (!seriesName.isBlank()) {
                    index.bySeriesName.computeIfAbsent(seriesName, k -> new ArrayList<>()).add(source);
                }
            }
            return index;
        }

        Map<String, Object> match(MatchColumns columns,
                                  String seriesName,
                                  String bookId) {
            boolean hasBookIdColumn = columns != null && !trim(columns.bookIdFieldKey()).isBlank();
            String id = normalizeCompare(hasBookIdColumn ? bookId : "");
            if (hasBookIdColumn && !id.isBlank()) {
                Map<String, Object> hit = byBookId.get(id);
                if (hit != null) {
                    return hit;
                }
            }
            String name = normalizeCompare(seriesName);
            if (name.isBlank()) {
                return null;
            }
            List<Map<String, Object>> candidates = bySeriesName.get(name);
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (hasBookIdColumn && !id.isBlank()) {
                for (Map<String, Object> candidate : candidates) {
                    String candidateId = normalizeCompare(cellText(candidate, "bookId"));
                    if (candidateId.isBlank()) {
                        candidateId = normalizeCompare(cellText(candidate, "opsKey"));
                    }
                    if (id.equals(candidateId)) {
                        return candidate;
                    }
                }
            }
            return candidates.get(0);
        }
    }

    private Map<String, Object> buildLookupSyncMeta(Map<String, Object> field,
                                                    LookupConfig config,
                                                    List<Map<String, Object>> sourceRows,
                                                    List<Map<String, Object>> hits,
                                                    int filledCount,
                                                    int matchedRows,
                                                    boolean perRow,
                                                    String sampleValue,
                                                    MatchColumns match) {
        String localKey = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
        int matchCount = hits == null ? 0 : hits.size();
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("fieldKey", localKey);
        sync.put("fieldTitle", trim(String.valueOf(field.getOrDefault("fieldTitle",
                field.getOrDefault("label", localKey)))));
        sync.put("matchCount", matchCount);
        sync.put("matchedRows", matchedRows);
        sync.put("filledCount", filledCount);
        sync.put("mode", perRow ? "per_row" : "static");
        sync.put("matchSeriesKey", match == null ? "" : trim(match.seriesNameFieldKey()));
        sync.put("matchBookKey", match == null ? "" : trim(match.bookIdFieldKey()));
        sync.put("sourceRowCount", sourceRows == null ? 0 : sourceRows.size());
        if (sourceRows == null || sourceRows.isEmpty()) {
            sync.put("status", "no_source");
            sync.put("message", "引用表近三天内无数据，请调整查找条件或日期范围");
        } else if (matchCount <= 0) {
            sync.put("status", "no_match");
            sync.put("message", "引用表无匹配数据（近三天内），请调整查找条件");
        } else if (!perRow) {
            sync.put("status", "ok");
            sync.put("value", sampleValue == null ? "" : sampleValue);
            sync.put("message", "未找到匹配列（名称/编号），全表填同一值；请添加匹配列后重新保存");
        } else if (perRow && matchedRows <= 0) {
            sync.put("status", "no_row_match");
            sync.put("message", "引用表有数据，但本表行未匹配到名称/编号列，请检查列名或内容");
        } else if (filledCount <= 0 && (sampleValue == null || sampleValue.isBlank())) {
            sync.put("status", "empty_value");
            sync.put("message", "已命中 " + matchCount + " 条，但引用字段值为空");
        } else if (filledCount <= 0) {
            sync.put("status", "ok");
            sync.put("value", sampleValue == null ? "" : sampleValue);
            sync.put("message", "已有手动填写内容，未覆盖");
        } else {
            sync.put("status", "ok");
            sync.put("value", sampleValue == null ? "" : sampleValue);
            sync.put("message", "");
        }
        return sync;
    }

    private Map<String, Object> buildLookupLimitMeta(Map<String, Object> field, String message) {
        String localKey = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
        Map<String, Object> sync = new LinkedHashMap<>();
        sync.put("fieldKey", localKey);
        sync.put("fieldTitle", trim(String.valueOf(field.getOrDefault("fieldTitle",
                field.getOrDefault("label", localKey)))));
        sync.put("status", "limit_exceeded");
        sync.put("message", message == null || message.isBlank() ? LIMIT_MESSAGE : message);
        sync.put("matchCount", 0);
        sync.put("filledCount", 0);
        return sync;
    }

    private String resolveCalcValue(LookupConfig config, List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) return "";
        String key = config.sourceFieldKey();
        if (key == null || key.isBlank()) return "";
        for (Map<String, Object> hit : hits) {
            if (hit == null) continue;
            String value = resolveSourceFieldValue(hit, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String resolveSourceFieldValue(Map<String, Object> source, String fieldKey) {
        String key = trim(fieldKey);
        if (key.isBlank()) return "";
        return cellText(source, key);
    }

    private List<Map<String, Object>> filterSourceHits(String tableId,
                                                       List<Map<String, Object>> sourceRows,
                                                       List<LookupCondition> conditions) {
        if (sourceRows == null || sourceRows.isEmpty()) return List.of();
        if (conditions == null || conditions.isEmpty()) return sourceRows;
        return findSourceMatches(sourceRows, conditions);
    }

    private List<Map<String, Object>> findSourceMatches(List<Map<String, Object>> sourceRows,
                                                        List<LookupCondition> conditions) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sourceRows == null || conditions == null || conditions.isEmpty()) {
            return out;
        }
        for (Map<String, Object> source : sourceRows) {
            if (source == null) continue;
            if (matchesAllSource(source, conditions)) {
                out.add(source);
            }
        }
        return out;
    }

    private boolean matchesAllSource(Map<String, Object> source, List<LookupCondition> conditions) {
        for (LookupCondition cond : conditions) {
            if (cond == null) continue;
            if (!matchesSourceCondition(source, cond)) {
                return false;
            }
        }
        return true;
    }

    private LookupConfig parseLookupConfigFromField(Map<String, Object> field) {
        if (field == null) {
            return LookupConfig.empty();
        }
        Object raw = field.get("masterLinkJson");
        if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) {
            raw = field.get("lookup");
        }
        return parseConfig(raw);
    }

    private boolean matchesSourceCondition(Map<String, Object> source, LookupCondition cond) {
        String op = cond.op() == null || cond.op().isBlank() ? "eq" : cond.op().trim();
        String text = cellText(source, cond.sourceFieldKey());
        List<String> wants = parseFilterValues(cond.value());

        if ("empty".equals(op)) {
            return isBlankCell(text);
        }
        if ("not_empty".equals(op)) {
            return !isBlankCell(text);
        }

        if ("after".equals(op) || "before".equals(op)) {
            String rowTs = normalizeDateTime(text);
            String wantTs = normalizeDateTime(wants.isEmpty() ? "" : wants.get(0));
            if (rowTs.isBlank() || wantTs.isBlank()) return false;
            int cmp = rowTs.compareTo(wantTs);
            return "after".equals(op) ? cmp > 0 : cmp < 0;
        }

        if ("gt".equals(op) || "gte".equals(op) || "lt".equals(op) || "lte".equals(op)) {
            if (isBlankCell(text)) return false;
            Double rowNum = parseNumber(text);
            Double wantNum = parseNumber(wants.isEmpty() ? "" : wants.get(0));
            if (rowNum == null || wantNum == null) return false;
            return switch (op) {
                case "gt" -> rowNum > wantNum;
                case "gte" -> rowNum >= wantNum;
                case "lt" -> rowNum < wantNum;
                case "lte" -> rowNum <= wantNum;
                default -> false;
            };
        }

        String normText = normalizeCompare(text);
        if ("eq".equals(op)) {
            if (looksLikeDate(text) || wants.stream().anyMatch(OpsSheetLookupService::looksLikeDate)) {
                String rowDay = normalizeDate(text);
                String wantDay = normalizeDate(wants.isEmpty() ? "" : wants.get(0));
                if (rowDay.isBlank() || wantDay.isBlank()) return false;
                return rowDay.equals(wantDay);
            }
            List<String> parts = splitOptionParts(text);
            List<String> wantNorm = wants.stream().map(OpsSheetLookupService::normalizeCompare).filter(s -> !s.isBlank()).toList();
            if (wantNorm.isEmpty()) return false;
            return parts.size() == wantNorm.size() && wantNorm.stream().allMatch(parts::contains);
        }
        if ("neq".equals(op)) {
            List<String> parts = splitOptionParts(text);
            List<String> wantNorm = wants.stream().map(OpsSheetLookupService::normalizeCompare).filter(s -> !s.isBlank()).toList();
            if (wantNorm.isEmpty()) return true;
            return !(parts.size() == wantNorm.size() && wantNorm.stream().allMatch(parts::contains));
        }
        if ("contains".equals(op)) {
            if (wants.isEmpty()) return true;
            List<String> parts = splitOptionParts(text);
            List<String> wantNorm = wants.stream().map(OpsSheetLookupService::normalizeCompare).filter(s -> !s.isBlank()).toList();
            if (!parts.isEmpty()) {
                return wantNorm.stream().anyMatch(w -> parts.stream().anyMatch(p -> p.equals(w)));
            }
            String lower = text.toLowerCase(Locale.ROOT);
            return wants.stream().anyMatch(w -> lower.contains(String.valueOf(w).toLowerCase(Locale.ROOT).trim()));
        }
        if ("not_contains".equals(op)) {
            if (wants.isEmpty()) return true;
            List<String> parts = splitOptionParts(text);
            List<String> wantNorm = wants.stream().map(OpsSheetLookupService::normalizeCompare).filter(s -> !s.isBlank()).toList();
            if (!parts.isEmpty()) {
                return wantNorm.stream().noneMatch(w -> parts.stream().anyMatch(p -> p.equals(w)));
            }
            String lower = text.toLowerCase(Locale.ROOT);
            return wants.stream().noneMatch(w -> lower.contains(String.valueOf(w).toLowerCase(Locale.ROOT).trim()));
        }
        return normText.equals(normalizeCompare(wants.isEmpty() ? "" : wants.get(0)));
    }

    private static List<String> splitOptionParts(String text) {
        String raw = trim(text);
        if (raw.isEmpty()) return List.of();
        String[] chunks = raw.split("[、,，/|]");
        List<String> out = new ArrayList<>();
        for (String chunk : chunks) {
            String p = normalizeCompare(chunk);
            if (!p.isBlank() && !out.contains(p)) out.add(p);
        }
        if (out.isEmpty()) {
            String single = normalizeCompare(raw);
            if (!single.isBlank()) out.add(single);
        }
        return out;
    }

    private static List<String> parseFilterValues(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw.trim();
        if (text.startsWith("[")) {
            try {
                List<?> arr = new ObjectMapper().readValue(text, List.class);
                List<String> out = new ArrayList<>();
                for (Object item : arr) {
                    String v = trim(String.valueOf(item));
                    if (!v.isBlank() && !out.contains(v)) out.add(v);
                }
                return out;
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (text.contains("|||")) {
            List<String> out = new ArrayList<>();
            for (String part : text.split("\\|\\|\\|")) {
                String v = trim(part);
                if (!v.isBlank() && !out.contains(v)) out.add(v);
            }
            return out;
        }
        if (text.contains("、")) {
            List<String> out = new ArrayList<>();
            for (String part : text.split("、")) {
                String v = trim(part);
                if (!v.isBlank() && !out.contains(v)) out.add(v);
            }
            return out;
        }
        return List.of(text);
    }

    private static boolean isNoValueOp(String op) {
        return "empty".equals(op) || "not_empty".equals(op);
    }

    private static boolean hasUnfilledLookupCells(List<Map<String, Object>> lookupFields,
                                                  List<Map<String, Object>> rows,
                                                  String tableId) {
        if (lookupFields == null || rows == null) {
            return false;
        }
        for (Map<String, Object> field : lookupFields) {
            String localKey = trim(String.valueOf(field.getOrDefault("fieldKey", "")));
            if (localKey.isBlank()) {
                continue;
            }
            if (hasUnfilledForField(rows, localKey, tableId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnfilledForField(List<Map<String, Object>> rows, String localKey, String tableId) {
        if (rows == null || localKey.isBlank()) {
            return false;
        }
        for (Map<String, Object> local : rows) {
            if (shouldAutoFillLookupCell(local, localKey, false, tableId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldAutoFillLookupCell(Map<String, Object> local,
                                                    String localKey,
                                                    boolean forceFill,
                                                    String tableId) {
        if (forceFill) {
            return true;
        }
        if (local == null || localKey.isBlank()) {
            return false;
        }
        // 中文备注：协作表仅从未落库的单元格自动同步；用户手动清空("")后不再覆盖。
        return !local.containsKey(localKey);
    }

    private boolean applyLookupFill(String tableId,
                                    String operator,
                                    Map<String, Object> local,
                                    String localKey,
                                    String value,
                                    boolean forceFill) {
        if (local == null || localKey.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        if (!shouldAutoFillLookupCell(local, localKey, forceFill, tableId)) {
            return false;
        }
        if (!persistLookupCell(tableId, operator, local, localKey, value)) {
            return false;
        }
        local.put(localKey, value);
        return true;
    }

    private boolean persistLookupCell(String tableId,
                                      String operator,
                                      Map<String, Object> row,
                                      String fieldKey,
                                      String value) {
        if (tableId == null || tableId.isBlank()) {
            return false;
        }
        if (sheetRowRepository == null) {
            return true;
        }
        String rowId = cellText(row, "opsKey");
        if (rowId.isBlank()) {
            rowId = cellText(row, "bookId");
        }
        if (rowId.isBlank()) {
            return false;
        }
        Map<String, Object> raw = sheetRowRepository.findActive(tableId, rowId);
        if (raw == null) {
            return false;
        }
        Map<String, Object> cells = parseCellsJson(String.valueOf(raw.getOrDefault("cellsJson", "")));
        String existing = trim(String.valueOf(cells.getOrDefault(fieldKey, "")));
        if (existing.equals(value)) {
            row.put("version", raw.get("version"));
            return true;
        }
        cells.put(fieldKey, value);
        int version = raw.get("version") instanceof Number n ? n.intValue() : 1;
        String op = trim(operator);
        if (op.isBlank()) {
            op = "lookup-sync";
        }
        try {
            String json = objectMapper.writeValueAsString(cells);
            int updated = sheetRowRepository.save(tableId, rowId, json, version, op);
            if (updated <= 0) {
                return false;
            }
            row.put("version", version + 1);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> parseCellsJson(String cellsJson) {
        if (cellsJson == null || cellsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(cellsJson, new TypeReference<>() {
            });
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private static boolean isBlankCell(String text) {
        String t = trim(text);
        return t.isEmpty() || "-".equals(t);
    }

    private static Double parseNumber(String text) {
        String t = trim(text).replace(",", "");
        if (t.isEmpty() || "-".equals(t)) {
            return null;
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean looksLikeDate(String text) {
        String t = trim(text);
        return t.matches("^\\d{4}-\\d{2}-\\d{2}.*");
    }

    private static String normalizeDate(String text) {
        String t = trim(text).replace("T", " ");
        if (t.isEmpty() || "-".equals(t)) {
            return "";
        }
        var m = java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})").matcher(t);
        return m.find() ? m.group(1) : "";
    }

    private static String normalizeDateTime(String text) {
        String t = trim(text).replace("T", " ");
        if (t.isEmpty() || "-".equals(t)) {
            return "";
        }
        if (t.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return t + " 00:00:00";
        }
        if (t.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")) {
            return t + ":00";
        }
        var m = java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").matcher(t);
        return m.find() ? m.group(1) : "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadSourceRows(String tableId, List<LookupCondition> conditions) {
        String tid = trim(tableId);
        if (tid.isBlank()) return List.of();
        Map<String, Object> table = tableRegistryRepository.findActive(tid);
        if (table == null || !"blank".equalsIgnoreCase(trim(String.valueOf(table.getOrDefault("kind", ""))))) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> raw : sheetRowRepository.listActive(tid)) {
            Map<String, Object> row = new LinkedHashMap<>();
            String rowId = trim(String.valueOf(raw.getOrDefault("rowId", "")));
            row.put("opsKey", rowId);
            row.put("bookId", rowId);
            Map<String, Object> cells = readCellsMap(String.valueOf(raw.getOrDefault("cellsJson", "")));
            row.putAll(cells);
            out.add(row);
            if (out.size() >= MAX_SOURCE_ROWS) break;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCellsMap(String cellsJson) {
        if (cellsJson == null || cellsJson.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> map = objectMapper.readValue(cellsJson, Map.class);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, String>> parseFieldOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(optionsJson, new TypeReference<>() {
            });
            List<Map<String, String>> out = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                if (item == null) continue;
                String value = trim(String.valueOf(item.getOrDefault("value", item.getOrDefault("label", ""))));
                if (value.isBlank()) continue;
                Map<String, String> opt = new LinkedHashMap<>();
                opt.put("value", value);
                opt.put("label", value);
                out.add(opt);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String cellText(Map<String, Object> row, String key) {
        if (row == null || key == null || key.isBlank()) return "";
        Object raw = row.get(key);
        return trim(raw == null ? null : String.valueOf(raw));
    }

    private static String normalizeCompare(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
