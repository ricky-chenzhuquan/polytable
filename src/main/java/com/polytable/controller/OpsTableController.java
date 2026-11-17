package com.polytable.controller;

import com.polytable.repository.OpsFieldConfigRepository;
import com.polytable.repository.OpsFieldOptionRepository;
import com.polytable.repository.OpsSheetFieldRepository;
import com.polytable.repository.OpsSheetRowRepository;
import com.polytable.repository.OpsTableRegistryRepository;
import com.polytable.repository.OpsTableViewsRepository;
import com.polytable.ops.SheetCellDateSupport;
import com.polytable.service.OpsAuthService;
import com.polytable.service.OpsFieldOptionDefaults;
import com.polytable.service.OpsFormulaEvalService;
import com.polytable.service.OpsSheetListRecordsEncodeCache;
import com.polytable.service.OpsSheetLookupService;
import com.polytable.service.OpsSheetMetaCacheService;
import com.polytable.service.OpsSheetOpLogService;
import com.polytable.service.OpsSheetRecordsEncoder;
import com.polytable.service.OpsSheetRecordsPayloadCache;
import com.polytable.service.OpsSheetRowListService;
import com.polytable.service.OpsSheetRowRecordsService;
import com.polytable.service.OpsSheetSyncPushService;
import com.polytable.service.OpsTenantFeatureService;
import com.polytable.service.OpsUndoSupport;
import com.polytable.service.OpsTenantService;
import com.polytable.service.ServerLoadGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 多维表格协作工作台：通用表（ops_table_registry + ops_sheet_row.cells_json）、
 * 字段/视图/公式/查找引用/协作推送等内核端点。
 */
@RestController
@RequestMapping("/api/v1/ops")
@ConditionalOnProperty(name = "ops.enabled", havingValue = "true", matchIfMissing = true)
public class OpsTableController {
    private static final Logger log = LoggerFactory.getLogger(OpsTableController.class);
    /** 从打包进 jar 的 index.html 注释读取，供发版提示条对比，避免 HTML 被 CDN 缓存后 HEAD 看不出更新。 */
    private static final String OPS_CLIENT_REV = loadOpsClientRev();

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 中文备注：重载闸门——大页加载（DB→对象→公式→编码）每请求堆开销可观，限并发排队，其余 429 让前端重试。
    private static final java.util.concurrent.Semaphore SHEET_LIST_HEAVY_GATE = new java.util.concurrent.Semaphore(8);

    // 中文备注：可在页面「修改字段/列」里维护选项的字段，key 与前端 COLUMN_DEFS 对齐。
    private static final Map<String, String> OPTION_FIELD_LABELS = Map.of(
            "editorAssignee", "执行人",
            "deliveryAssignee", "协作成员",
            "deliveryTest", "测试分配",
            "finalRating", "最终评级",
            "screeningRating", "初审评级",
            "dramaTypeLabel", "剧目类型",
            "copyrightOwner", "版权"
    );

    private static final java.util.Set<String> ALLOWED_FIELD_TYPES = java.util.Set.of(
            "text", "select", "multi", "date", "checkbox", "formula", "lookup"
    );
    // 中文备注：内置列 key 与自定义 custom_* 字段 key 禁止互相占用。
    private static final java.util.Set<String> RESERVED_FIELD_KEYS = java.util.Set.of(
            "actions", "opsKey", "version", "customFields", "formulaValues",
            "rowId", "bookId", "tableId", "updatedBy"
    );

    private final JdbcTemplate jdbcTemplate;
    private final OpsFieldOptionRepository fieldOptionRepository;
    private final OpsFieldConfigRepository fieldConfigRepository;
    private final OpsTableViewsRepository tableViewsRepository;
    private final OpsTableRegistryRepository tableRegistryRepository;
    private final OpsSheetFieldRepository sheetFieldRepository;
    private final OpsSheetRowRepository sheetRowRepository;
    private final OpsFormulaEvalService opsFormulaEvalService;
    private final ObjectMapper objectMapper;
    private final OpsAuthService opsAuthService;
    private final OpsTenantService opsTenantService;
    private final OpsSheetLookupService sheetLookupService;
    private final OpsSheetRowListService sheetRowListService;
    private final OpsSheetRowRecordsService sheetRowRecordsService;
    private final OpsSheetMetaCacheService metaCacheService;
    private final OpsSheetSyncPushService sheetSyncPushService;
    private final OpsSheetOpLogService sheetOpLogService;
    /** 中文备注：SSE 断线回放专用单线程池——与实时广播共用 broadcaster 会互相阻塞,回放异步、帧仍有序(实时帧在另一池)。 */
    private final java.util.concurrent.ExecutorService sseReplayExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ops-sse-replay");
        t.setDaemon(true);
        return t;
    });
    private final OpsSheetRecordsPayloadCache sheetRecordsPayloadCache;
    private final OpsSheetListRecordsEncodeCache sheetListRecordsEncodeCache;
    private final OpsTenantFeatureService tenantFeatureService;
    private final ServerLoadGuard serverLoadGuard;

    public OpsTableController(JdbcTemplate jdbcTemplate,
                              OpsFieldOptionRepository fieldOptionRepository,
                              OpsFieldConfigRepository fieldConfigRepository,
                              OpsTableViewsRepository tableViewsRepository,
                              OpsTableRegistryRepository tableRegistryRepository,
                              OpsSheetFieldRepository sheetFieldRepository,
                              OpsSheetRowRepository sheetRowRepository,
                              OpsFormulaEvalService opsFormulaEvalService,
                              ObjectMapper objectMapper,
                              OpsAuthService opsAuthService,
                              OpsTenantService opsTenantService,
                              OpsSheetLookupService sheetLookupService,
                              OpsSheetRowListService sheetRowListService,
                              OpsSheetRowRecordsService sheetRowRecordsService,
                              OpsSheetMetaCacheService metaCacheService,
                              OpsSheetSyncPushService sheetSyncPushService,
                              OpsSheetOpLogService sheetOpLogService,
                              OpsSheetRecordsPayloadCache sheetRecordsPayloadCache,
                              OpsSheetListRecordsEncodeCache sheetListRecordsEncodeCache,
                              @Autowired(required = false) OpsTenantFeatureService tenantFeatureService,
                              ServerLoadGuard serverLoadGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.fieldOptionRepository = fieldOptionRepository;
        this.fieldConfigRepository = fieldConfigRepository;
        this.tableViewsRepository = tableViewsRepository;
        this.tableRegistryRepository = tableRegistryRepository;
        this.sheetFieldRepository = sheetFieldRepository;
        this.sheetRowRepository = sheetRowRepository;
        this.opsFormulaEvalService = opsFormulaEvalService;
        this.objectMapper = objectMapper;
        this.opsAuthService = opsAuthService;
        this.opsTenantService = opsTenantService;
        this.sheetLookupService = sheetLookupService;
        this.sheetRowListService = sheetRowListService;
        this.sheetRowRecordsService = sheetRowRecordsService;
        this.metaCacheService = metaCacheService;
        this.sheetSyncPushService = sheetSyncPushService;
        this.sheetOpLogService = sheetOpLogService;
        this.sheetRecordsPayloadCache = sheetRecordsPayloadCache;
        this.sheetListRecordsEncodeCache = sheetListRecordsEncodeCache;
        this.tenantFeatureService = tenantFeatureService;
        this.serverLoadGuard = serverLoadGuard;
    }

    /** 中文备注：全员「最近修改」水位——读 ops_sheet_row 最近一次更新；前端标题跨同事同步。 */
    private Map<String, Object> workspaceLastModifyData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lastModifyTime", "");
        data.put("lastModifyBy", "");
        data.put("lastModifyMs", 0L);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT updated_by, update_time
                    FROM ops_sheet_row
                    WHERE deleted = 0
                    ORDER BY update_time DESC
                    LIMIT 1
                    """);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                Object by = row.get("updated_by");
                Object ts = row.get("update_time");
                if (ts instanceof java.sql.Timestamp t && t != null) {
                    data.put("lastModifyTime", t.toLocalDateTime().format(DATE_TIME_FORMATTER));
                    data.put("lastModifyMs", t.getTime());
                }
                data.put("lastModifyBy", by == null ? "" : String.valueOf(by));
            }
        } catch (Exception ignored) {
        }
        return data;
    }

    /** 中文备注：空白表行级审计读 ops_sheet_op_log（通用 schema，SSE op 帧同源）；fieldName 展示时反查列标题。 */
    private Map<String, String> sheetFieldKeyToTitle(String tableId) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
                String key = trimToEmpty((String) field.get("fieldKey"));
                String title = trimToEmpty((String) field.get("fieldTitle"));
                if (!key.isBlank()) {
                    out.putIfAbsent(key, title.isBlank() ? key : title);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private long auditMaxId(String tableId) {
        try {
            Long max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) FROM ops_sheet_op_log WHERE table_id = ?", Long.class, tableId);
            return max == null ? 0L : max;
        } catch (Exception e) {
            return 0L;
        }
    }

    private List<Map<String, Object>> auditEntriesAfter(String tableId, long fromId, String rowId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            out = jdbcTemplate.queryForList(
                    "SELECT id, field_key, old_val, new_val FROM ops_sheet_op_log "
                            + "WHERE table_id = ? AND row_id = ? AND id > ? ORDER BY id ASC",
                    tableId, rowId, fromId);
        } catch (Exception ignored) {
        }
        return out;
    }

    private long insertManualRowLog(String tableId, String rowId, String operator,
                                    String fieldKeyOrTitle, String oldValue, String newValue) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ops_sheet_op_log (table_id, row_id, operator, field_key, old_val, new_val)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, tableId, rowId, operator, fieldKeyOrTitle, oldValue, newValue);
            Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM ops_sheet_op_log", Long.class);
            return id == null ? 0L : id;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> findAuditLogById(long logId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, table_id, row_id, operator, field_key, old_val, new_val, create_time "
                            + "FROM ops_sheet_op_log WHERE id = ?", logId);
            return rows.isEmpty() ? null : auditRowToLog(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> findAuditLogsByIds(List<Long> ids) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return out;
        }
        for (int i = 0; i < ids.size(); i += 500) {
            List<Long> chunk = ids.subList(i, Math.min(i + 500, ids.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT id, table_id, row_id, operator, field_key, old_val, new_val, create_time "
                                + "FROM ops_sheet_op_log WHERE id IN (" + placeholders + ") ORDER BY id ASC",
                        chunk.toArray());
                for (Map<String, Object> row : rows) {
                    out.add(auditRowToLog(row));
                }
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(((Number) a.get("id")).longValue(), ((Number) b.get("id")).longValue()));
        return out;
    }

    /** 审计行 → /logs 条目（fieldName 反查列标题，保持原有展示语义）。 */
    private Map<String, Object> auditRowToLog(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        long id = row.get("id") instanceof Number n ? n.longValue() : 0L;
        String tableId = trimToEmpty((String) row.get("table_id"));
        String rowId = trimToEmpty((String) row.get("row_id"));
        String fieldKey = trimToEmpty((String) row.get("field_key"));
        String title = sheetFieldKeyToTitle(tableId).getOrDefault(fieldKey, fieldKey);
        item.put("id", id);
        item.put("dramaType", sheetLogType(tableId));
        item.put("bookId", rowId);
        item.put("operator", trimToEmpty((String) row.get("operator")));
        item.put("fieldName", title);
        item.put("oldValue", row.get("old_val") == null ? "" : String.valueOf(row.get("old_val")));
        item.put("newValue", row.get("new_val") == null ? "" : String.valueOf(row.get("new_val")));
        Object createTime = row.get("create_time");
        item.put("createTime", createTime instanceof java.sql.Timestamp t ? t.toLocalDateTime().format(DATE_TIME_FORMATTER) : "");
        return item;
    }

    /** 中文备注：查找引用校验/回填样本行——按表加载（协作表走 cells_json，最多 500 行）。 */
    private List<Map<String, Object>> loadLookupSampleRows(String tableId) {
        String tid = trimToEmpty(tableId);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (tid.isBlank()) {
            return rows;
        }
        Map<String, Object> table = tableRegistryRepository.findActive(tid);
        if (table == null || !"blank".equalsIgnoreCase(trimToEmpty((String) table.get("kind")))) {
            return rows;
        }
        try {
            for (Map<String, Object> raw : sheetRowRepository.listActive(tid)) {
                rows.add(toSheetCanvasRow(raw));
                if (rows.size() >= 500) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return rows;
    }

    /** 中文备注：按列 key/标题识别本表可用于逐行匹配的列（对齐前端「剧名/book_id」约定）。 */
    private Map<String, String> detectSheetMatchColumns(List<Map<String, Object>> fields) {
        String seriesKey = "";
        String bookKey = "";
        if (fields != null) {
            for (Map<String, Object> field : fields) {
                String key = trimToEmpty((String) field.get("fieldKey"));
                String title = trimToEmpty((String) field.get("fieldTitle"));
                if (seriesKey.isBlank()
                        && ("seriesName".equalsIgnoreCase(key) || "剧名".equals(title))) {
                    seriesKey = key;
                }
                if (bookKey.isBlank()
                        && ("bookId".equalsIgnoreCase(key) || "book_id".equalsIgnoreCase(key)
                        || "book_id".equalsIgnoreCase(title) || "bookId".equalsIgnoreCase(title))) {
                    bookKey = key;
                }
            }
        }
        Map<String, String> match = new LinkedHashMap<>();
        match.put("seriesNameFieldKey", seriesKey);
        match.put("bookIdFieldKey", bookKey);
        return match;
    }

    @GetMapping("/auth/config")
    public ApiResult<Map<String, Object>> authConfig(HttpServletRequest request) {
        // 中文备注：登录规则、人员名单不对外下发，防止外人照规则拼口令。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokenTtlHours", opsAuthService.tokenTtlHours());
        data.putAll(opsTenantService.publicConfig(opsTenantService.resolveMode(request)));
        data.putAll(effectiveOptionMap());
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：种子名单（仅用于首次入库 / 旧接口禁止删种子项），运行时选项一律读库。
     */
    private List<String> baseOptions(String fieldKey) {
        return OpsFieldOptionDefaults.seedOptions(fieldKey);
    }

    private List<String> effectiveOptions(String fieldKey) {
        return effectiveOptions(fieldKey, null);
    }

    private List<String> effectiveOptions(String fieldKey, OpsAuthService.Session session) {
        // 中文备注：工作区下拉仍读字段配置/选项表，与后台人员名单分离。
        List<String> fromConfig = optionsFromFieldConfig(fieldKey);
        List<String> all;
        if (fromConfig != null) {
            all = fromConfig;
        } else {
            List<String> merged = new ArrayList<>();
            List<String> custom;
            try {
                custom = fieldOptionRepository.listActive(fieldKey);
            } catch (Exception e) {
                custom = List.of();
            }
            for (String value : custom) {
                String v = trimToEmpty(value);
                if (!v.isBlank() && !merged.contains(v)) merged.add(v);
            }
            all = merged;
        }
        return all;
    }

    private List<String> optionsFromFieldConfig(String fieldKey) {
        try {
            Map<String, Object> row = fieldConfigRepository.find(fieldKey);
            if (row == null) return null;
            Object raw = row.get("optionsJson");
            if (raw == null || String.valueOf(raw).isBlank()) return null;
            List<Map<String, Object>> options = parseOptionsJson(String.valueOf(raw));
            List<String> values = new ArrayList<>();
            for (Map<String, Object> opt : options) {
                String v = trimToEmpty(String.valueOf(opt.getOrDefault("value", "")));
                if (!v.isBlank() && !values.contains(v)) values.add(v);
            }
            // 中文备注：空数组视为「未配置」，回落到内置 + 旧选项表，避免一次误保存把全员下拉清空。
            if (values.isEmpty()) return null;
            return values;
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseOptionsJson(String raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || "null".equals(raw)) return out;
        try {
            JsonNode arr = objectMapper.readTree(raw);
            if (!arr.isArray()) return out;
            int i = 0;
            for (JsonNode n : arr) {
                String value = trimToEmpty(n.path("value").asText(n.path("label").asText("")));
                if (value.isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                String color = trimToEmpty(n.path("color").asText(""));
                if (!color.isBlank()) item.put("color", color);
                item.put("sortOrder", n.path("sortOrder").asInt(i));
                out.add(item);
                i++;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private Map<String, Object> buildFieldConfigPayload(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (row == null) return item;
        item.put("fieldKey", row.get("fieldKey"));
        item.put("fieldType", row.get("fieldType"));
        item.put("defaultValue", row.get("defaultValue"));
        item.put("options", parseOptionsJson(String.valueOf(row.getOrDefault("optionsJson", ""))));
        item.put("isCustom", Boolean.TRUE.equals(row.get("isCustom")));
        String fieldTitle = row.get("fieldTitle") == null ? "" : String.valueOf(row.get("fieldTitle"));
        if (fieldTitle.isBlank()) {
            String fieldKey = String.valueOf(row.getOrDefault("fieldKey", ""));
            fieldTitle = OPTION_FIELD_LABELS.getOrDefault(fieldKey, "");
        }
        item.put("fieldTitle", fieldTitle);
        item.put("updatedBy", row.get("updatedBy"));
        String fieldType = trimToEmpty(String.valueOf(row.getOrDefault("fieldType", "text")));
        String masterLinkRaw = String.valueOf(row.getOrDefault("masterLinkJson", ""));
        if ("lookup".equalsIgnoreCase(fieldType)) {
            OpsSheetLookupService.LookupConfig lookup = sheetLookupService.parseConfig(masterLinkRaw);
            if (lookup.enabled()) {
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
                item.put("lookup", lookupMeta);
            }
        }
        return item;
    }

    private Map<String, Object> allFieldConfigsPayload() {
        Map<String, Object> configs = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Map<String, Object>> e : fieldConfigRepository.listAll().entrySet()) {
                configs.put(e.getKey(), buildFieldConfigPayload(e.getValue()));
            }
        } catch (Exception ignored) {
        }
        return configs;
    }

    private Map<String, Object> effectiveOptionMap() {
        return effectiveOptionMap(null);
    }

    private Map<String, Object> effectiveOptionMap(OpsAuthService.Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        String screeningKey = "screeningRating";
        data.put("screeningRatingOptions", effectiveOptions(screeningKey, session));
        data.put("dramaTypeLabelOptions", effectiveOptions("dramaTypeLabel", session));
        data.put("editorAssigneeOptions", effectiveOptions("editorAssignee", session));
        data.put("deliveryAssigneeOptions", effectiveOptions("deliveryAssignee", session));
        data.put("deliveryTestOptions", effectiveOptions("deliveryTest", session));
        data.put("finalRatingOptions", effectiveOptions("finalRating", session));
        data.put("copyrightOwnerOptions", effectiveOptions("copyrightOwner", session));
        data.put("fieldConfigs", allFieldConfigsPayload());
        return data;
    }

    private OpsAuthService.Session sessionOrNull(HttpServletRequest request) {
        try {
            if (!accessAllowed(request)) {
                return null;
            }
            return opsAuthService.requireValidSession(extractToken(request), opsTenantService.resolveMode(request));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> tenantFeatureExtras(HttpServletRequest request, OpsAuthService.Session session) {
        if (tenantFeatureService == null) {
            return Map.of();
        }
        return tenantFeatureService.sessionFeaturePayload(opsTenantService.resolveMode(request), session);
    }

    @GetMapping("/field-options")
    public ApiResult<Map<String, Object>> fieldOptions(HttpServletRequest request) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>(effectiveOptionMap(sessionOrNull(request)));
        Map<String, Object> editable = new LinkedHashMap<>();
        OPTION_FIELD_LABELS.forEach((key, label) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label);
            item.put("options", effectiveOptions(key, sessionOrNull(request)));
            item.put("builtin", baseOptions(key));
            editable.put(key, item);
        });
        data.put("fields", editable);
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：表头「修改字段/列」新增或删除选项；改动落库 + 写操作日志，全员可见。
     */
    @PostMapping("/field-options/save")
    @Transactional
    public ApiResult<Map<String, Object>> saveFieldOption(HttpServletRequest request,
                                                          @RequestBody FieldOptionRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        if (!OPTION_FIELD_LABELS.containsKey(fieldKey)) {
            return ApiResult.fail("A0400", "该字段不支持维护选项：" + fieldKey);
        }
        String action = trimToEmpty(body.action()).isBlank() ? "add" : trimToEmpty(body.action());
        String value = trimToEmpty(body.value());
        if (value.isBlank()) {
            return ApiResult.fail("A0400", "选项内容不能为空");
        }
        if (value.length() > 64) {
            return ApiResult.fail("A0400", "选项内容过长，请控制在 64 字以内");
        }
        String label = OPTION_FIELD_LABELS.get(fieldKey);
        if ("remove".equals(action)) {
            if (baseOptions(fieldKey).contains(value)) {
                return ApiResult.fail("A0400", "内置选项不可删除：" + value);
            }
            // 中文备注：只软删选项定义，不清单元格；同名选项日后可复活。
            fieldOptionRepository.remove(fieldKey, value, operator);
        } else {
            if (effectiveOptions(fieldKey).contains(value)) {
                return ApiResult.fail("A0400", "选项已存在：" + value);
            }
            boolean restored = fieldOptionRepository.existsDeleted(fieldKey, value);
            fieldOptionRepository.add(fieldKey, value, operator);
            if (log.isDebugEnabled()) {
                log.debug("field option saved: fieldKey={}, label={}, value={}, restored={}",
                        fieldKey, label, value, restored);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("options", effectiveOptions(fieldKey, sessionOrNull(request)));
        data.put("operator", operator);
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        return ApiResult.ok(data);
    }

    public record FieldOptionRequest(String fieldKey, String value, String action) {
    }

    public record FieldConfigSaveRequest(String fieldKey,
                                         String fieldType,
                                         String defaultValue,
                                         String title,
                                         List<Map<String, Object>> options,
                                         Map<String, Object> masterLink,
                                         String tableId) {
    }

    public record FieldConfigDeleteRequest(String fieldKey, Boolean purgeValues) {
    }

    public record FieldConfigCreateRequest(String title,
                                           String fieldType,
                                           String defaultValue,
                                           List<Map<String, Object>> options) {
    }
    /**
     * 中文备注：字段类型 / 默认值 / 带颜色选项，全员共享。
     */
    @GetMapping("/field-configs")
    public ApiResult<Map<String, Object>> listFieldConfigs(HttpServletRequest request) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        return ApiResult.ok(data);
    }


    /**
     * 中文备注：新增自定义字段（值存 custom_fields_json，不改物理列）。
     */
    @PostMapping("/field-configs/create")
    @Transactional
    public ApiResult<Map<String, Object>> createFieldConfig(HttpServletRequest request,
                                                            @RequestBody FieldConfigCreateRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String title = trimToEmpty(body == null ? null : body.title());
        if (title.length() > 40) title = title.substring(0, 40);
        if (title.isBlank()) {
            return ApiResult.fail("A0400", "请填写字段标题");
        }
        String fieldType = trimToEmpty(body.fieldType()).isBlank() ? "text" : trimToEmpty(body.fieldType());
        if (!ALLOWED_FIELD_TYPES.contains(fieldType)) {
            return ApiResult.fail("A0400", "不支持的字段类型：" + fieldType);
        }
        String fieldKey = "custom_" + Long.toString(System.currentTimeMillis(), 36)
                + "_" + Integer.toHexString(title.hashCode() & 0xffff);
        if (fieldKey.length() > 64) fieldKey = fieldKey.substring(0, 64);
        if (RESERVED_FIELD_KEYS.contains(fieldKey) || fieldConfigRepository.find(fieldKey) != null) {
            fieldKey = "custom_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (("select".equals(fieldType) || "multi".equals(fieldType)) && body.options() != null) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            int i = 0;
            for (Map<String, Object> opt : body.options()) {
                if (opt == null) continue;
                String value = trimToEmpty(String.valueOf(opt.getOrDefault("value",
                        opt.getOrDefault("label", ""))));
                if (value.isBlank() || value.length() > 64 || !seen.add(value)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                String color = trimToEmpty(String.valueOf(opt.getOrDefault("color", "")));
                if (!color.isBlank() && color.matches("^#[0-9a-fA-F]{6}$")) {
                    item.put("color", color.toLowerCase());
                }
                item.put("sortOrder", i++);
                normalized.add(item);
            }
        }
        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return ApiResult.fail("A0500", "选项序列化失败");
        }
        String defaultValue = trimToEmpty(body.defaultValue());
        fieldConfigRepository.insertCustom(fieldKey, title, fieldType,
                defaultValue.isBlank() ? null : defaultValue, optionsJson, operator);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if ("formula".equals(fieldType)) {
            opsFormulaEvalService.invalidateFormulaFieldCache();
        }
        return ApiResult.ok(data);
    }
    /**
     * 中文备注：表头「修改字段/列」点确定时整包保存：类型 + 选项(含颜色) + 默认值。
     */
    @PostMapping("/field-configs/save")
    @Transactional
    public ApiResult<Map<String, Object>> saveFieldConfig(HttpServletRequest request,
                                                          @RequestBody FieldConfigSaveRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        if (fieldKey.isBlank() || fieldKey.length() > 64) {
            return ApiResult.fail("A0400", "字段 key 无效");
        }
        if ("actions".equals(fieldKey) || "seriesName".equals(fieldKey)) {
            return ApiResult.fail("A0400", "该字段不允许修改类型配置");
        }
        String fieldType = trimToEmpty(body.fieldType()).isBlank() ? "text" : trimToEmpty(body.fieldType());
        if (!ALLOWED_FIELD_TYPES.contains(fieldType)) {
            return ApiResult.fail("A0400", "不支持的字段类型：" + fieldType);
        }
        String defaultValue = trimToEmpty(body.defaultValue());
        if ("date".equals(fieldType) && !defaultValue.isBlank()) {
            defaultValue = SheetCellDateSupport.normalizeDateTime(defaultValue);
        }
        // 中文备注：公式表达式比普通默认值更长，允许到 4000；其它类型仍限 255。
        int maxDefault = "formula".equals(fieldType) ? 4000 : 255;
        if (defaultValue.length() > maxDefault) {
            return ApiResult.fail("A0400", "默认值过长");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (("select".equals(fieldType) || "multi".equals(fieldType)) && body.options() != null) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            int i = 0;
            for (Map<String, Object> opt : body.options()) {
                if (opt == null) continue;
                String value = trimToEmpty(String.valueOf(opt.getOrDefault("value",
                        opt.getOrDefault("label", ""))));
                if (value.isBlank()) continue;
                if (value.length() > 64) {
                    return ApiResult.fail("A0400", "选项内容过长：" + value);
                }
                if (!seen.add(value)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                String color = trimToEmpty(String.valueOf(opt.getOrDefault("color", "")));
                if (!color.isBlank() && color.matches("^#[0-9a-fA-F]{6}$")) {
                    item.put("color", color.toLowerCase());
                }
                item.put("sortOrder", i++);
                normalized.add(item);
            }
        }
        // 中文备注：内置选项字段禁止空选项整包覆盖，避免四表下拉被一次误操作清空。
        if (("select".equals(fieldType) || "multi".equals(fieldType))
                && OPTION_FIELD_LABELS.containsKey(fieldKey)
                && normalized.isEmpty()) {
            return ApiResult.fail("A0400", "「" + OPTION_FIELD_LABELS.get(fieldKey) + "」至少保留一个选项");
        }
        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return ApiResult.fail("A0500", "选项序列化失败");
        }
        List<Map<String, Object>> previousOptions = List.of();
        Map<String, Object> beforeCfg = fieldConfigRepository.find(fieldKey);
        if (beforeCfg != null) {
            previousOptions = parseOptionsJson(String.valueOf(beforeCfg.getOrDefault("optionsJson", "")));
        }
        String masterLinkJson = null;
        if ("lookup".equals(fieldType)) {
            OpsSheetLookupService.LookupConfig lookupConfig = sheetLookupService.parseConfig(
                    body == null ? null : body.masterLink());
            if (!lookupConfig.enabled()) {
                return ApiResult.fail("A0400", "请完整配置查找引用：源表与源字段");
            }
            if (fieldKey.equals(lookupConfig.sourceFieldKey())) {
                return ApiResult.fail("A0400", "查找引用不能引用自身字段");
            }
            String validateTableId = trimToEmpty(body == null ? null : body.tableId());
            List<Map<String, Object>> validateMetas = new ArrayList<>();
            if (!validateTableId.isBlank()) {
                validateMetas.addAll(toSheetFieldMetas(sheetFieldRepository.listActive(validateTableId)));
            }
            validateMetas.addAll(sheetLookupService.buildLookupFieldMetasFromConfigs());
            List<Map<String, Object>> validateRows = loadLookupSampleRows(validateTableId);
            try {
                sheetLookupService.validateOrThrow(lookupConfig, validateMetas, validateRows);
            } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                return ApiResult.fail("A0429", e.getMessage());
            }
            masterLinkJson = sheetLookupService.serializeConfig(lookupConfig);
            defaultValue = "";
            optionsJson = "[]";
        }
        fieldConfigRepository.upsert(fieldKey, fieldType, defaultValue.isBlank() ? null : defaultValue,
                optionsJson, masterLinkJson, operator);
        Map<String, Object> existingCfg = fieldConfigRepository.find(fieldKey);
        if (existingCfg != null
                && (Boolean.TRUE.equals(existingCfg.get("isCustom")) || OPTION_FIELD_LABELS.containsKey(fieldKey))) {
            String title = trimToEmpty(body.title());
            if (title.length() > 40) title = title.substring(0, 40);
            if (!title.isBlank()) {
                fieldConfigRepository.updateTitle(fieldKey, title, operator);
            }
        }
        if (OPTION_FIELD_LABELS.containsKey(fieldKey) && ("select".equals(fieldType) || "multi".equals(fieldType))) {
            syncLegacyFieldOptions(fieldKey, normalized, operator);
        }
        List<String> removedOpts = optionValuesRemoved(previousOptions, normalized);
        if (!removedOpts.isEmpty()) {
            log.info("field config options removed: fieldKey={}, removed={}", fieldKey, removedOpts);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if ("lookup".equals(fieldType)) {
            String syncTableId = trimToEmpty(body == null ? null : body.tableId());
            List<Map<String, Object>> syncRows = loadLookupSampleRows(syncTableId);
            List<Map<String, Object>> syncMetas = new ArrayList<>();
            if (!syncTableId.isBlank()) {
                syncMetas.addAll(toSheetFieldMetas(sheetFieldRepository.listActive(syncTableId)));
            }
            syncMetas.addAll(sheetLookupService.buildLookupFieldMetasFromConfigs());
            try {
                List<Map<String, Object>> lookupSync = sheetLookupService.enrichSheetLookupFields(
                        syncTableId, operator, syncMetas, syncRows, java.util.Set.of(fieldKey));
                data.put("lookupSync", lookupSync == null ? List.of() : lookupSync);
            } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                return ApiResult.fail("A0429", e.getMessage());
            }
        }
        if ("formula".equals(fieldType)
                || (beforeCfg != null && "formula".equalsIgnoreCase(String.valueOf(beforeCfg.get("fieldType"))))) {
            opsFormulaEvalService.invalidateFormulaFieldCache();
        }
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：删除自定义字段定义；默认同步清理各表视图引用与运营行 custom_fields_json。
     * 内置列不可删。字段配置全局共享，删一次四表都生效。
     */
    @PostMapping("/field-configs/delete")
    @Transactional
    public ApiResult<Map<String, Object>> deleteFieldConfig(HttpServletRequest request,
                                                            @RequestBody FieldConfigDeleteRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        if (fieldKey.isBlank() || fieldKey.length() > 64) {
            return ApiResult.fail("A0400", "字段 key 无效");
        }
        if (RESERVED_FIELD_KEYS.contains(fieldKey) || !fieldKey.startsWith("custom_")) {
            return ApiResult.fail("A0400", "仅支持删除自定义字段");
        }
        Map<String, Object> existing = fieldConfigRepository.find(fieldKey);
        if (existing == null || !Boolean.TRUE.equals(existing.get("isCustom"))) {
            return ApiResult.fail("A0404", "自定义字段不存在或已删除");
        }
        String title = trimToEmpty(String.valueOf(existing.getOrDefault("fieldTitle", fieldKey)));
        if (!fieldConfigRepository.deleteCustom(fieldKey)) {
            return ApiResult.fail("A0404", "删除失败，字段可能已被删除");
        }
        try {
            fieldOptionRepository.removeAll(fieldKey, operator);
        } catch (Exception ignored) {
        }
        int viewTouched = stripFieldKeyFromAllTableViews(fieldKey, operator);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("title", title);
        data.put("purged", false);
        data.put("purgedRows", 0);
        data.put("viewsTouched", viewTouched);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if (existing != null && "formula".equalsIgnoreCase(String.valueOf(existing.get("fieldType")))) {
            opsFormulaEvalService.invalidateFormulaFieldCache();
        }
        return ApiResult.ok(data);
    }

    private List<String> optionValuesRemoved(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        java.util.Set<String> next = new java.util.LinkedHashSet<>();
        if (after != null) {
            for (Map<String, Object> opt : after) {
                String v = trimToEmpty(String.valueOf(opt.getOrDefault("value", "")));
                if (!v.isBlank()) next.add(v);
            }
        }
        List<String> removed = new ArrayList<>();
        if (before != null) {
            for (Map<String, Object> opt : before) {
                String v = trimToEmpty(String.valueOf(opt.getOrDefault("value", "")));
                if (!v.isBlank() && !next.contains(v) && !removed.contains(v)) {
                    removed.add(v);
                }
            }
        }
        return removed;
    }

    /**
     * 中文备注：从全部表的 views_json 中摘掉已删字段的列序/隐藏/标签/宽度/填色引用。
     */
    private int stripFieldKeyFromAllTableViews(String fieldKey, String operator) {
        int touched = 0;
        List<String> tableIds;
        try {
            tableIds = tableViewsRepository.listTableIds();
        } catch (Exception e) {
            tableIds = List.of("tbl_all", "tbl_created", "tbl_near", "tbl_promo", "tbl_adx");
        }
        if (tableIds == null || tableIds.isEmpty()) {
            tableIds = List.of("tbl_all", "tbl_created", "tbl_near", "tbl_promo", "tbl_adx");
        }
        for (String tableId : tableIds) {
            String raw = tableViewsRepository.findViewsJson(tableId);
            if (raw == null || raw.isBlank()) continue;
            try {
                JsonNode root = objectMapper.readTree(raw);
                if (!root.isArray()) continue;
                boolean changed = false;
                for (JsonNode viewNode : root) {
                    if (!(viewNode instanceof ObjectNode view)) continue;
                    changed |= stripKeyFromJsonArray(view, "hiddenFields", fieldKey);
                    changed |= stripKeyFromJsonArray(view, "columnOrder", fieldKey);
                    changed |= stripKeyFromJsonObject(view, "columnLabels", fieldKey);
                    changed |= stripKeyFromJsonObject(view, "columnWidths", fieldKey);
                    changed |= stripKeyFromJsonObject(view, "columnFills", fieldKey);
                }
                if (!changed) continue;
                tableViewsRepository.upsert(tableId, objectMapper.writeValueAsString(root), operator);
                touched++;
            } catch (Exception e) {
                System.out.println("协作工作台清理视图字段引用失败 tableId=" + tableId + "：" + e.getMessage());
            }
        }
        return touched;
    }

    private boolean stripKeyFromJsonArray(ObjectNode view, String prop, String fieldKey) {
        JsonNode node = view.get(prop);
        if (node == null || !node.isArray()) return false;
        ArrayNode next = objectMapper.createArrayNode();
        boolean removed = false;
        for (JsonNode item : node) {
            String v = item == null || item.isNull() ? "" : item.asText("");
            if (fieldKey.equals(v)) {
                removed = true;
                continue;
            }
            next.add(item);
        }
        if (!removed) return false;
        view.set(prop, next);
        return true;
    }

    private boolean stripKeyFromJsonObject(ObjectNode view, String prop, String fieldKey) {
        JsonNode node = view.get(prop);
        if (!(node instanceof ObjectNode obj) || !obj.has(fieldKey)) return false;
        obj.remove(fieldKey);
        return true;
    }

    private void syncLegacyFieldOptions(String fieldKey, List<Map<String, Object>> options, String operator) {
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        for (Map<String, Object> opt : options) {
            String v = trimToEmpty(String.valueOf(opt.getOrDefault("value", "")));
            if (!v.isBlank()) wanted.add(v);
        }
        List<String> builtin = baseOptions(fieldKey);
        List<String> current;
        try {
            current = fieldOptionRepository.listActive(fieldKey);
        } catch (Exception e) {
            current = List.of();
        }
        for (String old : current) {
            if (!wanted.contains(old) && !builtin.contains(old)) {
                fieldOptionRepository.remove(fieldKey, old, operator);
            }
        }
        for (String v : wanted) {
            if (builtin.contains(v)) continue;
            if (!current.contains(v)) {
                fieldOptionRepository.add(fieldKey, v, operator);
            }
        }
    }
    /**
     * 中文备注：侧栏表目录 = 系统四表 + 用户自定义表（blank / 历史 filter）。
     */
    @GetMapping("/tables")
    public ApiResult<Map<String, Object>> listTables(HttpServletRequest request) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tenantMode = opsTenantService.resolveMode(request);
        OpsAuthService.Session session = sessionOrNull(request);
        List<Map<String, Object>> custom = new ArrayList<>();
        for (Map<String, Object> row : tableRegistryRepository.listActive()) {
            custom.add(toCustomTableMeta(row));
        }
        custom = opsTenantService.filterCustomTables(tenantMode, custom);
        List<Map<String, Object>> builtin = List.of();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("builtin", builtin);
        data.put("custom", custom);
        data.put("tables", mergeTableCatalog(custom, builtin));
        data.putAll(opsTenantService.publicConfig(tenantMode));
        if (tenantFeatureService != null) {
            data.putAll(tenantFeatureService.sessionFeaturePayload(tenantMode, session));
        }
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：本期新建默认 blank 空白协作表；filter 仅 API 兼容历史。
     */
    @PostMapping("/tables/create")
    @Transactional
    public ApiResult<Map<String, Object>> createTable(HttpServletRequest request,
                                                      @RequestBody TableCreateRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String label = trimToEmpty(body == null ? null : body.label());
        if (label.length() > 30) {
            label = label.substring(0, 30);
        }
        if (label.isBlank()) {
            return ApiResult.fail("A0400", "表格名称不能为空");
        }
        String kind = trimToEmpty(body == null ? null : body.kind()).toLowerCase();
        if (kind.isBlank()) {
            kind = "blank";
        }
        if (!"blank".equals(kind) && !"filter".equals(kind)) {
            return ApiResult.fail("A0400", "不支持的表格类型：" + kind);
        }
        String baseType = trimToEmpty(body == null ? null : body.baseType()).toLowerCase();
        if (baseType.isBlank()) {
            baseType = "all";
        }
        String filtersJson = null;
        if ("filter".equals(kind)) {
            // 中文备注：筛选表仅保留 API 兼容；新仓库无系统表数据源，统一按空白协作表种子。
            if (!"all".equals(baseType)) {
                return ApiResult.fail("A0400", "筛选工作表本期仅支持 baseType=all");
            }
            if (body != null && body.defaultFilters() != null) {
                try {
                    filtersJson = objectMapper.writeValueAsString(body.defaultFilters());
                } catch (Exception e) {
                    return ApiResult.fail("A0400", "defaultFilters 格式错误");
                }
            }
        } else {
            baseType = "blank";
        }
        String tableId = "tbl_u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int sortOrder = tableRegistryRepository.nextSortOrder();
        String tenantMode = opsTenantService.resolveMode(request);
        tableRegistryRepository.insert(tableId, label, kind, baseType, filtersJson, sortOrder, operator, operator, tenantMode);
        seedBlankTableFields(tableId, operator);
        seedBlankTableRows(tableId, operator);
        seedBlankTableMainView(tableId, operator);
        Map<String, Object> created = tableRegistryRepository.findActive(tableId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", toCustomTableMeta(created));
        data.put("views", readSharedViews(tableId));
        if ("blank".equals(kind)) {
            List<Map<String, Object>> fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
            data.put("fields", fieldMetas);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> raw : sheetRowRepository.listActive(tableId)) {
                rows.add(toSheetCanvasRow(raw));
            }
            opsFormulaEvalService.enrichFormulaValues(rows, fieldMetas);
            data.put("rows", rows);
        }
        return ApiResult.ok(data);
    }

    @PostMapping("/tables/rename")
    @Transactional
    public ApiResult<Map<String, Object>> renameTable(HttpServletRequest request,
                                                      @RequestBody TableRenameRequest body) {
        OpsAuthService.Session session;
        try {
            session = requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator = trimToEmpty(session.displayName());
        String tableId = trimToEmpty(body == null ? null : body.tableId());
        String label = trimToEmpty(body == null ? null : body.label());
        if (label.length() > 30) {
            label = label.substring(0, 30);
        }
        if (tableId.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        if (label.isBlank()) {
            return ApiResult.fail("A0400", "表格名称不能为空");
        }
        Map<String, Object> existing = tableRegistryRepository.findActive(tableId);
        if (existing == null) {
            return ApiResult.fail("A0404", "表格不存在或已删除");
        }
        if (!opsTenantService.isCustomTableAllowed(opsTenantService.resolveMode(request), existing)) {
            return ApiResult.fail("A0403", "当前入口无权访问该表");
        }
        if (!canManageTableMeta(session, existing)) {
            return ApiResult.fail("A0403", "仅创建人或管理员可重命名表格");
        }
        if (!tableRegistryRepository.rename(tableId, label, operator)) {
            return ApiResult.fail("A0404", "表格不存在或已删除");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("table", toCustomTableMeta(tableRegistryRepository.findActive(tableId)));
        return ApiResult.ok(data);
    }

    @PostMapping("/tables/delete")
    @Transactional
    public ApiResult<Map<String, Object>> deleteTable(HttpServletRequest request,
                                                      @RequestBody TableDeleteRequest body) {
        OpsAuthService.Session session;
        try {
            session = requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator = trimToEmpty(session.displayName());
        String tableId = trimToEmpty(body == null ? null : body.tableId());
        if (tableId.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        Map<String, Object> existing = tableRegistryRepository.findActive(tableId);
        if (existing == null) {
            return ApiResult.fail("A0404", "表格不存在或已删除");
        }
        if (!opsTenantService.isCustomTableAllowed(opsTenantService.resolveMode(request), existing)) {
            return ApiResult.fail("A0403", "当前入口无权访问该表");
        }
        if (!canManageTableMeta(session, existing)) {
            return ApiResult.fail("A0403", "仅创建人或管理员可删除表格");
        }
        if (!tableRegistryRepository.softDelete(tableId, operator)) {
            return ApiResult.fail("A0404", "表格不存在或已删除");
        }
        tableViewsRepository.deleteByTableId(tableId);
        String kind = trimToEmpty((String) existing.get("kind"));
        if ("blank".equals(kind)) {
            sheetFieldRepository.softDeleteAll(tableId, operator);
            sheetRowRepository.softDeleteAll(tableId, operator);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tableId);
        data.put("deleted", true);
        return ApiResult.ok(data);
    }
    @GetMapping("/sheets/lookup/tables")
    public ApiResult<Map<String, Object>> listLookupTables(HttpServletRequest request,
                                                           @RequestParam(required = false) String currentTableId) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tables", sheetLookupService.listLinkableTables(trimToEmpty(currentTableId)));
        return ApiResult.ok(data);
    }

    @GetMapping("/sheets/lookup/tables/{tableId}/fields")
    public ApiResult<Map<String, Object>> listLookupTableFields(HttpServletRequest request,
                                                                  @PathVariable("tableId") String tableId) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
        String tid = trimToEmpty(tableId);
        if (tid.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tid);
        data.put("fields", sheetLookupService.listFieldsForTable(tid));
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/lookup/validate")
    public ApiResult<Map<String, Object>> validateLookupConfig(HttpServletRequest request,
                                                                @RequestBody Map<String, Object> body) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
        String currentTableId = trimToEmpty(body == null ? null : String.valueOf(body.get("tableId")));
        if (currentTableId.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        OpsSheetLookupService.LookupConfig config = sheetLookupService.parseConfig(body == null ? null : body.get("lookup"));
        if (!config.enabled()) {
            return ApiResult.fail("A0400", "请完整配置查找引用：源表与源字段");
        }
        List<Map<String, Object>> fieldMetas;
        List<Map<String, Object>> rows;
        if (requireActiveBlankTable(currentTableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        rows = new ArrayList<>();
        for (Map<String, Object> raw : sheetRowRepository.listActive(currentTableId)) {
            rows.add(toSheetCanvasRow(raw));
        }
        fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(currentTableId));
        try {
            sheetLookupService.validateOrThrow(config, fieldMetas, rows);
        } catch (OpsSheetLookupService.LookupLimitExceededException e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", false);
            data.put("message", e.getMessage());
            return ApiResult.fail("A0429", e.getMessage(), data);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("matchCount", sheetLookupService.countResultElements(config, fieldMetas, rows));
        return ApiResult.ok(data);
    }

    @GetMapping("/sheets/{tableId}/fields")
    public ApiResult<Map<String, Object>> listSheetFields(HttpServletRequest request,
                                                          @PathVariable("tableId") String tableId) {
        OpsAuthService.Session session;
        try {
            session = requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        Map<String, Object> table = requireActiveBlankTable(tableId, request);
        if (table == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tableId);
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        data.put("canManageMeta", canManageTableMeta(session, table));
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/fields/create")
    @Transactional
    public ApiResult<Map<String, Object>> createSheetField(HttpServletRequest request,
                                                           @PathVariable("tableId") String tableId,
                                                           @RequestBody SheetFieldCreateRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String title = trimToEmpty(body == null ? null : body.title());
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        if (title.isBlank()) {
            title = "未命名列";
        }
        String fieldType = trimToEmpty(body == null ? null : body.fieldType());
        if (fieldType.isBlank()) {
            fieldType = "text";
        }
        if (!ALLOWED_FIELD_TYPES.contains(fieldType)) {
            return ApiResult.fail("A0400", "不支持的字段类型：" + fieldType);
        }
        String fieldKey = "col_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        int sortOrder = sheetFieldRepository.nextSortOrder(tableId);
        sheetFieldRepository.insert(tableId, fieldKey, title, fieldType, "", sortOrder, operator);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("field", toSheetFieldMeta(sheetFieldRepository.findActive(tableId, fieldKey)));
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        return ApiResult.ok(data);
    }

    /** 空白表：从已有单元格去重，供切换单选/多选时生成选项。 */
    @GetMapping("/sheets/{tableId}/fields/{fieldKey}/distinct-values")
    public ApiResult<Map<String, Object>> sheetFieldDistinctValues(HttpServletRequest request,
                                                                  @PathVariable("tableId") String tableId,
                                                                  @PathVariable("fieldKey") String fieldKey,
                                                                  @RequestParam(name = "multi", defaultValue = "false") boolean multi) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String key = trimToEmpty(fieldKey);
        if (key.isBlank() || sheetFieldRepository.findActive(tableId, key) == null) {
            return ApiResult.fail("A0404", "列不存在或已删除");
        }
        List<String> values = distinctFieldValuesFromSheet(tableId, key, multi);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", key);
        data.put("values", values);
        data.put("options", buildOptionItemsFromValues(values));
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/fields/save")
    @Transactional
    public ApiResult<Map<String, Object>> saveSheetField(HttpServletRequest request,
                                                         @PathVariable("tableId") String tableId,
                                                         @RequestBody SheetFieldSaveRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        ApiResult<Map<String, Object>> saved = saveSheetFieldInternal(tableId, operator, body);
        if (!"00000".equals(saved.code())) {
            return saved;
        }
        Map<String, Object> data = new LinkedHashMap<>(saved.data());
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        return ApiResult.ok(data);
    }

    @PostMapping("/formula/preview")
    public ApiResult<Map<String, Object>> previewFormula(HttpServletRequest request,
                                                         @RequestBody FormulaPreviewRequest body) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tableId = trimToEmpty(body == null ? null : body.tableId());
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        String expression = body == null || body.expression() == null ? "" : body.expression().trim();
        Map<String, Object> sampleRow = body == null || body.sampleRow() == null ? Map.of() : body.sampleRow();
        if (fieldKey.isBlank() || expression.isBlank() || expression.length() > 10_000
                || sampleRow.size() > 500) {
            return ApiResult.fail("A0400", "公式预览参数无效");
        }
        List<Map<String, Object>> fieldMetas;
        Map<String, Object> customTable = tableId.startsWith("tbl_u_")
                ? tableRegistryRepository.findActive(tableId) : null;
        if (tableId.startsWith("tbl_u_") && customTable == null) {
            return ApiResult.fail("A0404", "协作表不存在或已删除");
        }
        if ("blank".equals(String.valueOf(customTable == null ? "" : customTable.get("kind")))) {
            if (requireActiveBlankTable(tableId, request) == null) {
                return ApiResult.fail("A0404", "空白表不存在或已删除");
            }
            fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
        } else {
            fieldMetas = new ArrayList<>();
            fieldConfigRepository.listAll().forEach((key, config) -> {
                if (config == null) return;
                Map<String, Object> field = new LinkedHashMap<>(config);
                field.put("fieldKey", key);
                fieldMetas.add(field);
            });
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", opsFormulaEvalService.previewFormula(expression, fieldKey, sampleRow, fieldMetas));
        return ApiResult.ok(data);
    }

    @GetMapping("/formula/functions")
    public ApiResult<Map<String, Object>> formulaFunctions(HttpServletRequest request) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        return ApiResult.ok(Map.of("names", opsFormulaEvalService.formulaFunctionNames(),
                "functions", opsFormulaEvalService.formulaFunctionCatalog()));
    }

    /** 同一粘贴批次复用字段校验与写入，外层事务负责字段和行的一致性。 */
    private ApiResult<Map<String, Object>> saveSheetFieldInternal(String tableId,
                                                                    String operator,
                                                                    SheetFieldSaveRequest body) {
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        if (fieldKey.isBlank()) {
            return ApiResult.fail("A0400", "fieldKey 不能为空");
        }
        if (sheetFieldRepository.findActive(tableId, fieldKey) == null) {
            return ApiResult.fail("A0404", "列不存在或已删除");
        }
        String title = trimToEmpty(body == null ? null : body.title());
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        if (title.isBlank()) {
            return ApiResult.fail("A0400", "列名不能为空");
        }
        String fieldType = trimToEmpty(body == null ? null : body.fieldType());
        if (fieldType.isBlank()) {
            fieldType = "text";
        }
        if (!ALLOWED_FIELD_TYPES.contains(fieldType)) {
            return ApiResult.fail("A0400", "不支持的字段类型：" + fieldType);
        }
        String defaultValue = body == null || body.defaultValue() == null ? "" : String.valueOf(body.defaultValue());
        if ("date".equals(fieldType) && !defaultValue.isBlank()) {
            defaultValue = SheetCellDateSupport.normalizeDateTime(defaultValue);
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        if ("select".equals(fieldType) || "multi".equals(fieldType)) {
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            if (body != null && body.options() != null) {
                for (Map<String, Object> opt : body.options()) {
                    if (opt == null) continue;
                    String value = trimToEmpty(String.valueOf(opt.getOrDefault("value", opt.getOrDefault("label", ""))));
                    if (value.isBlank() || !seen.add(value)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("value", value);
                    String color = trimToEmpty(String.valueOf(opt.getOrDefault("color", "")));
                    if (color.matches("(?i)^#[0-9a-f]{6}$")) {
                        item.put("color", color.toLowerCase());
                    }
                    normalized.add(item);
                }
            }
            if (normalized.isEmpty()) {
                normalized = buildOptionItemsFromValues(
                        distinctFieldValuesFromSheet(tableId, fieldKey, "multi".equals(fieldType)));
            }
        }
        String optionsJson;
        try {
            optionsJson = objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return ApiResult.fail("A0500", "选项序列化失败");
        }
        String masterLinkJson;
        if ("lookup".equals(fieldType)) {
            OpsSheetLookupService.LookupConfig lookupConfig = sheetLookupService.parseConfig(
                    body == null ? null : body.masterLink());
            if (!lookupConfig.enabled()) {
                return ApiResult.fail("A0400", "请完整配置查找引用：源表与源字段");
            }
            List<Map<String, Object>> localRows = new ArrayList<>();
            for (Map<String, Object> raw : sheetRowRepository.listActive(tableId)) {
                localRows.add(toSheetCanvasRow(raw));
            }
            List<Map<String, Object>> fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
            try {
                sheetLookupService.validateOrThrow(lookupConfig, fieldMetas, localRows);
            } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                return ApiResult.fail("A0429", e.getMessage());
            }
            masterLinkJson = sheetLookupService.serializeConfig(lookupConfig);
            defaultValue = "";
        } else {
            // 中文备注：非 lookup 列不启用主表关联，传参仅保留兼容。
            masterLinkJson = null;
        }
        if (!sheetFieldRepository.updateMeta(tableId, fieldKey, title, fieldType,
                defaultValue.isBlank() ? null : defaultValue, optionsJson,
                masterLinkJson,
                operator)) {
            return ApiResult.fail("A0404", "列不存在或已删除");
        }
        int normalizedCells = 0;
        if ("date".equals(fieldType)) {
            normalizedCells = normalizeDateFieldValuesInSheet(tableId, fieldKey, operator);
        } else if ("lookup".equals(fieldType)) {
            List<Map<String, Object>> syncRows = new ArrayList<>();
            for (Map<String, Object> raw : sheetRowRepository.listActive(tableId)) {
                syncRows.add(toSheetCanvasRow(raw));
            }
            List<Map<String, Object>> syncMetas = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
            try {
                sheetLookupService.enrichSheetLookupFields(
                        tableId, operator, syncMetas, syncRows, java.util.Set.of(fieldKey));
            } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                return ApiResult.fail("A0429", e.getMessage());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("field", toSheetFieldMeta(sheetFieldRepository.findActive(tableId, fieldKey)));
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        data.put("normalizedCells", normalizedCells);
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/fields/rename")
    @Transactional
    public ApiResult<Map<String, Object>> renameSheetField(HttpServletRequest request,
                                                           @PathVariable("tableId") String tableId,
                                                           @RequestBody SheetFieldRenameRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        String title = trimToEmpty(body == null ? null : body.title());
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        if (fieldKey.isBlank()) {
            return ApiResult.fail("A0400", "fieldKey 不能为空");
        }
        if (title.isBlank()) {
            return ApiResult.fail("A0400", "列名不能为空");
        }
        if (!sheetFieldRepository.rename(tableId, fieldKey, title, operator)) {
            return ApiResult.fail("A0404", "列不存在或已删除");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("field", toSheetFieldMeta(sheetFieldRepository.findActive(tableId, fieldKey)));
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/fields/delete")
    @Transactional
    public ApiResult<Map<String, Object>> deleteSheetField(HttpServletRequest request,
                                                           @PathVariable("tableId") String tableId,
                                                           @RequestBody SheetFieldDeleteRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String fieldKey = trimToEmpty(body == null ? null : body.fieldKey());
        if (fieldKey.isBlank()) {
            return ApiResult.fail("A0400", "fieldKey 不能为空");
        }
        List<Map<String, Object>> active = sheetFieldRepository.listActive(tableId);
        if (active.size() <= 1) {
            return ApiResult.fail("A0400", "至少保留一列");
        }
        if (!sheetFieldRepository.softDelete(tableId, fieldKey, operator)) {
            return ApiResult.fail("A0404", "列不存在或已删除");
        }
        // 中文备注：从各行 cells_json 去掉该 key，避免幽灵值。
        for (Map<String, Object> row : sheetRowRepository.listActive(tableId)) {
            try {
                Map<String, Object> cells = readCellsMap((String) row.get("cellsJson"));
                if (!cells.containsKey(fieldKey)) {
                    continue;
                }
                cells.remove(fieldKey);
                String json = objectMapper.writeValueAsString(cells);
                int ver = row.get("version") instanceof Number n ? n.intValue() : 1;
                sheetRowRepository.save(tableId, String.valueOf(row.get("rowId")), json, ver, operator);
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("deleted", true);
        data.put("fields", toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        return ApiResult.ok(data);
    }
    @GetMapping("/sheets/{tableId}/rows")
    public ApiResult<Map<String, Object>> listSheetRows(HttpServletRequest request,
                                                        @PathVariable("tableId") String tableId,
                                                        @RequestParam(value = "lookupForce", required = false) String lookupForce,
                                                        @RequestParam(value = "lookupSkip", required = false) String lookupSkip,
                                                        @RequestParam(value = "masterLinkSkip", required = false) String masterLinkSkip,
                                                        @RequestParam(value = "cursor", required = false) String cursor,
                                                        @RequestParam(value = "limit", required = false) Integer limit,
                                                        @RequestParam(value = "keyword", required = false) String keyword,
                                                        @RequestParam(value = "metaOnly", required = false) String metaOnly,
                                                        @RequestParam(value = "sinceMs", required = false) Long sinceMs) {
        OpsAuthService.Session session;
        try {
            session = requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        Map<String, Object> table = requireActiveBlankTable(tableId, request);
        if (table == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        // 中文备注：字段 metas 走 2s 缓存（轮询不再每请求重建 72 个 map）；
        // 水位 2s 缓存 + 写路径 evict，100 人轮询时 COUNT/SUM/MAX 全表扫描从 40 次/s 降到 ~1 次/s。
        List<Map<String, Object>> fields = metaCacheService.fields(tableId,
                () -> sheetFieldRepository.listActive(tableId));
        List<Map<String, Object>> fieldMetas = metaCacheService.fieldMetas(tableId,
                () -> toSheetFieldMetas(fields));
        String syncToken = metaCacheService.syncToken(tableId,
                () -> sheetRowRepository.contentSyncToken(tableId));
        boolean meta = isTruthyQueryFlag(metaOnly);
        long since = sinceMs == null ? 0L : Math.max(0L, sinceMs);
        boolean canManage = canManageTableMeta(session, table);

        if (meta) {
            String key = "sheet|" + tableId + "|rows-meta|" + canManage;
            Map<String, Object> data = metaCacheService.metaPayload(key, () -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tableId", tableId);
                payload.put("fields", fieldMetas);
                payload.put("total", metaCacheService.count(tableId, () -> sheetRowRepository.countActive(tableId)));
                payload.put("sheetSyncToken", syncToken);
                payload.put("canManageMeta", canManage);
                payload.put("rows", List.of());
                return payload;
            });
            return ApiResult.ok(data);
        }

        if (since > 0) {
            List<Map<String, Object>> changed = new ArrayList<>();
            for (Map<String, Object> raw : sheetRowRepository.listActiveUpdatedSince(tableId, since)) {
                changed.add(toSheetCanvasRow(raw));
            }
            opsFormulaEvalService.enrichFormulaValues(changed, fieldMetas);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tableId", tableId);
            data.put("fields", fieldMetas);
            data.put("rows", changed);
            data.put("deletedRowIds", sheetRowRepository.listDeletedRowIdsSince(tableId, since));
            data.put("sheetSyncToken", syncToken);
            data.put("canManageMeta", canManage);
            data.put("partial", true);
            return ApiResult.ok(data);
        }

        String kwEsc = OpsSheetRowListService.escapeLikeKeyword(keyword);
        int pageLimit = sheetRowListService.capPageLimit(limit);
        boolean heavyHeld = tryAcquireHeavyGate();
        if (!heavyHeld) {
            return ApiResult.fail("A0429", "服务器繁忙，请稍后重试");
        }
        try {
            OpsSheetRowRepository.SheetRowPage page = sheetRowRepository.listActivePage(
                    tableId, trimToEmpty(cursor), pageLimit, kwEsc);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> raw : page.rows()) {
                rows.add(toSheetCanvasRow(raw));
            }
            java.util.Set<String> lookupForceKeys = parseLookupForceKeys(lookupForce);
            List<Map<String, Object>> lookupSync = List.of();
            boolean firstPage = trimToEmpty(cursor).isBlank();
            // 中文备注：首屏按查找引用配置回填列值；masterLinkSkip 参数保留兼容（主表关联已下线）。
            if (firstPage && kwEsc.isBlank() && !isTruthyQueryFlag(lookupSkip)) {
                try {
                    lookupSync = sheetLookupService.enrichSheetLookupFields(
                            tableId, trimToEmpty(session.displayName()), fieldMetas, rows, lookupForceKeys);
                } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                    return ApiResult.fail("A0429", e.getMessage());
                }
            }
            opsFormulaEvalService.enrichFormulaValues(rows, fieldMetas);
            int total = firstPage
                    ? (kwEsc.isBlank()
                        ? metaCacheService.count(tableId, () -> sheetRowRepository.countActive(tableId))
                        : sheetRowRepository.countActive(tableId, kwEsc))
                    : -1;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tableId", tableId);
            data.put("table", toCustomTableMeta(table));
            data.put("fields", fieldMetas);
            data.put("rows", rows);
            data.put("lookupSync", lookupSync == null ? List.of() : lookupSync);
            data.put("total", total >= 0 ? total : null);
            data.put("totalHint", total >= 0 ? total : null);
            data.put("nextCursor", page.nextCursor());
            data.put("hasMore", page.hasMore());
            data.put("pageLimit", pageLimit);
            data.put("masterLinkMatch", detectSheetMatchColumns(fields));
            data.put("sheetSyncToken", syncToken);
            data.put("canManageMeta", canManage);
            return ApiResult.ok(data);
        } finally {
            SHEET_LIST_HEAVY_GATE.release();
        }
    }

    private boolean tryAcquireHeavyGate() {
        try {
            return SHEET_LIST_HEAVY_GATE.tryAcquire(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 协作表 records：encoding=2 稀疏列式 gzip 二进制（payloadNonce 另拉）；viewId 列投影；metaOnly 只返 total/fields。
     */
    @GetMapping("/sheets/{tableId}/records")
    public ApiResult<Map<String, Object>> listSheetRecords(HttpServletRequest request,
                                                           @PathVariable("tableId") String tableId,
                                                           @RequestParam(value = "lookupForce", required = false) String lookupForce,
                                                           @RequestParam(value = "lookupSkip", required = false) String lookupSkip,
                                                           @RequestParam(value = "masterLinkSkip", required = false) String masterLinkSkip,
                                                           @RequestParam(value = "cursor", required = false) String cursor,
                                                           @RequestParam(value = "limit", required = false) Integer limit,
                                                           @RequestParam(value = "keyword", required = false) String keyword,
                                                           @RequestParam(value = "metaOnly", required = false) String metaOnly,
                                                           @RequestParam(value = "viewId", required = false) String viewId) {
        OpsAuthService.Session session;
        try {
            session = requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        Map<String, Object> table = requireActiveBlankTable(tableId, request);
        if (table == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        // 中文备注：字段/水位走元数据缓存；100 人轮询时不再每次 COUNT+SUM+MAX 全表扫描。
        List<Map<String, Object>> fields = metaCacheService.fields(tableId,
                () -> sheetFieldRepository.listActive(tableId));
        List<Map<String, Object>> fieldMetas = metaCacheService.fieldMetas(tableId,
                () -> toSheetFieldMetas(fields));
        String syncToken = metaCacheService.syncToken(tableId,
                () -> sheetRowRepository.contentSyncToken(tableId));
        boolean meta = isTruthyQueryFlag(metaOnly);
        boolean canManage = canManageTableMeta(session, table);
        String viewIdTrim = trimToEmpty(viewId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tableId);
        data.put("viewId", viewIdTrim);
        data.put("fields", fieldMetas);
        data.put("sheetSyncToken", syncToken);
        data.put("canManageMeta", canManage);
        data.put("protocol", "ops-sheet-records-v1");
        if (meta) {
            String key = "sheet|" + tableId + "|records-meta|" + viewIdTrim + "|" + canManage;
            Map<String, Object> payload = metaCacheService.metaPayload(key, () -> {
                Map<String, Object> cached = new LinkedHashMap<>();
                cached.put("tableId", tableId);
                cached.put("viewId", viewIdTrim);
                cached.put("fields", fieldMetas);
                cached.put("sheetSyncToken", syncToken);
                cached.put("canManageMeta", canManage);
                cached.put("protocol", "ops-sheet-records-v1");
                cached.put("encoding", null);
                cached.put("records", null);
                cached.put("total", metaCacheService.count(tableId, () -> sheetRowRepository.countActive(tableId)));
                cached.put("totalHint", cached.get("total"));
                cached.put("rows", List.of());
                return cached;
            });
            data.putAll(payload);
            return ApiResult.ok(data);
        }

        String kwEsc = OpsSheetRowListService.escapeLikeKeyword(keyword);
        int pageLimit = sheetRowRecordsService.capRecordsPageLimit(limit);
        boolean firstPage = trimToEmpty(cursor).isBlank();
        boolean lookupSkipped = isTruthyQueryFlag(lookupSkip);
        boolean masterLinkSkipped = isTruthyQueryFlag(masterLinkSkip);
        String cacheKey = firstPage
                ? sheetRecordsEncodeCacheKey(tableId, viewIdTrim, pageLimit, kwEsc,
                        lookupSkipped, masterLinkSkipped, trimToEmpty(lookupForce))
                : null;
        // 中文备注：首屏 gzip 块 90s 编码缓存（key 不含水位；块内记录生成时水位）——
        // 「100 人早上同时打开表」与高频编辑场景都只有极少数请求真正编码；
        // 写操作后旧块继续服务（客户端靠 SSE 推送 + sinceMs 增量收敛，先旧后新），后台串行重编码。
        if (firstPage && cacheKey != null) {
            OpsSheetListRecordsEncodeCache.CachedGzip cached = sheetListRecordsEncodeCache.get(cacheKey);
            if (cached != null) {
                int total = kwEsc.isBlank()
                        ? metaCacheService.count(tableId, () -> sheetRowRepository.countActive(tableId))
                        : sheetRowRepository.countActive(tableId, kwEsc);
                data.put("table", toCustomTableMeta(table));
                data.put("lookupSync", List.of());
                data.put("total", total);
                data.put("totalHint", total);
                data.put("nextCursor", cached.nextCursor());
                data.put("hasMore", cached.hasMore());
                data.put("pageLimit", pageLimit);
                data.put("masterLinkMatch", detectSheetMatchColumns(fields));
                attachRecordsGzipEnvelope(data, viewIdTrim, cached.gzip(), cached.keys(), cached.rowCount());
                data.put("recordsEncodeCacheHit", true);
                boolean stale = !sheetListRecordsEncodeCache.isFresh(cacheKey, syncToken);
                data.put("recordsEncodeCacheStale", stale);
                if (stale && lookupSkipped) {
                    // 中文备注：异步重编码走 lookupSkip=1 路径（前端空白表固定 skip），无需会话上下文。
                    final String fTableId = tableId;
                    final String fViewId = viewIdTrim;
                    final int fPageLimit = pageLimit;
                    final String fKwEsc = kwEsc;
                    final boolean fMasterLinkSkip = masterLinkSkipped;
                    final String fCacheKey = cacheKey;
                    final String fSyncToken = syncToken;
                    sheetListRecordsEncodeCache.scheduleRebuild(fCacheKey,
                            () -> rebuildFirstPageGzip(fTableId, fViewId, fPageLimit, fKwEsc, fMasterLinkSkip,
                                    fCacheKey, fSyncToken));
                }
                return ApiResult.ok(data);
            }
        }
        boolean heavyHeld = tryAcquireHeavyGate();
        if (!heavyHeld) {
            return ApiResult.fail("A0429", "服务器繁忙，请稍后重试");
        }
        try {
            OpsSheetRowRepository.SheetRowPage page = sheetRowRepository.listActivePage(
                    tableId, trimToEmpty(cursor), pageLimit, kwEsc);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> raw : page.rows()) {
                rows.add(toSheetCanvasRow(raw));
            }
            java.util.Set<String> lookupForceKeys = parseLookupForceKeys(lookupForce);
            List<Map<String, Object>> lookupSync = List.of();
            if (firstPage && kwEsc.isBlank() && !masterLinkSkipped && !lookupSkipped) {
                try {
                    lookupSync = sheetLookupService.enrichSheetLookupFields(
                            tableId, trimToEmpty(session.displayName()), fieldMetas, rows, lookupForceKeys);
                } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                    return ApiResult.fail("A0429", e.getMessage());
                }
            }
            opsFormulaEvalService.enrichFormulaValues(rows, fieldMetas);
            int total = firstPage
                    ? (kwEsc.isBlank()
                        ? metaCacheService.count(tableId, () -> sheetRowRepository.countActive(tableId))
                        : sheetRowRepository.countActive(tableId, kwEsc))
                    : -1;
            data.put("table", toCustomTableMeta(table));
            data.put("lookupSync", lookupSync == null ? List.of() : lookupSync);
            data.put("total", total >= 0 ? total : null);
            data.put("totalHint", total >= 0 ? total : null);
            data.put("nextCursor", page.nextCursor());
            data.put("hasMore", page.hasMore());
            data.put("pageLimit", pageLimit);
            data.put("masterLinkMatch", detectSheetMatchColumns(fields));
            if (!firstPage) {
                data.put("encoding", null);
                data.put("records", null);
                data.put("rows", rows);
                return ApiResult.ok(data);
            }
            boolean encodeHeld = sheetListRecordsEncodeCache.acquireEncodePermit();
            try {
                OpsSheetRowRecordsService.SheetRecordsChunk chunk = sheetRowRecordsService.buildChunk(
                        tableId, viewIdTrim, rows, fieldMetas, page, pageLimit);
                sheetListRecordsEncodeCache.put(cacheKey, new OpsSheetListRecordsEncodeCache.CachedGzip(
                        chunk.gzipPayload(), chunk.keys(), chunk.rowCount(), chunk.nextCursor(), chunk.hasMore(),
                        syncToken));
                attachRecordsGzipEnvelope(data, viewIdTrim, chunk.gzipPayload(), chunk.keys(), chunk.rowCount());
                data.put("recordsEncodeCacheHit", false);
            } finally {
                sheetListRecordsEncodeCache.releaseEncodePermit(encodeHeld);
            }
            return ApiResult.ok(data);
        } finally {
            SHEET_LIST_HEAVY_GATE.release();
        }
    }

    /** 中文备注：首屏 records 编码缓存 key——不含水位（旧块服务 + 后台重编码）；flags 决定行内容差异。 */
    private String sheetRecordsEncodeCacheKey(String tableId, String viewId, int pageLimit,
                                              String kwEsc, boolean lookupSkip, boolean masterLinkSkip,
                                              String lookupForce) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("sheet|").append(trimToEmpty(tableId)).append('|').append(trimToEmpty(viewId));
        sb.append('|').append(pageLimit);
        sb.append('|').append(kwEsc == null ? "" : kwEsc.hashCode());
        sb.append('|').append(lookupSkip ? "1" : "0");
        sb.append('|').append(masterLinkSkip ? "1" : "0");
        sb.append('|').append(trimToEmpty(lookupForce));
        return sb.toString();
    }

    /**
     * 中文备注：后台重编码首屏 gzip（lookupSkip=1 路径，无会话上下文）。
     * 走独立闸门与编码许可，失败返回 null 放弃本轮（TTL 内旧块继续服务）。
     */
    private OpsSheetListRecordsEncodeCache.CachedGzip rebuildFirstPageGzip(String tableId, String viewId,
                                                                           int pageLimit, String kwEsc,
                                                                           boolean masterLinkSkip,
                                                                           String cacheKey, String syncToken) {
        if (!sheetListRecordsEncodeCache.acquireEncodePermit()) {
            return null;
        }
        boolean heavyHeld = tryAcquireHeavyGate();
        if (!heavyHeld) {
            sheetListRecordsEncodeCache.releaseEncodePermit(true);
            return null;
        }
        try {
            List<Map<String, Object>> fields = metaCacheService.fields(tableId,
                    () -> sheetFieldRepository.listActive(tableId));
            List<Map<String, Object>> fieldMetas = metaCacheService.fieldMetas(tableId,
                    () -> toSheetFieldMetas(fields));
            OpsSheetRowRepository.SheetRowPage page = sheetRowRepository.listActivePage(
                    tableId, "", pageLimit, kwEsc);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> raw : page.rows()) {
                rows.add(toSheetCanvasRow(raw));
            }
            opsFormulaEvalService.enrichFormulaValues(rows, fieldMetas);
            OpsSheetRowRecordsService.SheetRecordsChunk chunk = sheetRowRecordsService.buildChunk(
                    tableId, trimToEmpty(viewId), rows, fieldMetas, page, pageLimit);
            return new OpsSheetListRecordsEncodeCache.CachedGzip(
                    chunk.gzipPayload(), chunk.keys(), chunk.rowCount(), chunk.nextCursor(), chunk.hasMore(),
                    syncToken);
        } finally {
            SHEET_LIST_HEAVY_GATE.release();
            sheetListRecordsEncodeCache.releaseEncodePermit(true);
        }
    }

    /** 协作表 records gzip 块（与 JSON 信封同 nonce，一次查库）。 */
    @GetMapping("/sheets/{tableId}/records/payload")
    public org.springframework.http.ResponseEntity<byte[]> listSheetRecordsPayload(HttpServletRequest request,
                                                                                 @PathVariable("tableId") String tableId,
                                                                                 @RequestParam("nonce") String nonce) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(401).build();
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        byte[] gzip = sheetRecordsPayloadCache.take(trimToEmpty(nonce));
        if (gzip == null || gzip.length == 0) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("application/gzip"))
                .header("Cache-Control", "no-store")
                .body(gzip);
    }
    /**
     * 变更推送：EventSource 长连接（token 走 query param）；行写入推 dirty 信号 / 单元格级 op 帧，
     * 客户端收到后走既有 metaOnly + sinceMs 增量。轮询仍保留作降级兜底。
     * 断线握手：重连请求自动携带 Last-Event-ID(上次收到的 seq),服务端回放该表 id>seq 的 op;
     * 缺口过大(count=-1)不回放,由客户端整体走 delta。
     */
    @GetMapping("/sheets/{tableId}/sync/stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamSheetSync(HttpServletRequest request,
                                                                                            @PathVariable("tableId") String tableId) {
        try {
            requireSession(request);
        } catch (Exception e) {
            throw new IllegalArgumentException("A0230");
        }
        String tid = trimToEmpty(tableId);
        if (requireActiveBlankTable(tid, request) == null) {
            throw new IllegalArgumentException("A0404");
        }
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = sheetSyncPushService.register(tid);
        String lastEventId = trimToEmpty(request.getHeader("Last-Event-ID"));
        long lastSeq = 0L;
        try {
            lastSeq = lastEventId.isBlank() ? 0L : Long.parseLong(lastEventId);
        } catch (NumberFormatException ignored) {
            lastSeq = 0L;
        }
        if (lastSeq > 0) {
            final long fromSeq = lastSeq;
            final String tableIdFinal = tid;
            final org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitterFinal = emitter;
            // 中文备注：异步回放,不阻塞连接建立;帧带 id,后续实时帧继续按 seq 单调。
            sseReplayExecutor.execute(() -> {
                var replay = sheetOpLogService.replayAfter(tableIdFinal, fromSeq, 500);
                if (replay.count() > 0) {
                    String payload = "{\"t\":\"ops\",\"seq\":" + replay.seq() + ",\"replay\":true,\"ops\":" + replay.opsJson() + "}";
                    sheetSyncPushService.sendTo(emitterFinal, tableIdFinal, replay.seq(), payload);
                }
            });
        }
        return emitter;
    }

    @PostMapping("/sheets/{tableId}/rows")
    @Transactional
    public ApiResult<Map<String, Object>> createSheetRow(HttpServletRequest request,
                                                         @PathVariable("tableId") String tableId) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        Map<String, Object> cells = new LinkedHashMap<>();
        for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
            String key = trimToEmpty((String) field.get("fieldKey"));
            String def = field.get("defaultValue") == null ? "" : String.valueOf(field.get("defaultValue"));
            cells.put(key, def);
        }
        String rowId = "row_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int sortOrder = sheetRowRepository.nextSortOrder(tableId);
        try {
            sheetRowRepository.insert(tableId, rowId, objectMapper.writeValueAsString(cells), sortOrder, operator);
        } catch (Exception e) {
            return ApiResult.fail("A0500", "新增行失败：" + e.getMessage());
        }
        // 中文备注：W1——新增行无旧值可 diff,推 dirty 信号让订阅者立即走 delta 拉新行(毫秒级信号 + 权威数据)。
        metaCacheService.evictRows(tableId, rowId);
        Map<String, Object> raw = sheetRowRepository.findActive(tableId, rowId);
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> row = toSheetCanvasRow(raw);
        opsFormulaEvalService.enrichFormulaValues(List.of(row), toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        data.put("row", row);
        data.put("opsKey", rowId);
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/rows/save")
    @Transactional
    public ApiResult<Map<String, Object>> saveSheetRow(HttpServletRequest request,
                                                       @PathVariable("tableId") String tableId,
                                                       @RequestBody SheetRowSaveRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String rowId = trimToEmpty(body == null ? null : body.rowId());
        if (rowId.isBlank()) {
            rowId = trimToEmpty(body == null ? null : body.opsKey());
        }
        if (rowId.isBlank()) {
            return ApiResult.fail("A0400", "rowId 不能为空");
        }
        Map<String, Object> existing = sheetRowRepository.findActive(tableId, rowId);
        if (existing == null) {
            return ApiResult.fail("A0404", "行不存在或已删除");
        }
        // 中文备注：乐观锁——带 version 保存；与库不一致则拒绝，失败不写操作记录。
        int currentVersion = existing.get("version") instanceof Number n ? n.intValue() : 1;
        int expectedVersion = body == null || body.version() == null ? 0 : body.version();
        if (expectedVersion != currentVersion) {
            return ApiResult.fail("A0409", "这条记录刚被别人修改过，请刷新后再保存");
        }
        Map<String, Object> oldCells = readCellsMap((String) existing.get("cellsJson"));
        Map<String, Object> cells = new LinkedHashMap<>(oldCells);
        Map<String, String> keyToTitle = new LinkedHashMap<>();
        Set<String> allowed = new java.util.HashSet<>();
        for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
            String fieldKey = trimToEmpty((String) field.get("fieldKey"));
            if (fieldKey.isBlank()) {
                continue;
            }
            allowed.add(fieldKey);
            String title = trimToEmpty((String) field.get("fieldTitle"));
            keyToTitle.put(fieldKey, title.isBlank() ? fieldKey : title);
        }
        if (body != null && body.cells() != null) {
            for (Map.Entry<String, Object> e : body.cells().entrySet()) {
                String key = trimToEmpty(e.getKey());
                if (!allowed.contains(key)) {
                    continue;
                }
                cells.put(key, e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        // 中文备注：也接受扁平行字段（与行保存前端 payload 类似）。
        if (body != null && body.fields() != null) {
            for (Map.Entry<String, Object> e : body.fields().entrySet()) {
                String key = trimToEmpty(e.getKey());
                if (!allowed.contains(key)) {
                    continue;
                }
                cells.put(key, e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(cells);
        } catch (Exception e) {
            return ApiResult.fail("A0400", "cells 格式错误");
        }
        // 中文备注：保存前记录审计水位；保存由 OpsSheetRowRepository 统一 diff 写 ops_sheet_op_log，
        // 保存后取回本次写入的审计条目构造 changeLogs（含可撤回日志 id）。
        long auditWatermark = auditMaxId(tableId);
        int updated = sheetRowRepository.save(tableId, rowId, json, expectedVersion, operator);
        if (updated <= 0) {
            // 中文备注：并发下二次校验失败同样不落审计。
            return ApiResult.fail("A0409", "这条记录刚被别人修改过，请刷新后再保存");
        }
        List<Map<String, Object>> auditEntriesThisSave = auditEntriesAfter(tableId, auditWatermark, rowId);
        List<Map<String, Object>> changeLogs = new ArrayList<>();
        Map<String, Map<String, Object>> entriesById = new LinkedHashMap<>();
        for (Map<String, Object> entry : auditEntriesThisSave) {
            entriesById.put(trimToEmpty(String.valueOf(entry.get("field_key"))), entry);
        }
        for (String key : allowed) {
            String oldTrim = trimToEmpty(String.valueOf(oldCells.getOrDefault(key, "")));
            String newTrim = trimToEmpty(String.valueOf(cells.getOrDefault(key, "")));
            if (Objects.equals(oldTrim, newTrim)) {
                continue;
            }
            String title = keyToTitle.getOrDefault(key, key);
            Map<String, Object> entry = entriesById.get(key);
            long logId = entry == null ? 0L
                    : (entry.get("id") instanceof Number n ? n.longValue() : 0L);
            if (logId > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", logId);
                item.put("fieldName", title);
                item.put("fieldKey", key);
                item.put("oldValue", oldTrim);
                item.put("newValue", newTrim);
                changeLogs.add(item);
            }
        }
        Map<String, Object> raw = sheetRowRepository.findActive(tableId, rowId);
        Map<String, Object> savedRow = toSheetCanvasRow(raw);
        opsFormulaEvalService.enrichFormulaValues(List.of(savedRow), toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        Map<String, Object> data = new LinkedHashMap<>(savedRow);
        data.put("updatedBy", operator);
        data.put("changeLogs", changeLogs);
        data.putAll(workspaceLastModifyData());
        return ApiResult.ok(data);
    }

    /** 中文备注：空白表手动补一条操作历史（只写审计，不改单元格）。 */
    @PostMapping("/sheets/{tableId}/rows/{rowId}/logs")
    @Transactional
    public ApiResult<Map<String, Object>> appendSheetRowLog(HttpServletRequest request,
                                                             @PathVariable("tableId") String tableId,
                                                             @PathVariable("rowId") String rowId,
                                                             @RequestBody SheetRowManualLogRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String targetRowId = trimToEmpty(rowId);
        if (targetRowId.isBlank()) {
            return ApiResult.fail("A0400", "rowId 不能为空");
        }
        if (sheetRowRepository.findActive(tableId, targetRowId) == null) {
            return ApiResult.fail("A0404", "行不存在或已删除");
        }
        String fieldName = trimToEmpty(body == null ? "" : body.fieldName());
        if (fieldName.isBlank()) {
            return ApiResult.fail("A0400", "fieldName 不能为空");
        }
        String oldTrim = trimToEmpty(body == null ? "" : body.oldValue());
        String newTrim = trimToEmpty(body == null ? "" : body.newValue());
        if (oldTrim.isBlank() && newTrim.isBlank()) {
            return ApiResult.fail("A0400", "oldValue 与 newValue 不能同时为空");
        }
        // 中文备注：审计落 ops_sheet_op_log；fieldKey 反查为列 key（反查不到时保留原样）。
        String fieldKey = resolveSheetFieldKeyByTitle(tableId, fieldName);
        String storedKey = fieldKey == null || fieldKey.isBlank() ? fieldName : fieldKey;
        long logId = insertManualRowLog(tableId, targetRowId, operator, storedKey, oldTrim, newTrim);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", logId);
        data.put("rowId", targetRowId);
        data.put("fieldName", fieldName);
        data.put("oldValue", oldTrim);
        data.put("newValue", newTrim);
        data.putAll(workspaceLastModifyData());
        return ApiResult.ok(data);
    }

    /** 中文备注：空白表粘贴整批事务；临时 rowId 由服务端建行，任一冲突/失败则全部回滚。 */
    @PostMapping("/sheets/{tableId}/rows/paste-batch")
    @Transactional
    public ApiResult<Map<String, Object>> pasteSheetRowsBatch(HttpServletRequest request,
                                                               @PathVariable("tableId") String tableId,
                                                               @RequestBody SheetPasteBatchRequest body) {
        if (body == null || body.rows() == null || body.rows().isEmpty()) {
            return ApiResult.fail("A0400", "粘贴内容不能为空");
        }
        if (body.rows().size() > 5000) {
            return ApiResult.fail("A0400", "单次最多粘贴 5000 行");
        }
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        // 中文备注：先一次性校验整批版本，冲突在任何字段/行写入前返回，避免客户端看到半批成功。
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Set<String> preflightIds = new LinkedHashSet<>();
        for (SheetRowSaveRequest item : body.rows()) {
            String requestedId = trimToEmpty(item == null ? null : item.rowId());
            if (requestedId.isBlank()) requestedId = trimToEmpty(item == null ? null : item.opsKey());
            if (requestedId.isBlank() || requestedId.startsWith("tmp_")) continue;
            if (!preflightIds.add(requestedId)) {
                conflicts.add(Map.of("rowId", requestedId, "reason", "duplicate"));
                continue;
            }
            Map<String, Object> existing = sheetRowRepository.findActive(tableId, requestedId);
            int expected = item == null || item.version() == null ? 0 : item.version();
            int actual = existing == null ? -1 : ((Number) existing.getOrDefault("version", 1)).intValue();
            if (!Boolean.TRUE.equals(body.force()) && (existing == null || expected != actual)) {
                conflicts.add(Map.of("rowId", requestedId, "expectedVersion", expected, "actualVersion", actual,
                        "reason", existing == null ? "missing" : "version_conflict"));
            }
        }
        if (!conflicts.isEmpty()) {
            return ApiResult.fail("A0409", "批量粘贴存在并发冲突，请刷新后重试或选择覆盖", Map.of("conflicts", conflicts));
        }
        List<Map<String, Object>> beforeFields = new ArrayList<>();
        List<Map<String, Object>> beforeRows = new ArrayList<>();
        List<String> createdRowIds = new ArrayList<>();
        for (Map<String, Object> field : sheetFieldRepository.listActive(tableId)) beforeFields.add(new LinkedHashMap<>(field));
        for (SheetRowSaveRequest item : body.rows()) {
            String id = trimToEmpty(item == null ? null : item.rowId());
            if (id.isBlank()) id = trimToEmpty(item == null ? null : item.opsKey());
            if (id.isBlank() || id.startsWith("tmp_")) continue;
            Map<String, Object> row = sheetRowRepository.findActive(tableId, id);
            if (row != null) beforeRows.add(new LinkedHashMap<>(row));
        }
        for (SheetFieldSaveRequest fieldUpdate : body.fieldUpdates() == null ? List.<SheetFieldSaveRequest>of() : body.fieldUpdates()) {
            ApiResult<Map<String, Object>> fieldResult = saveSheetFieldInternal(tableId, operator, fieldUpdate);
            if (!"00000".equals(fieldResult.code())) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail(fieldResult.code(), fieldResult.msg());
            }
        }
        List<Map<String, Object>> saved = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        boolean allNewRows = body.rows().stream().allMatch(item -> {
            String id = trimToEmpty(item == null ? null : item.rowId());
            if (id.isBlank()) id = trimToEmpty(item == null ? null : item.opsKey());
            return id.isBlank() || id.startsWith("tmp_");
        });
        if (allNewRows) {
            List<Map<String, Object>> activeFields = sheetFieldRepository.listActive(tableId);
            Set<String> allowed = new LinkedHashSet<>();
            Map<String, Object> defaults = new LinkedHashMap<>();
            for (Map<String, Object> field : activeFields) {
                String key = trimToEmpty(String.valueOf(field.get("fieldKey")));
                if (!key.isBlank()) {
                    allowed.add(key);
                    defaults.put(key, field.get("defaultValue") == null ? "" : String.valueOf(field.get("defaultValue")));
                }
            }
            int nextSort = sheetRowRepository.nextSortOrder(tableId);
            List<Object[]> inserts = new ArrayList<>();
            try {
                for (int index = 0; index < body.rows().size(); index++) {
                    SheetRowSaveRequest item = body.rows().get(index);
                    String clientRowId = trimToEmpty(item == null ? null : item.rowId());
                    if (clientRowId.isBlank()) clientRowId = trimToEmpty(item == null ? null : item.opsKey());
                    String rowId = "row_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                    Map<String, Object> cells = new LinkedHashMap<>(defaults);
                    if (item != null && item.cells() != null) for (Map.Entry<String, Object> entry : item.cells().entrySet()) if (allowed.contains(trimToEmpty(entry.getKey()))) cells.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    if (item != null && item.fields() != null) for (Map.Entry<String, Object> entry : item.fields().entrySet()) if (allowed.contains(trimToEmpty(entry.getKey()))) cells.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    inserts.add(new Object[]{tableId, rowId, objectMapper.writeValueAsString(cells), nextSort + index * 10, operator});
                    createdRowIds.add(rowId);
                    Map<String, Object> savedRow = new LinkedHashMap<>();
                    savedRow.put("opsKey", rowId); savedRow.put("bookId", rowId); savedRow.put("dramaType", "blank"); savedRow.put("version", 1); savedRow.put("updatedBy", operator);
                    cells.forEach((key, value) -> savedRow.put(key, value == null ? "" : String.valueOf(value)));
                    savedRow.put("clientRowId", clientRowId);
                    saved.add(savedRow);
                }
                sheetRowRepository.insertBatch(inserts);
                // 中文备注：W1——批量粘贴新增行推 dirty 信号(新行无旧值,op 帧无意义),订阅者立即 delta 拉取。
                if (!createdRowIds.isEmpty()) {
                    metaCacheService.evictRows(tableId, "batch:" + createdRowIds.size());
                }
            } catch (Exception e) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail("A0500", "批量新增行失败：" + e.getMessage());
            }
        } else {
        for (SheetRowSaveRequest item : body.rows()) {
            String requestedId = trimToEmpty(item == null ? null : item.rowId());
            if (requestedId.isBlank()) requestedId = trimToEmpty(item == null ? null : item.opsKey());
            if (!requestedId.isBlank() && !requestedId.startsWith("tmp_") && !seen.add(requestedId)) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail("A0409", "粘贴批次包含重复行：" + requestedId);
            }
            SheetRowSaveRequest effective = item;
            if (requestedId.isBlank() || requestedId.startsWith("tmp_")) {
                ApiResult<Map<String, Object>> created = createSheetRow(request, tableId);
                if (!"00000".equals(created.code()) || created.data() == null) {
                    org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return ApiResult.fail(created.code(), created.msg());
                }
                String rowId = trimToEmpty(String.valueOf(created.data().get("opsKey")));
                createdRowIds.add(rowId);
                effective = new SheetRowSaveRequest(rowId, rowId, 1,
                        item == null ? null : item.cells(), item == null ? null : item.fields());
            } else if (Boolean.TRUE.equals(body.force())) {
                Map<String, Object> existing = sheetRowRepository.findActive(tableId, requestedId);
                if (existing == null) {
                    org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return ApiResult.fail("A0409", "覆盖失败：目标行已不存在");
                }
                int version = ((Number) existing.getOrDefault("version", 1)).intValue();
                effective = new SheetRowSaveRequest(requestedId, requestedId, version,
                        item == null ? null : item.cells(), item == null ? null : item.fields());
            }
            ApiResult<Map<String, Object>> result = saveSheetRow(request, tableId, effective);
            if (!"00000".equals(result.code())) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail(result.code(), result.msg());
            }
            Map<String, Object> savedRow = new LinkedHashMap<>(result.data());
            savedRow.put("clientRowId", requestedId);
            saved.add(savedRow);
        }
        }
        List<Map<String, Object>> responseFields = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
        opsFormulaEvalService.enrichFormulaValues(saved, responseFields);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", saved);
        data.put("count", saved.size());
        data.put("batchId", trimToEmpty(body.batchId()));
        data.put("fields", responseFields);
        try {
            jdbcTemplate.update("""
                    INSERT INTO ops_sheet_paste_batch (batch_id, table_id, operator, source_table_id, target_range,
                      before_fields_json, before_rows_json, created_row_ids_json, summary_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, trimToEmpty(body.batchId()), tableId, operator, trimToEmpty(body.sourceTableId()), trimToEmpty(body.targetRange()),
                    objectMapper.writeValueAsString(beforeFields), objectMapper.writeValueAsString(beforeRows),
                    objectMapper.writeValueAsString(createdRowIds), objectMapper.writeValueAsString(Map.of("success", saved.size(), "newRows", createdRowIds.size(), "skipped", 0, "failed", 0)));
        } catch (Exception e) {
            log.error("sheet paste batch audit write failed: batchId={}, tableId={}", body.batchId(), tableId, e);
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail("A0500", "粘贴审计写入失败");
        }
        return ApiResult.ok(data);
    }

    /** 按批次快照原子恢复字段配置、已有行内容并软删本批新增行。 */
    @PostMapping("/sheets/{tableId}/rows/paste-batch/undo")
    @Transactional
    public ApiResult<Map<String, Object>> undoSheetPasteBatch(HttpServletRequest request, @PathVariable String tableId,
                                                               @RequestBody UndoSheetPasteBatchRequest body) {
        String operator;
        try { operator = trimToEmpty(requireSession(request).displayName()); } catch (Exception e) { return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录"); }
        String batchId = trimToEmpty(body == null ? null : body.batchId());
        List<Map<String, Object>> batches = jdbcTemplate.query("SELECT * FROM ops_sheet_paste_batch WHERE batch_id=? AND table_id=? FOR UPDATE", (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>(); r.put("undone", rs.getInt("undone")); r.put("fields", rs.getString("before_fields_json")); r.put("rows", rs.getString("before_rows_json")); r.put("created", rs.getString("created_row_ids_json")); return r;
        }, batchId, tableId);
        if (batches.isEmpty()) return ApiResult.fail("A0404", "粘贴批次不存在");
        Map<String, Object> batch = batches.get(0);
        if (((Number) batch.get("undone")).intValue() != 0) return ApiResult.fail("A0409", "该批次已撤回");
        try {
            for (Object rawField : objectMapper.readValue(String.valueOf(batch.get("fields")), List.class)) {
                if (!(rawField instanceof Map<?, ?> raw)) continue;
                Map<String, Object> field = new LinkedHashMap<>(); raw.forEach((k, v) -> field.put(String.valueOf(k), v));
                sheetFieldRepository.updateMeta(tableId, String.valueOf(field.get("fieldKey")), String.valueOf(field.get("fieldTitle")), String.valueOf(field.get("fieldType")),
                        field.get("defaultValue") == null ? null : String.valueOf(field.get("defaultValue")), field.get("optionsJson") == null ? "[]" : String.valueOf(field.get("optionsJson")), operator);
            }
            for (Object rawRow : objectMapper.readValue(String.valueOf(batch.get("rows")), List.class)) {
                if (!(rawRow instanceof Map<?, ?> raw)) continue;
                Map<String, Object> row = new LinkedHashMap<>(); raw.forEach((k, v) -> row.put(String.valueOf(k), v));
                String id = String.valueOf(row.get("rowId")); Map<String, Object> current = sheetRowRepository.findActive(tableId, id);
                if (current == null) throw new IllegalStateException("原有行已删除：" + id);
                int version = ((Number) current.get("version")).intValue();
                if (sheetRowRepository.save(tableId, id, String.valueOf(row.get("cellsJson")), version, operator) <= 0) throw new IllegalStateException("行版本冲突：" + id);
            }
            List<String> createdIds = objectMapper.readValue(String.valueOf(batch.get("created")), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (createdIds != null && !createdIds.isEmpty()) {
                sheetRowRepository.softDeleteBatch(tableId, createdIds, operator);
            }
            jdbcTemplate.update("UPDATE ops_sheet_paste_batch SET undone=1 WHERE batch_id=?", batchId);
        } catch (Exception e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail("A0409", "批次撤回失败，未发生部分恢复：" + e.getMessage());
        }
        return ApiResult.ok(Map.of("batchId", batchId, "message", "已整体撤回粘贴批次"));
    }

    /** 纯预检：不写库、不建行，供确认弹窗展示覆盖/新增/只读与主键风险。 */
    @PostMapping("/sheets/{tableId}/paste-preflight")
    public ApiResult<Map<String, Object>> pastePreflight(HttpServletRequest request,
                                                         @PathVariable("tableId") String tableId,
                                                         @RequestBody SheetPastePreflightRequest body) {
        try { requireSession(request); } catch (Exception e) { return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录"); }
        if (requireActiveBlankTable(tableId, request) == null) return ApiResult.fail("A0404", "空白表不存在或已删除");
        List<SheetRowSaveRequest> rows = body == null || body.rows() == null ? List.of() : body.rows();
        int overwrite = 0, newRows = 0, readonly = 0;
        Set<String> ids = new LinkedHashSet<>();
        List<String> conflicts = new ArrayList<>();
        for (SheetRowSaveRequest row : rows) {
            String id = trimToEmpty(row == null ? null : row.rowId());
            if (id.isBlank() || id.startsWith("tmp_")) { newRows++; }
            else if (sheetRowRepository.findActive(tableId, id) != null) { overwrite++; }
            if (!id.isBlank() && !id.startsWith("tmp_") && !ids.add(id)) conflicts.add(id);
            if (row != null && row.cells() != null && row.cells().containsKey("bookId")) readonly++;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overwriteRows", overwrite);
        data.put("newRows", newRows);
        data.put("readonlyCells", readonly);
        data.put("conflicts", conflicts);
        data.put("invalidValues", List.of());
        data.put("skippedDuplicates", List.of());
        data.put("requiresConfirm", newRows > 0 || overwrite > 0 || !conflicts.isEmpty());
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/rows/delete")
    @Transactional
    public ApiResult<Map<String, Object>> deleteSheetRow(HttpServletRequest request,
                                                         @PathVariable("tableId") String tableId,
                                                         @RequestBody SheetRowDeleteRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String rowId = trimToEmpty(body == null ? null : body.rowId());
        if (rowId.isBlank()) {
            rowId = trimToEmpty(body == null ? null : body.opsKey());
        }
        if (rowId.isBlank()) {
            return ApiResult.fail("A0400", "rowId 不能为空");
        }
        if (!sheetRowRepository.softDelete(tableId, rowId, operator)) {
            return ApiResult.fail("A0404", "行不存在或已删除");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rowId", rowId);
        data.put("deleted", true);
        return ApiResult.ok(data);
    }

    @PostMapping("/sheets/{tableId}/rows/restore")
    @Transactional
    public ApiResult<Map<String, Object>> restoreSheetRow(HttpServletRequest request,
                                                          @PathVariable("tableId") String tableId,
                                                          @RequestBody SheetRowDeleteRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        String rowId = trimToEmpty(body == null ? null : body.rowId());
        if (rowId.isBlank()) {
            rowId = trimToEmpty(body == null ? null : body.opsKey());
        }
        if (rowId.isBlank()) {
            return ApiResult.fail("A0400", "rowId 不能为空");
        }
        if (!sheetRowRepository.restore(tableId, rowId, operator)) {
            return ApiResult.fail("A0404", "行不存在或无法恢复");
        }
        Map<String, Object> raw = sheetRowRepository.findActive(tableId, rowId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rowId", rowId);
        data.put("restored", true);
        Map<String, Object> restoredRow = toSheetCanvasRow(raw);
        opsFormulaEvalService.enrichFormulaValues(List.of(restoredRow), toSheetFieldMetas(sheetFieldRepository.listActive(tableId)));
        data.put("row", restoredRow);
        data.put("opsKey", rowId);
        return ApiResult.ok(data);
    }
    private List<Map<String, Object>> mergeTableCatalog(List<Map<String, Object>> custom, List<Map<String, Object>> builtin) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> row : builtin == null ? List.<Map<String, Object>>of() : builtin) {
            all.add(new LinkedHashMap<>(row));
        }
        all.addAll(custom == null ? List.of() : custom);
        return all;
    }

    private Map<String, Object> toCustomTableMeta(Map<String, Object> row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        String tableId = trimToEmpty((String) row.get("tableId"));
        String baseType = trimToEmpty((String) row.get("baseType"));
        if (baseType.isBlank()) {
            baseType = "all";
        }
        meta.put("tableId", tableId);
        meta.put("type", baseType);
        meta.put("baseType", baseType);
        meta.put("label", trimToEmpty((String) row.get("label")));
        meta.put("kind", trimToEmpty((String) row.get("kind")));
        meta.put("owner", trimToEmpty((String) row.get("owner")));
        meta.put("orgType", trimToEmpty((String) row.get("orgType")));
        meta.put("sortOrder", row.get("sortOrder"));
        meta.put("defaultFiltersJson", row.get("defaultFiltersJson"));
        meta.put("builtin", false);
        return meta;
    }
    private void seedBlankTableFields(String tableId, String operator) {
        sheetFieldRepository.insert(tableId, "col_a", "文本", "text", "", 10, operator);
        sheetFieldRepository.insert(tableId, "col_b", "文本 2", "text", "", 20, operator);
        sheetFieldRepository.insert(tableId, "col_c", "文本 3", "text", "", 30, operator);
        sheetFieldRepository.insert(tableId, "col_d", "文本 4", "text", "", 40, operator);
        sheetFieldRepository.insert(tableId, "col_e", "文本 5", "text", "", 50, operator);
    }

    private void seedBlankTableRows(String tableId, String operator) {
        Map<String, Object> cells = new LinkedHashMap<>();
        for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
            cells.put(trimToEmpty((String) field.get("fieldKey")), "");
        }
        try {
            String cellsJson = objectMapper.writeValueAsString(cells);
            for (int i = 1; i <= 5; i++) {
                String rowId = "row_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                sheetRowRepository.insert(tableId, rowId, cellsJson, i * 10, operator);
            }
        } catch (Exception e) {
            throw new IllegalStateException("种子空白表行失败：" + e.getMessage(), e);
        }
    }

    private void seedBlankTableMainView(String tableId, String operator) {
        try {
            Map<String, Object> main = new LinkedHashMap<>();
            String viewId = "vew_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            main.put("id", viewId);
            main.put("viewId", viewId);
            main.put("tableId", tableId);
            main.put("type", "blank");
            main.put("name", "表格");
            main.put("locked", true);
            main.put("protectMode", "public");
            main.put("owner", operator);
            main.put("dateMode", "range");
            main.put("date", "");
            main.put("keyword", "");
            main.put("filterRules", List.of());
            main.put("savedFilterRules", List.of());
            main.put("filterDirty", false);
            main.put("sortField", "");
            main.put("sortDir", "asc");
            main.put("sortRules", List.of());
            main.put("autoSort", true);
            main.put("groupRules", List.of());
            main.put("page", 1);
            main.put("pageSize", 50);
            main.put("hiddenFields", List.of());
            main.put("columnLabels", Map.of());
            main.put("columnWidths", Map.of());
            main.put("columnFills", Map.of());
            main.put("colorRules", List.of());
            main.put("columnOrder", List.of("col_a", "col_b", "col_c", "col_d", "col_e"));
            tableViewsRepository.upsert(tableId, objectMapper.writeValueAsString(List.of(main)), operator);
        } catch (Exception e) {
            throw new IllegalStateException("种子空白表主视图失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> requireActiveBlankTable(String tableId, HttpServletRequest request) {
        Map<String, Object> row = tableRegistryRepository.findActive(trimToEmpty(tableId));
        if (row == null) {
            return null;
        }
        if (!"blank".equalsIgnoreCase(trimToEmpty((String) row.get("kind")))) {
            return null;
        }
        if (request != null && !opsTenantService.isCustomTableAllowed(opsTenantService.resolveMode(request), row)) {
            return null;
        }
        return row;
    }

    private boolean canManageTableMeta(OpsAuthService.Session session, Map<String, Object> tableRow) {
        if (session == null || tableRow == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(trimToEmpty(session.role()))) {
            return true;
        }
        String owner = trimToEmpty((String) tableRow.get("owner"));
        return !owner.isBlank() && owner.equals(trimToEmpty(session.displayName()));
    }
    private void attachRecordsGzipEnvelope(Map<String, Object> data,
                                          String viewId,
                                          byte[] gzip,
                                          List<String> keys,
                                          int rowCount) {
        data.put("protocol", "ops-sheet-records-v1");
        data.put("viewId", viewId == null ? "" : viewId);
        data.put("encoding", OpsSheetRecordsEncoder.ENCODING_GZIP_SPARSE_BINARY);
        data.put("records", null);
        data.put("recordByteLength", gzip == null ? 0 : gzip.length);
        data.put("payloadNonce", sheetRecordsPayloadCache.store(gzip));
        data.put("recordKeys", keys);
        data.put("recordCount", rowCount);
        data.put("rows", List.of());
    }

    private java.util.Set<String> parseLookupForceKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String key = trimToEmpty(part);
            if (!key.isBlank()) {
                out.add(key);
            }
        }
        return out;
    }

    private static boolean isTruthyQueryFlag(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v);
    }
    private List<Map<String, Object>> toSheetFieldMetas(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> row : rows) {
            out.add(toSheetFieldMeta(row));
        }
        return out;
    }

    private Map<String, Object> toSheetFieldMeta(Map<String, Object> row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fieldKey", trimToEmpty((String) row.get("fieldKey")));
        meta.put("key", trimToEmpty((String) row.get("fieldKey")));
        meta.put("fieldTitle", trimToEmpty((String) row.get("fieldTitle")));
        meta.put("label", trimToEmpty((String) row.get("fieldTitle")));
        meta.put("fieldType", trimToEmpty((String) row.get("fieldType")));
        String fieldType = trimToEmpty((String) row.get("fieldType"));
        if (fieldType.isBlank()) {
            fieldType = "text";
        }
        meta.put("type", fieldType);
        meta.put("defaultValue", row.get("defaultValue") == null ? "" : String.valueOf(row.get("defaultValue")));
        meta.put("options", parseOptionsJson(String.valueOf(row.getOrDefault("optionsJson", ""))));
        String masterLinkRaw = String.valueOf(row.getOrDefault("masterLinkJson", ""));
        meta.put("masterLinkJson", masterLinkRaw.isBlank() || "null".equals(masterLinkRaw) ? null : masterLinkRaw);
        if ("lookup".equalsIgnoreCase(fieldType)) {
            OpsSheetLookupService.LookupConfig lookup = sheetLookupService.parseConfig(masterLinkRaw);
            if (lookup.enabled()) {
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
            }
        }
        meta.put("sortOrder", row.get("sortOrder"));
        meta.put("custom", true);
        return meta;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCellsMap(String cellsJson) {
        if (cellsJson == null || cellsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(cellsJson, Map.class);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static final List<String> SHEET_OPTION_COLOR_PALETTE = List.of(
            "#fde2e2", "#fff3e0", "#fff9db", "#e8f5e9", "#e3f2fd", "#f3e5f5",
            "#fce4ec", "#e0f7fa", "#f1f8e9", "#ede7f6", "#ffebee", "#e8eaf6",
            "#fff8e1", "#e0f2f1", "#f9fbe7", "#fafafa");

    private List<String> distinctFieldValuesFromSheet(String tableId, String fieldKey, boolean multi) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : sheetRowRepository.listActive(tableId)) {
            Map<String, Object> cells = readCellsMap((String) row.get("cellsJson"));
            Object raw = cells.get(fieldKey);
            if (raw == null) {
                continue;
            }
            String text = trimToEmpty(String.valueOf(raw));
            if (text.isBlank()) {
                continue;
            }
            if (multi) {
                for (String part : text.split("[、,，|｜]+")) {
                    String value = trimToEmpty(part);
                    if (!value.isBlank()) {
                        seen.add(value);
                    }
                }
            } else {
                seen.add(text);
            }
        }
        return new ArrayList<>(seen);
    }

    private List<Map<String, Object>> buildOptionItemsFromValues(List<String> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return out;
        }
        for (int i = 0; i < values.size(); i++) {
            String value = trimToEmpty(values.get(i));
            if (value.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", value);
            item.put("color", SHEET_OPTION_COLOR_PALETTE.get(i % SHEET_OPTION_COLOR_PALETTE.size()));
            out.add(item);
        }
        return out;
    }

    /** 中文备注：文本列切日期时，把已有单元格时间规范成 yyyy-MM-dd HH:mm:ss。 */
    private int normalizeDateFieldValuesInSheet(String tableId, String fieldKey, String operator) {
        int changed = 0;
        for (Map<String, Object> row : sheetRowRepository.listActive(tableId)) {
            String rowId = trimToEmpty((String) row.get("rowId"));
            if (rowId.isBlank()) {
                continue;
            }
            Map<String, Object> cells = readCellsMap((String) row.get("cellsJson"));
            if (!cells.containsKey(fieldKey)) {
                continue;
            }
            String before = trimToEmpty(String.valueOf(cells.get(fieldKey)));
            if (before.isBlank()) {
                continue;
            }
            String after = SheetCellDateSupport.normalizeDateTime(before);
            if (after.isBlank() || after.equals(before)) {
                continue;
            }
            cells.put(fieldKey, after);
            Object versionObj = row.get("version");
            int version = versionObj instanceof Number n ? n.intValue() : 0;
            try {
                String cellsJson = objectMapper.writeValueAsString(cells);
                if (sheetRowRepository.save(tableId, rowId, cellsJson, version, operator) > 0) {
                    changed++;
                }
            } catch (Exception ignored) {
            }
        }
        return changed;
    }

    private Map<String, Object> toSheetCanvasRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (raw == null) {
            return row;
        }
        String rowId = trimToEmpty((String) raw.get("rowId"));
        row.put("opsKey", rowId);
        row.put("bookId", rowId);
        row.put("dramaType", "blank");
        row.put("version", raw.get("version"));
        row.put("updatedBy", raw.get("updatedBy"));
        if (raw.get("updateTimeMs") != null) {
            row.put("updateTimeMs", raw.get("updateTimeMs"));
        }
        Map<String, Object> cells = readCellsMap((String) raw.get("cellsJson"));
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            row.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return row;
    }

    /**
     * 中文备注：拉取某张表的共享视图（含顺序）；无行时服务端种子默认主视图，禁止客户端用 localStorage 上传补种。
     */
    @GetMapping("/views")
    public ApiResult<Map<String, Object>> listTableViews(HttpServletRequest request,
                                                         @RequestParam String tableId) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tid = trimToEmpty(tableId);
        if (tid.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        List<Map<String, Object>> views = readSharedViews(tid);
        // 中文备注：工作台布局按人存，不种全员共享默认行。
        if (views.isEmpty() && !WORKSPACE_UI_TABLE_ID.equals(tid)) {
            ensureDefaultMainViewIfEmpty(tid, operator);
            views = readSharedViews(tid);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tid);
        data.put("views", views);
        data.put("updatedAt", readViewsUpdatedAt(tid));
        // 中文备注：视图 5s 轮询顺带带上全员「最近修改」，避免只靠 30s /online 导致对端标题不刷新。
        data.putAll(workspaceLastModifyData());
        return ApiResult.ok(data);
    }

    private static final String WORKSPACE_UI_TABLE_ID = "tbl__workspace";
    private static final String WORKSPACE_UI_VIEW_ID = "vew_workspace_layout";

    /**
     * 中文备注：库中无视图时落一颗主视图，避免前端拿本机缓存整表 POST 做种子。
     */
    private void ensureDefaultMainViewIfEmpty(String tableId, String operator) {
        if (!readSharedViews(tableId).isEmpty()) return;
        if (WORKSPACE_UI_TABLE_ID.equals(tableId)) {
            // 中文备注：侧栏宽/折叠改为按人 personal，不再种子全员共享布局。
            return;
        }
        seedBlankTableMainView(tableId, operator);
    }

    /**
     * 中文备注：整表覆盖保存共享视图；拖拽排序 / 新建 / 删除后调用。主视图（locked）至少保留一个。
     * 工作台布局表例外：按人 personal 存侧栏宽/折叠，不强制主视图。
     */
    @PostMapping("/views/save")
    @Transactional
    public ApiResult<Map<String, Object>> saveTableViews(HttpServletRequest request,
                                                         @RequestBody TableViewsSaveRequest body) {
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tid = trimToEmpty(body == null ? null : body.tableId());
        if (tid.isBlank()) {
            return ApiResult.fail("A0400", "tableId 不能为空");
        }
        List<Map<String, Object>> views = body.views() == null ? List.of() : body.views();
        if (views.isEmpty()) {
            return ApiResult.fail("A0400", "至少保留一个视图");
        }
        boolean workspaceUi = WORKSPACE_UI_TABLE_ID.equals(tid);
        if (workspaceUi) {
            // 中文备注：强制本人 personal，避免再写成全员 public 互相覆盖。
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Map<String, Object> raw : views) {
                if (raw == null) continue;
                Map<String, Object> view = new LinkedHashMap<>(raw);
                view.put("tableId", WORKSPACE_UI_TABLE_ID);
                view.put("locked", false);
                view.put("protectMode", "personal");
                view.put("owner", operator);
                if (trimToEmpty(String.valueOf(view.getOrDefault("viewId", view.get("id")))).isBlank()) {
                    view.put("id", WORKSPACE_UI_VIEW_ID + "__" + Integer.toUnsignedString(operator.hashCode()));
                    view.put("viewId", view.get("id"));
                }
                if (trimToEmpty(String.valueOf(view.get("name"))).isBlank()) {
                    view.put("name", "工作台布局");
                }
                normalized.add(view);
            }
            views = normalized;
            if (views.isEmpty()) {
                return ApiResult.fail("A0400", "至少保留一个视图");
            }
        }
        // 中文备注：合并他人视图 + 墓碑，避免 A 保存冲掉 B，也避免旧客户端把已删视图写回。
        // deletedViewIds：协作者删除公共副本时显式进墓碑；个人/锁定不可删。
        List<String> deletedViewIds = body.deletedViewIds() == null ? List.of() : body.deletedViewIds();
        String existingRaw = tableViewsRepository.findViewsJson(tid);
        if (existingRaw != null && !existingRaw.isBlank()) {
            List<Map<String, Object>> existingProbe = readSharedViews(tid);
            if (existingProbe.isEmpty()) {
                // 中文备注：库里有 JSON 却读不出 → 拒绝覆盖，防止一次保存清空全员个人视图。
                return ApiResult.fail("A0500", "视图数据损坏，已拒绝保存以免冲掉他人视图，请联系管理员修复");
            }
        }
        ViewsMergeResult merged = mergePreserveOthersPersonalViews(tid, views, operator, deletedViewIds);
        views = merged.views();
        sanitizeViewFilterValues(views);
        if (!workspaceUi) {
            boolean hasLocked = views.stream().anyMatch(v -> Boolean.TRUE.equals(v.get("locked")));
            if (!hasLocked) {
                Map<String, Object> first = new LinkedHashMap<>(views.get(0));
                first.put("locked", true);
                views = new ArrayList<>(views);
                views.set(0, first);
            }
            // 中文备注：禁止删光主视图——若前端误传全未锁定，上面已补；若显式无 locked 名仍保证至少一个。
            long lockedCount = views.stream().filter(v -> Boolean.TRUE.equals(v.get("locked"))).count();
            if (lockedCount < 1) {
                return ApiResult.fail("A0400", "主视图不能删除");
            }
        }
        try {
            String json = objectMapper.writeValueAsString(views);
            String tombstoneJson = objectMapper.writeValueAsString(merged.tombstones());
            tableViewsRepository.upsert(tid, json, tombstoneJson, operator);
        } catch (Exception e) {
            return ApiResult.fail("A0500", "保存视图失败：" + e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tableId", tid);
        data.put("views", readSharedViews(tid));
        data.put("updatedAt", readViewsUpdatedAt(tid));
        data.put("updatedBy", operator);
        return ApiResult.ok(data);
    }

    private String readViewsUpdatedAt(String tableId) {
        java.time.LocalDateTime t = tableViewsRepository.findUpdateTime(tableId);
        return t == null ? "" : t.toString();
    }

    /**
     * 中文备注：旧客户端把多选筛选 value 多转义成 {@code "value":"[\\"A\\"]"}，整包 views_json 无法解析。
     * 读出前尝试修一层，避免 GET 空列表、列序/筛选无法上云。
     */
    private String repairViewsJsonEscaping(String json) {
        if (json == null || json.isBlank()) return json;
        String fixed = json.replace("\"value\":\"[\\\\\"", "\"value\":\"[\\\"");
        fixed = fixed.replace("\\\\\",\\\\\"", "\\\",\\\"");
        fixed = fixed.replace("\\\\\"]\"", "\\\"]\"");
        return fixed;
    }

    /** 中文备注：把筛选 value 收敛成可再序列化的干净字符串，避免再次写出非法 JSON。 */
    @SuppressWarnings("unchecked")
    private void sanitizeViewFilterValues(List<Map<String, Object>> views) {
        if (views == null) return;
        for (Map<String, Object> view : views) {
            if (view == null) continue;
            sanitizeFilterRulesList(view.get("filterRules"));
            sanitizeFilterRulesList(view.get("savedFilterRules"));
            sanitizeColumnOrderForBuiltinTable(view);
        }
    }

    @SuppressWarnings("unchecked")
    private void sanitizeFilterRulesList(Object raw) {
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<String, Object> rule = (Map<String, Object>) item;
            Object value = rule.get("value");
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                rule.put("value", "");
                continue;
            }
            if (text.startsWith("[") && text.contains("\\\"")) {
                String cleaned = text.replace("\\\"", "\"");
                try {
                    objectMapper.readTree(cleaned);
                    rule.put("value", cleaned);
                    continue;
                } catch (Exception ignored) {
                    // fall through
                }
            }
            rule.put("value", text);
        }
    }

    @SuppressWarnings("unchecked")
    private void sanitizeColumnOrderForBuiltinTable(Map<String, Object> view) {
        if (view == null) return;
        String tableId = trimToEmpty(String.valueOf(view.getOrDefault("tableId", "")));
        // 中文备注：系统剧目表不准残留空白表 col_*，否则前端列序膨胀、轮询像被挪列。
        if (tableId.isBlank() || tableId.startsWith("tbl_u_")) return;
        Object raw = view.get("columnOrder");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return;
        List<Object> cleaned = new ArrayList<>();
        for (Object item : list) {
            String key = trimToEmpty(item == null ? null : String.valueOf(item));
            if (key.isBlank() || key.startsWith("col_")) continue;
            cleaned.add(key);
        }
        if (cleaned.size() != list.size()) {
            view.put("columnOrder", cleaned);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readSharedViews(String tableId) {
        String json = tableViewsRepository.findViewsJson(tableId);
        if (json == null || json.isBlank()) return List.of();
        try {
            boolean neededRepair = false;
            List<Map<String, Object>> list;
            try {
                list = objectMapper.readValue(json, List.class);
            } catch (Exception first) {
                String repaired = repairViewsJsonEscaping(json);
                if (repaired.equals(json)) throw first;
                list = objectMapper.readValue(repaired, List.class);
                neededRepair = true;
            }
            if (list == null) return List.of();
            // 中文备注：读出时补齐 protectMode/owner，避免旧数据刷新后保护级别丢失。
            for (Map<String, Object> view : list) {
                if (view == null) continue;
                boolean isMain = Boolean.TRUE.equals(view.get("locked"));
                String mode = normalizeProtectMode(view.get("protectMode"), isMain);
                view.put("protectMode", mode);
                if (view.get("owner") == null) {
                    view.put("owner", "");
                } else {
                    view.put("owner", trimToEmpty(String.valueOf(view.get("owner"))));
                }
            }
            // 中文备注：历史整表复制曾把每个副本都标成 locked，造成一张表出现多个“主视图”且全都不能删。
            // 每张表只保留顺序第一项为主视图，其余历史副本降级为公共普通视图，配置和数据原样保留。
            if (demoteDuplicateLockedViews(list)) {
                neededRepair = true;
            }
            sanitizeViewFilterValues(list);
            // 中文备注：损坏 JSON 读通后落回干净快照，避免每次 GET/合并仍踩坑。
            if (neededRepair) {
                try {
                    tableViewsRepository.upsert(tableId, objectMapper.writeValueAsString(list), null, "system-repair");
                } catch (Exception ignored) {
                    // 中文备注：自愈失败不阻断读；下次保存仍会再写。
                }
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    static boolean demoteDuplicateLockedViews(List<Map<String, Object>> views) {
        boolean foundMain = false;
        boolean changed = false;
        for (Map<String, Object> view : views == null ? List.<Map<String, Object>>of() : views) {
            if (view == null || !Boolean.TRUE.equals(view.get("locked"))) continue;
            if (!foundMain) {
                foundMain = true;
                continue;
            }
            view.put("locked", false);
            view.put("protectMode", "public");
            changed = true;
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSharedViewsJson(String json) throws Exception {
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, List.class);
            return list == null ? List.of() : list;
        } catch (Exception first) {
            String repaired = repairViewsJsonEscaping(json);
            if (repaired.equals(json)) throw first;
            List<Map<String, Object>> list = objectMapper.readValue(repaired, List.class);
            return list == null ? List.of() : list;
        }
    }

    private record ViewsMergeResult(List<Map<String, Object>> views, List<String> tombstones) {
    }

    private static final int VIEW_TOMBSTONE_CAP = 2000;

    @SuppressWarnings("unchecked")
    private LinkedHashSet<String> readViewTombstones(String tableId) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String json = tableViewsRepository.findTombstoneJson(tableId);
        if (json == null || json.isBlank()) return out;
        try {
            List<Object> list = objectMapper.readValue(json, List.class);
            if (list == null) return out;
            for (Object item : list) {
                String id = trimToEmpty(item == null ? null : String.valueOf(item));
                if (!id.isBlank()) out.add(id);
            }
        } catch (Exception ignored) {
            // 中文备注：坏 JSON 当空墓碑，不阻断保存。
        }
        return out;
    }

    /**
     * 中文备注：整表覆盖保存时，保留「他人」的个人/公共/锁定视图，避免某人本机缺 tab 就把别人新建的冲掉。
     * 当前用户自己的视图以本次提交为准（可删可改）；删掉的 id 进墓碑，拒绝旧客户端复活。
     * deletedViewIds：显式删除仅公共非主视图（不要求 owner=本人）；个人/锁定不可删。
     */
    @SuppressWarnings("unchecked")
    private ViewsMergeResult mergePreserveOthersPersonalViews(String tableId,
                                                              List<Map<String, Object>> incoming,
                                                              String operator,
                                                              List<String> deletedViewIds) {
        List<Map<String, Object>> existing = readSharedViews(tableId);
        Map<String, Map<String, Object>> existingById = new LinkedHashMap<>();
        for (Map<String, Object> old : existing) {
            if (old == null) continue;
            String id = trimToEmpty(String.valueOf(old.getOrDefault("viewId", old.get("id"))));
            if (!id.isBlank()) existingById.put(id, old);
        }
        LinkedHashSet<String> tombstones = readViewTombstones(tableId);
        LinkedHashSet<String> explicitDelete = new LinkedHashSet<>();
        if (deletedViewIds != null) {
            for (String rawId : deletedViewIds) {
                String id = trimToEmpty(rawId);
                if (!id.isBlank()) explicitDelete.add(id);
            }
        }
        // 中文备注：显式删除仅允许公共非主视图；个人 / 锁定（保护）不可删。
        for (Map<String, Object> old : existing) {
            if (old == null) continue;
            String id = trimToEmpty(String.valueOf(old.getOrDefault("viewId", old.get("id"))));
            if (id.isBlank() || !explicitDelete.contains(id)) continue;
            boolean isMain = Boolean.TRUE.equals(old.get("locked"));
            if (isMain) continue;
            String mode = normalizeProtectMode(old.get("protectMode"), isMain);
            if (!"public".equals(mode)) continue;
            tombstones.add(id);
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Map<String, Object> raw : incoming) {
            if (raw == null) continue;
            Map<String, Object> view = new LinkedHashMap<>(raw);
            // 中文备注：仅保护级别变更请求带此标记；勿落库。
            Object touchRaw = view.remove("protectModeTouch");
            boolean protectModeTouch = Boolean.TRUE.equals(touchRaw)
                    || "true".equalsIgnoreCase(trimToEmpty(touchRaw == null ? null : String.valueOf(touchRaw)));
            String id = trimToEmpty(String.valueOf(view.getOrDefault("viewId", view.get("id"))));
            // 中文备注：已进墓碑的 id 一律丢弃，防止本机旧缓存 / 他人旧客户端写回。
            if (!id.isBlank() && tombstones.contains(id)) {
                continue;
            }
            Map<String, Object> old = id.isBlank() ? null : existingById.get(id);
            if (old != null) {
                String oldMode = normalizeProtectMode(old.get("protectMode"), Boolean.TRUE.equals(old.get("locked")));
                String oldOwner = trimToEmpty(old.get("owner") == null ? null : String.valueOf(old.get("owner")));
                // 中文备注：他人个人视图绝不接受同 id 覆盖。旧客户端仍持有 public 快照时，
                // 若进入 cleaned，后面 existing 补回会被 incomingIds 短路，personal 就会被打回 public。
                if ("personal".equals(oldMode) && !oldOwner.isBlank() && !oldOwner.equals(operator)) {
                    continue;
                }
                // 中文备注：本人个人视图若无显式 touch，禁止被过期 public/locked 快照降级（多标签页脏写）。
                String incomingMode = normalizeProtectMode(view.get("protectMode"), Boolean.TRUE.equals(view.get("locked")));
                if ("personal".equals(oldMode) && oldOwner.equals(operator)
                        && !"personal".equals(incomingMode) && !protectModeTouch) {
                    view.put("protectMode", "personal");
                    view.put("owner", oldOwner);
                }
            }
            String mode = normalizeProtectMode(view.get("protectMode"), Boolean.TRUE.equals(view.get("locked")));
            view.put("protectMode", mode);
            String owner = trimToEmpty(view.get("owner") == null ? null : String.valueOf(view.get("owner")));
            if ("personal".equals(mode)) {
                // 中文备注：丢弃「冒充他人」的个人视图提交，后面从已有记录补回真正所有者的。
                if (!owner.isBlank() && !owner.equals(operator)) {
                    continue;
                }
                view.put("owner", operator);
                // 主视图不允许个人可见。
                if (Boolean.TRUE.equals(view.get("locked"))) {
                    view.put("protectMode", "public");
                }
            } else if ("locked".equals(mode) && owner.isBlank()) {
                view.put("owner", operator);
            } else if ("public".equals(mode) && owner.isBlank()) {
                // 保持空或原样
            }
            cleaned.add(view);
        }
        Set<String> incomingIds = cleaned.stream()
                .map(v -> trimToEmpty(String.valueOf(v.getOrDefault("viewId", v.get("id")))))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (Map<String, Object> old : existing) {
            if (old == null) continue;
            String id = trimToEmpty(String.valueOf(old.getOrDefault("viewId", old.get("id"))));
            if (!id.isBlank() && incomingIds.contains(id)) continue;
            if (!id.isBlank() && tombstones.contains(id)) continue;
            String mode = normalizeProtectMode(old.get("protectMode"), Boolean.TRUE.equals(old.get("locked")));
            String owner = trimToEmpty((String) old.get("owner"));
            boolean isMain = Boolean.TRUE.equals(old.get("locked"));
            // 中文备注：无 owner 的主视图仍全员共享；其它无主视图不强留，避免删不掉僵尸 tab。
            if (owner.isBlank()) {
                if (isMain && "public".equals(mode)) {
                    cleaned.add(new LinkedHashMap<>(old));
                } else if (!id.isBlank() && !isMain) {
                    tombstones.add(id);
                }
                continue;
            }
            if (owner.equals(operator)) {
                // 中文备注：本人提交缺失时，仅公共非主视图记墓碑；个人/锁定不可通过省略删除。
                if (!id.isBlank() && !isMain && "public".equals(mode)) {
                    tombstones.add(id);
                } else if (!id.isBlank()) {
                    cleaned.add(new LinkedHashMap<>(old));
                }
                continue;
            }
            // 中文备注：他人个人/公共/锁定视图都保留；否则 A 新建公共副本后，B 旧客户端一保存就会冲掉。
            cleaned.add(new LinkedHashMap<>(old));
        }
        List<String> tombstoneList = new ArrayList<>(tombstones);
        if (tombstoneList.size() > VIEW_TOMBSTONE_CAP) {
            tombstoneList = new ArrayList<>(tombstoneList.subList(
                    tombstoneList.size() - VIEW_TOMBSTONE_CAP, tombstoneList.size()));
        }
        return new ViewsMergeResult(cleaned, tombstoneList);
    }

    private String normalizeProtectMode(Object raw, boolean isMainView) {
        String mode = trimToEmpty(raw == null ? null : String.valueOf(raw)).toLowerCase();
        if (!Set.of("public", "locked", "personal").contains(mode)) {
            mode = "public";
        }
        if (isMainView && "personal".equals(mode)) {
            return "public";
        }
        return mode;
    }

    public record TableViewsSaveRequest(String tableId,
                                        List<Map<String, Object>> views,
                                        List<String> deletedViewIds) {
    }

    public record TableCreateRequest(String label, String kind, String baseType, List<Map<String, Object>> defaultFilters) {
    }

    public record TableRenameRequest(String tableId, String label) {
    }

    public record TableDeleteRequest(String tableId) {
    }

    public record SheetFieldCreateRequest(String title, String fieldType) {
    }

    public record SheetFieldRenameRequest(String fieldKey, String title) {
    }

    public record SheetFieldSaveRequest(String fieldKey, String title, String fieldType,
                                        String defaultValue, List<Map<String, Object>> options,
                                        Map<String, Object> masterLink) {
    }

    public record FormulaPreviewRequest(String tableId, String fieldKey, String expression,
                                        Map<String, Object> sampleRow) {
    }

    public record SheetFieldDeleteRequest(String fieldKey) {
    }

    public record SheetRowSaveRequest(String rowId, String opsKey, Integer version,
                                      Map<String, Object> cells, Map<String, Object> fields) {
    }

    public record SheetPasteBatchRequest(String batchId, List<SheetRowSaveRequest> rows,
                                         List<SheetFieldSaveRequest> fieldUpdates, Boolean force,
                                         String sourceTableId, String targetRange) {
    }

    public record SheetPastePreflightRequest(List<SheetRowSaveRequest> rows) {
    }

    public record UndoSheetPasteBatchRequest(String batchId) {}

    public record SheetRowDeleteRequest(String rowId, String opsKey) {
    }
    @PostMapping("/auth/login")
    public ApiResult<Map<String, Object>> login(HttpServletRequest request, @RequestBody LoginRequest body) {
        try {
            OpsAuthService.LoginResult result = opsAuthService.login(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.password(),
                    opsTenantService.resolveMode(request)
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", result.token());
            data.putAll(opsAuthService.sessionView(result.session()));
            data.putAll(tenantFeatureExtras(request, result.session()));
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    /** 中文备注：发版探测，不登录也可读；走 /api 以免 HTML 被 CDN 缓存后 HEAD 看不出更新。 */
    @GetMapping("/client-version")
    public ApiResult<Map<String, Object>> clientVersion(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate");
        return ApiResult.ok(Map.of("rev", OPS_CLIENT_REV == null ? "" : OPS_CLIENT_REV));
    }

    @GetMapping("/auth/me")
    public ApiResult<Map<String, Object>> me(HttpServletRequest request) {
        try {
            OpsAuthService.Session session = requireSession(request);
            Map<String, Object> view = new LinkedHashMap<>(opsAuthService.sessionView(session));
            view.putAll(tenantFeatureExtras(request, session));
            return ApiResult.ok(view);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }
    /** 中文备注：页面仍在使用时换发令牌，避免固定 24 小时到点中断编辑。 */
    @PostMapping("/auth/refresh")
    public ApiResult<Map<String, Object>> refreshLogin(HttpServletRequest request) {
        try {
            OpsAuthService.LoginResult result = opsAuthService.refreshToken(
                    extractToken(request),
                    opsTenantService.resolveMode(request)
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", result.token());
            data.putAll(opsAuthService.sessionView(result.session()));
            data.putAll(tenantFeatureExtras(request, result.session()));
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    @PostMapping("/auth/logout")
    public ApiResult<Map<String, Object>> logout(HttpServletRequest request) {
        opsAuthService.logout(extractToken(request));
        return ApiResult.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/online")
    public ApiResult<Map<String, Object>> onlineUsers(HttpServletRequest request) {
        try {
            // 中文备注：查询在线列表同时续心跳，页面轮询即可保持自己在线。登录永不因负载拒绝。
            requireSession(request);
            String tenantMode = opsTenantService.resolveMode(request);
            List<Map<String, Object>> users = opsAuthService.listOnlineUsers(tenantMode);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", users.size());
            data.put("users", users);
            data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            data.putAll(opsTenantService.publicConfig(tenantMode));
            // 中文备注：全员最近保存时间，供标题「最近修改」跨同事同步。
            data.putAll(workspaceLastModifyData());
            data.putAll(serverLoadGuard.snapshot());
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    /**
     * 中文备注：轻量水位——仅「最近修改」+ serverTime，供前端 2s 快轮询，保证多窗口/多端文案一致。
     */
    @GetMapping("/workspace/last-modify")
    public ApiResult<Map<String, Object>> workspaceLastModify(HttpServletRequest request) {
        try {
            requireSession(request);
            Map<String, Object> data = new LinkedHashMap<>(workspaceLastModifyData());
            data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            data.put("serverTimeMs", System.currentTimeMillis());
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    /**
     * 中文备注：左上角「最近修改」抽屉——全站近 1 天操作时间线。
     */
    @GetMapping("/workspace/activity")
    public ApiResult<Map<String, Object>> workspaceActivity(HttpServletRequest request,
                                                              @RequestParam(required = false, defaultValue = "80") int limit) {
        try {
            requireSession(request);
            int safeLimit = Math.min(200, Math.max(1, limit));
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT id, table_id, row_id, operator, field_key, old_val, new_val, create_time
                        FROM ops_sheet_op_log
                        WHERE create_time >= (NOW() - INTERVAL 1 DAY)
                        ORDER BY id DESC
                        LIMIT ?
                        """, safeLimit);
                for (Map<String, Object> row : rows) {
                    Map<String, Object> item = auditRowToLog(row);
                    item.put("operatorDisplay", item.get("operator"));
                    item.put("activityText", item.get("fieldName"));
                    items.add(item);
                }
            } catch (Exception ignored) {
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("count", items.size());
            data.put("retentionDays", 1);
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    /**
     * 中文备注：上报当前协作焦点（所在表 / 选中或编辑单元格），其他登录用户轮询在线列表即可看见。
     */
    @PostMapping("/auth/presence")
    public ApiResult<Map<String, Object>> updatePresence(HttpServletRequest request,
                                                         @RequestBody(required = false) PresenceRequest body) {
        try {
            OpsAuthService.Session session = requireSession(request);
            String token = extractToken(request);
            opsAuthService.touchPresence(token, session, new OpsAuthService.FocusUpdate(
                    body == null ? null : body.tableId(),
                    body == null ? null : body.tableLabel(),
                    body == null ? null : body.dramaType(),
                    body == null ? null : body.opsKey(),
                    body == null ? null : body.seriesName(),
                    body == null ? null : body.colKey(),
                    body == null ? null : body.colLabel(),
                    body == null ? null : body.mode(),
                    body == null ? null : body.selJson()
            ));
            String tenantMode = opsTenantService.resolveMode(request);
            List<Map<String, Object>> users = opsAuthService.listOnlineUsers(tenantMode);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", users.size());
            data.put("users", users);
            data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            data.putAll(opsTenantService.publicConfig(tenantMode));
            data.putAll(workspaceLastModifyData());
            data.putAll(serverLoadGuard.snapshot());
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    public record PresenceRequest(String tableId,
                                  String tableLabel,
                                  String dramaType,
                                  String opsKey,
                                  String seriesName,
                                  String colKey,
                                  String colLabel,
                                  String mode,
                                  String selJson) {
    }
    @GetMapping("/logs")
    public ApiResult<Map<String, Object>> logs(HttpServletRequest request,
                                               @RequestParam String bookId,
                                               @RequestParam(required = false) String type,
                                               @RequestParam(required = false, defaultValue = "1") int page,
                                               @RequestParam(required = false, defaultValue = "20") int pageSize,
                                               @RequestParam(required = false, defaultValue = "desc") String sort) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String me = "";
        try {
            me = trimToEmpty(requireSession(request).displayName());
        } catch (Exception ignored) {
            me = "";
        }
        // 中文备注：协作表审计以 type=sheet:{tableId} 定位（读 ops_sheet_op_log）；其余 type 无系统表数据源，返回空列表。
        String rawType = trimToEmpty(type);
        String logType = rawType;
        String targetBookId = trimToEmpty(bookId);
        if (targetBookId.isBlank()) {
            return ApiResult.fail("A0400", "缺少 bookId");
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        boolean sortAsc = "asc".equalsIgnoreCase(trimToEmpty(sort));
        long total = 0L;
        List<Map<String, Object>> items = new ArrayList<>();
        String sheetTableId = isSheetLogType(logType) ? tableIdFromSheetLogType(logType) : "";
        if (!sheetTableId.isBlank()) {
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ops_sheet_op_log WHERE table_id = ? AND row_id = ?",
                        Long.class, sheetTableId, targetBookId);
                total = count == null ? 0L : count;
                int offset = (safePage - 1) * safePageSize;
                items = jdbcTemplate.queryForList(
                        "SELECT id, table_id, row_id, operator, field_key, old_val, new_val, create_time "
                                + "FROM ops_sheet_op_log WHERE table_id = ? AND row_id = ? "
                                + "ORDER BY id " + (sortAsc ? "ASC" : "DESC")
                                + " LIMIT ? OFFSET ?",
                        sheetTableId, targetBookId, safePageSize, offset);
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (Map<String, Object> row : items) {
                    mapped.add(auditRowToLog(row));
                }
                items = mapped;
            } catch (Exception e) {
                total = 0L;
                items = new ArrayList<>();
            }
        }
        int totalPages = total == 0 ? 0 : (int) ((total + safePageSize - 1) / safePageSize);
        Map<String, String> customTitleToKey = customFieldTitleToKey();
        Map<String, String> sheetTitleToKey = sheetTableId.isBlank()
                ? Map.of()
                : sheetFieldTitleToKey(sheetTableId);
        for (Map<String, Object> item : items) {
            annotateLogUndoFlags(item, me, customTitleToKey, sheetTitleToKey);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", targetBookId);
        data.put("type", logType);
        data.put("page", safePage);
        data.put("pageSize", safePageSize);
        data.put("total", total);
        data.put("totalPages", totalPages);
        data.put("hasMore", safePage < totalPages);
        data.put("sort", sortAsc ? "asc" : "desc");
        data.put("count", items.size());
        data.put("currentUser", me);
        data.put("items", items);
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：撤回本人的一条操作日志；右侧对比 before=撤回前(当前/日志新值)、after=撤回后(恢复到日志旧值)。
     * 不能撤回他人操作；当前值已被后续改动时拒绝，避免误覆盖。
     */
    @PostMapping("/logs/undo")
    @Transactional
    public ApiResult<Map<String, Object>> undoLog(HttpServletRequest request,
                                                  @RequestBody UndoLogRequest body) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (operator.isBlank()) {
            return ApiResult.fail("A0230", "请先登录");
        }
        long logId = body == null || body.logId() == null ? 0L : body.logId();
        Map<String, Object> log = findAuditLogById(logId);
        if (log == null) {
            return ApiResult.fail("A0404", "操作记录不存在");
        }
        return undoLogEntry(request, operator, log, false);
    }

    /** 中文备注：批次撤回必须在同一事务内执行；任一日志不可撤回则整批回滚。 */
    @PostMapping("/logs/undo-batch")
    @Transactional
    public ApiResult<Map<String, Object>> undoLogBatch(HttpServletRequest request,
                                                       @RequestBody UndoBatchRequest body) {
        if (body == null || body.logIds() == null || body.logIds().isEmpty()) {
            return ApiResult.fail("A0400", "撤回批次不能为空");
        }
        if (body.logIds().size() > 5000) {
            return ApiResult.fail("A0400", "单次最多撤回 5000 条");
        }
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (operator.isBlank()) {
            return ApiResult.fail("A0230", "请先登录");
        }
        List<Map<String, Object>> ordered = findAuditLogsByIds(body.logIds());
        if (ordered.size() != body.logIds().size()) {
            return ApiResult.fail("A0404", "部分操作记录不存在");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> log : ordered) {
            ApiResult<Map<String, Object>> result = undoLogEntry(request, operator, log, true);
            if (!"00000".equals(result.code())) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail(result.code(), "批量撤回失败，已全部回滚：" + result.msg());
            }
            results.add(result.data());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", results.size());
        payload.put("items", results);
        payload.put("batchId", trimToEmpty(body.batchId()));
        payload.put("message", "已整体撤回 " + results.size() + " 项修改");
        return ApiResult.ok(payload);
    }
    private ApiResult<Map<String, Object>> undoLogEntry(HttpServletRequest request,
                                                        String operator,
                                                        Map<String, Object> log,
                                                        boolean deferSideEffects) {
        long logId = log.get("id") instanceof Number n ? n.longValue() : 0L;
        String fieldName = trimToEmpty((String) log.get("fieldName"));
        String logOperator = trimToEmpty((String) log.get("operator"));
        String bookId = trimToEmpty((String) log.get("bookId"));
        String rawLogType = trimToEmpty((String) log.get("dramaType"));
        String loggedOld = trimToEmpty((String) log.get("oldValue"));
        String loggedNew = trimToEmpty((String) log.get("newValue"));
        if (isSheetLogType(rawLogType)) {
            return undoSheetCellLog(request, operator, logId, rawLogType, bookId, fieldName,
                    logOperator, loggedOld, loggedNew);
        }
        return ApiResult.fail("A0400", "该类型操作不支持撤回");
    }
    public record UndoLogRequest(Long logId) {
    }

    public record SheetRowManualLogRequest(String fieldName, String oldValue, String newValue) {
    }

    public record UndoBatchRequest(String batchId, List<Long> logIds) {
    }

    private String sheetLogType(String tableId) {
        return "sheet:" + trimToEmpty(tableId);
    }

    private boolean isSheetLogType(String logType) {
        return trimToEmpty(logType).startsWith("sheet:");
    }

    private String tableIdFromSheetLogType(String logType) {
        String raw = trimToEmpty(logType);
        return raw.startsWith("sheet:") ? raw.substring("sheet:".length()) : "";
    }

    /** 中文备注：撤回协作表单元格；字段标题反查 fieldKey，当前值须仍等于日志 newValue。 */
    private ApiResult<Map<String, Object>> undoSheetCellLog(HttpServletRequest request,
                                                            String operator,
                                                            long logId,
                                                            String sheetLogTypeValue,
                                                            String rowId,
                                                            String fieldName,
                                                            String logOperator,
                                                            String loggedOld,
                                                            String loggedNew) {
        OpsUndoSupport.Eligibility eligibility = OpsUndoSupport.eligibility(
                logOperator, operator, fieldName, true);
        if (!eligibility.canUndo()) {
            return ApiResult.fail("A0403",
                    eligibility.blockReason().isBlank() ? "不可撤回" : eligibility.blockReason());
        }
        String tableId = tableIdFromSheetLogType(sheetLogTypeValue);
        if (tableId.isBlank() || requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        if (trimToEmpty(rowId).isBlank()) {
            return ApiResult.fail("A0400", "rowId 不能为空");
        }
        String fieldKey = resolveSheetFieldKeyByTitle(tableId, fieldName);
        if (fieldKey == null || fieldKey.isBlank()) {
            return ApiResult.fail("A0403", "无法定位空白表字段，暂不支持撤回");
        }
        Map<String, Object> existing = sheetRowRepository.findActive(tableId, rowId);
        if (existing == null) {
            return ApiResult.fail("A0404", "行不存在或已删除");
        }
        Map<String, Object> cells = readCellsMap((String) existing.get("cellsJson"));
        String current = trimToEmpty(String.valueOf(cells.getOrDefault(fieldKey, "")));
        if (!OpsUndoSupport.currentMatchesLoggedNew(current, loggedNew, null)) {
            return ApiResult.fail("A0409",
                    "当前「" + fieldName + "」已被后续修改（现为 "
                            + OpsUndoSupport.display(current) + "），无法安全撤回");
        }
        String restoredValue = trimToEmpty(loggedOld);
        cells.put(fieldKey, restoredValue);
        String json;
        try {
            json = objectMapper.writeValueAsString(cells);
        } catch (Exception e) {
            return ApiResult.fail("A0400", "cells 格式错误");
        }
        int currentVersion = existing.get("version") instanceof Number n ? n.intValue() : 1;
        int updated = sheetRowRepository.save(tableId, rowId, json, currentVersion, operator);
        if (updated <= 0) {
            return ApiResult.fail("A0409", "这条记录刚被别人修改过，请刷新后再撤回");
        }
        // 中文备注：撤回本身经 sheetRowRepository.save 写入 ops_sheet_op_log 审计（SSE 帧同步推送）。
        Map<String, Object> raw = sheetRowRepository.findActive(tableId, rowId);
        int restoredVersion = raw != null && raw.get("version") instanceof Number n ? n.intValue() : -1;
        Map<String, Object> contrast = OpsUndoSupport.contrast(fieldName, loggedNew, restoredValue);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logId", logId);
        data.put("bookId", rowId);
        data.put("opsKey", rowId);
        data.put("tableId", tableId);
        data.put("dramaType", "blank");
        data.put("fieldName", fieldName);
        data.put("fieldKey", fieldKey);
        data.put("operator", operator);
        data.put("contrast", contrast);
        data.put("restoredValue", restoredValue);
        data.put("previousValue", loggedNew);
        data.put("version", restoredVersion > 0 ? restoredVersion : null);
        data.put("message", "已撤回：将「" + fieldName + "」从 "
                + OpsUndoSupport.display(loggedNew) + " 恢复为 " + OpsUndoSupport.display(restoredValue));
        return ApiResult.ok(data);
    }

    private String resolveSheetFieldKeyByTitle(String tableId, String fieldTitle) {
        String title = trimToEmpty(fieldTitle);
        if (title.isBlank()) {
            return null;
        }
        for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
            String key = trimToEmpty((String) field.get("fieldKey"));
            String ft = trimToEmpty((String) field.get("fieldTitle"));
            if (title.equals(ft) || title.equals(key)) {
                return key;
            }
        }
        return null;
    }

    private Map<String, String> sheetFieldTitleToKey(String tableId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> field : metaCacheService.fields(tableId, () -> sheetFieldRepository.listActive(tableId))) {
            String key = trimToEmpty((String) field.get("fieldKey"));
            String title = trimToEmpty((String) field.get("fieldTitle"));
            if (key.isBlank()) {
                continue;
            }
            if (!title.isBlank()) {
                out.putIfAbsent(title, key);
            }
            out.putIfAbsent(key, key);
        }
        return out;
    }
    private void annotateLogUndoFlags(Map<String, Object> item,
                                      String currentUser,
                                      Map<String, String> customTitleToKey) {
        annotateLogUndoFlags(item, currentUser, customTitleToKey, Map.of());
    }

    private void annotateLogUndoFlags(Map<String, Object> item,
                                      String currentUser,
                                      Map<String, String> customTitleToKey,
                                      Map<String, String> sheetTitleToKey) {
        if (item == null) {
            return;
        }
        String fieldName = trimToEmpty((String) item.get("fieldName"));
        String logOperator = trimToEmpty((String) item.get("operator"));
        String oldValue = trimToEmpty((String) item.get("oldValue"));
        String newValue = trimToEmpty((String) item.get("newValue"));
        String logTypeValue = trimToEmpty((String) item.get("dramaType"));
        OpsUndoSupport.FieldTarget target = OpsUndoSupport.resolveTarget(fieldName);
        boolean sheetLog = isSheetLogType(logTypeValue);
        boolean sheetResolved = sheetLog
                && sheetTitleToKey != null
                && (sheetTitleToKey.containsKey(fieldName) || resolveSheetFieldKeyByTitle(
                        tableIdFromSheetLogType(logTypeValue), fieldName) != null);
        boolean customResolved = target.kind() != OpsUndoSupport.Kind.CUSTOM_FIELD
                || (customTitleToKey != null && customTitleToKey.containsKey(fieldName))
                || sheetResolved;
        OpsUndoSupport.Eligibility el = OpsUndoSupport.eligibility(
                logOperator, currentUser, fieldName, customResolved);
        item.put("mine", el.mine());
        // 中文备注：空白表操作历史只做审计展示，不提供历史内撤回（全局 ⌘Z 仍可用）。
        if (sheetLog) {
            item.put("canUndo", false);
            item.put("undoBlockReason", "空白表操作历史不支持撤回");
        } else {
            item.put("canUndo", el.canUndo());
            item.put("undoBlockReason", el.blockReason());
        }
        item.put("beforeValue", oldValue);
        item.put("afterValue", newValue);
        item.put("beforeDisplay", OpsUndoSupport.display(oldValue));
        item.put("afterDisplay", OpsUndoSupport.display(newValue));
        item.put("contrast", OpsUndoSupport.contrast(fieldName, oldValue, newValue));
        if (sheetResolved && sheetTitleToKey != null) {
            String fk = sheetTitleToKey.get(fieldName);
            if (fk != null) {
                item.put("fieldKey", fk);
            }
        }
    }

    private Map<String, String> customFieldTitleToKey() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Map<String, Object>> e : fieldConfigRepository.listAll().entrySet()) {
                String key = trimToEmpty(e.getKey());
                if (!key.startsWith("custom_")) {
                    continue;
                }
                Map<String, Object> cfg = e.getValue();
                String title = cfg == null ? "" : trimToEmpty(String.valueOf(cfg.getOrDefault("fieldTitle", "")));
                if (!title.isBlank()) {
                    out.putIfAbsent(title, key);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String resolveCustomFieldKeyByTitle(String title) {
        String t = trimToEmpty(title);
        if (t.isBlank()) {
            return null;
        }
        return customFieldTitleToKey().get(t);
    }
    private static String loadOpsClientRev() {
        try (InputStream in = OpsTableController.class.getResourceAsStream("/static/ops/index.html")) {
            if (in == null) {
                return "";
            }
            String head = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("ops-static-rev:(\\S+)").matcher(head);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean accessAllowed(HttpServletRequest request) {
        return opsAuthService.isValidToken(extractToken(request), opsTenantService.resolveMode(request));
    }

    private OpsAuthService.Session requireSession(HttpServletRequest request) {
        return opsAuthService.requireValidSession(extractToken(request), opsTenantService.resolveMode(request));
    }

    private String extractToken(HttpServletRequest request) {
        String header = trimToEmpty(request.getHeader("X-Ops-Token"));
        if (!header.isBlank()) {
            return header;
        }
        String auth = trimToEmpty(request.getHeader("Authorization"));
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return trimToEmpty(request.getParameter("token"));
    }
    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
    public record LoginRequest(String displayName, String password) {
    }
}
