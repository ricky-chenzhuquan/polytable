package com.polytable.service;

import com.polytable.repository.OpsAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 协作空间租户功能权限：主实例记录目录（只读）、当日分享参考表（按账户授权）。
 */
@Service
public class OpsTenantFeatureService {

    public static final String CATALOG_TABLE_ID = "tbl_hq_catalog";
    public static final String CATALOG_LIST_TYPE = "hq-catalog";

    /** 协作空间只读镜像：主实例临近发布（tbl_near）、ADX 排行（tbl_adx）。 */
    public static final String MIRROR_NEAR_TABLE_ID = "tbl_near";
    public static final String MIRROR_ADX_TABLE_ID = "tbl_adx";
    /** 协作空间当日分享链参考（读主实例 + 协作账号采链）。 */
    public static final String AGENCY_AS_PROMO_TABLE_ID = "tbl_agency_promo";

    private final OpsAccountRepository accountRepository;
    private final boolean agencyCatalogEnabled;
    private final boolean agencyHqMirrorNearEnabled;
    private final boolean agencyHqMirrorAdxEnabled;

    public OpsTenantFeatureService(OpsAccountRepository accountRepository,
                                   @Value("${ops.tenant.agency-catalog-enabled:true}") boolean agencyCatalogEnabled,
                                   @Value("${ops.tenant.agency-hq-mirror-near-enabled:true}") boolean agencyHqMirrorNearEnabled,
                                   @Value("${ops.tenant.agency-hq-mirror-adx-enabled:true}") boolean agencyHqMirrorAdxEnabled) {
        this.accountRepository = accountRepository;
        this.agencyCatalogEnabled = agencyCatalogEnabled;
        this.agencyHqMirrorNearEnabled = agencyHqMirrorNearEnabled;
        this.agencyHqMirrorAdxEnabled = agencyHqMirrorAdxEnabled;
    }

    public boolean agencyCatalogEnabled() {
        return agencyCatalogEnabled;
    }

    public boolean canSeeAgencyCatalog(String tenantMode) {
        return agencyCatalogEnabled
                && OpsTenantService.ORG_AGENCY.equals(OpsTenantService.normalizeOrg(tenantMode, OpsTenantService.ORG_HQ));
    }

    public boolean canSeeAgencyHqMirrorNear(String tenantMode) {
        return agencyHqMirrorNearEnabled && canSeeAgencyCatalog(tenantMode);
    }

    public boolean canSeeAgencyHqMirrorAdx(String tenantMode) {
        return agencyHqMirrorAdxEnabled && canSeeAgencyCatalog(tenantMode);
    }

    public boolean canSeeAgencyAsPromoTable(String tenantMode) {
        return canSeeAgencyCatalog(tenantMode);
    }

    /** 中文备注：代投跨库只读列表（目录 / 快上线 / ADX），禁止写 HQ。 */
    public boolean isAgencyHqReadOnlyListType(String tenantMode, String listType) {
        if (!OpsTenantService.ORG_AGENCY.equals(OpsTenantService.normalizeOrg(tenantMode, OpsTenantService.ORG_HQ))) {
            return false;
        }
        String type = listType == null ? "" : listType.trim().toLowerCase();
        if (CATALOG_LIST_TYPE.equals(type)) {
            return canSeeAgencyCatalog(tenantMode);
        }
        if ("near".equals(type)) {
            return canSeeAgencyHqMirrorNear(tenantMode);
        }
        if ("adx".equals(type)) {
            return canSeeAgencyHqMirrorAdx(tenantMode);
        }
        if ("agency-promo".equals(type)) {
            return canSeeAgencyAsPromoTable(tenantMode);
        }
        return false;
    }

    public boolean canSeeAgencyPromoTable(String tenantMode, OpsAuthService.Session session) {
        if (!OpsTenantService.ORG_AGENCY.equals(OpsTenantService.normalizeOrg(tenantMode, OpsTenantService.ORG_HQ))) {
            return true;
        }
        if (session != null && OpsAuthService.ROLE_ADMIN.equalsIgnoreCase(session.role())) {
            return true;
        }
        Map<String, Boolean> flags = loadFeatureFlags(session);
        return OpsAccountFeatureFlags.enabled(flags, OpsAccountFeatureFlags.PROMO_TABLE);
    }

    public Map<String, Boolean> loadFeatureFlags(OpsAuthService.Session session) {
        if (session == null || session.displayName() == null || session.displayName().isBlank()) {
            return Map.of();
        }
        Map<String, Object> account = accountRepository.findByDisplayName(session.displayName());
        if (account == null) {
            return Map.of();
        }
        return OpsAccountFeatureFlags.parse(account.get("featureFlags"));
    }

    public Map<String, Object> sessionFeaturePayload(String tenantMode, OpsAuthService.Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agencyCatalogEnabled", canSeeAgencyCatalog(tenantMode));
        data.put("agencyHqMirrorNearEnabled", canSeeAgencyHqMirrorNear(tenantMode));
        data.put("agencyHqMirrorAdxEnabled", canSeeAgencyHqMirrorAdx(tenantMode));
        data.put("agencyPromoTableVisible", canSeeAgencyPromoTable(tenantMode, session));
        data.put("agencyAsPromoTableVisible", canSeeAgencyAsPromoTable(tenantMode));
        data.put("featureFlags", loadFeatureFlags(session));
        return data;
    }
}
