package com.polytable.controller;

import com.polytable.repository.DramaOperationRepository;
import com.polytable.repository.DramaOperationRepository.OpsSaveFields;
import com.polytable.repository.DramaReminderFeedRepository;
import com.polytable.repository.OpsEditedComicRankRepository;
import com.polytable.repository.OpsFieldConfigRepository;
import com.polytable.repository.OpsFieldOptionRepository;
import com.polytable.repository.OpsHiddenRowRepository;
import com.polytable.repository.OpsSheetFieldRepository;
import com.polytable.repository.OpsSheetRowRepository;
import com.polytable.repository.OpsTableRegistryRepository;
import com.polytable.repository.OpsTableViewsRepository;
import com.polytable.collect.ChangduCollectGuard;
import com.polytable.collect.SheetLinkCollectLane;
import com.polytable.ops.SheetCellDateSupport;
import com.polytable.service.AgencyHqCatalogListService;
import com.polytable.service.AgencyPromoLinkCollectService;
import com.polytable.service.AgencySheetLinkCollectService;
import com.polytable.task.AgencySheetLinkCollectScheduler;
import com.polytable.service.OpsAuthService;
import com.polytable.service.OpsTenantFeatureService;
import com.polytable.service.OpsTenantService;
import com.polytable.service.OpsDramaListService;
import com.polytable.service.OpsFieldOptionDefaults;
import com.polytable.service.OpsFormulaEvalService;
import com.polytable.service.OpsEditorGroupMemberService;
import com.polytable.service.OpsEditorMaterialCompleteNotifier;
import com.polytable.service.OpsLeaderGroupAssignTrigger;
import com.polytable.service.OpsLeaderGroupService;
import com.polytable.service.OpsCopyrightSupport;
import com.polytable.service.OpsListNotifyService;
import com.polytable.service.OpsManualRowService;
import com.polytable.service.OpsMasterFieldLinkService;
import com.polytable.service.OpsSheetLookupService;
import com.polytable.service.OpsSheetMetaCacheService;
import com.polytable.service.OpsSheetRowListService;
import com.polytable.service.OpsSheetOpLogService;
import com.polytable.service.OpsSheetSyncPushService;
import com.polytable.service.OpsSheetListRecordsEncodeCache;
import com.polytable.service.OpsSheetRecordsEncoder;
import com.polytable.service.OpsSheetRecordsPayloadCache;
import com.polytable.service.OpsSheetRowRecordsService;
import com.polytable.service.OpsUndoSupport;
import com.polytable.service.ServerLoadGuard;
import com.polytable.task.LocalPromotionLinkCollectTask;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 运营协作工作台：字段对齐飞书主表；IAA/IAP 可手填，未覆盖时跟随后台推送。
 */
@RestController
@RequestMapping("/api/v1/ops")
@ConditionalOnProperty(name = "ops.enabled", havingValue = "true", matchIfMissing = true)
public class DramaOpsController {
    private static final Logger log = LoggerFactory.getLogger(DramaOpsController.class);
    /** 中文备注：从打包进 jar 的 index.html 注释读取，供发版提示条对比，避免 HTML 被 CDN 缓存后 HEAD 看不出更新。 */
    private static final String OPS_CLIENT_REV = loadOpsClientRev();

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 中文备注：列表改为服务端分页，单页上限控制数据库与前端压力，避免一天上千条一次拉满。
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    // 中文备注：工作台按飞书发布计划同一时间窗口一次拉齐，不再底部分页；设上限防异常窗口爆量。
    private static final int FEISHU_WINDOW_LIST_MAX = 3000;
    // 中文备注：与飞书主表推送一致——只用 COALESCE(预估, 实际) 落在窗口内的标准剧，不扫全库。
    private static final String FEISHU_PUBLISH_WINDOW_WHERE = """
            COALESCE(estimate_publish_time, publish_time) >= ?
              AND COALESCE(estimate_publish_time, publish_time) < ?
            """;
    private static final String FEISHU_PUBLISH_WINDOW_WHERE_ALIAS = """
            COALESCE(s.estimate_publish_time, s.publish_time) >= ?
              AND COALESCE(s.estimate_publish_time, s.publish_time) < ?
            """;
    private static final int IAP_PANEL_COUNT = 7;
    private static final Map<String, String> SERIES_TABLES = Map.of(
            "drama", "drama_series",
            "real", "real_drama_series",
            "douyin", "douyin_mini_paid_drama_series"
    );
    private static final Map<String, String> DEFAULT_TYPE_LABELS = Map.of(
            "real", "真人剧",
            "douyin", "抖音小程序付费短剧"
    );
    // 中文备注：版权方选项对齐飞书主表「版权」，默认「番茄」。
    private static final String DEFAULT_COPYRIGHT_OWNER = "番茄";
    private static final List<String> PROMOTION_FIELD_KEYS = buildPromotionFieldKeys();
    private static final List<String> EDITABLE_FIELDS = List.of(
            "剧名", "描述类型", "抖音作品id", "发布抖音号",
            "预估投放时间", "预估发布时间", "常读创建时间", "总集数",
            "剧目类型", "筛剧评级", "最终评级", "剪辑", "素材数", "投放测试",
            "网盘链接", "优先级", "投手评级", "投手",
            "IAA链接(默认)", "IAA链接(卡2)", "IAA链接（第2集）",
            "IAP面板1", "IAP链接1", "IAP面板2", "IAP链接2", "IAP面板3", "IAP链接3",
            "IAP面板4", "IAP链接4", "IAP面板5", "IAP链接5", "IAP面板6", "IAP链接6",
            "IAP面板7", "IAP链接7"
    );

    // 中文备注：可在页面「修改字段/列」里维护选项的字段，key 与前端 COLUMN_DEFS 对齐。
    private static final Map<String, String> OPTION_FIELD_LABELS = Map.of(
            "editorAssignee", "剪辑",
            "deliveryAssignee", "投手",
            "deliveryTest", "投放测试",
            "finalRating", "最终评级",
            "screeningRating", "筛剧评级",
            "dramaTypeLabel", "剧目类型",
            "copyrightOwner", "版权"
    );

    private static final List<Map<String, Object>> BUILTIN_TABLES = List.of(
            Map.of("tableId", "tbl_all", "type", "all", "label", "全部剧目", "kind", "builtin", "baseType", "all"),
            Map.of("tableId", "tbl_created", "type", "created", "label", "当日创建剧目", "kind", "builtin", "baseType", "created"),
            Map.of("tableId", "tbl_near", "type", "near", "label", "剧目时间临近发布采集表", "kind", "builtin", "baseType", "near"),
            Map.of("tableId", "tbl_promo", "type", "promo", "label", "当日剧目推广链接参考", "kind", "builtin", "baseType", "promo"),
            Map.of("tableId", "tbl_adx", "type", "adx", "label", "ADX抖音热播榜top采集表", "kind", "builtin", "baseType", "adx")
    );
    private static final Set<String> BUILTIN_TABLE_IDS = Set.of("tbl_all", "tbl_created", "tbl_near", "tbl_promo", "tbl_adx");
    private static final Set<String> ALLOWED_FILTER_BASE_TYPES = Set.of("all");

    // 中文备注：重载闸门——大页加载（4000 行 DB→对象→公式→编码）每请求堆开销可观，
    // 无闸门时 32 并发首屏会打出 OOM（112 实测 14 次 Java heap space，连接池随后被毒化）。
    // 限 8 并发排队 8s，其余 429 让前端重试；metaOnly/增量/编码缓存命中不走闸门。
    private static final java.util.concurrent.Semaphore SHEET_LIST_HEAVY_GATE = new java.util.concurrent.Semaphore(8);

    private final JdbcTemplate jdbcTemplate;
    private final com.polytable.service.OpsListFastCache opsListFastCache;
    private final DramaOperationRepository dramaOperationRepository;
    private final DramaReminderFeedRepository reminderFeedRepository;
    private final OpsEditedComicRankRepository editedComicRankRepository;
    private final OpsFieldOptionRepository fieldOptionRepository;
    private final OpsFieldConfigRepository fieldConfigRepository;
    private final OpsTableViewsRepository tableViewsRepository;
    private final OpsTableRegistryRepository tableRegistryRepository;
    private final OpsSheetFieldRepository sheetFieldRepository;
    private final OpsSheetRowRepository sheetRowRepository;
    private final OpsHiddenRowRepository hiddenRowRepository;
    private final OpsDramaListService opsDramaListService;
    private final OpsFormulaEvalService opsFormulaEvalService;
    private final OpsListNotifyService opsListNotifyService;
    private final OpsManualRowService opsManualRowService;
    private final LocalPromotionLinkCollectTask localPromotionLinkCollectTask;
    private final ObjectMapper objectMapper;
    private final OpsAuthService opsAuthService;
    private final OpsTenantService opsTenantService;
    private final AgencySheetLinkCollectService agencySheetLinkCollectService;
    private final AgencySheetLinkCollectScheduler agencySheetLinkCollectScheduler;
    private final OpsMasterFieldLinkService masterFieldLinkService;
    private final OpsSheetLookupService sheetLookupService;
    private final OpsLeaderGroupService leaderGroupService;
    private final OpsEditorGroupMemberService editorGroupMemberService;
    private final OpsEditorMaterialCompleteNotifier editorMaterialCompleteNotifier;
    private final OpsLeaderGroupAssignTrigger leaderGroupAssignTrigger;
    private final AgencyHqCatalogListService agencyHqCatalogListService;
    private final AgencyPromoLinkCollectService agencyPromoLinkCollectService;
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
    private static final java.util.Set<String> ALLOWED_FIELD_TYPES = java.util.Set.of(
            "text", "select", "multi", "date", "checkbox", "formula", "lookup"
    );
    // 中文备注：内置列与推广字段禁止被自定义 field_key 占用。
    private static final java.util.Set<String> RESERVED_FIELD_KEYS = java.util.Set.of(
            "actions", "seriesName", "categoryText", "bookId", "douyinWorkId", "douyinAccount",
            "publishAwemeUser", "isFocus", "dramaTypeLabel", "copyrightOwner",
            "estimatePublishTime", "publishTime", "changduCreateTime", "timeDiffMinutes",
            "onlineTime", "crawlUpdateTime", "episodeAmount", "playCountAdd", "playCount",
            "screeningRating", "finalRating", "editorAssignee", "materialCount", "deliveryTest",
            "cloudDiskUrl", "priorityLevel", "bidderRating", "deliveryAssignee", "remark",
            "iaaDefaultUrl", "iaaCard2Url", "iaaEpisode2Url",
            "iapPanel1", "iapLink1", "iapPanel2", "iapLink2", "iapPanel3", "iapLink3",
            "iapPanel4", "iapLink4", "iapPanel5", "iapLink5", "iapPanel6", "iapLink6",
            "iapPanel7", "iapLink7", "opsKey", "version", "dramaType", "customFields"
    );
    // 中文备注：与飞书机器人开关同源，提醒抽屉文案据此展示「推机器人」或「仅网页」。
    private final boolean quickPublishPushEnabled;
    private final String markValue;
    private final List<String> markedTheaters;
    private final List<String> markedAwemeUsers;
    private final List<String> markedDramaAwemeUsers;
    private final List<String> markedRealDramaAwemeUsers;

    public DramaOpsController(JdbcTemplate jdbcTemplate,
                              com.polytable.service.OpsListFastCache opsListFastCache,
                              DramaOperationRepository dramaOperationRepository,
                              DramaReminderFeedRepository reminderFeedRepository,
                              OpsEditedComicRankRepository editedComicRankRepository,
                              OpsFieldOptionRepository fieldOptionRepository,
                              OpsFieldConfigRepository fieldConfigRepository,
                              OpsTableViewsRepository tableViewsRepository,
                              OpsTableRegistryRepository tableRegistryRepository,
                              OpsSheetFieldRepository sheetFieldRepository,
                              OpsSheetRowRepository sheetRowRepository,
                              OpsHiddenRowRepository hiddenRowRepository,
                              OpsDramaListService opsDramaListService,
                              OpsFormulaEvalService opsFormulaEvalService,
                              OpsListNotifyService opsListNotifyService,
                              OpsManualRowService opsManualRowService,
                              LocalPromotionLinkCollectTask localPromotionLinkCollectTask,
                              ObjectMapper objectMapper,
                              OpsAuthService opsAuthService,
                              OpsTenantService opsTenantService,
                              AgencySheetLinkCollectService agencySheetLinkCollectService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              AgencySheetLinkCollectScheduler agencySheetLinkCollectScheduler,
                              OpsMasterFieldLinkService masterFieldLinkService,
                              OpsSheetLookupService sheetLookupService,
                              OpsLeaderGroupService leaderGroupService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              OpsEditorGroupMemberService editorGroupMemberService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              OpsEditorMaterialCompleteNotifier editorMaterialCompleteNotifier,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              OpsLeaderGroupAssignTrigger leaderGroupAssignTrigger,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              AgencyHqCatalogListService agencyHqCatalogListService,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              AgencyPromoLinkCollectService agencyPromoLinkCollectService,
                              OpsSheetRowListService sheetRowListService,
                              OpsSheetRowRecordsService sheetRowRecordsService,
                              OpsSheetMetaCacheService metaCacheService,
                              OpsSheetSyncPushService sheetSyncPushService,
                              OpsSheetOpLogService sheetOpLogService,
                              OpsSheetRecordsPayloadCache sheetRecordsPayloadCache,
                              OpsSheetListRecordsEncodeCache sheetListRecordsEncodeCache,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              OpsTenantFeatureService tenantFeatureService,
                              ServerLoadGuard serverLoadGuard,
                              @Value("${feishu.robot.quick-publish-push-enabled:true}") boolean quickPublishPushEnabled,
                              @Value("${feishu.bitable.mark.value:是}") String markValue,
                              @Value("${feishu.bitable.mark.theaters:}") String markedTheaters,
                              @Value("${feishu.bitable.mark.aweme-users:}") String markedAwemeUsers,
                              @Value("${feishu.bitable.mark.drama-aweme-users:}") String markedDramaAwemeUsers,
                              @Value("${feishu.bitable.mark.real-drama-aweme-users:}") String markedRealDramaAwemeUsers) {
        this.jdbcTemplate = jdbcTemplate;
        this.opsListFastCache = opsListFastCache;
        this.dramaOperationRepository = dramaOperationRepository;
        this.reminderFeedRepository = reminderFeedRepository;
        this.editedComicRankRepository = editedComicRankRepository;
        this.fieldOptionRepository = fieldOptionRepository;
        this.fieldConfigRepository = fieldConfigRepository;
        this.tableViewsRepository = tableViewsRepository;
        this.tableRegistryRepository = tableRegistryRepository;
        this.sheetFieldRepository = sheetFieldRepository;
        this.sheetRowRepository = sheetRowRepository;
        this.hiddenRowRepository = hiddenRowRepository;
        this.opsDramaListService = opsDramaListService;
        this.opsFormulaEvalService = opsFormulaEvalService;
        this.opsListNotifyService = opsListNotifyService;
        this.opsManualRowService = opsManualRowService;
        this.localPromotionLinkCollectTask = localPromotionLinkCollectTask;
        this.objectMapper = objectMapper;
        this.opsAuthService = opsAuthService;
        this.opsTenantService = opsTenantService;
        this.agencySheetLinkCollectService = agencySheetLinkCollectService;
        this.agencySheetLinkCollectScheduler = agencySheetLinkCollectScheduler;
        this.masterFieldLinkService = masterFieldLinkService;
        this.sheetLookupService = sheetLookupService;
        this.leaderGroupService = leaderGroupService;
        this.editorGroupMemberService = editorGroupMemberService;
        this.editorMaterialCompleteNotifier = editorMaterialCompleteNotifier;
        this.leaderGroupAssignTrigger = leaderGroupAssignTrigger;
        this.agencyHqCatalogListService = agencyHqCatalogListService;
        this.agencyPromoLinkCollectService = agencyPromoLinkCollectService;
        this.sheetRowListService = sheetRowListService;
        this.metaCacheService = metaCacheService;
        this.sheetSyncPushService = sheetSyncPushService;
        this.sheetOpLogService = sheetOpLogService;
        this.sheetRowRecordsService = sheetRowRecordsService;
        this.sheetRecordsPayloadCache = sheetRecordsPayloadCache;
        this.sheetListRecordsEncodeCache = sheetListRecordsEncodeCache;
        this.tenantFeatureService = tenantFeatureService;
        this.serverLoadGuard = serverLoadGuard;
        this.quickPublishPushEnabled = quickPublishPushEnabled;
        this.markValue = markValue == null || markValue.isBlank() ? "是" : markValue.trim();
        this.markedTheaters = parseList(markedTheaters);
        this.markedAwemeUsers = parseList(markedAwemeUsers);
        this.markedDramaAwemeUsers = parseList(markedDramaAwemeUsers);
        this.markedRealDramaAwemeUsers = parseList(markedRealDramaAwemeUsers);
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
        // 中文备注：协作台「剪辑」等下拉仍读字段配置/选项表，与后台均分名单分离。
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
        if (leaderGroupService.enabled() && fieldKey.equals(leaderGroupService.leaderRatingField())) {
            return leaderGroupService.filterScreeningRatingOptions(all, session);
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
        if (leaderGroupService.enabled()) {
            data.put("groupBelongingOptions", effectiveOptions(leaderGroupService.groupField(), session));
            data.put("groupFieldKey", leaderGroupService.groupField());
            data.put("groupFieldTitle", leaderGroupService.groupFieldTitle());
            String createEditorFieldKey = leaderGroupService.createEditorAssignField();
            if (!createEditorFieldKey.isBlank()) {
                data.put("createEditorAssignFieldKey", createEditorFieldKey);
                data.put("createEditorAssignFieldTitle", leaderGroupService.createEditorAssignFieldTitle());
            }
            String leaderRatingKey = leaderGroupService.leaderRatingField();
            if (!leaderRatingKey.isBlank()) {
                data.put("leaderRatingFieldKey", leaderRatingKey);
            }
            List<String> leaderRatingAll = optionsFromFieldConfigUnfiltered(leaderRatingKey);
            if (leaderRatingAll == null) {
                leaderRatingAll = effectiveOptions(leaderRatingKey, null);
            }
            data.put("leaderGroupEnabled", true);
            data.put("leaderGroupLeaders", leaderGroupService.discoverLeadersFromOptions(leaderRatingAll));
            data.put("leaderGroupMinEpisodeAmount", leaderGroupService.minEpisodeAmount());
        }
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

    private Map<String, Object> leaderGroupSessionExtras(OpsAuthService.Session session) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (!leaderGroupService.enabled() || session == null) {
            return extras;
        }
        String leaderRatingKey = leaderGroupService.leaderRatingField();
        List<String> leaderRatingAll = optionsFromFieldConfigUnfiltered(leaderRatingKey);
        if (leaderRatingAll == null) {
            leaderRatingAll = effectiveOptions(leaderRatingKey, null);
        }
        extras.put("leaderGroupEnabled", true);
        extras.put("leaderGroupPrefix", leaderGroupService.resolveLeaderPrefix(session.displayName(), leaderRatingAll));
        extras.put("leaderGroupLeaders", leaderGroupService.discoverLeadersFromOptions(leaderRatingAll));
        extras.put("leaderGroupMinEpisodeAmount", leaderGroupService.minEpisodeAmount());
        return extras;
    }

    private Map<String, Object> tenantFeatureExtras(HttpServletRequest request, OpsAuthService.Session session) {
        if (tenantFeatureService == null) {
            return Map.of();
        }
        return tenantFeatureService.sessionFeaturePayload(opsTenantService.resolveMode(request), session);
    }

    private List<String> optionsFromFieldConfigUnfiltered(String fieldKey) {
        return optionsFromFieldConfig(fieldKey);
    }

    private void maybeScheduleLeaderGroupAssign(String estimatePublishTime,
                                                String publishTime,
                                                Integer episodeAmount) {
        if (leaderGroupAssignTrigger == null || !leaderGroupService.enabled()) {
            return;
        }
        if (episodeAmount == null || episodeAmount < leaderGroupService.minEpisodeAmount()) {
            return;
        }
        LocalDate day = leaderGroupService.parseDeliveryDay(estimatePublishTime);
        if (day == null) {
            day = leaderGroupService.parseDeliveryDay(publishTime);
        }
        if (day != null) {
            leaderGroupAssignTrigger.scheduleDeliveryDay(day);
        }
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
            int changed = fieldOptionRepository.remove(fieldKey, value, operator);
            if (changed > 0) {
                // 中文备注：只软删选项定义，不清单元格；同名选项日后可复活。
                dramaOperationRepository.insertLog("all", "FIELD_OPTION:" + fieldKey, operator,
                        label + "选项", value, "已删定义（单元格已填值保留）");
            }
        } else {
            if (effectiveOptions(fieldKey).contains(value)) {
                return ApiResult.fail("A0400", "选项已存在：" + value);
            }
            boolean restored = fieldOptionRepository.existsDeleted(fieldKey, value);
            fieldOptionRepository.add(fieldKey, value, operator);
            dramaOperationRepository.insertLog("all", "FIELD_OPTION:" + fieldKey, operator,
                    label + "选项", "", restored ? ("恢复：" + value) : value);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateDramaRequest(String dramaType,
                                     String seriesName,
                                     String copyrightOwner,
                                     String bookId,
                                     String estimatePublishTime,
                                     String publishTime,
                                     String changduCreateTime,
                                     String episodeAmount,
                                     String categoryText,
                                     String douyinWorkId,
                                     String publishAwemeUser,
                                     String dramaTypeLabel,
                                     String screeningRating,
                                     String finalRating,
                                     String editorAssignee,
                                     String materialCount,
                                     String deliveryTest,
                                     String cloudDiskUrl,
                                     String priorityLevel,
                                     String bidderRating,
                                     String deliveryAssignee,
                                     String iaaDefaultUrl,
                                     String iaaCard2Url,
                                     String iaaEpisode2Url,
                                     String iapPanel1,
                                     String iapLink1,
                                     String iapPanel2,
                                     String iapLink2,
                                     String iapPanel3,
                                     String iapLink3,
                                     String iapPanel4,
                                     String iapLink4,
                                     String iapPanel5,
                                     String iapLink5,
                                     String iapPanel6,
                                     String iapLink6,
                                     String iapPanel7,
                                     String iapLink7,
                                     // 中文备注：前端可能顺带传 operator；实际操作人仍以登录会话为准。
                                     String operator,
                                     Map<String, String> customFields) {
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
        dramaOperationRepository.insertLog("all", "FIELD_CREATE:" + fieldKey, operator,
                "新增字段", "", title + " / " + fieldType);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if ("formula".equals(fieldType)) {
            opsDramaListService.invalidateFormulaFieldCache();
        }
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：手动新增行——双写主表 + 运营表；无 book_id 时按剧名+版权去重。
     */
    @PostMapping("/dramas/create")
    @Transactional
    public ApiResult<Map<String, Object>> createDrama(HttpServletRequest request,
                                                      @RequestBody CreateDramaRequest body) {
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
        String copyright = trimToEmpty(body == null ? null : body.copyrightOwner());
        if (!copyright.isBlank() && !effectiveOptions("copyrightOwner").contains(copyright)) {
            return ApiResult.fail("A0400", "不支持的版权选项：" + copyright);
        }
        Map<String, String> customFields = body == null || body.customFields() == null
                ? null : new LinkedHashMap<>(body.customFields());
        if (leaderGroupService.enabled()) {
            Integer episode = parseFlexibleInt(body == null ? null : body.episodeAmount());
            int episodeAmount = episode == null ? 0 : episode;
            String editorForGroup = normalizeMultiOptionValue(body == null ? null : body.editorAssignee());
            String lockedGroup = resolveLockedGroup(editorForGroup, customFields, null);
            if (!lockedGroup.isBlank()) {
                if (customFields == null) {
                    customFields = new LinkedHashMap<>();
                }
                customFields.put(leaderGroupService.groupField(), lockedGroup);
            } else if (leaderGroupService.shouldAutoAssignGroup(
                    body == null ? null : body.dramaType(), episodeAmount, "", editorForGroup)) {
                LocalDate deliveryDay = leaderGroupService.parseDeliveryDay(
                        body == null ? null : body.estimatePublishTime());
                String group = deliveryDay == null
                        ? leaderGroupService.pickLeaderForAssignment()
                        : leaderGroupService.pickGroupForDeliveryDay(deliveryDay);
                if (!group.isBlank()) {
                    if (customFields == null) {
                        customFields = new LinkedHashMap<>();
                    }
                    if (!customFields.containsKey(leaderGroupService.groupField())) {
                        customFields.put(leaderGroupService.groupField(), group);
                    }
                }
            }
        }
        OpsManualRowService.CreateResult result = opsManualRowService.create(
                new OpsManualRowService.CreateRequest(
                        body == null ? null : body.dramaType(),
                        body == null ? null : body.seriesName(),
                        copyright,
                        body == null ? null : body.bookId(),
                        body == null ? null : body.estimatePublishTime(),
                        body == null ? null : body.publishTime(),
                        body == null ? null : body.changduCreateTime(),
                        body == null ? null : body.episodeAmount(),
                        body == null ? null : body.categoryText(),
                        body == null ? null : body.douyinWorkId(),
                        body == null ? null : body.publishAwemeUser(),
                        body == null ? null : body.dramaTypeLabel(),
                        body == null ? null : body.screeningRating(),
                        body == null ? null : body.finalRating(),
                        body == null ? null : body.editorAssignee(),
                        body == null ? null : body.materialCount(),
                        body == null ? null : body.deliveryTest(),
                        body == null ? null : body.cloudDiskUrl(),
                        body == null ? null : body.priorityLevel(),
                        body == null ? null : body.bidderRating(),
                        body == null ? null : body.deliveryAssignee(),
                        extractPromotionFromCreate(body),
                        customFields
                ),
                operator
        );
        if (!result.ok()) {
            String msg = result.message();
            Map<String, Object> failData = new LinkedHashMap<>();
            if (result.conflict() != null && !result.conflict().isEmpty()) {
                failData.putAll(result.conflict());
            }
            if (result.conflictOpsKey() != null && !result.conflictOpsKey().isBlank()) {
                failData.putIfAbsent("opsKey", result.conflictOpsKey());
                failData.put("conflictOpsKey", result.conflictOpsKey());
            }
            if (!failData.isEmpty()) {
                return ApiResult.fail(result.code(), msg, failData);
            }
            return ApiResult.fail(result.code(), msg);
        }
        try {
            opsDramaListService.clearFirstPageCache();
        } catch (Exception ignored) {
        }
        maybeScheduleLeaderGroupAssign(body == null ? null : body.estimatePublishTime(),
                body == null ? null : body.publishTime(),
                parseFlexibleInt(body == null ? null : body.episodeAmount()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("opsKey", result.opsKey());
        data.put("row", result.row());
        data.put("listVersion", opsDramaListService.currentListVersion());
        return ApiResult.ok(data);
    }

    /**
     * 中文备注：删除记录=全员软隐藏；撤销走 unhide。不物理删采集/运营数据。
     */
    @PostMapping("/dramas/hide")
    @Transactional
    public ApiResult<Map<String, Object>> hideDramaRow(HttpServletRequest request,
                                                       @RequestBody DramaHideRequest body) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String dramaType = trimToEmpty(body == null ? null : body.dramaType());
        String opsKey = trimToEmpty(body == null ? null : body.opsKey());
        if (opsKey.isBlank()) {
            opsKey = trimToEmpty(body == null ? null : body.bookId());
        }
        if (dramaType.isBlank() || opsKey.isBlank()) {
            return ApiResult.fail("A0400", "dramaType / opsKey 不能为空");
        }
        if (!SERIES_TABLES.containsKey(dramaType) && !"adx".equals(dramaType)) {
            return ApiResult.fail("A0400", "不支持的业务线");
        }
        String snapshot = "";
        try {
            if (body != null && body.snapshot() != null) {
                snapshot = objectMapper.writeValueAsString(body.snapshot());
            }
        } catch (Exception ignored) {
            snapshot = "";
        }
        if (!hiddenRowRepository.hide(dramaType, opsKey, operator, snapshot)) {
            return ApiResult.fail("A0500", "删除失败");
        }
        try {
            opsListNotifyService.notifyOpsSave(dramaType, opsKey);
            opsDramaListService.clearFirstPageCache();
        } catch (Exception ignored) {
        }
        dramaOperationRepository.insertLog(dramaType, opsKey, operator, "删除记录", "", "软隐藏");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dramaType", dramaType);
        data.put("opsKey", opsKey);
        data.put("hidden", true);
        data.put("listVersion", opsDramaListService.currentListVersion());
        return ApiResult.ok(data);
    }

    @PostMapping("/dramas/unhide")
    @Transactional
    public ApiResult<Map<String, Object>> unhideDramaRow(HttpServletRequest request,
                                                         @RequestBody DramaHideRequest body) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String operator;
        try {
            operator = trimToEmpty(requireSession(request).displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String dramaType = trimToEmpty(body == null ? null : body.dramaType());
        String opsKey = trimToEmpty(body == null ? null : body.opsKey());
        if (opsKey.isBlank()) {
            opsKey = trimToEmpty(body == null ? null : body.bookId());
        }
        if (dramaType.isBlank() || opsKey.isBlank()) {
            return ApiResult.fail("A0400", "dramaType / opsKey 不能为空");
        }
        if (!hiddenRowRepository.unhide(dramaType, opsKey)) {
            return ApiResult.fail("A0404", "记录未删除或已恢复");
        }
        try {
            opsListNotifyService.notifyOpsSave(dramaType, opsKey);
            opsDramaListService.clearFirstPageCache();
        } catch (Exception ignored) {
        }
        dramaOperationRepository.insertLog(dramaType, opsKey, operator, "撤销删除", "", "恢复显示");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dramaType", dramaType);
        data.put("opsKey", opsKey);
        data.put("hidden", false);
        data.put("listVersion", opsDramaListService.currentListVersion());
        return ApiResult.ok(data);
    }

    public record DramaHideRequest(String dramaType, String opsKey, String bookId, Map<String, Object> snapshot) {
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
            if (validateTableId.isBlank()) {
                validateTableId = "tbl_all";
            }
            List<Map<String, Object>> validateMetas = new ArrayList<>(sheetLookupService.builtinMatchFieldMetas());
            validateMetas.addAll(sheetLookupService.buildLookupFieldMetasFromConfigs());
            List<Map<String, Object>> validateRows = loadLookupValidationRows(validateTableId, typeFromBuiltinTableId(validateTableId));
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
        String auditNew = fieldType + " / " + normalized.size() + " options";
        if (!removedOpts.isEmpty()) {
            auditNew = auditNew + "；移除选项=" + String.join("、", removedOpts) + "（单元格已填值保留）";
        }
        dramaOperationRepository.insertLog("all", "FIELD_CONFIG:" + fieldKey, operator,
                "字段配置", summarizeOptions(previousOptions), auditNew);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if ("lookup".equals(fieldType)) {
            String syncTableId = trimToEmpty(body == null ? null : body.tableId());
            if (syncTableId.isBlank()) {
                syncTableId = "tbl_all";
            }
            List<Map<String, Object>> syncRows = loadLookupValidationRows(syncTableId, typeFromBuiltinTableId(syncTableId));
            List<Map<String, Object>> syncMetas = new ArrayList<>(sheetLookupService.builtinMatchFieldMetas());
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
            opsDramaListService.invalidateFormulaFieldCache();
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
        boolean purge = body == null || body.purgeValues() == null || Boolean.TRUE.equals(body.purgeValues());
        if (!fieldConfigRepository.deleteCustom(fieldKey)) {
            return ApiResult.fail("A0404", "删除失败，字段可能已被删除");
        }
        try {
            fieldOptionRepository.removeAll(fieldKey, operator);
        } catch (Exception ignored) {
        }
        int viewTouched = stripFieldKeyFromAllTableViews(fieldKey, operator);
        int purgedRows = 0;
        if (purge) {
            try {
                purgedRows = dramaOperationRepository.purgeCustomFieldKey(fieldKey);
            } catch (Exception e) {
                System.out.println("协作工作台清理自定义字段值失败：" + e.getMessage());
            }
        }
        dramaOperationRepository.insertLog("all", "FIELD_DELETE:" + fieldKey, operator,
                "删除字段", title, purge ? ("已清理单元格 " + purgedRows + " 行") : "仅删定义未清值");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fieldKey", fieldKey);
        data.put("title", title);
        data.put("purged", purge);
        data.put("purgedRows", purgedRows);
        data.put("viewsTouched", viewTouched);
        data.put("fieldConfigs", allFieldConfigsPayload());
        data.putAll(effectiveOptionMap(sessionOrNull(request)));
        if (existing != null && "formula".equalsIgnoreCase(String.valueOf(existing.get("fieldType")))) {
            opsDramaListService.invalidateFormulaFieldCache();
        }
        return ApiResult.ok(data);
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
        List<Map<String, Object>> builtin = opsTenantService.filterBuiltinTables(tenantMode, BUILTIN_TABLES, session);
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
            if (!ALLOWED_FILTER_BASE_TYPES.contains(baseType)) {
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
        if ("blank".equals(kind)) {
            seedBlankTableFields(tableId, operator);
            seedBlankTableRows(tableId, operator);
            seedBlankTableMainView(tableId, operator);
        } else {
            seedFilterTableMainView(tableId, baseType, label, filtersJson, operator);
        }
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
        if (BUILTIN_TABLE_IDS.contains(tableId)) {
            return ApiResult.fail("A0400", "系统表请在本地改展示名，不写注册表");
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
        if (BUILTIN_TABLE_IDS.contains(tableId)) {
            return ApiResult.fail("A0400", "系统表不能删除");
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

    @GetMapping("/sheets/master-link/fields")
    public ApiResult<Map<String, Object>> listMasterLinkFields(HttpServletRequest request) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fields", masterFieldLinkService.linkableMasterFields());
        data.put("hint", "空白表列可关联主表字段；匹配列自动识别表头「剧名」「book_id」");
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
        if (OpsSheetLookupService.isBuiltinTableId(currentTableId)) {
            fieldMetas = new ArrayList<>(sheetLookupService.builtinMatchFieldMetas());
            fieldMetas.addAll(sheetLookupService.buildLookupFieldMetasFromConfigs());
            rows = loadLookupValidationRows(currentTableId, typeFromBuiltinTableId(currentTableId));
        } else {
            if (requireActiveBlankTable(currentTableId, request) == null) {
                return ApiResult.fail("A0404", "空白表不存在或已删除");
            }
            rows = new ArrayList<>();
            for (Map<String, Object> raw : sheetRowRepository.listActive(currentTableId)) {
                rows.add(toSheetCanvasRow(raw));
            }
            fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(currentTableId));
        }
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
            masterLinkJson = masterFieldLinkService.serializeLinkConfig(
                    masterFieldLinkService.parseLinkConfig(body == null ? null : body.masterLink()));
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
            // 中文备注：首屏可跑主表关联（飞书式对表）；masterLinkSkip=1 仅出库内 cells，对表走 idle 二阶段请求。
            if (firstPage && kwEsc.isBlank() && !isTruthyQueryFlag(masterLinkSkip)) {
                masterFieldLinkService.enrichSheetPayload(fieldMetas, rows);
                if (!isTruthyQueryFlag(lookupSkip)) {
                    try {
                        lookupSync = sheetLookupService.enrichSheetLookupFields(
                                tableId, trimToEmpty(session.displayName()), fieldMetas, rows, lookupForceKeys);
                    } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                        return ApiResult.fail("A0429", e.getMessage());
                    }
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
            data.put("masterLinkMatch", Map.of(
                    "seriesNameFieldKey", masterFieldLinkService.detectMatchColumns(fields).seriesNameFieldKey(),
                    "bookIdFieldKey", masterFieldLinkService.detectMatchColumns(fields).bookIdFieldKey()
            ));
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
     * 飞书式协作表 records：encoding=2 稀疏列式 gzip 二进制（payloadNonce 另拉）；viewId 列投影；metaOnly 只返 total/fields。
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
        // 写操作后旧块继续服务（客户端靠 SSE 推送 + sinceMs 增量收敛，飞书式「先旧后新」），后台串行重编码。
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
                data.put("masterLinkMatch", Map.of(
                        "seriesNameFieldKey", masterFieldLinkService.detectMatchColumns(fields).seriesNameFieldKey(),
                        "bookIdFieldKey", masterFieldLinkService.detectMatchColumns(fields).bookIdFieldKey()
                ));
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
            if (firstPage && kwEsc.isBlank() && !masterLinkSkipped) {
                masterFieldLinkService.enrichSheetPayload(fieldMetas, rows);
                if (!lookupSkipped) {
                    try {
                        lookupSync = sheetLookupService.enrichSheetLookupFields(
                                tableId, trimToEmpty(session.displayName()), fieldMetas, rows, lookupForceKeys);
                    } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                        return ApiResult.fail("A0429", e.getMessage());
                    }
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
            data.put("masterLinkMatch", Map.of(
                    "seriesNameFieldKey", masterFieldLinkService.detectMatchColumns(fields).seriesNameFieldKey(),
                    "bookIdFieldKey", masterFieldLinkService.detectMatchColumns(fields).bookIdFieldKey()
            ));
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
            if (kwEsc == null || kwEsc.isBlank()) {
                if (!masterLinkSkip) {
                    masterFieldLinkService.enrichSheetPayload(fieldMetas, rows);
                }
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

    /** 中文备注：W1——系统表(builtin)也能订阅;EventSource 重连自动带 Last-Event-ID,按水位回放缺口。 */
    private static final java.util.Set<String> BUILTIN_STREAM_TABLES = java.util.Set.of(
            "tbl_all", "tbl_created", "tbl_near", "tbl_promo", "tbl_adx", "tbl_hq_catalog");

    /** 业务线 → 广播表(订阅按表隔离;same-table 订阅者毫秒级,其他表走 delta 水印)。 */
    private static String builtinTableForType(String type) {
        return switch (String.valueOf(type == null ? "" : type)) {
            case "adx" -> "tbl_adx";
            case "created" -> "tbl_created";
            case "near" -> "tbl_near";
            case "promo" -> "tbl_promo";
            default -> "tbl_all";
        };
    }

    /**
     * 飞书式变更推送：EventSource 长连接（token 走 query param）；行写入推 dirty 信号 / 单元格级 op 帧，
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
        boolean blankOk = requireActiveBlankTable(tid, request) != null;
        boolean builtinOk = BUILTIN_STREAM_TABLES.contains(tid);
        if (!blankOk && !builtinOk) {
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
        // 中文备注：也接受扁平行字段（与 dramas/save 前端 payload 类似）。
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
        int updated = sheetRowRepository.save(tableId, rowId, json, expectedVersion, operator);
        if (updated <= 0) {
            // 中文备注：并发下二次校验失败同样不落审计。
            return ApiResult.fail("A0409", "这条记录刚被别人修改过，请刷新后再保存");
        }
        // 中文备注：仅保存成功才写审计（A→成功一条；B 刷新后再成功又一条）。
        List<Map<String, Object>> changeLogs = new ArrayList<>();
        String sheetType = sheetLogDramaType(tableId);
        for (String key : allowed) {
            String oldTrim = trimToEmpty(String.valueOf(oldCells.getOrDefault(key, "")));
            String newTrim = trimToEmpty(String.valueOf(cells.getOrDefault(key, "")));
            if (Objects.equals(oldTrim, newTrim)) {
                continue;
            }
            String title = keyToTitle.getOrDefault(key, key);
            long logId = dramaOperationRepository.insertLog(sheetType, rowId, operator, title, oldTrim, newTrim);
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
        data.putAll(dramaOperationRepository.findWorkspaceLastModify());
        notifyEditorMaterialCompleteForSheetRow(tableId, rowId, oldCells, cells, keyToTitle, operator);
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
        String sheetType = sheetLogDramaType(tableId);
        long logId = dramaOperationRepository.insertLog(sheetType, targetRowId, operator, fieldName, oldTrim, newTrim);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", logId);
        data.put("rowId", targetRowId);
        data.put("fieldName", fieldName);
        data.put("oldValue", oldTrim);
        data.put("newValue", newTrim);
        data.putAll(dramaOperationRepository.findWorkspaceLastModify());
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

    /** 空白协作表：近 24h 缺 IAP链接1 的行异步采链（代投 tbl_u_fdd5c57b2e36 / 总部 HQ tbl_u_85f6bab6cf07）。 */
    @PostMapping("/sheets/{tableId}/link-collect")
    public ApiResult<Map<String, Object>> startSheetLinkCollect(HttpServletRequest request,
                                                                 @PathVariable("tableId") String tableId,
                                                                 @RequestBody(required = false) Map<String, Object> body) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        SheetLinkCollectLane lane;
        try {
            lane = resolveSheetLinkCollectLane(request, tableId);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0403", e.getMessage());
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        int windowHours = 24;
        if (body != null && body.get("windowHours") instanceof Number n) {
            int raw = n.intValue();
            // 0=不限制行活动时间（bulk 缺链扫描本身已全表；详情补数亦适用）
            windowHours = raw <= 0 ? 0 : Math.min(168, Math.max(1, raw));
        }
        boolean bulk = body != null && Boolean.TRUE.equals(body.get("bulk"));
        boolean skipTimeBackfill = bulk || (body != null && Boolean.TRUE.equals(body.get("skipTimeBackfill")));
        int maxBulk = agencySheetLinkCollectService.bulkBatchLimit(lane);
        int limit = maxBulk;
        if (body != null && body.get("limit") instanceof Number lim) {
            limit = Math.min(maxBulk, Math.max(1, lim.intValue()));
        }
        AgencySheetLinkCollectService.LinkCollectOptions options = bulk
                ? AgencySheetLinkCollectService.LinkCollectOptions.bulk(limit)
                : new AgencySheetLinkCollectService.LinkCollectOptions(
                Math.min(AgencySheetLinkCollectService.MAX_UI_BATCH, limit), skipTimeBackfill, false, 0);
        try {
            Map<String, Object> data = agencySheetLinkCollectService.startJob(tableId, windowHours, lane, options);
            return ApiResult.ok(data);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0400", e.getMessage());
        } catch (Exception e) {
            return ApiResult.fail("A0500", "启动采链失败：" + e.getMessage());
        }
    }

    @GetMapping("/sheets/{tableId}/link-collect/backlog")
    public ApiResult<Map<String, Object>> sheetLinkCollectBacklog(HttpServletRequest request,
                                                                   @PathVariable("tableId") String tableId,
                                                                   @RequestParam(value = "bulk", defaultValue = "false") boolean bulk) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        SheetLinkCollectLane lane;
        try {
            lane = resolveSheetLinkCollectLane(request, tableId);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0403", e.getMessage());
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        try {
            return ApiResult.ok(agencySheetLinkCollectService.backlogSnapshot(tableId, lane, bulk));
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @GetMapping("/sheets/{tableId}/link-collect/jobs/{jobId}")
    public ApiResult<Map<String, Object>> sheetLinkCollectJob(HttpServletRequest request,
                                                               @PathVariable("tableId") String tableId,
                                                               @PathVariable("jobId") String jobId) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        SheetLinkCollectLane lane;
        try {
            lane = resolveSheetLinkCollectLane(request, tableId);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0403", e.getMessage());
        }
        Map<String, Object> data = agencySheetLinkCollectService.jobSnapshot(tableId, trimToEmpty(jobId), lane);
        if (!Boolean.TRUE.equals(data.get("found"))) {
            return ApiResult.fail("A0404", "采链任务不存在");
        }
        return ApiResult.ok(data);
    }

    /** 中文备注：空白表采链进行中任务（任意登录用户可见，用于按钮「采链中」同步）。 */
    /**
     * 代投：等同后台 cron 开跑一批 bulk（仅 ad 实例；不在 monitor/HQ 暴露有效调度）。
     * Shift+点击采链按钮时可由前端调用，与「自动续批」叠加。
     */
    @PostMapping("/sheets/{tableId}/link-collect/background-tick")
    public ApiResult<Map<String, Object>> sheetLinkCollectBackgroundTick(HttpServletRequest request,
                                                                          @PathVariable("tableId") String tableId,
                                                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        try {
            resolveSheetLinkCollectLane(request, tableId);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0403", e.getMessage());
        }
        if (agencySheetLinkCollectScheduler == null) {
            Map<String, Object> fallback = agencySheetLinkCollectService.runAgencyScheduledBulkBatch(true, "ui-tick");
            return ApiResult.ok(fallback);
        }
        boolean respectQuiet = body == null || !Boolean.FALSE.equals(body.get("respectQuietHours"));
        return ApiResult.ok(agencySheetLinkCollectScheduler.tickNow("ui-tick", respectQuiet));
    }

    @GetMapping("/sheets/{tableId}/link-collect/active")
    public ApiResult<Map<String, Object>> sheetLinkCollectActive(HttpServletRequest request,
                                                                  @PathVariable("tableId") String tableId) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        SheetLinkCollectLane lane;
        try {
            lane = resolveSheetLinkCollectLane(request, tableId);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0403", e.getMessage());
        }
        if (requireActiveBlankTable(tableId, request) == null) {
            return ApiResult.fail("A0404", "空白表不存在或已删除");
        }
        try {
            return ApiResult.ok(agencySheetLinkCollectService.activeJobSnapshot(tableId, lane));
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    private SheetLinkCollectLane resolveSheetLinkCollectLane(HttpServletRequest request, String tableId) {
        String tid = trimToEmpty(tableId);
        if (OpsTenantService.ORG_AGENCY.equals(opsTenantService.resolveMode(request))) {
            if (!ChangduCollectGuard.AGENCY_SHEET_TABLE_ID.equals(tid)) {
                throw new IllegalStateException("仅代投协作表支持同步推广链");
            }
            return SheetLinkCollectLane.AGENCY;
        }
        if (!ChangduCollectGuard.DEFAULT_HQ_BLANK_SHEET_TABLE_ID.equals(tid)) {
            throw new IllegalStateException("仅总部漫剧协作表支持同步推广链");
        }
        return SheetLinkCollectLane.HQ;
    }

    private List<Map<String, Object>> mergeTableCatalog(List<Map<String, Object>> custom) {
        return mergeTableCatalog(custom, BUILTIN_TABLES);
    }

    private List<Map<String, Object>> mergeTableCatalog(List<Map<String, Object>> custom, List<Map<String, Object>> builtin) {
        List<Map<String, Object>> all = new ArrayList<>();
        List<Map<String, Object>> source = builtin == null || builtin.isEmpty() ? BUILTIN_TABLES : builtin;
        for (Map<String, Object> row : source) {
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

    private void seedFilterTableMainView(String tableId, String baseType, String label,
                                         String filtersJson, String operator) {
        try {
            List<Object> filterRules = List.of();
            if (filtersJson != null && !filtersJson.isBlank()) {
                JsonNode node = objectMapper.readTree(filtersJson);
                if (node.isArray()) {
                    filterRules = objectMapper.convertValue(node, List.class);
                }
            }
            Map<String, Object> main = new LinkedHashMap<>();
            String viewId = "vew_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            main.put("id", viewId);
            main.put("viewId", viewId);
            main.put("tableId", tableId);
            main.put("type", baseType);
            main.put("name", "主视图");
            main.put("locked", true);
            main.put("protectMode", "public");
            main.put("owner", operator);
            boolean createdTable = "created".equals(baseType);
            boolean nearTable = "near".equals(baseType);
            main.put("dateMode", createdTable ? "day" : "range");
            main.put("date", createdTable ? java.time.LocalDate.now().toString() : "");
            main.put("keyword", "");
            main.put("filterRules", filterRules);
            main.put("savedFilterRules", filterRules);
            main.put("filterDirty", false);
            main.put("sortField", createdTable || nearTable ? "changduCreateTime" : "estimatePublishTime");
            main.put("sortDir", createdTable || nearTable ? "desc" : "asc");
            main.put("sortRules", List.of());
            main.put("autoSort", true);
            if (createdTable) {
                main.put("groupRules", List.of(
                        Map.of("id", "gr_created_day", "field", "changduCreateTime", "dir", "desc", "dateGranularity", "day"),
                        Map.of("id", "gr_created_delivery", "field", "estimatePublishTime", "dir", "asc", "dateGranularity", "day")
                ));
            } else {
                main.put("groupRules", List.of());
            }
            main.put("page", 1);
            main.put("pageSize", 50);
            main.put("hiddenFields", List.of());
            main.put("columnLabels", Map.of());
            main.put("columnWidths", Map.of());
            main.put("columnFills", Map.of());
            main.put("columnOrder", List.of());
            tableViewsRepository.upsert(tableId, objectMapper.writeValueAsString(List.of(main)), operator);
        } catch (Exception e) {
            throw new IllegalStateException("种子主视图失败：" + e.getMessage(), e);
        }
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

    /** 飞书式：系统表首屏 encoding=2 gzip；补页走 JSON rows（勿每批 gzip）。 */
    private void applyDramaListRecordsEnvelope(Map<String, Object> data, String tableId, String viewId) {
        if (data == null) {
            return;
        }
        Object rawRows = data.get("rows");
        if (!(rawRows instanceof List<?> list)) {
            return;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                rows.add(row);
            }
        }
        String cacheKey = dramaListRecordsEncodeCacheKey(data, tableId, viewId);
        OpsSheetListRecordsEncodeCache.CachedGzip cached = sheetListRecordsEncodeCache.get(cacheKey);
        if (cached != null) {
            attachRecordsGzipEnvelope(data, viewId, cached.gzip(), cached.keys(), cached.rowCount());
            data.put("recordsEncodeCacheHit", true);
            return;
        }
        List<Map<String, Object>> fieldMetas = List.of();
        if (tableId != null && !tableId.isBlank()) {
            try {
                fieldMetas = toSheetFieldMetas(sheetFieldRepository.listActive(tableId));
            } catch (Exception ignored) {
                fieldMetas = List.of();
            }
        }
        String nextCursor = data.get("nextCursor") == null ? null : String.valueOf(data.get("nextCursor"));
        boolean hasMore = Boolean.TRUE.equals(data.get("hasMore"));
        int pageLimit = readIntFromData(data.get("limit"), rows.size());
        boolean encodeHeld = sheetListRecordsEncodeCache.acquireEncodePermit();
        try {
            OpsSheetRowRecordsService.SheetRecordsChunk chunk = sheetRowRecordsService.buildChunkFromRows(
                    tableId, viewId, rows, fieldMetas, nextCursor, hasMore, pageLimit);
            byte[] gzip = chunk.gzipPayload();
            sheetListRecordsEncodeCache.put(cacheKey,
                    new OpsSheetListRecordsEncodeCache.CachedGzip(gzip, chunk.keys(), chunk.rowCount()));
            attachRecordsGzipEnvelope(data, viewId, gzip, chunk.keys(), chunk.rowCount());
            data.put("recordsEncodeCacheHit", false);
        } finally {
            sheetListRecordsEncodeCache.releaseEncodePermit(encodeHeld);
        }
    }

    private static int readIntFromData(Object lim, int fallback) {
        if (lim instanceof Number n) {
            return n.intValue();
        }
        if (lim != null) {
            try {
                return Integer.parseInt(String.valueOf(lim));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String dramaListRecordsEncodeCacheKey(Map<String, Object> data, String tableId, String viewId) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("drama|").append(trimToEmpty(tableId)).append('|').append(trimToEmpty(viewId));
        sb.append('|').append(String.valueOf(data.get("type")));
        sb.append('|').append(String.valueOf(data.get("date")));
        sb.append('|').append(String.valueOf(data.get("dateEnd")));
        sb.append('|').append(String.valueOf(data.get("limit")));
        sb.append('|').append(String.valueOf(data.get("keyword")));
        sb.append('|').append(Boolean.TRUE.equals(data.get("lite")) ? "1" : "0");
        Object filters = data.get("filters");
        sb.append('|').append(filters == null ? "" : String.valueOf(filters.hashCode()));
        sb.append('|').append(String.valueOf(data.get("agencyPromoLinksRevision")));
        return sb.toString();
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

    private static String typeFromBuiltinTableId(String tableId) {
        String tid = tableId == null ? "" : tableId.trim();
        return switch (tid) {
            case "tbl_created" -> "created";
            case "tbl_near" -> "near";
            case "tbl_promo" -> "promo";
            case "tbl_agency_promo" -> OpsDramaListService.AGENCY_PROMO_TYPE;
            case "tbl_adx" -> "adx";
            case "tbl_hq_catalog" -> OpsTenantFeatureService.CATALOG_LIST_TYPE;
            default -> "all";
        };
    }

    private static String builtinTableIdFromType(String type) {
        String normalized = type == null ? "all" : type.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "created" -> "tbl_created";
            case "near" -> "tbl_near";
            case "promo" -> "tbl_promo";
            case "agency-promo" -> OpsTenantFeatureService.AGENCY_AS_PROMO_TABLE_ID;
            case "adx" -> "tbl_adx";
            case "hq-catalog" -> OpsTenantFeatureService.CATALOG_TABLE_ID;
            default -> "tbl_all";
        };
    }

    /** 代投剧目目录 / 内置表：前端偶发 type 不准时，用 tableId 纠正。 */
    private static String effectiveDramaListType(String type, String tableId) {
        String tid = tableId == null ? "" : tableId.trim();
        if (OpsTenantFeatureService.CATALOG_TABLE_ID.equals(tid)) {
            return OpsTenantFeatureService.CATALOG_LIST_TYPE;
        }
        if (!tid.isBlank() && !"tbl_all".equals(tid)) {
            String fromTable = typeFromBuiltinTableId(tid);
            if (!"all".equals(fromTable)) {
                return fromTable;
            }
        }
        String normalized = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if (OpsTenantFeatureService.CATALOG_LIST_TYPE.equals(normalized)) {
            return OpsTenantFeatureService.CATALOG_LIST_TYPE;
        }
        return normalized.isBlank() ? "all" : normalized;
    }

    private List<Map<String, Object>> loadLookupValidationRows(String tableId, String dramaType) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!OpsSheetLookupService.isBuiltinTableId(tableId)) {
            return rows;
        }
        try {
            Map<String, Object> payload = opsDramaListService.list(
                    null, null, 3, dramaType, 500, null, null, null, true);
            Object raw = payload == null ? null : payload.get("rows");
            if (!(raw instanceof List<?> list)) {
                return rows;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    row.put("dramaType", dramaType);
                    rows.add(row);
                    if (rows.size() >= 500) {
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return rows;
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
        } else {
            OpsMasterFieldLinkService.MasterLinkConfig link = masterFieldLinkService.parseLinkConfig(masterLinkRaw);
            if (link.enabled()) {
                Map<String, Object> masterLink = new LinkedHashMap<>();
                masterLink.put("targetFieldKey", link.targetFieldKey());
                masterLink.put("targetFieldLabel", link.targetFieldLabel());
                masterLink.put("syncOptions", link.syncOptions());
                masterLink.put("syncValue", link.syncValue());
                masterLink.put("mode", "oneWay");
                masterLink.put("fillPolicy", "blankOnly");
                meta.put("masterLink", masterLink);
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
        data.putAll(dramaOperationRepository.findWorkspaceLastModify());
        return ApiResult.ok(data);
    }

    private static final String WORKSPACE_UI_TABLE_ID = "tbl__workspace";
    private static final String WORKSPACE_UI_VIEW_ID = "vew_workspace_layout";
    private static final int WORKSPACE_SIDEBAR_DEFAULT_WIDTH = 180;

    /**
     * 中文备注：库中无视图时落一颗主视图，避免前端拿本机缓存整表 POST 做种子。
     */
    private void ensureDefaultMainViewIfEmpty(String tableId, String operator) {
        if (!readSharedViews(tableId).isEmpty()) return;
        if (WORKSPACE_UI_TABLE_ID.equals(tableId)) {
            // 中文备注：侧栏宽/折叠改为按人 personal，不再种子全员共享布局。
            return;
        }
        Map<String, Object> reg = tableRegistryRepository.findActive(tableId);
        if (reg != null && "blank".equalsIgnoreCase(trimToEmpty((String) reg.get("kind")))) {
            seedBlankTableMainView(tableId, operator);
            return;
        }
        String baseType = reg == null ? "" : trimToEmpty((String) reg.get("baseType"));
        if (baseType.isBlank()) {
            if ("tbl_created".equals(tableId)) baseType = "created";
            else if ("tbl_near".equals(tableId)) baseType = "near";
            else if ("tbl_promo".equals(tableId)) baseType = "promo";
            else if ("tbl_adx".equals(tableId)) baseType = "adx";
            else baseType = "all";
        }
        String label = reg == null ? "" : trimToEmpty((String) reg.get("label"));
        String filtersJson = reg == null ? null : (String) reg.get("defaultFiltersJson");
        seedFilterTableMainView(tableId, baseType, label, filtersJson, operator);
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
            data.putAll(leaderGroupSessionExtras(result.session()));
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
            view.putAll(leaderGroupSessionExtras(session));
            view.putAll(tenantFeatureExtras(request, session));
            return ApiResult.ok(view);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    /**
     * 中文备注：运维——按预估投放日窗口均分达到配置集数门槛的剪辑小组候选剧目。
     */
    @PostMapping("/leader-group/rebalance")
    public ApiResult<Map<String, Object>> rebalanceLeaderGroup(HttpServletRequest request) {
        try {
            requireSession(request);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
        if (!leaderGroupService.enabled()) {
            return ApiResult.fail("A0403", "组长分组功能未启用");
        }
        LocalDate start = leaderGroupService.assignWindowStart();
        LocalDate end = leaderGroupService.assignWindowEndInclusive();
        Map<String, Object> data = leaderGroupService.rebalanceDailyGroupAssignments(start, end);
        data.put("counts", leaderGroupService.countAssignmentsByLeader());
        return ApiResult.ok(data);
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
            data.putAll(leaderGroupSessionExtras(result.session()));
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
            data.putAll(dramaOperationRepository.findWorkspaceLastModify());
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
            Map<String, Object> data = new LinkedHashMap<>(dramaOperationRepository.findWorkspaceLastModify());
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
            List<Map<String, Object>> items = dramaOperationRepository.listWorkspaceRecentActivity(limit);
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
            data.putAll(dramaOperationRepository.findWorkspaceLastModify());
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

    @GetMapping("/dramas")
    @Transactional
    public ApiResult<Map<String, Object>> listDramas(HttpServletRequest request,
                                                     @RequestParam(required = false) String date,
                                                     @RequestParam(required = false) String dateEnd,
                                                     @RequestParam(required = false, defaultValue = "3") int days,
                                                     @RequestParam(required = false, defaultValue = "all") String type,
                                                     @RequestParam(required = false) String tableId,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false, defaultValue = "1") int page,
                                                     @RequestParam(required = false, defaultValue = "50") int pageSize,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String filters,
                                                     @RequestParam(required = false, defaultValue = "estimatePublishTime") String sortField,
                                                     @RequestParam(required = false, defaultValue = "asc") String sortDir,
                                                     @RequestParam(required = false, defaultValue = "false") boolean lite,
                                                     @RequestParam(value = "lookupForce", required = false) String lookupForce,
                                                     @RequestParam(value = "lookupSkip", required = false) String lookupSkip,
                                                     @RequestParam(value = "viewId", required = false) String viewId,
                                                     @RequestParam(value = "records", required = false) String records) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tenantMode = opsTenantService.resolveMode(request);
        OpsAuthService.Session session = sessionOrNull(request);
        String listType = effectiveDramaListType(type, tableId);
        if (!opsTenantService.isListTypeAllowed(tenantMode, listType, session)) {
            return ApiResult.fail("A0403", "当前入口无权访问该表");
        }
        // 中文备注：limit=1 纯总数预取（无游标/关键词/筛选/records）走水印级 total 缓存，
        // COUNT 在 5 万行窗口上要 2.5s 且与首屏流抢库；数据未变时直接复用。
        boolean totalOnlyPrefetch = Integer.valueOf(1).equals(limit)
                && (cursor == null || cursor.isBlank())
                && (keyword == null || keyword.isBlank())
                && (filters == null || filters.isBlank())
                && (records == null || records.isBlank())
                && page <= 1;
        long listWatermark = opsListFastCache.watermark();
        String totalCacheKey = "total|" + listType + "|" + date + "|" + dateEnd + "|" + days + "|"
                + sortField + "|" + sortDir + "|" + listWatermark;
        if (totalOnlyPrefetch) {
            Integer cachedTotal = opsListFastCache.getTotal(totalCacheKey, listWatermark);
            if (cachedTotal != null) {
                Map<String, Object> cachedData = new LinkedHashMap<>();
                cachedData.put("total", cachedTotal);
                cachedData.put("totalHint", cachedTotal);
                cachedData.put("type", listType);
                cachedData.put("rows", java.util.Collections.emptyList());
                cachedData.put("hasMore", false);
                cachedData.put("recordCount", 0);
                return ApiResult.ok(cachedData);
            }
        }
        boolean heavyHeld = false;
        try {
            Integer effectiveLimit = limit;
            if (effectiveLimit == null && pageSize > 0 && pageSize <= opsDramaListService.maxPageSize()) {
                effectiveLimit = pageSize;
            }
            if (effectiveLimit == null || effectiveLimit <= 0) {
                effectiveLimit = opsDramaListService.maxPageSize();
            }
            effectiveLimit = serverLoadGuard.capListBatchSize(effectiveLimit);
            heavyHeld = serverLoadGuard.enterHeavyQueryForList(effectiveLimit);
            Map<String, Object> data;
            if (tenantFeatureService.isAgencyHqReadOnlyListType(tenantMode, listType)
                    && agencyHqCatalogListService != null) {
                data = agencyHqCatalogListService.listForAgencyType(
                        listType, date, dateEnd, days, effectiveLimit, cursor, keyword, filters);
            } else {
                data = opsDramaListService.list(date, dateEnd, days, listType, effectiveLimit, cursor, keyword, filters,
                        lite, sortField, sortDir);
            }
            data.put("listBatchLimit", effectiveLimit);
            // 中文备注：limit=1 必然 hasMore=true,不能拿它当回填条件;total<=0 是 COUNT 失败哨兵,不缓存。
            if (totalOnlyPrefetch && data.get("total") instanceof Number num && num.longValue() > 0) {
                opsListFastCache.putTotal(totalCacheKey, num.intValue(), listWatermark);
            }
            if (!isTruthyQueryFlag(lookupSkip)) {
                java.util.Set<String> lookupForceKeys = parseLookupForceKeys(lookupForce);
                List<Map<String, Object>> lookupMetas = sheetLookupService.buildLookupFieldMetasFromConfigs();
                if (!lookupMetas.isEmpty()) {
                    Object rawRows = data.get("rows");
                    if (rawRows instanceof List<?> list && !list.isEmpty()) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> m) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                                rows.add(row);
                            }
                        }
                        List<Map<String, Object>> fieldMetas = new ArrayList<>(sheetLookupService.builtinMatchFieldMetas());
                        fieldMetas.addAll(lookupMetas);
                        String operator = "";
                        try {
                            operator = trimToEmpty(requireSession(request).displayName());
                        } catch (Exception ignored) {
                        }
                        String lookupTableId = trimToEmpty(tableId);
                        if (lookupTableId.isBlank()) {
                            lookupTableId = builtinTableIdFromType(listType);
                        }
                        try {
                            List<Map<String, Object>> lookupSync = sheetLookupService.enrichSheetLookupFields(
                                    lookupTableId, operator, fieldMetas, rows, lookupForceKeys);
                            data.put("lookupSync", lookupSync == null ? List.of() : lookupSync);
                            data.put("rows", rows);
                        } catch (OpsSheetLookupService.LookupLimitExceededException e) {
                            return ApiResult.fail("A0429", e.getMessage());
                        }
                    }
                }
            }
            data.putAll(effectiveOptionMap(session));
            if (Boolean.TRUE.equals(data.get("readOnly"))) {
                data.put("editableFields", List.of());
            } else {
                data.put("editableFields", EDITABLE_FIELDS);
            }
            data.putAll(serverLoadGuard.snapshot());
            if (isTruthyQueryFlag(records) && trimToEmpty(cursor).isBlank()) {
                String recordsTableId = trimToEmpty(tableId);
                if (recordsTableId.isBlank()) {
                    recordsTableId = builtinTableIdFromType(listType);
                }
                applyDramaListRecordsEnvelope(data, recordsTableId, trimToEmpty(viewId));
            }
            return ApiResult.ok(data);
        } catch (IllegalStateException e) {
            return ApiResult.fail("A0401", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        } finally {
            serverLoadGuard.releaseHeavyQuery(heavyHeld);
        }
    }

    /** Main operations table: one request, progressively written NDJSON response, no payload nonce follow-up. */
    @GetMapping("/dramas/stream")
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody streamDramas(HttpServletRequest request,
                                          HttpServletResponse response,
                                          @RequestParam(required = false) String date,
                                          @RequestParam(required = false) String dateEnd,
                                          @RequestParam(required = false, defaultValue = "3") int days,
                                          @RequestParam(required = false, defaultValue = "all") String type,
                                          @RequestParam(required = false) String tableId,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String filters,
                                          @RequestParam(required = false, defaultValue = "estimatePublishTime") String sortField,
                                          @RequestParam(required = false, defaultValue = "asc") String sortDir,
                                          @RequestParam(required = false, defaultValue = "false") boolean lite,
                                          @RequestParam(value = "lookupForce", required = false) String lookupForce,
                                          @RequestParam(value = "lookupSkip", required = false) String lookupSkip) throws IOException {
        if (!trimToEmpty(cursor).isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResult.fail("A0400", "单响应流不接受 cursor"));
            return null;
        }
        int fullLimit = limit == null || limit <= 0 ? 60_000 : Math.min(60_000, limit);
        // 中文备注：水印级流式缓存——数据未变时同窗口重复加载直接吐 gzip 字节（≈50ms）。
        long streamWatermark = opsListFastCache.watermark();
        String streamCacheKey = "stream|" + type + "|" + date + "|" + dateEnd + "|" + days + "|"
                + sortField + "|" + sortDir + "|" + fullLimit + "|"
                + (keyword == null ? "" : keyword) + "|" + (filters == null ? "" : filters) + "|"
                + streamWatermark;
        byte[] cachedStream = opsListFastCache.getStream(streamCacheKey, streamWatermark);
        if (cachedStream != null
                && trimToEmpty(request.getHeader(org.springframework.http.HttpHeaders.ACCEPT_ENCODING)).toLowerCase().contains("gzip")) {
            response.setContentType("application/x-ndjson;charset=UTF-8");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
            response.setHeader(HttpHeaders.VARY, "Accept-Encoding");
            response.getOutputStream().write(cachedStream);
            response.getOutputStream().flush();
            return null;
        }
        ApiResult<Map<String, Object>> result = listDramas(request, date, dateEnd, days, type, tableId,
                fullLimit, null, 1, fullLimit, keyword, filters, sortField, sortDir, lite,
                lookupForce, lookupSkip, null, null);
        if (!"00000".equals(result.code()) || result.data() == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), result);
            return null;
        }
        response.setContentType("application/x-ndjson;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        // 中文备注：NDJSON 裸传体积巨大（10 天窗口 1.8 万行 ≈ 55MB），浏览器解析期间页面卡死。
        // 客户端支持 gzip 时按 syncFlush 流式压缩（55MB→约 6MB），fetch 自动透明解压，前端零改动。
        String acceptEncoding = trimToEmpty(request.getHeader(HttpHeaders.ACCEPT_ENCODING)).toLowerCase();
        if (acceptEncoding.contains("gzip")) {
            response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
            response.setHeader(HttpHeaders.VARY, "Accept-Encoding");
            // 中文备注：gzip trailer 只在 close() 时写出，必须在任务内显式 close，否则浏览器解压报截断。
            return output -> {
                // 中文备注：写穿式缓存——gzip 字节同时写客户端与内存缓冲,完成后回填缓存,输出仍渐进。
                java.io.ByteArrayOutputStream streamBuf = new java.io.ByteArrayOutputStream();
                java.io.OutputStream tee = new java.io.FilterOutputStream(output) {
                    @Override
                    public void write(int b) throws IOException { output.write(b); streamBuf.write(b); }
                    @Override
                    public void write(byte[] b, int off, int len) throws IOException { output.write(b, off, len); streamBuf.write(b, off, len); }
                };
                java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(tee, 8192, true);
                try {
                    new com.polytable.service.OpsDramaNdjsonStreamWriter(objectMapper).write(result.data(), gzipOut);
                } finally {
                    gzipOut.close();
                    opsListFastCache.putStream(streamCacheKey, streamBuf.toByteArray(), streamWatermark);
                }
            };
        }
        return output ->
                new com.polytable.service.OpsDramaNdjsonStreamWriter(objectMapper).write(result.data(), output);
    }

    /** 系统表 /dramas records=1 时的 gzip 块（与 JSON 信封同 nonce）。 */
    @GetMapping("/dramas/records/payload")
    public org.springframework.http.ResponseEntity<byte[]> dramaListRecordsPayload(HttpServletRequest request,
                                                                                 @RequestParam("nonce") String nonce) {
        try {
            requireSession(request);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(401).build();
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
     * 中文备注：查询带剪辑的推广参考候选（默认明天投放日）。与「当日剧目推广链接参考」同源，只读不写飞书。
     * 例：GET /api/v1/ops/promo/edited?date=2026-07-27
     */
    @GetMapping("/promo/edited")
    public ApiResult<Map<String, Object>> promoEdited(HttpServletRequest request,
                                                      @RequestParam(required = false) String date,
                                                      @RequestParam(required = false, defaultValue = "200") int limit) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        try {
            LocalDate day = (date == null || date.isBlank())
                    ? LocalDate.now().plusDays(1)
                    : LocalDate.parse(date.trim());
            return ApiResult.ok(opsDramaListService.summarizePromoEdited(day, limit));
        } catch (Exception e) {
            return ApiResult.fail("A0500", e.getMessage());
        }
    }

    /**
     * 中文备注：手动触发本地推广链接采集（优先今日+明日带剪辑缺链；到点才创建链接）。不写飞书。
     */
    @PostMapping("/agency-promo/collect")
    public ApiResult<Map<String, Object>> agencyPromoCollect(HttpServletRequest request) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (!OpsTenantService.ORG_AGENCY.equals(opsTenantService.resolveMode(request))) {
            return ApiResult.fail("A0403", "代投 A/S 推广链采集仅在代投入口可用");
        }
        if (agencyPromoLinkCollectService == null) {
            return ApiResult.fail("A0500", "代投 A/S 推广链采集未启用（需在 agency JVM 配置）");
        }
        try {
            Map<String, Object> result = agencyPromoLinkCollectService.collectTodaySafe();
            return ApiResult.ok(result);
        } catch (Exception e) {
            return ApiResult.fail("A0500", e.getMessage());
        }
    }

    @PostMapping("/promo/collect")
    public ApiResult<Map<String, Object>> promoCollect(HttpServletRequest request) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        try {
            localPromotionLinkCollectTask.collectLocalPromotionLinksInternal();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", true);
            data.put("message", "已触发本地推广链接采集（今日+明日带剪辑优先；未到投放点的只展示、不建链）");
            data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            return ApiResult.ok(data);
        } catch (Exception e) {
            return ApiResult.fail("A0500", e.getMessage());
        }
    }

    /**
     * 中文备注：入库后增量；前端 since=listVersion 合并，禁止整表 reload。
     */
    @GetMapping("/dramas/delta")
    public ApiResult<Map<String, Object>> listDramasDelta(HttpServletRequest request,
                                                          @RequestParam(required = false) String date,
                                                          @RequestParam(required = false) String dateEnd,
                                                          @RequestParam(required = false, defaultValue = "3") int days,
                                                          @RequestParam(required = false, defaultValue = "all") String type,
                                                          @RequestParam(required = false) String tableId,
                                                          @RequestParam(required = false, defaultValue = "0") long since,
                                                          @RequestParam(required = false) String keyword) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        String tenantMode = opsTenantService.resolveMode(request);
        OpsAuthService.Session session = sessionOrNull(request);
        String listType = effectiveDramaListType(type, tableId);
        if (!opsTenantService.isListTypeAllowed(tenantMode, listType, session)) {
            return ApiResult.fail("A0403", "当前入口无权访问该表");
        }
        try {
            String normalized = listType == null ? "all" : listType.trim().toLowerCase();
            // 中文备注：仅 critical 推迟 ADX 静默刷新；near/全部剧目增量照常，保证新剧及时进表。
            if (serverLoadGuard.shouldDeferBackgroundRefresh(normalized)) {
                Map<String, Object> deferred = new LinkedHashMap<>();
                deferred.put("rows", List.of());
                deferred.put("deletedKeys", List.of());
                deferred.put("listVersion", since);
                deferred.put("deferredByLoad", true);
                deferred.putAll(serverLoadGuard.snapshot());
                return ApiResult.ok(deferred);
            }
            Map<String, Object> data;
            if (tenantFeatureService.isAgencyHqReadOnlyListType(tenantMode, listType)
                    && agencyHqCatalogListService != null) {
                data = agencyHqCatalogListService.deltaForAgencyType(listType, date, dateEnd, days, since, keyword);
            } else {
                data = opsDramaListService.delta(date, dateEnd, days, listType, since, keyword);
            }
            data.putAll(serverLoadGuard.snapshot());
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/dramas/save")
    @Transactional
    public ApiResult<Map<String, Object>> save(HttpServletRequest request, @RequestBody SaveRequest body) {
        try {
            return saveInternal(request, body);
        } catch (IllegalStateException e) {
            // 中文备注：主表落库失败时回滚运营表，避免只改一半。
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail("A0404", e.getMessage());
        }
    }

    /** 中文备注：批量粘贴批量保存请求。rows 复用 SaveRequest 结构(单行保存的全部语义)。 */
    public record DramaSaveBatchRequest(String batchId,
                                        Boolean force,
                                        List<SaveRequest> rows) {
    }

    /**
     * 中文备注：大批量粘贴批量保存——单 HTTP 单事务复用 saveOneInternal 全部语义
     * (校验/主表同步/审计/op_log),基线 300 行逐行 HTTP 32.6s → 预期 1-2s。
     * 版本策略：force=false 全批预检,任一版本不一致返回 A0409+conflicts(不写库);
     * force=true 以库内当前版本覆盖写入(last-write-wins,version+1)。
     * op 帧合并：收集全部行 diff,循环结束按表合并为一次广播;changeLogs 随回包返回供撤回栈。
     */
    @PostMapping("/dramas/save-batch")
    @Transactional
    public ApiResult<Map<String, Object>> saveBatch(HttpServletRequest request,
                                                    @RequestBody DramaSaveBatchRequest body) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        OpsAuthService.Session session;
        String operator;
        String tenantMode;
        try {
            session = requireSession(request);
            operator = trimToEmpty(session.displayName());
            tenantMode = opsTenantService.resolveMode(request);
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (body == null || body.rows() == null || body.rows().isEmpty()) {
            return ApiResult.fail("A0400", "批量保存内容不能为空");
        }
        if (body.rows().size() > 5000) {
            return ApiResult.fail("A0400", "单次最多保存 5000 行");
        }
        boolean force = Boolean.TRUE.equals(body.force());
        // 1) 版本预检(一次 IN 查询;drama_operation 单表,bookId 全局唯一)
        List<String> bookIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SaveRequest row : body.rows()) {
            String bid = trimToEmpty(row == null ? null : row.bookId());
            if (bid.isBlank() && row != null) bid = trimToEmpty(row.seriesName());
            if (bid.isBlank() || seen.contains(bid)) continue;
            seen.add(bid);
            bookIds.add(bid);
        }
        Map<String, Integer> dbVersions = new LinkedHashMap<>();
        if (!bookIds.isEmpty() && !force) {
            for (int i = 0; i < bookIds.size(); i += 500) {
                List<String> chunk = bookIds.subList(i, Math.min(i + 500, bookIds.size()));
                String placeholders = chunk.stream().map(b -> "?").collect(java.util.stream.Collectors.joining(","));
                try {
                    List<Map<String, Object>> found = jdbcTemplate.queryForList(
                            "SELECT book_id, version FROM drama_operation WHERE book_id IN (" + placeholders + ")",
                            chunk.toArray());
                    for (Map<String, Object> f : found) {
                        dbVersions.put(String.valueOf(f.get("book_id")), ((Number) f.getOrDefault("version", 0)).intValue());
                    }
                } catch (Exception preErr) {
                    log.error("save-batch 版本预检查询失败(降级为逐行乐观锁): bookIds={}, err={}", chunk.size(), String.valueOf(preErr));
                }
            }
        }
        if (!force) {
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (SaveRequest row : body.rows()) {
                if (row == null) continue;
                String bid = trimToEmpty(row.bookId());
                if (bid.isBlank()) bid = trimToEmpty(row.seriesName());
                int expected = row.version() == null ? 0 : row.version();
                Integer actual = dbVersions.get(bid);
                // 库内不存在的行视为新建(允许保存);存在则必须版本一致
                if (actual != null && actual.intValue() != expected) {
                    conflicts.add(Map.of("bookId", bid, "expectedVersion", expected, "actualVersion", actual));
                }
            }
            if (!conflicts.isEmpty()) {
                return ApiResult.fail("A0409", "批量粘贴存在并发冲突，请刷新后重试或选择覆盖",
                        Map.of("conflicts", conflicts));
            }
        }
        // 2) 逐行保存(force=true 以库内当前版本覆盖)
        List<Map<String, Object>> opsCollector = new ArrayList<>();
        List<Map<String, Object>> savedRows = new ArrayList<>();
        List<Map<String, Object>> rowErrors = new ArrayList<>();
        for (SaveRequest row : body.rows()) {
            if (row == null) continue;
            String bid = trimToEmpty(row.bookId());
            if (bid.isBlank()) bid = trimToEmpty(row.seriesName());
            try {
                if (force) {
                    Integer actual = dbVersions.get(bid);
                    if (actual == null) {
                        Map<String, Object> found = dramaOperationRepository.find(normalizeType(row.dramaType()), bid);
                        actual = found == null ? 0 : ((Number) found.getOrDefault("version", 0)).intValue();
                        dbVersions.put(bid, actual);
                    }
                    row = new SaveRequest(
                            row.tableId(), row.dramaType(), row.bookId(), row.seriesName(), row.dramaTypeLabel(),
                            row.copyrightOwner(), row.screeningRating(), row.finalRating(), row.editorAssignee(),
                            row.materialCount(), row.deliveryTest(), row.cloudDiskUrl(), row.priorityLevel(),
                            row.bidderRating(), row.deliveryAssignee(), row.remark(), row.estimatePublishTime(),
                            row.publishTime(), row.changduCreateTime(), row.episodeAmount(), row.categoryText(),
                            row.douyinWorkId(), row.publishAwemeUser(), row.iaaDefaultUrl(), row.iaaCard2Url(),
                            row.iaaEpisode2Url(), row.iapPanel1(), row.iapLink1(), row.iapPanel2(), row.iapLink2(),
                            row.iapPanel3(), row.iapLink3(), row.iapPanel4(), row.iapLink4(), row.iapPanel5(),
                            row.iapLink5(), row.iapPanel6(), row.iapLink6(), row.iapPanel7(), row.iapLink7(),
                            actual, row.operator(), row.customFields());
                    if (actual == null) actual = 0;
                }
                ApiResult<Map<String, Object>> result = saveOneInternal(session, operator, tenantMode, row, opsCollector);
                if ("00000".equals(result.code()) && result.data() != null) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("bookId", trimToEmpty(String.valueOf(result.data().get("bookId"))));
                    one.put("version", result.data().get("version"));
                    one.put("changeLogs", result.data().get("changeLogs"));
                    savedRows.add(one);
                } else {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("bookId", bid);
                    err.put("code", result.code());
                    err.put("msg", result.msg());
                    rowErrors.add(err);
                }
            } catch (Exception rowEx) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("bookId", bid);
                err.put("code", "A0500");
                err.put("msg", String.valueOf(rowEx.getMessage()));
                rowErrors.add(err);
            }
        }
        // 3) op 帧合并广播(按表聚合 seq 最大帧;seq 自增单调,取每表最大 seq、ops 串接)
        if (!opsCollector.isEmpty()) {
            Map<String, StringBuilder> opsByTable = new LinkedHashMap<>();
            Map<String, Long> maxSeqByTable = new LinkedHashMap<>();
            for (Map<String, Object> c : opsCollector) {
                String tid = String.valueOf(c.get("tableId"));
                opsByTable.computeIfAbsent(tid, k -> new StringBuilder()).append(String.valueOf(c.get("opsJson")));
                Long prev = maxSeqByTable.get(tid);
                long seq = ((Number) c.get("seq")).longValue();
                maxSeqByTable.put(tid, prev == null ? seq : Math.max(prev, seq));
            }
            for (Map.Entry<String, StringBuilder> e : opsByTable.entrySet()) {
                String merged = "[" + e.getValue().toString().replace("][", ",") + "]";
                try {
                    sheetSyncPushService.broadcastOps(e.getKey(), maxSeqByTable.getOrDefault(e.getKey(), 0L), merged, operator);
                } catch (Exception ignored) {
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", savedRows);
        data.put("count", savedRows.size());
        data.put("errors", rowErrors);
        data.put("batchId", trimToEmpty(body.batchId()));
        return ApiResult.ok(data);
    }

    private ApiResult<Map<String, Object>> saveInternal(HttpServletRequest request, SaveRequest body) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (body == null || (trimToEmpty(body.bookId()).isBlank() && trimToEmpty(body.seriesName()).isBlank())) {
            return ApiResult.fail("A0400", "缺少 bookId");
        }
        String tenantMode = opsTenantService.resolveMode(request);
        OpsAuthService.Session session;
        String operator;
        try {
            session = requireSession(request);
            operator = trimToEmpty(session.displayName());
        } catch (Exception e) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        return saveOneInternal(session, operator, tenantMode, body, null);
    }

    /**
     * 中文备注：单行保存核心(供单条与批量端点复用)。opsCollector 非空=批量模式:
     * op 帧不逐行广播,收集后在批量端点合并为一帧(一次 SSE 推送)。
     */
    private ApiResult<Map<String, Object>> saveOneInternal(OpsAuthService.Session session, String operator,
                                                           String tenantMode, SaveRequest body,
                                                           List<Map<String, Object>> opsCollector) {
        if (body == null || (trimToEmpty(body.bookId()).isBlank() && trimToEmpty(body.seriesName()).isBlank())) {
            return ApiResult.fail("A0400", "缺少 bookId");
        }
        String type = normalizeType(body.dramaType());
        if ("adx".equals(type) && tenantFeatureService.isAgencyHqReadOnlyListType(tenantMode, "adx")) {
            return ApiResult.fail("A0403", "代投 ADX 只读表不可保存，请在总部协作台修改");
        }
        if (!SERIES_TABLES.containsKey(type) && !"adx".equals(type)) {
            return ApiResult.fail("A0400", "不支持的业务线：" + body.dramaType());
        }
        // 中文备注：ADX 热榜无采集主表行，用剧名键落运营字段即可。
        String saveBookId = trimToEmpty(body.bookId());
        if ("adx".equals(type)) {
            if (!saveBookId.startsWith("name-") && !trimToEmpty(body.seriesName()).isBlank()) {
                saveBookId = "name-" + trimToEmpty(body.seriesName());
            }
            if (saveBookId.isBlank()) {
                return ApiResult.fail("A0400", "缺少剧名");
            }
        } else if (saveBookId.isBlank()) {
            return ApiResult.fail("A0400", "缺少 bookId");
        }
        // 中文备注：操作人来自预解析会话(批量端点复用同一会话,禁止 body 伪造他人姓名)。
        if (operator == null || operator.isBlank()) {
            return ApiResult.fail("A0230", "请先登录，系统将自动记录操作人");
        }
        // 中文备注：剧名可编辑但不能清空；空串视为未填。
        String seriesName = trimToEmpty(body.seriesName());
        if (seriesName.isBlank()) {
            return ApiResult.fail("A0400", "剧名不能为空");
        }
        if (seriesName.length() > 120) {
            seriesName = seriesName.substring(0, 120);
        }
        Map<String, Object> before = dramaOperationRepository.find(type, saveBookId);
        // 中文备注：主表快照提前加载——首次建运营行时，未传版权/类型要从主表或列表默认回填，禁止写成空再双写清主表。
        Map<String, Object> seriesBefore = loadSeriesMasterSnapshot(type, saveBookId);
        // 中文备注：选项校验用「默认+自定义」合集；空串=清空；字段缺失(null)=保留库里原值。
        // 选项从配置删除后：单元格已填值仍可原样保存（祖传保留），禁止写入新的未知选项。
        String screeningRating = resolveOpsTextField(body.screeningRating(), before, "screeningRating");
        if (!isAllowedPersistedOption("screeningRating", screeningRating, beforeValue(before, "screeningRating"), session)) {
            return ApiResult.fail("A0400", "不支持的筛剧评级：" + screeningRating);
        }
        String dramaTypeLabel = resolveOpsDramaTypeLabelForSave(body.dramaTypeLabel(), before, type);
        if (!isAllowedPersistedOption("dramaTypeLabel", dramaTypeLabel, beforeValue(before, "dramaTypeLabel"))) {
            return ApiResult.fail("A0400", "不支持的剧目类型：" + dramaTypeLabel);
        }
        String editorAssignee = normalizeMultiOptionValue(
                resolveOpsTextField(body.editorAssignee(), before, "editorAssignee"));
        List<String> unsupportedEditors = unsupportedMultiOptionValues(
                "editorAssignee", editorAssignee, beforeValue(before, "editorAssignee"));
        if (!unsupportedEditors.isEmpty()) {
            return ApiResult.fail("A0400", "不支持的剪辑选项：" + String.join("、", unsupportedEditors));
        }
        // 中文备注：投放测试 / 投手与剪辑同为多选；须按「、」拆开校验，不能把整串当单选项。
        String deliveryTest = normalizeMultiOptionValue(
                resolveOpsTextField(body.deliveryTest(), before, "deliveryTest"));
        List<String> unsupportedDeliveryTests = unsupportedMultiOptionValues(
                "deliveryTest", deliveryTest, beforeValue(before, "deliveryTest"));
        if (!unsupportedDeliveryTests.isEmpty()) {
            return ApiResult.fail("A0400", "不支持的投放测试选项：" + String.join("、", unsupportedDeliveryTests));
        }
        String finalRating = resolveOpsTextField(body.finalRating(), before, "finalRating");
        if (!isAllowedPersistedOption("finalRating", finalRating, beforeValue(before, "finalRating"))) {
            return ApiResult.fail("A0400", "不支持的最终评级选项：" + finalRating);
        }
        String deliveryAssignee = normalizeMultiOptionValue(
                resolveOpsTextField(body.deliveryAssignee(), before, "deliveryAssignee"));
        List<String> unsupportedDeliveryAssignees = unsupportedMultiOptionValues(
                "deliveryAssignee", deliveryAssignee, beforeValue(before, "deliveryAssignee"));
        if (!unsupportedDeliveryAssignees.isEmpty()) {
            return ApiResult.fail("A0400", "不支持的投手选项：" + String.join("、", unsupportedDeliveryAssignees));
        }
        String copyrightOwner = resolveOpsCopyrightForSave(body.copyrightOwner(), before, seriesBefore);
        // 中文备注：版权允许清空；已填值即便选项已删也保留；全新非法值仍纠正为默认「番茄」。
        if (!copyrightOwner.isBlank()
                && !isAllowedPersistedOption("copyrightOwner", copyrightOwner, beforeValue(before, "copyrightOwner"))) {
            copyrightOwner = DEFAULT_COPYRIGHT_OWNER;
        }
        String materialCount = resolveOpsTextField(body.materialCount(), before, "materialCount");
        String cloudDiskUrl = resolveOpsTextField(body.cloudDiskUrl(), before, "cloudDiskUrl");
        String priorityLevel = resolveOpsTextField(body.priorityLevel(), before, "priorityLevel");
        String bidderRating = resolveOpsTextField(body.bidderRating(), before, "bidderRating");

        Map<String, String> seriesPromotion = loadSeriesPromotionBaseline(type, saveBookId);
        Map<String, String> promotionPatch = extractPromotionPatch(body);
        String existingPromotionOverride = before == null ? null : (String) before.get("promotionOverrideJson");
        String promotionOverrideJson = mergePromotionOverrideJson(
                existingPromotionOverride, promotionPatch, seriesPromotion);
        Map<String, String> userPromotion = new LinkedHashMap<>(seriesPromotion);
        userPromotion.putAll(parsePromotionOverridePresent(promotionOverrideJson));

        // 中文备注：页面已去掉备注列，保存时保留库里原有备注，避免被空值覆盖。
        String preservedRemark = before == null ? "" : trimToEmpty((String) before.get("remark"));
        // 中文备注：customFields 仅合并请求里出现的键；前端应只传本次改动的自定义列。
        Map<String, String> customFieldsPatch = body.customFields() == null
                ? null : new LinkedHashMap<>(body.customFields());
        String customFieldsJson = mergeCustomFieldsJson(
                before == null ? null : (String) before.get("customFieldsJson"),
                customFieldsPatch != null ? customFieldsPatch : body.customFields());
        OpsSaveFields fields = new OpsSaveFields(
                type,
                saveBookId,
                seriesName,
                dramaTypeLabel,
                copyrightOwner,
                screeningRating,
                finalRating,
                editorAssignee,
                materialCount,
                deliveryTest,
                cloudDiskUrl,
                priorityLevel,
                bidderRating,
                deliveryAssignee,
                preservedRemark,
                promotionOverrideJson,
                customFieldsJson
        );

        int newVersion = dramaOperationRepository.saveWithVersion(
                fields,
                body.version() == null ? 0 : body.version(),
                operator
        );
        if (newVersion < 0) {
            return ApiResult.fail("A0409", "这条记录刚被别人修改过，请刷新后再保存");
        }
        // 中文备注：请求里缺省/null 的主表字段视为未改，沿用库里原值，避免只改运营列时把创建时间/版权等清空。
        Map<String, String> effectiveMaster = resolveEffectiveSeriesMaster(
                seriesBefore, body, seriesName);
        boolean masterChanged = isSeriesMasterChanged(seriesBefore, effectiveMaster);
        int seriesUpdated = updateSeriesMasterFields(type, saveBookId, effectiveMaster);
        if (seriesUpdated == -1) {
            // 中文备注：抛错触发 @Transactional 回滚运营表写入，避免只改一半。
            throw new IllegalStateException("同步采集主表失败，请稍后重试");
        }
        if (masterChanged && seriesUpdated == 0 && SERIES_TABLES.containsKey(type)) {
            throw new IllegalStateException("采集主表未找到该剧（book_id=" + saveBookId + "），时间/集数等无法落库");
        }
        // 中文备注：收集本次写入的日志 id，前端可做飞书式全局撤回栈（跨表跳回）。
        List<Map<String, Object>> changeLogs = new ArrayList<>();
        if (seriesUpdated > 0) {
            recordSeriesMasterDiffLogs(type, saveBookId, operator,
                    seriesBefore == null ? Map.of() : seriesBefore, effectiveMaster, changeLogs);
        }
        recordDiffLogs(type, fields.bookId(), operator, before, fields, userPromotion, changeLogs);
        recordCustomFieldDiffLogs(type, fields.bookId(), operator,
                before == null ? null : (String) before.get("customFieldsJson"),
                customFieldsJson, changeLogs);
        // 中文备注：W1 系统表 op 帧——运营行保存按「字段键级」diff 出 before/after,写 ops_sheet_op_log 并广播
        // (与 recordDiffLogs 同源取值逻辑,labels 换键名,客户端 row[fld] 直接应用)。订阅者毫秒级可见,
        // 未订阅表走 delta 水印权威兜底;失败静默不影响保存主流程。
        try {
            Map<String, Object> beforeOps = new LinkedHashMap<>();
            Map<String, Object> afterOps = new LinkedHashMap<>();
            beforeOps.put("seriesName", beforeValue(before, "seriesName"));
            afterOps.put("seriesName", fields.seriesName());
            beforeOps.put("dramaTypeLabel", beforeValue(before, "dramaTypeLabel"));
            afterOps.put("dramaTypeLabel", fields.dramaTypeLabel());
            beforeOps.put("copyrightOwner", beforeValue(before, "copyrightOwner"));
            afterOps.put("copyrightOwner", fields.copyrightOwner());
            beforeOps.put("screeningRating", beforeValue(before, "screeningRating"));
            afterOps.put("screeningRating", fields.screeningRating());
            beforeOps.put("finalRating", beforeValue(before, "finalRating"));
            afterOps.put("finalRating", fields.finalRating());
            beforeOps.put("editorAssignee", beforeValue(before, "editorAssignee"));
            afterOps.put("editorAssignee", fields.editorAssignee());
            beforeOps.put("materialCount", beforeValue(before, "materialCount"));
            afterOps.put("materialCount", fields.materialCount());
            beforeOps.put("deliveryTest", beforeValue(before, "deliveryTest"));
            afterOps.put("deliveryTest", fields.deliveryTest());
            beforeOps.put("cloudDiskUrl", beforeValue(before, "cloudDiskUrl"));
            afterOps.put("cloudDiskUrl", fields.cloudDiskUrl());
            beforeOps.put("priorityLevel", beforeValue(before, "priorityLevel"));
            afterOps.put("priorityLevel", fields.priorityLevel());
            beforeOps.put("bidderRating", beforeValue(before, "bidderRating"));
            afterOps.put("bidderRating", fields.bidderRating());
            beforeOps.put("deliveryAssignee", beforeValue(before, "deliveryAssignee"));
            afterOps.put("deliveryAssignee", fields.deliveryAssignee());
            Map<String, String> beforeOverride = parsePromotionOverridePresent(
                    before == null ? null : (String) before.get("promotionOverrideJson"));
            Map<String, String> seriesBaselineForOps = loadSeriesPromotionBaseline(type, fields.bookId());
            for (String key : PROMOTION_FIELD_KEYS) {
                beforeOps.put(key, beforeOverride.containsKey(key)
                        ? beforeOverride.get(key)
                        : trimToEmpty(seriesBaselineForOps.get(key)));
                afterOps.put(key, trimToEmpty(userPromotion.get(key)));
            }
            Map<String, String> beforeCustom = parseCustomFieldsMap(before == null ? null : (String) before.get("customFieldsJson"));
            Map<String, String> afterCustom = parseCustomFieldsMap(customFieldsJson);
            for (Map.Entry<String, String> e : afterCustom.entrySet()) {
                beforeOps.put(e.getKey(), beforeCustom.get(e.getKey()));
                afterOps.put(e.getKey(), e.getValue());
            }
            String broadcastTableId = trimToEmpty(body.tableId());
            if (broadcastTableId.isBlank()) broadcastTableId = builtinTableForType(type);
            if (broadcastTableId != null && !broadcastTableId.isBlank()) {
                var opsDiff = sheetOpLogService.diffAndRecord(broadcastTableId, fields.bookId(), operator,
                        objectMapper.writeValueAsString(beforeOps), objectMapper.writeValueAsString(afterOps), newVersion);
                if (opsDiff.changed() > 0) {
                    if (opsCollector != null) {
                        // 中文备注：批量模式——收集到统一帧,端点循环结束后一次广播。
                        Map<String, Object> collected = new LinkedHashMap<>();
                        collected.put("tableId", broadcastTableId);
                        collected.put("seq", opsDiff.seq());
                        collected.put("opsJson", opsDiff.opsJson());
                        opsCollector.add(collected);
                    } else {
                        sheetSyncPushService.broadcastOps(broadcastTableId, opsDiff.seq(), opsDiff.opsJson(), operator);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        notifyEditorMaterialCompleteIfNeeded(body == null ? null : body.tableId(), type, fields, before, operator);
        try {
            opsListNotifyService.notifyOpsSave(type, fields.bookId());
            opsDramaListService.clearFirstPageCache();
        } catch (Exception ignored) {
        }
        maybeScheduleLeaderGroupAssign(
                String.valueOf(effectiveMaster.getOrDefault("estimatePublishTime", "")),
                String.valueOf(effectiveMaster.getOrDefault("publishTime", "")),
                parseFlexibleInt(String.valueOf(effectiveMaster.getOrDefault("episodeAmount", ""))));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", fields.bookId());
        data.put("version", newVersion);
        data.put("updatedBy", operator);
        data.put("savedAt", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        data.put("seriesSynced", seriesUpdated > 0);
        data.put("seriesUpdated", seriesUpdated);
        data.put("changeLogs", changeLogs);
        // 中文备注：回传主表字段，前端确认落库后的展示值。
        data.put("seriesName", seriesName);
        data.put("estimatePublishTime", effectiveMaster.getOrDefault("estimatePublishTime", ""));
        data.put("publishTime", effectiveMaster.getOrDefault("publishTime", ""));
        data.put("changduCreateTime", effectiveMaster.getOrDefault("changduCreateTime", ""));
        data.put("episodeAmount", effectiveMaster.getOrDefault("episodeAmount", ""));
        data.put("categoryText", effectiveMaster.getOrDefault("categoryText", ""));
        data.put("douyinWorkId", effectiveMaster.getOrDefault("douyinWorkId", ""));
        data.put("publishAwemeUser", effectiveMaster.getOrDefault("publishAwemeUser", ""));
        // 中文备注：回传推广展示值（采集基线 + 本次覆盖），避免前端被增量/缓存冲回旧链接。
        Map<String, String> effectivePromotion = new LinkedHashMap<>(seriesPromotion);
        effectivePromotion.putAll(parsePromotionOverridePresent(promotionOverrideJson));
        for (String key : PROMOTION_FIELD_KEYS) {
            data.put(key, trimToEmpty(effectivePromotion.get(key)));
        }
        Map<String, Object> formulaRow = new LinkedHashMap<>(seriesBefore == null ? Map.of() : seriesBefore);
        if (before != null) formulaRow.putAll(before);
        formulaRow.putAll(effectiveMaster);
        formulaRow.putAll(effectivePromotion);
        formulaRow.put("dramaType", type);
        formulaRow.put("bookId", fields.bookId());
        formulaRow.put("seriesName", seriesName);
        formulaRow.put("dramaTypeLabel", dramaTypeLabel);
        formulaRow.put("copyrightOwner", copyrightOwner);
        formulaRow.put("screeningRating", screeningRating);
        formulaRow.put("finalRating", finalRating);
        formulaRow.put("editorAssignee", editorAssignee);
        formulaRow.put("materialCount", materialCount);
        formulaRow.put("deliveryTest", deliveryTest);
        formulaRow.put("cloudDiskUrl", cloudDiskUrl);
        formulaRow.put("priorityLevel", priorityLevel);
        formulaRow.put("bidderRating", bidderRating);
        formulaRow.put("deliveryAssignee", deliveryAssignee);
        applyCustomFields(formulaRow, customFieldsJson);
        opsFormulaEvalService.enrichFormulaValues(List.of(formulaRow));
        data.put("formulaValues", formulaRow.getOrDefault("formulaValues", Map.of()));
        data.putAll(dramaOperationRepository.findWorkspaceLastModify());
        return ApiResult.ok(data);
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
        // 中文备注：空白表审计 drama_type=sheet:{tableId}，勿 normalize 成 blank/all。
        String rawType = trimToEmpty(type);
        String dramaType = isSheetLogDramaType(rawType)
                ? rawType
                : (rawType.isBlank() ? "" : normalizeType(rawType));
        String targetBookId = trimToEmpty(bookId);
        if (targetBookId.isBlank()) {
            return ApiResult.fail("A0400", "缺少 bookId");
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        boolean sortAsc = "asc".equalsIgnoreCase(trimToEmpty(sort));
        long total = dramaOperationRepository.countLogs(dramaType, targetBookId);
        int totalPages = total == 0 ? 0 : (int) ((total + safePageSize - 1) / safePageSize);
        List<Map<String, Object>> items = dramaOperationRepository.listLogs(
                dramaType,
                targetBookId,
                safePage,
                safePageSize,
                sortAsc
        );
        Map<String, String> customTitleToKey = customFieldTitleToKey();
        String sheetTableId = isSheetLogDramaType(dramaType) ? tableIdFromSheetLogDramaType(dramaType) : "";
        Map<String, String> sheetTitleToKey = sheetTableId.isBlank()
                ? Map.of()
                : sheetFieldTitleToKey(sheetTableId);
        for (Map<String, Object> item : items) {
            annotateLogUndoFlags(item, me, customTitleToKey, sheetTitleToKey);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookId", targetBookId);
        data.put("type", dramaType);
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
        Map<String, Object> log = dramaOperationRepository.findLogById(logId);
        if (log == null) {
            return ApiResult.fail("A0404", "操作记录不存在");
        }
        return undoLogEntry(request, operator, log, false, null);
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
        List<Map<String, Object>> ordered = dramaOperationRepository.findLogsByIds(body.logIds());
        if (ordered.size() != body.logIds().size()) {
            return ApiResult.fail("A0404", "部分操作记录不存在");
        }
        ApiResult<Map<String, Object>> fast = tryFastBatchUndoOpsField(operator, ordered);
        if (fast != null) {
            return fast;
        }
        Map<String, Map<String, Map<String, Object>>> opsCache = buildOpsCacheForLogs(ordered);
        LinkedHashSet<String> notifyKeys = new LinkedHashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> log : ordered) {
            ApiResult<Map<String, Object>> result = undoLogEntry(request, operator, log, true, opsCache);
            if (!"00000".equals(result.code())) {
                org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return ApiResult.fail(result.code(), "批量撤回失败，已全部回滚：" + result.msg());
            }
            results.add(result.data());
            String dramaType = trimToEmpty((String) result.data().get("dramaType"));
            String bookId = trimToEmpty((String) result.data().get("bookId"));
            if (!dramaType.isBlank() && !bookId.isBlank()) {
                notifyKeys.add(dramaType + "\0" + bookId);
            }
        }
        flushUndoSideEffectsAfterCommit(notifyKeys);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", results.size());
        payload.put("items", results);
        payload.put("batchId", trimToEmpty(body.batchId()));
        payload.put("message", "已整体撤回 " + results.size() + " 项修改");
        return ApiResult.ok(payload);
    }

    private void flushUndoSideEffectsAfterCommit(Set<String> dramaBookKeys) {
        if (dramaBookKeys == null || dramaBookKeys.isEmpty()) {
            return;
        }
        Set<String> stableKeys = Set.copyOf(dramaBookKeys);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    flushUndoSideEffects(stableKeys);
                }
            });
            return;
        }
        flushUndoSideEffects(stableKeys);
    }

    private void flushUndoSideEffects(Set<String> dramaBookKeys) {
        if (dramaBookKeys == null || dramaBookKeys.isEmpty()) {
            return;
        }
        for (String key : dramaBookKeys) {
            int sep = key.indexOf('\0');
            if (sep <= 0) {
                continue;
            }
            try {
                opsListNotifyService.notifyOpsSave(key.substring(0, sep), key.substring(sep + 1));
            } catch (Exception ignored) {
            }
        }
        try {
            opsDramaListService.clearFirstPageCache();
        } catch (Exception ignored) {
        }
    }

    private Map<String, Map<String, Map<String, Object>>> buildOpsCacheForLogs(List<Map<String, Object>> logs) {
        Map<String, List<String>> bookIdsByType = new LinkedHashMap<>();
        for (Map<String, Object> log : logs) {
            String rawType = trimToEmpty((String) log.get("dramaType"));
            if (isSheetLogDramaType(rawType)) {
                continue;
            }
            String dramaType = normalizeType(rawType);
            if (dramaType.isBlank()) {
                dramaType = "drama";
            }
            String bookId = trimToEmpty((String) log.get("bookId"));
            if (bookId.isBlank()) {
                continue;
            }
            bookIdsByType.computeIfAbsent(dramaType, k -> new ArrayList<>()).add(bookId);
        }
        Map<String, Map<String, Map<String, Object>>> cache = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : bookIdsByType.entrySet()) {
            List<String> ids = entry.getValue().stream().filter(id -> !id.isBlank()).distinct().toList();
            Map<String, Map<String, Object>> byBook = new LinkedHashMap<>();
            for (Map<String, Object> row : dramaOperationRepository.listByTypeAndBookIds(entry.getKey(), ids)) {
                byBook.put(trimToEmpty((String) row.get("bookId")), row);
            }
            cache.put(entry.getKey(), byBook);
        }
        return cache;
    }

    /** @return 成功结果；不符合快速路径时返回 null（支持多列 OPS 字段同批撤回） */
    private ApiResult<Map<String, Object>> tryFastBatchUndoOpsField(String operator,
                                                                    List<Map<String, Object>> logs) {
        if (logs == null || logs.size() < 8) {
            return null;
        }
        String rawDramaType = trimToEmpty((String) logs.get(0).get("dramaType"));
        if (isSheetLogDramaType(rawDramaType)) {
            return null;
        }
        String dramaType = normalizeType(rawDramaType);
        if (dramaType.isBlank()) {
            dramaType = "drama";
        }
        LinkedHashMap<String, OpsUndoSupport.FieldTarget> fieldTargets = new LinkedHashMap<>();
        LinkedHashMap<String, String> fieldToDbColumn = new LinkedHashMap<>();
        for (Map<String, Object> log : logs) {
            String fieldName = trimToEmpty((String) log.get("fieldName"));
            if (fieldName.isBlank()) {
                return null;
            }
            if (!dramaType.equals(normalizeType(trimToEmpty((String) log.get("dramaType"))))) {
                return null;
            }
            String logOperator = trimToEmpty((String) log.get("operator"));
            OpsUndoSupport.FieldTarget target = OpsUndoSupport.resolveTarget(fieldName);
            if (!OpsUndoSupport.supportsFastBatchUndo(target)) {
                return null;
            }
            String dbColumn = OpsUndoSupport.opsFieldDbColumn(target.saveKey());
            if (dbColumn == null) {
                return null;
            }
            OpsUndoSupport.Eligibility eligibility = OpsUndoSupport.eligibility(
                    logOperator, operator, fieldName, true);
            if (!eligibility.canUndo()) {
                return ApiResult.fail("A0403", eligibility.blockReason().isBlank() ? "不可撤回" : eligibility.blockReason());
            }
            fieldTargets.putIfAbsent(fieldName, target);
            fieldToDbColumn.putIfAbsent(fieldName, dbColumn);
        }
        List<String> fieldNamesOrdered = new ArrayList<>(fieldTargets.keySet());
        fieldNamesOrdered.sort(String::compareTo);
        List<String> dbColumnsOrdered = fieldNamesOrdered.stream().map(fieldToDbColumn::get).toList();
        int colCount = fieldNamesOrdered.size();

        Map<String, Map<String, Map<String, Object>>> logsByBook = new LinkedHashMap<>();
        for (Map<String, Object> log : logs) {
            String bookId = trimToEmpty((String) log.get("bookId"));
            String fieldName = trimToEmpty((String) log.get("fieldName"));
            if (bookId.isBlank() || fieldName.isBlank()) {
                return null;
            }
            logsByBook.computeIfAbsent(bookId, k -> new LinkedHashMap<>()).put(fieldName, log);
        }

        List<String> bookIds = new ArrayList<>(logsByBook.keySet());
        Map<String, Map<String, Object>> opsByBook = new LinkedHashMap<>();
        for (Map<String, Object> row : dramaOperationRepository.listByTypeAndBookIds(dramaType, bookIds)) {
            opsByBook.put(trimToEmpty((String) row.get("bookId")), row);
        }

        List<DramaOperationRepository.OpsMultiColumnUndoRow> undoRows = new ArrayList<>();
        List<Map<String, String>> auditRows = new ArrayList<>();
        for (String bookId : bookIds) {
            Map<String, Map<String, Object>> perField = logsByBook.get(bookId);
            if (perField.size() != colCount) {
                return null;
            }
            Map<String, Object> ops = opsByBook.get(bookId);
            if (ops == null) {
                return ApiResult.fail("A0404", "记录不存在：" + bookId);
            }
            int version = ops.get("version") instanceof Number n ? n.intValue() : 0;
            List<DramaOperationRepository.OpsColumnPatch> patches = new ArrayList<>();
            for (String fieldName : fieldNamesOrdered) {
                Map<String, Object> log = perField.get(fieldName);
                if (log == null) {
                    return null;
                }
                OpsUndoSupport.FieldTarget target = fieldTargets.get(fieldName);
                String loggedOld = trimToEmpty((String) log.get("oldValue"));
                String loggedNew = trimToEmpty((String) log.get("newValue"));
                String current = readCurrentFieldValueFromOps(ops, target, null);
                if (!OpsUndoSupport.currentMatchesLoggedNew(current, loggedNew, target, false)) {
                    return ApiResult.fail("A0409",
                            "当前「" + fieldName + "」已被后续修改（现为 "
                                    + OpsUndoSupport.display(current) + "），无法安全撤回");
                }
                String restored = OpsUndoSupport.normalizeRestoreValue(target, loggedOld, false);
                patches.add(new DramaOperationRepository.OpsColumnPatch(
                        fieldToDbColumn.get(fieldName), loggedNew, restored));
                Map<String, String> audit = new LinkedHashMap<>();
                audit.put("dramaType", dramaType);
                audit.put("bookId", bookId);
                audit.put("operator", operator);
                audit.put("fieldName", OpsUndoSupport.undoLogFieldName(fieldName));
                audit.put("oldValue", loggedNew);
                audit.put("newValue", restored);
                auditRows.add(audit);
            }
            undoRows.add(new DramaOperationRepository.OpsMultiColumnUndoRow(bookId, version, patches));
        }
        try {
            if (colCount == 1) {
                List<DramaOperationRepository.OpsFieldUndoRow> singleColRows = new ArrayList<>();
                for (DramaOperationRepository.OpsMultiColumnUndoRow row : undoRows) {
                    DramaOperationRepository.OpsColumnPatch patch = row.patches().get(0);
                    singleColRows.add(new DramaOperationRepository.OpsFieldUndoRow(
                            row.bookId(), row.expectedVersion(), patch.expectedValue(), patch.restoreValue()));
                }
                dramaOperationRepository.batchRestoreOpsFieldColumn(dramaType, dbColumnsOrdered.get(0), operator, singleColRows);
            } else {
                dramaOperationRepository.batchRestoreOpsMultiColumn(dramaType, operator, dbColumnsOrdered, undoRows);
            }
            dramaOperationRepository.insertLogsBatch(auditRows);
        } catch (IllegalStateException | IllegalArgumentException e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail("A0409", e.getMessage());
        }
        LinkedHashSet<String> notifyKeys = new LinkedHashSet<>();
        for (String bookId : bookIds) {
            notifyKeys.add(dramaType + "\0" + bookId);
        }
        flushUndoSideEffectsAfterCommit(notifyKeys);
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("fieldName", fieldNamesOrdered.size() == 1 ? fieldNamesOrdered.get(0) : "多字段");
        sample.put("dramaType", dramaType);
        sample.put("count", logs.size());
        sample.put("listVersion", opsDramaListService.currentListVersion());
        String fieldHint = fieldNamesOrdered.size() == 1
                ? "「" + fieldNamesOrdered.get(0) + "」"
                : fieldNamesOrdered.size() + " 个字段";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", logs.size());
        payload.put("items", List.of(sample));
        payload.put("message", "已整体撤回 " + logs.size() + " 项" + fieldHint + "修改");
        return ApiResult.ok(payload);
    }

    private ApiResult<Map<String, Object>> undoLogEntry(HttpServletRequest request,
                                                        String operator,
                                                        Map<String, Object> log,
                                                        boolean deferSideEffects,
                                                        Map<String, Map<String, Map<String, Object>>> opsCache) {
        long logId = log.get("id") instanceof Number n ? n.longValue() : 0L;
        String fieldName = trimToEmpty((String) log.get("fieldName"));
        String logOperator = trimToEmpty((String) log.get("operator"));
        String bookId = trimToEmpty((String) log.get("bookId"));
        String rawDramaType = trimToEmpty((String) log.get("dramaType"));
        String loggedOld = trimToEmpty((String) log.get("oldValue"));
        String loggedNew = trimToEmpty((String) log.get("newValue"));
        if (isSheetLogDramaType(rawDramaType)) {
            return undoSheetCellLog(request, operator, logId, rawDramaType, bookId, fieldName,
                    logOperator, loggedOld, loggedNew);
        }
        String dramaType = normalizeType(rawDramaType);
        if (dramaType.isBlank()) {
            dramaType = "drama";
        }
        String customKey = resolveCustomFieldKeyByTitle(fieldName);
        OpsUndoSupport.FieldTarget target = OpsUndoSupport.resolveTarget(fieldName);
        OpsUndoSupport.Eligibility eligibility = OpsUndoSupport.eligibility(
                logOperator, operator, fieldName, customKey != null || target.kind() != OpsUndoSupport.Kind.CUSTOM_FIELD);
        if (!eligibility.canUndo()) {
            return ApiResult.fail("A0403", eligibility.blockReason().isBlank() ? "不可撤回" : eligibility.blockReason());
        }

        Map<String, Object> contrast = OpsUndoSupport.contrast(fieldName, loggedNew, loggedOld);
        int restoredVersion = -1;
        String restoredValue = loggedOld;
        Map<String, Object> prefetchedOps = null;
        if (opsCache != null) {
            Map<String, Map<String, Object>> byBook = opsCache.get(dramaType);
            if (byBook != null) {
                prefetchedOps = byBook.get(bookId);
            }
        }
        try {
            switch (target.kind()) {
                case SOFT_HIDE -> {
                    if (!hiddenRowRepository.isHidden(dramaType, bookId)) {
                        return ApiResult.fail("A0409", "当前未处于隐藏状态，无法撤回该删除");
                    }
                    if (!hiddenRowRepository.unhide(dramaType, bookId)) {
                        return ApiResult.fail("A0500", "撤回失败");
                    }
                    dramaOperationRepository.insertLog(dramaType, bookId, operator,
                            OpsUndoSupport.undoLogFieldName(fieldName), "软隐藏", "恢复显示");
                }
                case SOFT_UNHIDE, CREATE_ROW -> {
                    if (hiddenRowRepository.isHidden(dramaType, bookId)) {
                        return ApiResult.fail("A0409", "当前已隐藏，无需再次撤回");
                    }
                    if (!hiddenRowRepository.hide(dramaType, bookId, operator, "")) {
                        return ApiResult.fail("A0500", "撤回失败");
                    }
                    dramaOperationRepository.insertLog(dramaType, bookId, operator,
                            OpsUndoSupport.undoLogFieldName(fieldName), "显示中", "软隐藏");
                }
                case OPS_FIELD, PROMO_FIELD, SERIES_FIELD, CUSTOM_FIELD -> {
                    String current = prefetchedOps != null && target.kind() == OpsUndoSupport.Kind.OPS_FIELD
                            ? readCurrentFieldValueFromOps(prefetchedOps, target, customKey)
                            : readCurrentFieldValue(dramaType, bookId, target, customKey);
                    boolean checkboxField = customKey != null && isCheckboxCustomField(customKey);
                    if (!OpsUndoSupport.currentMatchesLoggedNew(current, loggedNew, target, checkboxField)) {
                        return ApiResult.fail("A0409",
                                "当前「" + fieldName + "」已被后续修改（现为 "
                                        + OpsUndoSupport.display(current) + "），无法安全撤回");
                    }
                    restoredValue = OpsUndoSupport.normalizeRestoreValue(target, loggedOld, checkboxField);
                    restoredVersion = applyFieldUndo(dramaType, bookId, operator, target, customKey, restoredValue, prefetchedOps);
                    dramaOperationRepository.insertLog(dramaType, bookId, operator,
                            OpsUndoSupport.undoLogFieldName(fieldName), loggedNew, restoredValue);
                }
                default -> {
                    return ApiResult.fail("A0400", "该类型操作不支持撤回");
                }
            }
        } catch (IllegalStateException e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            return ApiResult.fail("A0404", e.getMessage());
        } catch (Exception e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
            System.out.println("协作工作台撤回失败：" + e.getMessage());
            return ApiResult.fail("A0500", "撤回写入失败，请刷新后重试");
        }

        if (!deferSideEffects) {
            flushUndoSideEffectsAfterCommit(Set.of(dramaType + "\0" + bookId));
        }

        if (restoredVersion < 0) {
            Map<String, Object> opsAfter = prefetchedOps != null
                    ? prefetchedOps
                    : dramaOperationRepository.find(dramaType, bookId);
            if (opsAfter != null && opsAfter.get("version") instanceof Number n) {
                restoredVersion = n.intValue();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logId", logId);
        data.put("bookId", bookId);
        data.put("dramaType", dramaType);
        data.put("fieldName", fieldName);
        data.put("operator", operator);
        data.put("contrast", contrast);
        data.put("restoredValue", restoredValue);
        data.put("previousValue", loggedNew);
        data.put("version", restoredVersion > 0 ? restoredVersion : null);
        data.put("listVersion", opsDramaListService.currentListVersion());
        data.put("message", "已撤回：将「" + fieldName + "」从 "
                + OpsUndoSupport.display(loggedNew) + " 恢复为 " + OpsUndoSupport.display(restoredValue));
        return ApiResult.ok(data);
    }

    private String readCurrentFieldValueFromOps(Map<String, Object> ops,
                                                OpsUndoSupport.FieldTarget target,
                                                String customKey) {
        if (ops == null || target == null) {
            return "";
        }
        return switch (target.kind()) {
            case OPS_FIELD -> {
                if ("seriesName".equals(target.saveKey())) {
                    yield trimToEmpty((String) ops.get("seriesName"));
                }
                yield beforeValue(ops, target.saveKey());
            }
            case PROMO_FIELD -> {
                Map<String, String> override = parsePromotionOverridePresent((String) ops.get("promotionOverrideJson"));
                yield trimToEmpty(override.getOrDefault(target.saveKey(), ""));
            }
            case SERIES_FIELD -> "";
            case CUSTOM_FIELD -> {
                if (customKey == null || customKey.isBlank()) {
                    yield "";
                }
                Map<String, String> customs = parseCustomFieldsMap((String) ops.get("customFieldsJson"));
                yield trimToEmpty(customs.getOrDefault(customKey, ""));
            }
            default -> "";
        };
    }

    public record UndoLogRequest(Long logId) {
    }

    public record SheetRowManualLogRequest(String fieldName, String oldValue, String newValue) {
    }

    public record UndoBatchRequest(String batchId, List<Long> logIds) {
    }

    private String sheetLogDramaType(String tableId) {
        return "sheet:" + trimToEmpty(tableId);
    }

    private boolean isSheetLogDramaType(String dramaType) {
        return trimToEmpty(dramaType).startsWith("sheet:");
    }

    private String tableIdFromSheetLogDramaType(String dramaType) {
        String raw = trimToEmpty(dramaType);
        return raw.startsWith("sheet:") ? raw.substring("sheet:".length()) : "";
    }

    /** 中文备注：撤回空白表单元格；字段标题反查 fieldKey，当前值须仍等于日志 newValue。 */
    private ApiResult<Map<String, Object>> undoSheetCellLog(HttpServletRequest request,
                                                            String operator,
                                                            long logId,
                                                            String sheetDramaType,
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
        String tableId = tableIdFromSheetLogDramaType(sheetDramaType);
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
        dramaOperationRepository.insertLog(sheetDramaType, rowId, operator,
                OpsUndoSupport.undoLogFieldName(fieldName), loggedNew, restoredValue);
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
        String logDramaType = trimToEmpty((String) item.get("dramaType"));
        OpsUndoSupport.FieldTarget target = OpsUndoSupport.resolveTarget(fieldName);
        boolean sheetLog = isSheetLogDramaType(logDramaType);
        boolean sheetResolved = sheetLog
                && sheetTitleToKey != null
                && (sheetTitleToKey.containsKey(fieldName) || resolveSheetFieldKeyByTitle(
                        tableIdFromSheetLogDramaType(logDramaType), fieldName) != null);
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

    private boolean isCheckboxCustomField(String customKey) {
        if (customKey == null || customKey.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> cfg = fieldConfigRepository.find(customKey);
            return cfg != null
                    && "checkbox".equals(trimToEmpty(String.valueOf(cfg.getOrDefault("fieldType", ""))));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readCurrentFieldValue(String dramaType,
                                         String bookId,
                                         OpsUndoSupport.FieldTarget target,
                                         String customKey) {
        if (target == null) {
            return "";
        }
        return switch (target.kind()) {
            case OPS_FIELD -> {
                Map<String, Object> ops = dramaOperationRepository.find(dramaType, bookId);
                if (ops == null) {
                    yield "";
                }
                if ("seriesName".equals(target.saveKey())) {
                    yield trimToEmpty((String) ops.get("seriesName"));
                }
                yield beforeValue(ops, target.saveKey());
            }
            case PROMO_FIELD -> {
                Map<String, String> series = loadSeriesPromotionBaseline(dramaType, bookId);
                Map<String, Object> ops = dramaOperationRepository.find(dramaType, bookId);
                Map<String, String> override = parsePromotionOverridePresent(
                        ops == null ? null : (String) ops.get("promotionOverrideJson"));
                if (override.containsKey(target.saveKey())) {
                    yield trimToEmpty(override.get(target.saveKey()));
                }
                yield trimToEmpty(series.get(target.saveKey()));
            }
            case SERIES_FIELD -> {
                Map<String, Object> series = loadSeriesMasterSnapshot(dramaType, bookId);
                if (series == null) {
                    yield "";
                }
                yield trimToEmpty(String.valueOf(series.getOrDefault(target.saveKey(), "")));
            }
            case CUSTOM_FIELD -> {
                if (customKey == null || customKey.isBlank()) {
                    yield "";
                }
                Map<String, Object> ops = dramaOperationRepository.find(dramaType, bookId);
                Map<String, String> customs = parseCustomFieldsMap(
                        ops == null ? null : (String) ops.get("customFieldsJson"));
                yield trimToEmpty(customs.getOrDefault(customKey, ""));
            }
            default -> "";
        };
    }

    /** @return 撤回后的运营行 version；无运营行时返回 -1 */
    private int applyFieldUndo(String dramaType,
                               String bookId,
                               String operator,
                               OpsUndoSupport.FieldTarget target,
                               String customKey,
                               String restoreValue) {
        return applyFieldUndo(dramaType, bookId, operator, target, customKey, restoreValue, null);
    }

    private int applyFieldUndo(String dramaType,
                               String bookId,
                               String operator,
                               OpsUndoSupport.FieldTarget target,
                               String customKey,
                               String restoreValue,
                               Map<String, Object> prefetchedOps) {
        Map<String, Object> ops = prefetchedOps != null ? prefetchedOps : dramaOperationRepository.find(dramaType, bookId);
        boolean needSeries = target.kind() == OpsUndoSupport.Kind.SERIES_FIELD
                || "seriesName".equals(target.saveKey())
                || ("copyrightOwner".equals(target.saveKey()) && target.kind() == OpsUndoSupport.Kind.OPS_FIELD);
        Map<String, Object> seriesBefore = needSeries ? loadSeriesMasterSnapshot(dramaType, bookId) : null;
        String restore = OpsUndoSupport.normalizeRestoreValue(target, restoreValue);

        String seriesName = ops == null ? "" : beforeValue(ops, "seriesName");
        if (seriesName.isBlank() && seriesBefore != null) {
            seriesName = trimToEmpty(String.valueOf(seriesBefore.getOrDefault("seriesName", "")));
        }
        if ("seriesName".equals(target.saveKey())) {
            seriesName = restore;
        }
        if (seriesName.isBlank()) {
            throw new IllegalStateException("缺少剧名，无法撤回");
        }

        String dramaTypeLabel = ops == null ? "" : beforeValue(ops, "dramaTypeLabel");
        String copyrightOwner = ops == null ? "" : beforeValue(ops, "copyrightOwner");
        String screeningRating = ops == null ? "" : beforeValue(ops, "screeningRating");
        String finalRating = ops == null ? "" : beforeValue(ops, "finalRating");
        String editorAssignee = ops == null ? "" : beforeValue(ops, "editorAssignee");
        String materialCount = ops == null ? "" : beforeValue(ops, "materialCount");
        String deliveryTest = ops == null ? "" : beforeValue(ops, "deliveryTest");
        String cloudDiskUrl = ops == null ? "" : beforeValue(ops, "cloudDiskUrl");
        String priorityLevel = ops == null ? "" : beforeValue(ops, "priorityLevel");
        String bidderRating = ops == null ? "" : beforeValue(ops, "bidderRating");
        String deliveryAssignee = ops == null ? "" : beforeValue(ops, "deliveryAssignee");
        String remark = ops == null ? "" : beforeValue(ops, "remark");
        String promotionOverrideJson = ops == null ? null : (String) ops.get("promotionOverrideJson");
        String customFieldsJson = ops == null ? null : (String) ops.get("customFieldsJson");
        int version = 0;
        if (ops != null && ops.get("version") instanceof Number n) {
            version = n.intValue();
        }

        if (target.kind() == OpsUndoSupport.Kind.OPS_FIELD) {
            switch (target.saveKey()) {
                case "dramaTypeLabel" -> dramaTypeLabel = restore;
                case "copyrightOwner" -> copyrightOwner = restore;
                case "screeningRating" -> screeningRating = restore;
                case "finalRating" -> finalRating = restore;
                case "editorAssignee" -> editorAssignee = restore;
                case "materialCount" -> materialCount = restore;
                case "deliveryTest" -> deliveryTest = restore;
                case "cloudDiskUrl" -> cloudDiskUrl = restore;
                case "priorityLevel" -> priorityLevel = restore;
                case "bidderRating" -> bidderRating = restore;
                case "deliveryAssignee" -> deliveryAssignee = restore;
                default -> {
                }
            }
        } else if (target.kind() == OpsUndoSupport.Kind.PROMO_FIELD) {
            Map<String, String> seriesPromo = loadSeriesPromotionBaseline(dramaType, bookId);
            Map<String, String> patch = new LinkedHashMap<>();
            patch.put(target.saveKey(), restore);
            promotionOverrideJson = mergePromotionOverrideJson(promotionOverrideJson, patch, seriesPromo);
        } else if (target.kind() == OpsUndoSupport.Kind.CUSTOM_FIELD) {
            if (customKey == null || customKey.isBlank()) {
                throw new IllegalStateException("无法定位自定义字段");
            }
            Map<String, String> patch = new LinkedHashMap<>();
            patch.put(customKey, restore);
            customFieldsJson = mergeCustomFieldsJson(customFieldsJson, patch);
        }

        OpsSaveFields fields = new OpsSaveFields(
                dramaType, bookId, seriesName, dramaTypeLabel, copyrightOwner,
                screeningRating, finalRating, editorAssignee, materialCount, deliveryTest,
                cloudDiskUrl, priorityLevel, bidderRating, deliveryAssignee, remark,
                promotionOverrideJson, customFieldsJson
        );
        int newVersion = dramaOperationRepository.saveWithVersion(fields, version, operator);
        if (newVersion < 0) {
            throw new IllegalStateException("这条记录刚被别人修改过，请刷新后再撤回");
        }

        boolean touchSeries = target.kind() == OpsUndoSupport.Kind.SERIES_FIELD
                || "seriesName".equals(target.saveKey())
                || ("copyrightOwner".equals(target.saveKey()) && target.kind() == OpsUndoSupport.Kind.OPS_FIELD);
        if (touchSeries) {
            Map<String, String> effective = new LinkedHashMap<>();
            effective.put("seriesName", seriesName);
            effective.put("estimatePublishTime", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("estimatePublishTime", ""))));
            effective.put("publishTime", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("publishTime", ""))));
            effective.put("changduCreateTime", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("changduCreateTime", ""))));
            effective.put("episodeAmount", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("episodeAmount", ""))));
            effective.put("categoryText", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("categoryText", ""))));
            effective.put("douyinWorkId", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("douyinWorkId", ""))));
            effective.put("publishAwemeUser", seriesBefore == null ? ""
                    : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("publishAwemeUser", ""))));
            effective.put("copyrightOwner", copyrightOwner);
            if (target.kind() == OpsUndoSupport.Kind.SERIES_FIELD) {
                effective.put(target.saveKey(), restore);
            }
            int seriesUpdated = updateSeriesMasterFields(dramaType, bookId, effective);
            if (seriesUpdated == -1) {
                throw new IllegalStateException("同步采集主表失败，请稍后重试");
            }
            if (seriesUpdated == 0 && SERIES_TABLES.containsKey(trimToEmpty(dramaType))) {
                throw new IllegalStateException("采集主表未找到该剧，无法撤回主表字段");
            }
        }
        return newVersion;
    }

    /**
     * 夜间提醒 / 新剧提醒查询：推飞书机器人并由网页查看；不写多维表。
     * 中文备注：默认全部；筛选项兼容线上历史 type 别名，避免「线上不通」。
     */
    @GetMapping("/reminders")
    public ApiResult<Map<String, Object>> reminders(HttpServletRequest request,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false, defaultValue = "1") int page,
                                                    @RequestParam(required = false, defaultValue = "20") int pageSize) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (!opsTenantService.remindersEnabled(opsTenantService.resolveMode(request))) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "");
            data.put("page", Math.max(1, page));
            data.put("pageSize", Math.min(100, Math.max(1, pageSize)));
            data.put("total", 0L);
            data.put("totalPages", 0);
            data.put("hasMore", false);
            data.put("count", 0);
            data.put("items", List.of());
            data.put("types", List.of());
            data.put("pushToFeishu", false);
            data.put("remindersEnabled", false);
            data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            return ApiResult.ok(data);
        }
        String filterKey = normalizeReminderFilter(type);
        List<String> typeCodes = DramaReminderFeedRepository.resolveTypeCodes(filterKey);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        long total = reminderFeedRepository.count(typeCodes);
        int totalPages = total == 0 ? 0 : (int) ((total + safePageSize - 1) / safePageSize);
        List<Map<String, Object>> items = reminderFeedRepository.list(typeCodes, safePage, safePageSize);
        for (Map<String, Object> item : items) {
            String code = trimToEmpty((String) item.get("reminderType"));
            item.put("reminderTypeLabel", reminderTypeLabel(code));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", filterKey);
        data.put("page", safePage);
        data.put("pageSize", safePageSize);
        data.put("total", total);
        data.put("totalPages", totalPages);
        data.put("hasMore", safePage < totalPages);
        data.put("count", items.size());
        data.put("items", items);
        data.put("types", List.of(
                Map.of("value", "", "label", "全部"),
                Map.of("value", "new", "label", "新剧提醒"),
                Map.of("value", "evening", "label", "夜间提醒"),
                Map.of("value", "rank", "label", "剧排名榜单"),
                Map.of("value", "focus", "label", "重点剧提醒")
        ));
        data.put("pushToFeishu", quickPublishPushEnabled);
        data.put("remindersEnabled", true);
        data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        return ApiResult.ok(data);
    }

    private Map<String, Object> emptyReminderSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("unread", 0);
        summary.put("evening", 0);
        summary.put("newDrama", 0);
        summary.put("rank", 0);
        summary.put("focus", 0);
        summary.put("latest", null);
        summary.put("remindersEnabled", false);
        return summary;
    }

    /**
     * 漫剧小时榜历史：按小时可查，有迹可循。
     */
    @GetMapping("/edited-comic-rank/history")
    public ApiResult<Map<String, Object>> editedComicRankHistory(HttpServletRequest request,
                                                                 @RequestParam(required = false, defaultValue = "1") int page,
                                                                 @RequestParam(required = false, defaultValue = "24") int pageSize) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        long total = editedComicRankRepository.countRuns();
        int totalPages = total == 0 ? 0 : (int) ((total + safePageSize - 1) / safePageSize);
        List<Map<String, Object>> items = editedComicRankRepository.listHistory(safePage, safePageSize);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", safePage);
        data.put("pageSize", safePageSize);
        data.put("total", total);
        data.put("totalPages", totalPages);
        data.put("items", items);
        return ApiResult.ok(data);
    }

    /**
     * 漫剧小时榜详情：榜单行 + 图片地址。
     */
    @GetMapping("/edited-comic-rank/detail")
    public ApiResult<Map<String, Object>> editedComicRankDetail(HttpServletRequest request,
                                                                @RequestParam String day,
                                                                @RequestParam int hour) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        LocalDate rankDay;
        try {
            rankDay = LocalDate.parse(day.trim());
        } catch (Exception e) {
            return ApiResult.fail("A0400", "day 格式错误，请用 yyyy-MM-dd");
        }
        if (hour < 0 || hour > 23) {
            return ApiResult.fail("A0400", "hour 需在 0～23");
        }
        return ApiResult.ok(editedComicRankRepository.detail(rankDay, hour));
    }

    /**
     * 漫剧小时榜图片：读 play-adx 生成的 png（同机目录或 run 表绝对路径）。
     */
    @GetMapping("/edited-comic-rank/image")
    public ResponseEntity<byte[]> editedComicRankImage(HttpServletRequest request,
                                                       @RequestParam String day,
                                                       @RequestParam int hour) {
        if (!accessAllowed(request)) {
            return ResponseEntity.status(401).build();
        }
        LocalDate rankDay;
        try {
            rankDay = LocalDate.parse(day.trim());
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (hour < 0 || hour > 23) {
            return ResponseEntity.badRequest().build();
        }
        Path image = editedComicRankRepository.resolveImagePath(rankDay, hour);
        if (image == null || !Files.isRegularFile(image)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(image);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .contentType(MediaType.IMAGE_PNG)
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 提醒未读汇总：按 since 之后统计晚间/新剧条数，供工作台角标轮询。
     */
    @GetMapping("/reminders/summary")
    public ApiResult<Map<String, Object>> reminderSummary(HttpServletRequest request,
                                                          @RequestParam(required = false) String since) {
        if (!accessAllowed(request)) {
            return ApiResult.fail("A0230", "未登录或登录已过期，请重新登录");
        }
        if (!opsTenantService.remindersEnabled(opsTenantService.resolveMode(request))) {
            Map<String, Object> summary = emptyReminderSummary();
            summary.put("since", trimToEmpty(since));
            summary.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
            return ApiResult.ok(summary);
        }
        Timestamp sinceTs = null;
        String sinceText = trimToEmpty(since);
        if (!sinceText.isBlank()) {
            try {
                sinceTs = Timestamp.valueOf(LocalDateTime.parse(sinceText, DATE_TIME_FORMATTER));
            } catch (Exception ignored) {
                try {
                    sinceTs = Timestamp.from(Instant.parse(sinceText));
                } catch (Exception e) {
                    return ApiResult.fail("A0400", "since 时间格式错误，请用 yyyy-MM-dd HH:mm:ss");
                }
            }
        }
        Map<String, Object> summary = reminderFeedRepository.summarySince(sinceTs);
        Object latestObj = summary.get("latest");
        if (latestObj instanceof Map<?, ?> latestMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> latest = (Map<String, Object>) latestMap;
            String code = trimToEmpty((String) latest.get("reminderType"));
            latest.put("reminderTypeLabel", reminderTypeLabel(code));
        }
        summary.put("since", sinceText);
        summary.put("remindersEnabled", true);
        summary.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        return ApiResult.ok(summary);
    }

    private String normalizeReminderFilter(String type) {
        String raw = trimToEmpty(type);
        if (raw.isBlank() || "all".equalsIgnoreCase(raw) || "全部".equals(raw)) return "";
        List<String> codes = DramaReminderFeedRepository.resolveTypeCodes(raw);
        if (codes.stream().anyMatch(DramaReminderFeedRepository::isEveningType)) return "evening";
        if (codes.stream().anyMatch(DramaReminderFeedRepository::isNewDramaType)) return "new";
        if (codes.stream().anyMatch(DramaReminderFeedRepository::isRankType)) return "rank";
        if (codes.stream().anyMatch(DramaReminderFeedRepository::isKeyTheaterType)) return "focus";
        if (codes.stream().anyMatch(DramaReminderFeedRepository::isUpcomingPublishType)) return "upcoming";
        return raw;
    }

    private String reminderTypeLabel(String code) {
        return switch (code) {
            case "EVENING_PUBLISH_WINDOW" -> "夜间提醒";
            case "QUICK_PUBLISH_TIME_DIFF", "QUICK_PUBLISH" -> "新剧提醒";
            case "EDITED_COMIC_RANK" -> "剧排名榜单";
            case "KEY_THEATER" -> "重点剧提醒";
            case "UPCOMING_PUBLISH" -> "即将上线提醒";
            default -> code.isBlank() ? "提醒" : code;
        };
    }

    private static List<String> buildPromotionFieldKeys() {
        List<String> keys = new ArrayList<>();
        keys.add("iaaDefaultUrl");
        keys.add("iaaCard2Url");
        keys.add("iaaEpisode2Url");
        for (int i = 1; i <= IAP_PANEL_COUNT; i++) {
            keys.add("iapPanel" + i);
            keys.add("iapLink" + i);
        }
        return List.copyOf(keys);
    }

    private void fillIapPanelsFromSeriesJson(Map<String, Object> row, String iapPanelsJson) {
        for (int i = 1; i <= IAP_PANEL_COUNT; i++) {
            row.put("iapPanel" + i, "");
            row.put("iapLink" + i, "");
        }
        if (iapPanelsJson == null || iapPanelsJson.isBlank()) {
            return;
        }
        try {
            JsonNode panels = objectMapper.readTree(iapPanelsJson);
            if (!panels.isArray()) {
                return;
            }
            List<JsonNode> sorted = new ArrayList<>();
            panels.forEach(sorted::add);
            sorted.sort(Comparator.comparing(node -> parsePayFee(node.path("payFee").asText(""))));
            int index = 1;
            for (JsonNode panel : sorted) {
                if (index > IAP_PANEL_COUNT) {
                    break;
                }
                row.put("iapPanel" + index, trimToEmpty(panel.path("payFee").asText("")));
                row.put("iapLink" + index, trimToEmpty(panel.path("promotionUrl").asText("")));
                index++;
            }
        } catch (Exception e) {
            System.out.println("协作工作台解析IAP面板失败：" + e.getMessage());
        }
    }

    private void applyPromotionOverride(Map<String, Object> row, String overrideJson) {
        if (overrideJson == null || overrideJson.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(overrideJson);
            if (!node.isObject()) {
                return;
            }
            for (String key : PROMOTION_FIELD_KEYS) {
                if (!node.has(key) || node.get(key).isNull()) {
                    continue;
                }
                String ov = trimToEmpty(node.get(key).asText(""));
                String cur = trimToEmpty(Objects.toString(row.get(key), ""));
                // 中文备注：空串覆盖不得盖住已采集链接（历史误清空会导致「库里有、前端空」）。
                // 人工覆盖请填非空文案/正确链接；空串只表示「跟采集走」。
                if (ov.isEmpty() && !cur.isEmpty()) {
                    continue;
                }
                row.put(key, ov);
            }
        } catch (Exception e) {
            System.out.println("协作工作台解析推广链接覆盖失败：" + e.getMessage());
        }
    }

    private Map<String, String> loadSeriesPromotionBaseline(String type, String bookId) {
        Map<String, String> baseline = emptyPromotionMap();
        if (!"drama".equals(type) || bookId.isBlank()) {
            return baseline;
        }
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT iap_panels_json, iaa_card2_url, iaa_episode2_url
                FROM drama_series
                WHERE book_id = ?
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String iaaCard2 = trimToEmpty(rs.getString("iaa_card2_url"));
            row.put("iaaDefaultUrl", iaaCard2);
            row.put("iaaCard2Url", iaaCard2);
            row.put("iaaEpisode2Url", trimToEmpty(rs.getString("iaa_episode2_url")));
            fillIapPanelsFromSeriesJson(row, rs.getString("iap_panels_json"));
            return row;
        }, bookId);
        if (rows.isEmpty()) {
            return baseline;
        }
        Map<String, Object> row = rows.get(0);
        for (String key : PROMOTION_FIELD_KEYS) {
            baseline.put(key, trimToEmpty(Objects.toString(row.get(key), "")));
        }
        return baseline;
    }

    private Map<String, String> extractPromotionFromRequest(SaveRequest body) {
        Map<String, String> values = emptyPromotionMap();
        values.put("iaaDefaultUrl", trimToEmpty(body.iaaDefaultUrl()));
        values.put("iaaCard2Url", trimToEmpty(body.iaaCard2Url()));
        values.put("iaaEpisode2Url", trimToEmpty(body.iaaEpisode2Url()));
        values.put("iapPanel1", trimToEmpty(body.iapPanel1()));
        values.put("iapLink1", trimToEmpty(body.iapLink1()));
        values.put("iapPanel2", trimToEmpty(body.iapPanel2()));
        values.put("iapLink2", trimToEmpty(body.iapLink2()));
        values.put("iapPanel3", trimToEmpty(body.iapPanel3()));
        values.put("iapLink3", trimToEmpty(body.iapLink3()));
        values.put("iapPanel4", trimToEmpty(body.iapPanel4()));
        values.put("iapLink4", trimToEmpty(body.iapLink4()));
        values.put("iapPanel5", trimToEmpty(body.iapPanel5()));
        values.put("iapLink5", trimToEmpty(body.iapLink5()));
        values.put("iapPanel6", trimToEmpty(body.iapPanel6()));
        values.put("iapLink6", trimToEmpty(body.iapLink6()));
        values.put("iapPanel7", trimToEmpty(body.iapPanel7()));
        values.put("iapLink7", trimToEmpty(body.iapLink7()));
        return values;
    }

    /**
     * 中文备注：仅收集请求里显式出现的推广字段（null=未传）。
     * 前端应按「本次改动字段」提交，避免整包空串冲掉已有覆盖。
     */
    private Map<String, String> extractPromotionPatch(SaveRequest body) {
        Map<String, String> patch = new LinkedHashMap<>();
        if (body == null) {
            return patch;
        }
        putPromotionPatch(patch, "iaaDefaultUrl", body.iaaDefaultUrl());
        putPromotionPatch(patch, "iaaCard2Url", body.iaaCard2Url());
        putPromotionPatch(patch, "iaaEpisode2Url", body.iaaEpisode2Url());
        putPromotionPatch(patch, "iapPanel1", body.iapPanel1());
        putPromotionPatch(patch, "iapLink1", body.iapLink1());
        putPromotionPatch(patch, "iapPanel2", body.iapPanel2());
        putPromotionPatch(patch, "iapLink2", body.iapLink2());
        putPromotionPatch(patch, "iapPanel3", body.iapPanel3());
        putPromotionPatch(patch, "iapLink3", body.iapLink3());
        putPromotionPatch(patch, "iapPanel4", body.iapPanel4());
        putPromotionPatch(patch, "iapLink4", body.iapLink4());
        putPromotionPatch(patch, "iapPanel5", body.iapPanel5());
        putPromotionPatch(patch, "iapLink5", body.iapLink5());
        putPromotionPatch(patch, "iapPanel6", body.iapPanel6());
        putPromotionPatch(patch, "iapLink6", body.iapLink6());
        putPromotionPatch(patch, "iapPanel7", body.iapPanel7());
        putPromotionPatch(patch, "iapLink7", body.iapLink7());
        return patch;
    }

    private void putPromotionPatch(Map<String, String> patch, String key, String raw) {
        if (raw == null) {
            return;
        }
        patch.put(key, trimToEmpty(raw));
    }

    private String mergePromotionOverrideJson(String existingJson,
                                              Map<String, String> patch,
                                              Map<String, String> seriesBaseline) {
        if (patch == null || patch.isEmpty()) {
            return existingJson;
        }
        Map<String, String> effective = new LinkedHashMap<>();
        if (seriesBaseline != null) {
            effective.putAll(seriesBaseline);
        }
        effective.putAll(parsePromotionOverridePresent(existingJson));
        effective.putAll(patch);
        return buildPromotionOverrideJson(effective, seriesBaseline == null ? Map.of() : seriesBaseline);
    }

    /**
     * 中文备注：null=字段未传，保留库值；非 null（含空串）=按请求写入。
     */
    private String resolveOpsTextField(String requestValue, Map<String, Object> before, String key) {
        if (requestValue == null) {
            return beforeValue(before, key);
        }
        return trimToEmpty(requestValue);
    }

    /**
     * 中文备注：版权局部保存——未传时保留运营原值；首次建运营行回填主表版权或默认「番茄」，禁止写成空再双写清主表。
     */
    private String resolveOpsCopyrightForSave(String requestValue,
                                              Map<String, Object> opsBefore,
                                              Map<String, Object> seriesBefore) {
        if (requestValue != null) {
            return trimToEmpty(requestValue);
        }
        if (opsBefore != null) {
            return beforeValue(opsBefore, "copyrightOwner");
        }
        String master = seriesBefore == null
                ? ""
                : trimToEmpty(String.valueOf(seriesBefore.getOrDefault("copyrightOwner", "")));
        return master.isBlank() ? DEFAULT_COPYRIGHT_OWNER : master;
    }

    /**
     * 中文备注：剧目类型局部保存——未传时保留运营原值；首次建运营行回填业务线默认类型，与列表无运营行展示一致。
     */
    private String resolveOpsDramaTypeLabelForSave(String requestValue,
                                                   Map<String, Object> opsBefore,
                                                   String dramaType) {
        if (requestValue != null) {
            return trimToEmpty(requestValue);
        }
        if (opsBefore != null) {
            return beforeValue(opsBefore, "dramaTypeLabel");
        }
        return DEFAULT_TYPE_LABELS.getOrDefault(trimToEmpty(dramaType), "");
    }

    private Map<String, String> extractPromotionFromCreate(CreateDramaRequest body) {
        if (body == null) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("iaaDefaultUrl", trimToEmpty(body.iaaDefaultUrl()));
        values.put("iaaCard2Url", trimToEmpty(body.iaaCard2Url()));
        values.put("iaaEpisode2Url", trimToEmpty(body.iaaEpisode2Url()));
        values.put("iapPanel1", trimToEmpty(body.iapPanel1()));
        values.put("iapLink1", trimToEmpty(body.iapLink1()));
        values.put("iapPanel2", trimToEmpty(body.iapPanel2()));
        values.put("iapLink2", trimToEmpty(body.iapLink2()));
        values.put("iapPanel3", trimToEmpty(body.iapPanel3()));
        values.put("iapLink3", trimToEmpty(body.iapLink3()));
        values.put("iapPanel4", trimToEmpty(body.iapPanel4()));
        values.put("iapLink4", trimToEmpty(body.iapLink4()));
        values.put("iapPanel5", trimToEmpty(body.iapPanel5()));
        values.put("iapLink5", trimToEmpty(body.iapLink5()));
        values.put("iapPanel6", trimToEmpty(body.iapPanel6()));
        values.put("iapLink6", trimToEmpty(body.iapLink6()));
        values.put("iapPanel7", trimToEmpty(body.iapPanel7()));
        values.put("iapLink7", trimToEmpty(body.iapLink7()));
        return values;
    }

    private Map<String, String> emptyPromotionMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : PROMOTION_FIELD_KEYS) {
            map.put(key, "");
        }
        return map;
    }

    /**
     * 中文备注：仅保存与后台推送不一致的字段；全一致则写 null，后续后台推送仍能刷新页面。
     * 空串不得写成覆盖去盖采集值——否则一旦误提交空，前端会一直空白直到人工清 override。
     */
    private String buildPromotionOverrideJson(Map<String, String> userValues, Map<String, String> seriesValues) {
        ObjectNode override = objectMapper.createObjectNode();
        for (String key : PROMOTION_FIELD_KEYS) {
            String userValue = trimToEmpty(userValues.get(key));
            String seriesValue = trimToEmpty(seriesValues.get(key));
            if (userValue.isEmpty() && !seriesValue.isEmpty()) {
                continue;
            }
            if (!Objects.equals(userValue, seriesValue)) {
                override.put(key, userValue);
            }
        }
        if (override.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(override);
        } catch (Exception e) {
            throw new IllegalStateException("序列化推广链接覆盖失败：" + e.getMessage(), e);
        }
    }

    private String evaluateFocus(String normalizedType, String categoryText, String copyrightOwner, String publishAwemeUser) {
        String theaterText = (categoryText + " " + copyrightOwner).trim();
        for (String theater : markedTheaters) {
            if (containsIgnoreCase(theaterText, theater)) {
                return markValue;
            }
        }
        for (String awemeUser : markedAwemeUsers) {
            if (containsIgnoreCase(publishAwemeUser, awemeUser)) {
                return markValue;
            }
        }
        List<String> typeMarkedAwemeUsers = "real".equals(normalizedType)
                ? markedRealDramaAwemeUsers
                : ("drama".equals(normalizedType) ? markedDramaAwemeUsers : List.of());
        for (String awemeUser : typeMarkedAwemeUsers) {
            if (containsIgnoreCase(publishAwemeUser, awemeUser)) {
                return markValue;
            }
        }
        return "";
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        if (source == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return source.toLowerCase().contains(keyword.toLowerCase());
    }

    private List<String> parseList(String value) {
        String normalized = trimToEmpty(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(normalized.split("[,，、\\n;；]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }

    private BigDecimal parsePayFee(String value) {
        try {
            String text = trimToEmpty(value);
            return text.isBlank() ? BigDecimal.valueOf(Long.MAX_VALUE) : new BigDecimal(text);
        } catch (Exception e) {
            return BigDecimal.valueOf(Long.MAX_VALUE);
        }
    }

    private void recordDiffLogs(String type,
                                String bookId,
                                String operator,
                                Map<String, Object> before,
                                OpsSaveFields fields,
                                Map<String, String> userPromotion,
                                List<Map<String, Object>> changeLogs) {
        logIfChanged(type, bookId, operator, "剧名", beforeValue(before, "seriesName"), fields.seriesName(), changeLogs);
        logIfChanged(type, bookId, operator, "剧目类型", beforeValue(before, "dramaTypeLabel"), fields.dramaTypeLabel(), changeLogs);
        logIfChanged(type, bookId, operator, "版权", beforeValue(before, "copyrightOwner"), fields.copyrightOwner(), changeLogs);
        logIfChanged(type, bookId, operator, "筛剧评级", beforeValue(before, "screeningRating"), fields.screeningRating(), changeLogs);
        logIfChanged(type, bookId, operator, "最终评级", beforeValue(before, "finalRating"), fields.finalRating(), changeLogs);
        logIfChanged(type, bookId, operator, "剪辑", beforeValue(before, "editorAssignee"), fields.editorAssignee(), changeLogs);
        logIfChanged(type, bookId, operator, "素材数", beforeValue(before, "materialCount"), fields.materialCount(), changeLogs);
        logIfChanged(type, bookId, operator, "投放测试", beforeValue(before, "deliveryTest"), fields.deliveryTest(), changeLogs);
        logIfChanged(type, bookId, operator, "网盘链接", beforeValue(before, "cloudDiskUrl"), fields.cloudDiskUrl(), changeLogs);
        logIfChanged(type, bookId, operator, "优先级", beforeValue(before, "priorityLevel"), fields.priorityLevel(), changeLogs);
        logIfChanged(type, bookId, operator, "投手评级", beforeValue(before, "bidderRating"), fields.bidderRating(), changeLogs);
        logIfChanged(type, bookId, operator, "投手", beforeValue(before, "deliveryAssignee"), fields.deliveryAssignee(), changeLogs);

        Map<String, String> seriesBaseline = loadSeriesPromotionBaseline(type, bookId);
        Map<String, String> beforeOverride = parsePromotionOverridePresent(
                before == null ? null : (String) before.get("promotionOverrideJson"));
        Map<String, String> labels = Map.ofEntries(
                Map.entry("iaaDefaultUrl", "IAA链接(默认)"),
                Map.entry("iaaCard2Url", "IAA链接(卡2)"),
                Map.entry("iaaEpisode2Url", "IAA链接（第2集）"),
                Map.entry("iapPanel1", "IAP面板1"), Map.entry("iapLink1", "IAP链接1"),
                Map.entry("iapPanel2", "IAP面板2"), Map.entry("iapLink2", "IAP链接2"),
                Map.entry("iapPanel3", "IAP面板3"), Map.entry("iapLink3", "IAP链接3"),
                Map.entry("iapPanel4", "IAP面板4"), Map.entry("iapLink4", "IAP链接4"),
                Map.entry("iapPanel5", "IAP面板5"), Map.entry("iapLink5", "IAP链接5"),
                Map.entry("iapPanel6", "IAP面板6"), Map.entry("iapLink6", "IAP链接6"),
                Map.entry("iapPanel7", "IAP面板7"), Map.entry("iapLink7", "IAP链接7")
        );
        for (String key : PROMOTION_FIELD_KEYS) {
            // 中文备注：对比「展示值」而非仅 override；否则改剪辑时会把采集链接误记成新填写。
            String oldValue = beforeOverride.containsKey(key)
                    ? beforeOverride.get(key)
                    : trimToEmpty(seriesBaseline.get(key));
            String newValue = trimToEmpty(userPromotion.get(key));
            if (!Objects.equals(oldValue, newValue)) {
                logIfChanged(type, bookId, operator, labels.getOrDefault(key, key), oldValue, newValue, changeLogs);
            }
        }
    }

    private void notifyEditorMaterialCompleteIfNeeded(String tableId,
                                                      String type,
                                                      OpsSaveFields fields,
                                                      Map<String, Object> before,
                                                      String operator) {
        if (editorMaterialCompleteNotifier == null || fields == null) {
            return;
        }
        try {
            editorMaterialCompleteNotifier.onDramaOpsSave(
                    tableId,
                    fields.bookId(),
                    fields.seriesName(),
                    beforeValue(before, "editorAssignee"),
                    fields.editorAssignee(),
                    beforeValue(before, "materialCount"),
                    fields.materialCount(),
                    operator);
        } catch (Exception e) {
            log.warn("剪辑完成推送检查失败 bookId={}: {}", fields.bookId(), e.getMessage());
        }
    }

    private void notifyEditorMaterialCompleteForSheetRow(String tableId,
                                                         String rowId,
                                                         Map<String, Object> beforeCells,
                                                         Map<String, Object> afterCells,
                                                         Map<String, String> keyToTitle,
                                                         String operator) {
        if (editorMaterialCompleteNotifier == null) {
            return;
        }
        try {
            editorMaterialCompleteNotifier.onSheetRowSave(
                    tableId, rowId, beforeCells, afterCells, keyToTitle, operator);
        } catch (Exception e) {
            log.warn("空白表剪辑完成推送检查失败 tableId={} rowId={}: {}", tableId, rowId, e.getMessage());
        }
    }

    private Map<String, String> parsePromotionOverride(String overrideJson) {
        Map<String, String> map = emptyPromotionMap();
        map.putAll(parsePromotionOverridePresent(overrideJson));
        return map;
    }

    /** 中文备注：只返回 override JSON 里出现过的键（含空串清空）。 */
    private Map<String, String> parsePromotionOverridePresent(String overrideJson) {
        Map<String, String> map = new LinkedHashMap<>();
        if (overrideJson == null || overrideJson.isBlank()) {
            return map;
        }
        try {
            JsonNode node = objectMapper.readTree(overrideJson);
            if (!node.isObject()) {
                return map;
            }
            for (String key : PROMOTION_FIELD_KEYS) {
                if (node.has(key) && !node.get(key).isNull()) {
                    map.put(key, trimToEmpty(node.get(key).asText("")));
                }
            }
        } catch (Exception ignored) {
            // ignore malformed historical json
        }
        return map;
    }

    private String beforeValue(Map<String, Object> before, String key) {
        return before == null ? "" : trimToEmpty((String) before.get(key));
    }

    /**
     * 中文备注：空值允许；在选项列表内允许；与库中原值相同也允许（选项已删的祖传值）。
     */
    private boolean isAllowedPersistedOption(String fieldKey, String nextValue, String existingValue) {
        return isAllowedPersistedOption(fieldKey, nextValue, existingValue, null);
    }

    private boolean isAllowedPersistedOption(String fieldKey,
                                             String nextValue,
                                             String existingValue,
                                             OpsAuthService.Session session) {
        String next = trimToEmpty(nextValue);
        if (next.isBlank()) {
            return true;
        }
        if (leaderGroupService.enabled() && fieldKey.equals(leaderGroupService.leaderRatingField())) {
            List<String> all = effectiveOptions(fieldKey, null);
            return leaderGroupService.isAllowedScreeningRating(next, existingValue, all, session);
        }
        if (effectiveOptions(fieldKey, session).contains(next)) {
            return true;
        }
        return Objects.equals(next, trimToEmpty(existingValue));
    }

    private List<String> unsupportedMultiOptionValues(String fieldKey, String nextValue, String existingValue) {
        List<String> supported = effectiveOptions(fieldKey);
        java.util.Set<String> existing = new java.util.LinkedHashSet<>(splitMultiOptionValues(existingValue));
        List<String> unsupported = new ArrayList<>();
        for (String v : splitMultiOptionValues(nextValue)) {
            if (supported.contains(v) || existing.contains(v)) {
                continue;
            }
            unsupported.add(v);
        }
        return unsupported;
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

    private String summarizeOptions(List<Map<String, Object>> options) {
        if (options == null || options.isEmpty()) {
            return "(无选项)";
        }
        List<String> values = new ArrayList<>();
        for (Map<String, Object> opt : options) {
            String v = trimToEmpty(String.valueOf(opt.getOrDefault("value", "")));
            if (!v.isBlank()) values.add(v);
        }
        if (values.isEmpty()) return "(无选项)";
        String joined = String.join("、", values);
        return joined.length() <= 200 ? joined : joined.substring(0, 197) + "...";
    }

    private void logIfChanged(String type,
                              String bookId,
                              String operator,
                              String fieldName,
                              String oldValue,
                              String newValue) {
        logIfChanged(type, bookId, operator, fieldName, oldValue, newValue, null);
    }

    private void logIfChanged(String type,
                              String bookId,
                              String operator,
                              String fieldName,
                              String oldValue,
                              String newValue,
                              List<Map<String, Object>> changeLogs) {
        String oldTrim = trimToEmpty(oldValue);
        String newTrim = trimToEmpty(newValue);
        if (Objects.equals(oldTrim, newTrim)) {
            return;
        }
        // 中文备注：save() 已加事务；日志失败时整次保存回滚，确保数据与审计记录一致。
        long logId = dramaOperationRepository.insertLog(type, bookId, operator, fieldName, oldTrim, newTrim);
        if (changeLogs != null && logId > 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", logId);
            item.put("fieldName", fieldName);
            item.put("oldValue", oldTrim);
            item.put("newValue", newTrim);
            changeLogs.add(item);
        }
    }

    private static String loadOpsClientRev() {
        try (InputStream in = DramaOpsController.class.getResourceAsStream("/static/ops/index.html")) {
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

    private String opsKey(Map<String, Object> seriesRow) {
        String bookId = trimToEmpty((String) seriesRow.get("bookId"));
        if (!bookId.isBlank()) {
            return bookId;
        }
        String seriesName = trimToEmpty((String) seriesRow.get("seriesName"));
        return seriesName.isBlank() ? "" : "name-" + seriesName;
    }

    private ApiResult<Map<String, Object>> listDramasAllTypes(LocalDate startDate,
                                                              LocalDate endDateExclusive,
                                                              LocalDateTime start,
                                                              LocalDateTime end,
                                                              boolean rangeMode,
                                                              int safeDays,
                                                              int safePage,
                                                              int safePageSize,
                                                              int offset,
                                                              String kw,
                                                              boolean hasKeyword,
                                                              String like,
                                                              String normalizedSortField,
                                                              String normalizedSortDir) {
        // 中文备注：三张采集表 UNION，时间窗口对齐飞书主表推送；一次返回，不再 OFFSET 分页。
        String sortExpr = "publishTime".equals(normalizedSortField)
                ? "u.publish_time"
                : "COALESCE(u.estimate_publish_time, u.publish_time)";
        String timeWhere = FEISHU_PUBLISH_WINDOW_WHERE;
        String keywordWhere = hasKeyword ? """
                  AND (
                       series_name LIKE ? ESCAPE '\\\\'
                    OR book_id LIKE ? ESCAPE '\\\\'
                    OR IFNULL(douyin_work_id, '') LIKE ? ESCAPE '\\\\'
                    OR IFNULL(publish_aweme_user, '') LIKE ? ESCAPE '\\\\'
                  )
                """ : "";

        String dramaSelect = """
                SELECT 'drama' AS drama_type, id, book_id, series_name, episode_amount, publish_time, estimate_publish_time,
                       category_text, copyright_owner, publish_aweme_user, douyin_work_id,
                       iap_panels_json, iaa_card2_url, iaa_episode2_url
                FROM drama_series
                WHERE %s %s
                """.formatted(timeWhere, keywordWhere);
        String realSelect = """
                SELECT 'real' AS drama_type, id, book_id, series_name, episode_amount, publish_time, estimate_publish_time,
                       category_text, copyright_owner, publish_aweme_user, douyin_work_id,
                       NULL AS iap_panels_json, NULL AS iaa_card2_url, NULL AS iaa_episode2_url
                FROM real_drama_series
                WHERE %s %s
                """.formatted(timeWhere, keywordWhere);
        String douyinSelect = """
                SELECT 'douyin' AS drama_type, id, book_id, series_name, episode_amount, publish_time, estimate_publish_time,
                       category_text, copyright_owner, publish_aweme_user, douyin_work_id,
                       NULL AS iap_panels_json, NULL AS iaa_card2_url, NULL AS iaa_episode2_url
                FROM douyin_mini_paid_drama_series
                WHERE %s %s
                """.formatted(timeWhere, keywordWhere);

        String unionBody = dramaSelect + " UNION ALL " + realSelect + " UNION ALL " + douyinSelect;
        String countSql = "SELECT COUNT(1) FROM (" + unionBody + ") u";
        String listSql = """
                SELECT * FROM (%s) u
                ORDER BY %s %s, u.id %s
                LIMIT ?
                """.formatted(unionBody, sortExpr, normalizedSortDir, normalizedSortDir);

        List<Object> args = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            args.add(start);
            args.add(end);
            if (hasKeyword) {
                for (int i = 0; i < 4; i++) {
                    args.add(like);
                }
            }
        }
        Long totalObj = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());
        long total = totalObj == null ? 0L : totalObj;
        int totalPages = total == 0 ? 0 : 1;
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(FEISHU_WINDOW_LIST_MAX);

        List<Map<String, Object>> seriesRows = jdbcTemplate.query(listSql, (rs, rowNum) -> {
            String rowType = trimToEmpty(rs.getString("drama_type"));
            return mapSeriesRow(rs, rowType, "drama".equals(rowType));
        }, listArgs.toArray());

        attachOperations(seriesRows);
        return ApiResult.ok(buildListResponse(startDate, endDateExclusive, rangeMode, safeDays,
                "all", kw, 1, FEISHU_WINDOW_LIST_MAX, total, totalPages, false,
                normalizedSortField, normalizedSortDir, seriesRows));
    }

    private Map<String, Object> mapSeriesRow(ResultSet rs, String dramaType, boolean hasPromotionColumns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dramaType", dramaType);
        row.put("bookId", trimToEmpty(rs.getString("book_id")));
        row.put("douyinWorkId", trimToEmpty(rs.getString("douyin_work_id")));
        row.put("publishAwemeUser", trimToEmpty(rs.getString("publish_aweme_user")));
        row.put("defaultDramaTypeLabel", DEFAULT_TYPE_LABELS.getOrDefault(dramaType, ""));
        row.put("copyrightOwner", trimToEmpty(rs.getString("copyright_owner")));
        row.put("seriesName", trimToEmpty(rs.getString("series_name")));
        row.put("estimatePublishTime", formatTime(rs.getTimestamp("estimate_publish_time")));
        row.put("publishTime", formatTime(rs.getTimestamp("publish_time")));
        row.put("episodeAmount", rs.getObject("episode_amount"));
        row.put("categoryText", trimToEmpty(rs.getString("category_text")));
        String iapJson = "";
        String iaaCard2 = "";
        String iaaEpisode2 = "";
        if (hasPromotionColumns) {
            iapJson = rs.getString("iap_panels_json");
            iaaCard2 = trimToEmpty(rs.getString("iaa_card2_url"));
            iaaEpisode2 = trimToEmpty(rs.getString("iaa_episode2_url"));
        }
        // 中文备注：后台推送默认值；IAA默认暂与卡2同源，人工可再改。
        row.put("iaaDefaultUrl", iaaCard2);
        row.put("iaaCard2Url", iaaCard2);
        row.put("iaaEpisode2Url", iaaEpisode2);
        fillIapPanelsFromSeriesJson(row, iapJson);
        return row;
    }

    private void attachOperations(List<Map<String, Object>> seriesRows) {
        Map<String, List<String>> keysByType = new LinkedHashMap<>();
        for (Map<String, Object> row : seriesRows) {
            String opsKey = opsKey(row);
            row.put("opsKey", opsKey);
            String dramaType = trimToEmpty((String) row.get("dramaType"));
            if (opsKey.isBlank() || dramaType.isBlank()) {
                continue;
            }
            keysByType.computeIfAbsent(dramaType, k -> new ArrayList<>()).add(opsKey);
        }
        Map<String, Map<String, Object>> opsByComposite = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : keysByType.entrySet()) {
            for (Map<String, Object> op : dramaOperationRepository.listByTypeAndBookIds(entry.getKey(), entry.getValue())) {
                opsByComposite.put(entry.getKey() + ":" + op.get("bookId"), op);
            }
        }
        for (Map<String, Object> row : seriesRows) {
            String dramaType = trimToEmpty((String) row.get("dramaType"));
            Map<String, Object> op = opsByComposite.get(dramaType + ":" + row.get("opsKey"));
            String savedTypeLabel = op == null ? "" : trimToEmpty((String) op.get("dramaTypeLabel"));
            String defaultTypeLabel = trimToEmpty((String) row.get("defaultDramaTypeLabel"));
            // 中文备注：已有运营行时允许剧目类型为空（清空生效）；无运营行才回落默认类型。
            row.put("dramaTypeLabel", op == null
                    ? defaultTypeLabel
                    : savedTypeLabel);
            // 中文备注：运营已写过剧名则覆盖采集名（含 ADX）；ADX 运营键仍按采集原名生成，避免改名丢关联。
            String savedSeriesName = op == null ? "" : trimToEmpty((String) op.get("seriesName"));
            if (!savedSeriesName.isBlank()) {
                row.put("seriesName", savedSeriesName);
            }
            // 中文备注：版权允许清空；运营为空时回落主表/默认「番茄」（避免自动分配建空运营行后整列空白）。
            String masterCopyright = trimToEmpty((String) row.get("copyrightOwner"));
            String savedCopyright = op == null ? "" : trimToEmpty((String) op.get("copyrightOwner"));
            String effectiveCopyright = OpsCopyrightSupport.effectiveDisplay(savedCopyright, masterCopyright);
            row.put("copyrightOwner", effectiveCopyright);
            row.put("isFocus", evaluateFocus(dramaType,
                    trimToEmpty((String) row.get("categoryText")),
                    effectiveCopyright,
                    trimToEmpty((String) row.get("publishAwemeUser"))));
            row.put("screeningRating", op == null ? "" : trimToEmpty((String) op.get("screeningRating")));
            row.put("finalRating", op == null ? "" : trimToEmpty((String) op.get("finalRating")));
            row.put("editorAssignee", op == null ? "" : trimToEmpty((String) op.get("editorAssignee")));
            row.put("materialCount", op == null ? "" : trimToEmpty((String) op.get("materialCount")));
            row.put("deliveryTest", op == null ? "" : trimToEmpty((String) op.get("deliveryTest")));
            row.put("cloudDiskUrl", op == null ? "" : trimToEmpty((String) op.get("cloudDiskUrl")));
            row.put("priorityLevel", op == null ? "" : trimToEmpty((String) op.get("priorityLevel")));
            row.put("bidderRating", op == null ? "" : trimToEmpty((String) op.get("bidderRating")));
            row.put("deliveryAssignee", op == null ? "" : trimToEmpty((String) op.get("deliveryAssignee")));
            applyPromotionOverride(row, op == null ? null : (String) op.get("promotionOverrideJson"));
            applyCustomFields(row, op == null ? null : (String) op.get("customFieldsJson"));
            row.put("version", op == null ? 0 : op.get("version"));
            row.put("updatedBy", op == null ? "" : trimToEmpty((String) op.get("updatedBy")));
            row.put("opsUpdateTime", op == null ? "" : Objects.toString(op.get("updateTime"), ""));
        }
    }

    private Map<String, Object> buildListResponse(LocalDate startDate,
                                                  LocalDate endDateExclusive,
                                                  boolean rangeMode,
                                                  int safeDays,
                                                  String normalizedType,
                                                  String kw,
                                                  int safePage,
                                                  int safePageSize,
                                                  long total,
                                                  int totalPages,
                                                  boolean filterAllMode,
                                                  String normalizedSortField,
                                                  String normalizedSortDir,
                                                  List<Map<String, Object>> seriesRows) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", startDate.toString());
        data.put("dateEnd", endDateExclusive.minusDays(1).toString());
        data.put("days", rangeMode ? safeDays : 1);
        data.put("dateMode", rangeMode ? "range" : "day");
        data.put("type", normalizedType);
        data.put("keyword", kw);
        data.put("page", safePage);
        data.put("pageSize", safePageSize);
        data.put("total", total);
        data.put("totalPages", totalPages);
        data.put("filterAllMode", filterAllMode);
        data.put("filterTruncated", false);
        data.put("filterResultMax", MAX_PAGE_SIZE);
        data.put("sortField", normalizedSortField);
        data.put("sortDir", normalizedSortDir.toLowerCase());
        data.putAll(effectiveOptionMap());
        data.put("editableFields", EDITABLE_FIELDS);
        data.put("serverTime", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        data.put("maxPageSize", MAX_PAGE_SIZE);
        data.put("rows", seriesRows);
        return data;
    }

    private String normalizeType(String type) {
        String normalized = trimToEmpty(type).toLowerCase();
        if (normalized.isBlank() || "all".equals(normalized)) {
            return "all";
        }
        return normalized;
    }

    private String normalizeSortField(String sortField) {
        String field = trimToEmpty(sortField);
        // 中文备注：只允许白名单字段，避免拼接 SQL 注入。
        if ("publishTime".equals(field) || "publish_time".equals(field)) {
            return "publishTime";
        }
        return "estimatePublishTime";
    }

    private String buildOrderBySql(String sortField, String sortDir, boolean withAlias) {
        String prefix = withAlias ? "s." : "";
        String idCol = withAlias ? "s.id" : "id";
        // 中文备注：排序也按飞书标准有效发布时间（预估优先）。
        String timeCol = "COALESCE(" + prefix + "estimate_publish_time, " + prefix + "publish_time)";
        if ("publishTime".equals(sortField)) {
            return prefix + "publish_time " + sortDir + ", " + idCol + " " + sortDir;
        }
        return timeCol + " " + sortDir + ", " + idCol + " " + sortDir;
    }

    private LocalDate parseDate(String date) {
        try {
            return date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date.trim());
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private String formatTime(Timestamp value) {
        return value == null ? "" : value.toLocalDateTime().format(DATE_TIME_FORMATTER);
    }

    private String resolveOpsCopyrightOwner(String savedCopyright) {
        String saved = trimToEmpty(savedCopyright);
        if (!saved.isBlank() && baseOptions("copyrightOwner").contains(saved)) {
            return saved;
        }
        return DEFAULT_COPYRIGHT_OWNER;
    }

    /**
     * 中文备注：已有运营行时允许版权为空；非空已填值原样返回（选项删除不清空）。
     */
    private String resolveOpsCopyrightOwnerAllowBlank(String savedCopyright) {
        return trimToEmpty(savedCopyright);
    }

    /**
     * 中文备注：采集主表可编辑字段快照，供审计对比；ADX 等无主表类型跳过。
     * 手动新增的 name-* book_id 也会双写主表，需纳入快照。
     */
    private Map<String, Object> loadSeriesMasterSnapshot(String dramaType, String bookId) {
        String table = SERIES_TABLES.get(trimToEmpty(dramaType));
        String id = trimToEmpty(bookId);
        if (table == null || id.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query("""
                    SELECT series_name, estimate_publish_time, publish_time, changdu_create_time,
                           episode_amount, category_text, douyin_work_id, publish_aweme_user, copyright_owner
                    FROM %s
                    WHERE book_id = ?
                    LIMIT 1
                    """.formatted(table), (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seriesName", trimToEmpty(rs.getString("series_name")));
                row.put("estimatePublishTime", formatTime(rs.getTimestamp("estimate_publish_time")));
                row.put("publishTime", formatTime(rs.getTimestamp("publish_time")));
                row.put("changduCreateTime", formatTime(rs.getTimestamp("changdu_create_time")));
                Object episode = rs.getObject("episode_amount");
                row.put("episodeAmount", episode == null ? "" : String.valueOf(episode));
                row.put("categoryText", trimToEmpty(rs.getString("category_text")));
                row.put("douyinWorkId", trimToEmpty(rs.getString("douyin_work_id")));
                row.put("publishAwemeUser", trimToEmpty(rs.getString("publish_aweme_user")));
                row.put("copyrightOwner", trimToEmpty(rs.getString("copyright_owner")));
                return row;
            }, id);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            System.out.println("协作工作台读取采集主表失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 中文备注：合成最终要落库的主表字段。请求缺省(null)=保留原值；显式空串=清空。
     * 版权必须按 body.copyrightOwner 的 null/空串语义解析，不能用运营表已解析值（首次局部保存未传版权时运营侧可能是默认番茄）。
     */
    private Map<String, String> resolveEffectiveSeriesMaster(Map<String, Object> before,
                                                             SaveRequest body,
                                                             String seriesName) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("seriesName", trimToEmpty(seriesName));
        out.put("copyrightOwner", resolveMasterTextField(
                body == null ? null : body.copyrightOwner(), before, "copyrightOwner"));
        out.put("estimatePublishTime", resolveMasterTimeField(
                body == null ? null : body.estimatePublishTime(), before, "estimatePublishTime"));
        out.put("publishTime", resolveMasterTimeField(
                body == null ? null : body.publishTime(), before, "publishTime"));
        out.put("changduCreateTime", resolveMasterTimeField(
                body == null ? null : body.changduCreateTime(), before, "changduCreateTime"));
        out.put("episodeAmount", resolveMasterTextField(
                body == null ? null : body.episodeAmount(), before, "episodeAmount"));
        out.put("categoryText", resolveMasterTextField(
                body == null ? null : body.categoryText(), before, "categoryText"));
        out.put("douyinWorkId", resolveMasterTextField(
                body == null ? null : body.douyinWorkId(), before, "douyinWorkId"));
        out.put("publishAwemeUser", resolveMasterTextField(
                body == null ? null : body.publishAwemeUser(), before, "publishAwemeUser"));
        return out;
    }

    private String resolveMasterTimeField(String requestValue, Map<String, Object> before, String key) {
        if (requestValue == null) {
            return before == null ? "" : trimToEmpty(String.valueOf(before.getOrDefault(key, "")));
        }
        return formatTime(parseFlexibleTimestamp(requestValue));
    }

    private String resolveMasterTextField(String requestValue, Map<String, Object> before, String key) {
        if (requestValue == null) {
            return before == null ? "" : trimToEmpty(String.valueOf(before.getOrDefault(key, "")));
        }
        if ("episodeAmount".equals(key)) {
            Integer episode = parseFlexibleInt(requestValue);
            return episode == null ? "" : String.valueOf(episode);
        }
        return trimToEmpty(requestValue);
    }

    /**
     * 中文备注：剧名/时间/集数等双写采集主表（含手动新增的 name-* 行）。
     * @return -2 跳过(ADX/无主表)；-1 SQL失败；0 未命中行；>0 更新行数
     */
    private int updateSeriesMasterFields(String dramaType, String bookId, Map<String, String> effective) {
        String table = SERIES_TABLES.get(trimToEmpty(dramaType));
        String id = trimToEmpty(bookId);
        String name = trimToEmpty(effective == null ? null : effective.get("seriesName"));
        if (table == null || id.isBlank()) {
            return -2;
        }
        if (name.isBlank()) {
            return -1;
        }
        Timestamp estimateTs = parseFlexibleTimestamp(effective.get("estimatePublishTime"));
        Timestamp publishTs = parseFlexibleTimestamp(effective.get("publishTime"));
        Timestamp changduTs = parseFlexibleTimestamp(effective.get("changduCreateTime"));
        Integer episode = parseFlexibleInt(effective.get("episodeAmount"));
        String categoryText = trimToEmpty(effective.get("categoryText"));
        String douyinWorkId = trimToEmpty(effective.get("douyinWorkId"));
        String publishAwemeUser = trimToEmpty(effective.get("publishAwemeUser"));
        String copyrightOwner = trimToEmpty(effective.get("copyrightOwner"));
        try {
            return jdbcTemplate.update("""
                    UPDATE %s
                    SET series_name = ?,
                        estimate_publish_time = ?,
                        publish_time = ?,
                        changdu_create_time = ?,
                        episode_amount = ?,
                        category_text = ?,
                        douyin_work_id = ?,
                        publish_aweme_user = ?,
                        copyright_owner = ?
                    WHERE book_id = ?
                    """.formatted(table),
                    name,
                    estimateTs,
                    publishTs,
                    changduTs,
                    episode,
                    categoryText.isBlank() ? null : categoryText,
                    douyinWorkId.isBlank() ? null : douyinWorkId,
                    publishAwemeUser.isBlank() ? null : publishAwemeUser,
                    copyrightOwner.isBlank() ? null : copyrightOwner,
                    id
            );
        } catch (Exception e) {
            System.out.println("协作工作台同步采集主表失败：" + e.getMessage());
            return -1;
        }
    }

    private boolean isSeriesMasterChanged(Map<String, Object> before, Map<String, String> effective) {
        String nextEstimate = trimToEmpty(effective.get("estimatePublishTime"));
        String nextPublish = trimToEmpty(effective.get("publishTime"));
        String nextChangdu = trimToEmpty(effective.get("changduCreateTime"));
        String nextEpisode = trimToEmpty(effective.get("episodeAmount"));
        String nextCategory = trimToEmpty(effective.get("categoryText"));
        String nextWorkId = trimToEmpty(effective.get("douyinWorkId"));
        String nextAweme = trimToEmpty(effective.get("publishAwemeUser"));
        String nextName = trimToEmpty(effective.get("seriesName"));
        String nextCopyright = trimToEmpty(effective.get("copyrightOwner"));
        if (before == null) {
            // 中文备注：主表无行时，仅当改了时间/集数/描述/抖音/版权字段才强制要求落库成功。
            return !nextEstimate.isBlank() || !nextPublish.isBlank() || !nextChangdu.isBlank()
                    || !nextEpisode.isBlank() || !nextCategory.isBlank()
                    || !nextWorkId.isBlank() || !nextAweme.isBlank() || !nextCopyright.isBlank();
        }
        return !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("seriesName", ""))), nextName)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("estimatePublishTime", ""))), nextEstimate)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("publishTime", ""))), nextPublish)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("changduCreateTime", ""))), nextChangdu)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("episodeAmount", ""))), nextEpisode)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("categoryText", ""))), nextCategory)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("douyinWorkId", ""))), nextWorkId)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("publishAwemeUser", ""))), nextAweme)
                || !Objects.equals(trimToEmpty(String.valueOf(before.getOrDefault("copyrightOwner", ""))), nextCopyright);
    }

    private void recordSeriesMasterDiffLogs(String type,
                                            String bookId,
                                            String operator,
                                            Map<String, Object> before,
                                            Map<String, String> effective,
                                            List<Map<String, Object>> changeLogs) {
        if (before == null) {
            before = Map.of();
        }
        if (effective == null) {
            effective = Map.of();
        }
        // seriesName 的审计仍走运营表 recordDiffLogs，这里只记采集主表字段。
        logIfChanged(type, bookId, operator, "预估投放时间",
                String.valueOf(before.getOrDefault("estimatePublishTime", "")),
                trimToEmpty(effective.get("estimatePublishTime")), changeLogs);
        logIfChanged(type, bookId, operator, "预估发布时间",
                String.valueOf(before.getOrDefault("publishTime", "")),
                trimToEmpty(effective.get("publishTime")), changeLogs);
        logIfChanged(type, bookId, operator, "常读创建时间",
                String.valueOf(before.getOrDefault("changduCreateTime", "")),
                trimToEmpty(effective.get("changduCreateTime")), changeLogs);
        logIfChanged(type, bookId, operator, "总集数",
                String.valueOf(before.getOrDefault("episodeAmount", "")),
                trimToEmpty(effective.get("episodeAmount")), changeLogs);
        logIfChanged(type, bookId, operator, "描述类型",
                String.valueOf(before.getOrDefault("categoryText", "")),
                trimToEmpty(effective.get("categoryText")), changeLogs);
        logIfChanged(type, bookId, operator, "抖音作品id",
                String.valueOf(before.getOrDefault("douyinWorkId", "")),
                trimToEmpty(effective.get("douyinWorkId")), changeLogs);
        logIfChanged(type, bookId, operator, "发布抖音号",
                String.valueOf(before.getOrDefault("publishAwemeUser", "")),
                trimToEmpty(effective.get("publishAwemeUser")), changeLogs);
        logIfChanged(type, bookId, operator, "版权(主表)",
                String.valueOf(before.getOrDefault("copyrightOwner", "")),
                trimToEmpty(effective.get("copyrightOwner")), changeLogs);
    }

    private void recordCustomFieldDiffLogs(String type,
                                           String bookId,
                                           String operator,
                                           String beforeJson,
                                           String afterJson,
                                           List<Map<String, Object>> changeLogs) {
        Map<String, String> beforeMap = parseCustomFieldsMap(beforeJson);
        Map<String, String> afterMap = parseCustomFieldsMap(afterJson);
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(beforeMap.keySet());
        keys.addAll(afterMap.keySet());
        for (String key : keys) {
            String oldValue = beforeMap.getOrDefault(key, "");
            String newValue = afterMap.getOrDefault(key, "");
            if (Objects.equals(oldValue, newValue)) continue;
            String label = key;
            try {
                Map<String, Object> cfg = fieldConfigRepository.find(key);
                if (cfg != null) {
                    String title = trimToEmpty(String.valueOf(cfg.getOrDefault("fieldTitle", "")));
                    if (!title.isBlank()) label = title;
                }
            } catch (Exception ignored) {
            }
            logIfChanged(type, bookId, operator, label, oldValue, newValue, changeLogs);
        }
    }

    private Map<String, String> parseCustomFieldsMap(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return map;
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) return map;
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (key == null || key.isBlank() || !key.startsWith("custom_")) return;
                JsonNode v = e.getValue();
                map.put(key, v == null || v.isNull() ? "" : trimToEmpty(v.asText("")));
            });
        } catch (Exception ignored) {
        }
        return map;
    }

    private Timestamp parseFlexibleTimestamp(String raw) {
        String text = trimToEmpty(raw).replace('T', ' ');
        if (text.isBlank()) {
            return null;
        }
        try {
            if (text.length() == 10) {
                return Timestamp.valueOf(text + " 00:00:00");
            }
            if (text.length() == 16) {
                return Timestamp.valueOf(text + ":00");
            }
            if (text.length() >= 19) {
                return Timestamp.valueOf(text.substring(0, 19));
            }
            return Timestamp.valueOf(LocalDateTime.parse(text, DATE_TIME_FORMATTER));
        } catch (Exception e) {
            try {
                return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Integer parseFlexibleInt(String raw) {
        String text = trimToEmpty(raw).replaceAll("[^0-9-]", "");
        if (text.isBlank() || "-".equals(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> splitMultiOptionValues(String raw) {
        String text = trimToEmpty(raw);
        if (text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\s*[、,，|｜]+\\s*"))
                .map(this::trimToEmpty)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private String normalizeMultiOptionValue(String raw) {
        return String.join("、", splitMultiOptionValues(raw));
    }

    private boolean isCustomFieldConfig(Map<String, Object> cfg) {
        if (cfg == null) {
            return false;
        }
        Object flag = cfg.get("isCustom");
        if (Boolean.TRUE.equals(flag)) {
            return true;
        }
        if (flag instanceof Number n) {
            return n.intValue() == 1;
        }
        return "1".equals(String.valueOf(flag)) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    private void applyCustomFields(Map<String, Object> row, String customFieldsJson) {
        if (customFieldsJson == null || customFieldsJson.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(customFieldsJson);
            if (!node.isObject()) return;
            node.fields().forEachRemaining(e -> {
                String key = e.getKey();
                if (key == null || key.isBlank() || RESERVED_FIELD_KEYS.contains(key)) return;
                JsonNode v = e.getValue();
                row.put(key, v == null || v.isNull() ? "" : v.asText(""));
            });
        } catch (Exception ignored) {
        }
    }

    private String mergeCustomFieldsJson(String existingJson, Map<String, String> patch) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(existingJson);
                if (node.isObject()) {
                    node.fields().forEachRemaining(e -> {
                        if (e.getKey() != null && e.getValue() != null && !e.getValue().isNull()) {
                            merged.put(e.getKey(), e.getValue().asText(""));
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }
        if (patch != null) {
            for (Map.Entry<String, String> e : patch.entrySet()) {
                String key = trimToEmpty(e.getKey());
                if (key.isBlank() || RESERVED_FIELD_KEYS.contains(key) || !key.startsWith("custom_")) continue;
                Map<String, Object> cfg = fieldConfigRepository.find(key);
                if (cfg == null || !isCustomFieldConfig(cfg)) continue;
                String next = trimToEmpty(e.getValue());
                // 中文备注：自定义多选同样按顿号规范化，避免「A、B」落库形态不一致。
                if ("multi".equals(trimToEmpty(String.valueOf(cfg.getOrDefault("fieldType", ""))))) {
                    next = normalizeMultiOptionValue(next);
                }
                merged.put(key, next);
            }
        }
        if (merged.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            return existingJson;
        }
    }

    private String resolveLockedGroup(String editorAssignee,
                                      Map<String, String> customFields,
                                      String existingCustomFieldsJson) {
        if (leaderGroupService == null || !leaderGroupService.enabled()) {
            return resolveEditorLockedGroup(editorAssignee);
        }
        return leaderGroupService.resolveLockedGroup(
                readLeaderRatingValue(customFields, existingCustomFieldsJson),
                editorAssignee);
    }

    private String readLeaderRatingValue(Map<String, String> customFields, String existingCustomFieldsJson) {
        if (leaderGroupService == null) {
            return "";
        }
        String fieldKey = leaderGroupService.leaderRatingField();
        if (fieldKey.isBlank()) {
            return "";
        }
        if (customFields != null && customFields.containsKey(fieldKey)) {
            return trimToEmpty(customFields.get(fieldKey));
        }
        return OpsLeaderGroupService.readCustomField(existingCustomFieldsJson, fieldKey);
    }

    private String resolveEditorLockedGroup(String editorAssignee) {
        if (editorGroupMemberService == null) {
            return "";
        }
        return editorGroupMemberService.resolveGroupForEditors(editorAssignee);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String escapeLike(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record LoginRequest(String displayName, String password) {
    }

    public record SaveRequest(String tableId,
                              String dramaType,
                              String bookId,
                              String seriesName,
                              String dramaTypeLabel,
                              String copyrightOwner,
                              String screeningRating,
                              String finalRating,
                              String editorAssignee,
                              String materialCount,
                              String deliveryTest,
                              String cloudDiskUrl,
                              String priorityLevel,
                              String bidderRating,
                              String deliveryAssignee,
                              String remark,
                              String estimatePublishTime,
                              String publishTime,
                              String changduCreateTime,
                              String episodeAmount,
                              String categoryText,
                              String douyinWorkId,
                              String publishAwemeUser,
                              String iaaDefaultUrl,
                              String iaaCard2Url,
                              String iaaEpisode2Url,
                              String iapPanel1,
                              String iapLink1,
                              String iapPanel2,
                              String iapLink2,
                              String iapPanel3,
                              String iapLink3,
                              String iapPanel4,
                              String iapLink4,
                              String iapPanel5,
                              String iapLink5,
                              String iapPanel6,
                              String iapLink6,
                              String iapPanel7,
                              String iapLink7,
                              Integer version,
                              String operator,
                              Map<String, String> customFields) {
    }
}
