package com.polytable.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 协作台租户：总部（hq）与代投（agency）分域访问。
 * 默认关闭（ops.tenant.enabled=false），上线前再开。
 */
@Service
public class OpsTenantService {

    public static final String ORG_HQ = "hq";
    public static final String ORG_AGENCY = "agency";

    private static final Map<String, String> TABLE_TYPE_BY_ID = Map.of(
            "tbl_all", "all",
            "tbl_created", "created",
            "tbl_near", "near",
            "tbl_promo", "promo",
            "tbl_adx", "adx"
    );

    private final boolean enabled;
    private final String defaultMode;
    private final Map<String, String> hostModes;
    private final Set<String> agencyTableIds;
    private final String hqLabel;
    private final String agencyLabel;
    private final OpsTenantFeatureService tenantFeatureService;

    public OpsTenantService(
            @Value("${ops.tenant.enabled:false}") boolean enabled,
            @Value("${ops.tenant.default-mode:hq}") String defaultMode,
            @Value("${ops.tenant.host-modes:}") String hostModesRaw,
            @Value("${ops.tenant.agency-tables:}") String agencyTablesRaw,
            @Value("${ops.tenant.hq-label:协作台}") String hqLabel,
            @Value("${ops.tenant.agency-label:代投协作台}") String agencyLabel,
            @Autowired(required = false) OpsTenantFeatureService tenantFeatureService) {
        this.enabled = enabled;
        this.defaultMode = normalizeOrg(defaultMode, ORG_HQ);
        this.hostModes = parseHostModes(hostModesRaw);
        this.agencyTableIds = parseIdSet(agencyTablesRaw, Set.of());
        this.hqLabel = hqLabel == null || hqLabel.isBlank() ? "协作台" : hqLabel.trim();
        this.agencyLabel = agencyLabel == null || agencyLabel.isBlank() ? "代投协作台" : agencyLabel.trim();
        this.tenantFeatureService = tenantFeatureService;
    }

    public boolean enabled() {
        return enabled;
    }

    public String resolveMode(HttpServletRequest request) {
        if (!enabled) {
            return ORG_HQ;
        }
        if (request != null) {
            String host = normalizeHost(request.getHeader("Host"));
            if (!host.isBlank()) {
                String mapped = hostModes.get(host);
                if (mapped != null) {
                    return mapped;
                }
                // 中文备注：子域前缀兜底，如 ad.example.cn / agency.example.com → agency。
                int dot = host.indexOf('.');
                if (dot > 0) {
                    String prefix = host.substring(0, dot);
                    if (ORG_AGENCY.equals(prefix) || "ad".equals(prefix)) {
                        return ORG_AGENCY;
                    }
                    if ("hq".equals(prefix) || "ops".equals(prefix)) {
                        return ORG_HQ;
                    }
                }
            }
        }
        return defaultMode;
    }

    public String labelForMode(String mode) {
        return ORG_AGENCY.equals(normalizeOrg(mode, ORG_HQ)) ? agencyLabel : hqLabel;
    }

    public String loginDeniedMessage(String accountOrg, String requestMode) {
        String account = normalizeOrg(accountOrg, ORG_HQ);
        String req = normalizeOrg(requestMode, ORG_HQ);
        if (ORG_AGENCY.equals(account) && ORG_HQ.equals(req)) {
            return "该账户为代投账号，请使用代投专用入口登录";
        }
        if (ORG_HQ.equals(account) && ORG_AGENCY.equals(req)) {
            return "该账户为总部账号，请使用总部协作台登录";
        }
        return "账户与当前入口不匹配，请联系管理员";
    }

    public boolean accountMatchesRequest(String accountOrg, String requestMode) {
        if (!enabled) {
            return true;
        }
        return normalizeOrg(accountOrg, ORG_HQ).equals(normalizeOrg(requestMode, ORG_HQ));
    }

    public boolean tokenMatchesRequest(String tokenOrg, String requestMode) {
        return accountMatchesRequest(tokenOrg, requestMode);
    }

    public Set<String> allowedBuiltinTableIds(String mode) {
        if (!enabled || !ORG_AGENCY.equals(normalizeOrg(mode, ORG_HQ))) {
            return Set.of("tbl_all", "tbl_created", "tbl_near", "tbl_promo", "tbl_adx");
        }
        return agencyTableIds;
    }

    public boolean isBuiltinTableAllowed(String mode, String tableId) {
        if (tableId == null || tableId.isBlank()) {
            return false;
        }
        if (!enabled || !ORG_AGENCY.equals(normalizeOrg(mode, ORG_HQ))) {
            return true;
        }
        return agencyTableIds.contains(tableId.trim());
    }

    public boolean isListTypeAllowed(String mode, String listType) {
        return isListTypeAllowed(mode, listType, null);
    }

    public boolean isListTypeAllowed(String mode, String listType, OpsAuthService.Session session) {
        String normalizedMode = normalizeOrg(mode, ORG_HQ);
        String type = listType == null ? "" : listType.trim().toLowerCase(Locale.ROOT);
        if (OpsTenantFeatureService.CATALOG_LIST_TYPE.equals(type)) {
            return tenantFeatureService != null && tenantFeatureService.canSeeAgencyCatalog(normalizedMode);
        }
        if ("near".equals(type) && enabled && ORG_AGENCY.equals(normalizedMode)) {
            return tenantFeatureService != null && tenantFeatureService.canSeeAgencyHqMirrorNear(normalizedMode);
        }
        if ("adx".equals(type) && enabled && ORG_AGENCY.equals(normalizedMode)) {
            return tenantFeatureService != null && tenantFeatureService.canSeeAgencyHqMirrorAdx(normalizedMode);
        }
        if ("promo".equals(type) && enabled && ORG_AGENCY.equals(normalizedMode)) {
            return tenantFeatureService != null && tenantFeatureService.canSeeAgencyPromoTable(normalizedMode, session);
        }
        if ("agency-promo".equals(type) && enabled && ORG_AGENCY.equals(normalizedMode)) {
            return tenantFeatureService != null && tenantFeatureService.canSeeAgencyAsPromoTable(normalizedMode);
        }
        if (!enabled || !ORG_AGENCY.equals(normalizedMode)) {
            return true;
        }
        for (String tableId : agencyTableIds) {
            if (type.equals(TABLE_TYPE_BY_ID.getOrDefault(tableId, ""))) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> filterBuiltinTables(String mode, List<Map<String, Object>> builtin) {
        return filterBuiltinTables(mode, builtin, null);
    }

    public List<Map<String, Object>> filterBuiltinTables(String mode,
                                                       List<Map<String, Object>> builtin,
                                                       OpsAuthService.Session session) {
        if (builtin == null || builtin.isEmpty()) {
            return List.of();
        }
        if (!enabled || !ORG_AGENCY.equals(normalizeOrg(mode, ORG_HQ))) {
            return builtin;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (tenantFeatureService != null && tenantFeatureService.canSeeAgencyCatalog(mode)) {
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("tableId", OpsTenantFeatureService.CATALOG_TABLE_ID);
            catalog.put("type", OpsTenantFeatureService.CATALOG_LIST_TYPE);
            catalog.put("label", "剧目目录");
            catalog.put("kind", "builtin");
            catalog.put("baseType", OpsTenantFeatureService.CATALOG_LIST_TYPE);
            catalog.put("readOnly", true);
            catalog.put("catalog", true);
            out.add(catalog);
        }
        if (tenantFeatureService != null && tenantFeatureService.canSeeAgencyHqMirrorNear(mode)) {
            out.add(hqReadOnlyBuiltinRow(OpsTenantFeatureService.MIRROR_NEAR_TABLE_ID, "near", "临近发布采集表"));
        }
        if (tenantFeatureService != null && tenantFeatureService.canSeeAgencyHqMirrorAdx(mode)) {
            out.add(hqReadOnlyBuiltinRow(OpsTenantFeatureService.MIRROR_ADX_TABLE_ID, "adx", "ADX 热播榜采集表"));
        }
        if (tenantFeatureService != null && tenantFeatureService.canSeeAgencyAsPromoTable(mode)) {
            out.add(hqReadOnlyBuiltinRow(
                    OpsTenantFeatureService.AGENCY_AS_PROMO_TABLE_ID,
                    "agency-promo",
                    "代投最终评级A/S当日推广链接"));
        }
        if (tenantFeatureService != null && tenantFeatureService.canSeeAgencyPromoTable(mode, session)) {
            for (Map<String, Object> row : builtin) {
                if ("tbl_promo".equals(String.valueOf(row.get("tableId")))) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    public List<Map<String, Object>> filterCustomTables(String mode, List<Map<String, Object>> custom) {
        if (custom == null || custom.isEmpty()) {
            return List.of();
        }
        if (!enabled) {
            return custom;
        }
        String org = normalizeOrg(mode, ORG_HQ);
        return custom.stream()
                .filter(row -> isCustomTableAllowed(org, row))
                .collect(Collectors.toList());
    }

    /** 中文备注：自定义表按 org_type 隔离；历史无 org_type 视为 hq。 */
    public boolean isCustomTableAllowed(String mode, Map<String, Object> row) {
        if (!enabled || row == null) {
            return true;
        }
        String tableOrg = normalizeOrg(String.valueOf(row.getOrDefault("orgType", ORG_HQ)), ORG_HQ);
        return tableOrg.equals(normalizeOrg(mode, ORG_HQ));
    }

    private static Map<String, Object> hqReadOnlyBuiltinRow(String tableId, String type, String label) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableId", tableId);
        row.put("type", type);
        row.put("label", label);
        row.put("kind", "builtin");
        row.put("baseType", type);
        row.put("readOnly", true);
        row.put("hqMirror", true);
        return row;
    }

    public Map<String, Object> publicConfig(String mode) {
        Map<String, Object> data = new LinkedHashMap<>();
        String normalized = normalizeOrg(mode, ORG_HQ);
        data.put("tenantEnabled", enabled);
        data.put("tenantMode", normalized);
        data.put("tenantLabel", labelForMode(normalized));
        data.put("orgType", normalized);
        data.put("remindersEnabled", remindersEnabled(normalized));
        return data;
    }

    /** 中文备注：代投入口不展示总部新剧/夜间/榜单提醒。 */
    public boolean remindersEnabled(String mode) {
        if (!enabled) {
            return true;
        }
        return !ORG_AGENCY.equals(normalizeOrg(mode, ORG_HQ));
    }

    public static String normalizeOrg(String value, String fallback) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (ORG_AGENCY.equals(v) || "代投".equals(value)) {
            return ORG_AGENCY;
        }
        if (ORG_HQ.equals(v) || "总部".equals(value)) {
            return ORG_HQ;
        }
        return fallback == null || fallback.isBlank() ? ORG_HQ : normalizeOrg(fallback, ORG_HQ);
    }

    private static Map<String, String> parseHostModes(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("[,;\\n]")) {
            String item = part.trim();
            if (item.isEmpty() || !item.contains(":")) {
                continue;
            }
            int idx = item.indexOf(':');
            String host = normalizeHost(item.substring(0, idx));
            String mode = normalizeOrg(item.substring(idx + 1), ORG_HQ);
            if (!host.isBlank()) {
                out.put(host, mode);
            }
        }
        return out;
    }

    private static Set<String> parseIdSet(String raw, Set<String> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        return out.isEmpty() ? fallback : Set.copyOf(out);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }
}
