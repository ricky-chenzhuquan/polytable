package com.polytable.controller;

import com.polytable.service.OpsAccountAgencySync;
import com.polytable.service.OpsAccountFeatureFlags;
import com.polytable.service.OpsAuthService;
import com.polytable.service.OpsEditorGroupMemberService;
import com.polytable.service.OpsLeaderGroupAssignRuleService;
import com.polytable.service.OpsLeaderGroupService;
import com.polytable.service.OpsRoleService;
import com.polytable.service.OpsTenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：协作台账户管理，仅系统管理员可登录操作。
 */
@RestController
@RequestMapping("/api/v1/ops/admin")
public class OpsAdminController {

    private final OpsAuthService opsAuthService;
    private final OpsAccountAgencySync opsAccountAgencySync;
    private final OpsTenantService opsTenantService;
    private final OpsLeaderGroupAssignRuleService leaderGroupAssignRuleService;
    private final OpsEditorGroupMemberService editorGroupMemberService;
    private final OpsLeaderGroupService leaderGroupService;
    private OpsRoleService roleService;

    public OpsAdminController(OpsAuthService opsAuthService,
                              OpsAccountAgencySync opsAccountAgencySync,
                              OpsTenantService opsTenantService,
                              OpsLeaderGroupAssignRuleService leaderGroupAssignRuleService,
                              OpsEditorGroupMemberService editorGroupMemberService,
                              OpsLeaderGroupService leaderGroupService) {
        this.opsAuthService = opsAuthService;
        this.opsAccountAgencySync = opsAccountAgencySync;
        this.opsTenantService = opsTenantService;
        this.leaderGroupAssignRuleService = leaderGroupAssignRuleService;
        this.editorGroupMemberService = editorGroupMemberService;
        this.leaderGroupService = leaderGroupService;
    }

    @Autowired(required = false)
    public void setRoleService(OpsRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/auth/config")
    public ApiResult<Map<String, Object>> config(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tokenTtlHours", opsAuthService.tokenTtlHours());
        data.putAll(opsTenantService.publicConfig(tenantMode(request)));
        data.put("hint", "请输入管理员账号和口令。启停账户、改密、重置口令后立即生效。");
        return ApiResult.ok(data);
    }

    @PostMapping("/auth/login")
    public ApiResult<Map<String, Object>> login(HttpServletRequest request, @RequestBody LoginRequest body) {
        try {
            OpsAuthService.LoginResult result = opsAuthService.login(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.password(),
                    false,
                    tenantMode(request)
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", result.token());
            data.putAll(sessionView(result.session()));
            return ApiResult.ok(data);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    @GetMapping("/auth/me")
    public ApiResult<Map<String, Object>> me(HttpServletRequest request) {
        try {
            return ApiResult.ok(sessionView(requireValid(request)));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    @PostMapping("/auth/logout")
    public ApiResult<Map<String, Object>> logout(HttpServletRequest request) {
        opsAuthService.logout(extractToken(request));
        return ApiResult.ok(Map.of("ok", true));
    }

    @GetMapping("/accounts")
    public ApiResult<Map<String, Object>> listAccounts(HttpServletRequest request,
                                                       @RequestParam(required = false, defaultValue = "1") int page,
                                                       @RequestParam(required = false, defaultValue = "20") int pageSize,
                                                       @RequestParam(required = false) String orgType,
                                                       @RequestParam(required = false) String personnelType,
                                                       @RequestParam(required = false) String keyword) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(2));
            return ApiResult.ok(opsAuthService.listAccountsPage(
                    normalizeOrgFilter(orgType),
                    OpsAuthService.normalizePersonnelType(personnelType),
                    trim(keyword),
                    page,
                    pageSize
            ));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0230", e.getMessage());
        }
    }

    @PostMapping("/accounts/create")
    public ApiResult<Map<String, Object>> createAccount(HttpServletRequest request, @RequestBody AccountCreateRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(2));
            String personnelType = OpsAuthService.normalizePersonnelType(body == null ? null : body.personnelType());
            Map<String, Object> created = opsAuthService.createAccount(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.initials(),
                    body == null ? null : body.password(),
                    body == null || body.enabled() == null || body.enabled(),
                    body == null ? null : body.remark(),
                    admin.displayName(),
                    resolveCreateOrgType(request, body),
                    personnelType,
                    body == null ? null : body.screeningAssignEnabled()
            );
            if (roleService != null) {
                String roleKey = OpsAuthService.ROLE_ADMIN.equals(admin.role()) && body != null
                        && body.roleKey() != null && !body.roleKey().isBlank()
                        ? body.roleKey().trim() : OpsRoleService.DEFAULT_ROLE_KEY;
                roleService.assignAccountRole(String.valueOf(created.get("displayName")), roleKey, admin.displayName());
                created = new LinkedHashMap<>(created);
                created.put("role", roleKey);
            }
            if (!personnelType.isBlank()) {
                created = new LinkedHashMap<>(created);
                created.put("personnelType", personnelType);
                created.put("personnelTypeLabel", OpsAuthService.personnelTypeLabel(personnelType));
            }
            if ("editor".equals(personnelType)
                    && Boolean.TRUE.equals(created.get("enabled"))
                    && Boolean.TRUE.equals(created.get("screeningAssignEnabled"))) {
                created = new LinkedHashMap<>(created);
                try {
                    Map<String, Object> poolRefresh = leaderGroupService.afterEditorJoinedCreateAssignPool();
                    created.put("screeningAssignOptionsSync", poolRefresh.get("createEditorAssignField"));
                    created.put("screeningAssignSchedule", poolRefresh);
                } catch (RuntimeException e) {
                    created.put("screeningAssignSchedule", Map.of("error", e.getMessage() == null ? "登记失败" : e.getMessage()));
                }
            }
            return ApiResult.ok(created);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/enabled")
    public ApiResult<Map<String, Object>> setEnabled(HttpServletRequest request, @RequestBody EnabledRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(2));
            if (body == null || body.enabled() == null) {
                return ApiResult.fail("A0400", "缺少 enabled");
            }
            opsAuthService.setAccountEnabled(body.displayName(), body.enabled(), admin.displayName());
            return ApiResult.ok(Map.of("ok", true, "displayName", trim(body.displayName()), "enabled", body.enabled()));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/password")
    public ApiResult<Map<String, Object>> changePassword(HttpServletRequest request, @RequestBody PasswordRequest body) {
        try {
            OpsAuthService.Session admin = requireAdmin(request);
            opsAuthService.changeAccountPassword(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.newPassword(),
                    admin.displayName()
            );
            return ApiResult.ok(Map.of("ok", true, "displayName", trim(body == null ? null : body.displayName())));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/reset-password")
    public ApiResult<Map<String, Object>> resetPassword(HttpServletRequest request, @RequestBody NameRequest body) {
        try {
            OpsAuthService.Session admin = requireAdmin(request);
            Map<String, Object> result = opsAuthService.resetAccountPassword(
                    body == null ? null : body.displayName(),
                    admin.displayName()
            );
            return ApiResult.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    /** 手动将代投账户推送到代投库（单账户或全部 org_type=agency）。 */
    @PostMapping("/accounts/sync-to-agency")
    public ApiResult<Map<String, Object>> syncAccountsToAgency(HttpServletRequest request,
                                                               @RequestBody(required = false) NameRequest body) {
        try {
            requireAdmin(request);
            String name = body == null ? null : trim(body.displayName());
            if (name != null && !name.isBlank()) {
                OpsAccountAgencySync.AccountSyncResult result = opsAccountAgencySync.syncAccount(name);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("displayName", name);
                data.put("agencySyncAttempted", result.attempted());
                data.put("agencySyncSuccess", result.success());
                data.put("agencySyncChannel", result.channel());
                data.put("agencySyncMessage", result.message());
                data.put("remotePushEnabled", opsAccountAgencySync.remotePushEnabled());
                if (result.attempted() && !result.success()) {
                    return ApiResult.fail("A0500", "代投库同步失败：" + result.message(), data);
                }
                return ApiResult.ok(data);
            }
            return ApiResult.ok(opsAccountAgencySync.syncAllAgencyAccounts());
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/org-type")
    public ApiResult<Map<String, Object>> updateOrgType(HttpServletRequest request, @RequestBody OrgTypeRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(2));
            if (body == null || trim(body.orgType()).isBlank()) {
                return ApiResult.fail("A0400", "缺少 orgType");
            }
            Map<String, Object> result = opsAuthService.updateAccountOrgType(
                    body.displayName(),
                    body.orgType(),
                    admin.displayName()
            );
            return ApiResult.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/personnel-type")
    public ApiResult<Map<String, Object>> updatePersonnelType(HttpServletRequest request,
                                                               @RequestBody PersonnelTypeRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(2));
            Map<String, Object> result = opsAuthService.updateAccountPersonnelSettings(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.personnelType(),
                    body == null ? null : body.screeningAssignEnabled(),
                    admin.displayName());
            if (Boolean.TRUE.equals(result.get("screeningAssignmentEnabledTransitionedToEnabled"))) {
                try {
                    Map<String, Object> poolRefresh = leaderGroupService.afterEditorJoinedCreateAssignPool();
                    result.put("screeningAssignOptionsSync", poolRefresh.get("createEditorAssignField"));
                    result.put("screeningAssignSchedule", poolRefresh);
                } catch (RuntimeException e) {
                    result.put("screeningAssignOptionsSync", Map.of("error", e.getMessage() == null ? "同步失败" : e.getMessage()));
                    result.put("screeningAssignSchedule", Map.of("error", e.getMessage() == null ? "登记失败" : e.getMessage()));
                }
            } else {
                try {
                    result.put("screeningAssignOptionsSync", leaderGroupService.ensureCreateEditorAssignFieldOptions());
                } catch (RuntimeException e) {
                    result.put("screeningAssignOptionsSync", Map.of("error", e.getMessage() == null ? "同步失败" : e.getMessage()));
                }
            }
            return ApiResult.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    /** 代投账户功能权限：如「当日推广参考表」可见性。 */
    @PostMapping("/accounts/feature-flags")
    public ApiResult<Map<String, Object>> updateFeatureFlags(HttpServletRequest request,
                                                             @RequestBody FeatureFlagsRequest body) {
        try {
            OpsAuthService.Session admin = requireAdmin(request);
            if (body == null || trim(body.displayName()).isBlank()) {
                return ApiResult.fail("A0400", "缺少 displayName");
            }
            Map<String, Boolean> flags = new LinkedHashMap<>();
            if (body.promoTable() != null) {
                flags.put(OpsAccountFeatureFlags.PROMO_TABLE, body.promoTable());
            }
            Map<String, Object> result = opsAuthService.updateAccountFeatureFlags(
                    body.displayName(),
                    flags,
                    admin.displayName()
            );
            return ApiResult.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/accounts/initials")
    public ApiResult<Map<String, Object>> updateInitials(HttpServletRequest request, @RequestBody InitialsRequest body) {
        try {
            OpsAuthService.Session admin = requireAdmin(request);
            Map<String, Object> result = opsAuthService.updateAccountInitials(
                    body == null ? null : body.displayName(),
                    body == null ? null : body.initials(),
                    admin.displayName()
            );
            return ApiResult.ok(result);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @GetMapping("/leader-group/assign-rules")
    public ApiResult<Map<String, Object>> listAssignRules(HttpServletRequest request,
                                                          @RequestParam(required = false) String from,
                                                          @RequestParam(required = false) String to,
                                                          @RequestParam(required = false) String assignBy) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            return ApiResult.ok(leaderGroupAssignRuleService.listRules(parseDate(from), parseDate(to), assignBy));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/assign-rules/create")
    public ApiResult<Map<String, Object>> createAssignRule(HttpServletRequest request,
                                                           @RequestBody AssignRuleRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            Map<String, Object> created = leaderGroupAssignRuleService.createRule(
                    parseRequiredDate(body == null ? null : body.deliveryDate()),
                    body == null ? null : body.assignBy(),
                    body == null ? List.of() : body.excludedGroups(),
                    body == null ? null : body.expectedDramaCount(),
                    body == null ? null : body.remark(),
                    body == null || body.enabled() == null || body.enabled(),
                    admin.displayName()
            );
            return ApiResult.ok(created);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/assign-rules/update")
    public ApiResult<Map<String, Object>> updateAssignRule(HttpServletRequest request,
                                                           @RequestBody AssignRuleUpdateRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            if (body == null || body.id() == null) {
                return ApiResult.fail("A0400", "缺少规则 id");
            }
            Map<String, Object> updated = leaderGroupAssignRuleService.updateRule(
                    body.id(),
                    parseRequiredDate(body.deliveryDate()),
                    body.assignBy(),
                    body.excludedGroups() == null ? List.of() : body.excludedGroups(),
                    body.expectedDramaCount(),
                    body.remark(),
                    body.enabled() == null || body.enabled(),
                    admin.displayName()
            );
            return ApiResult.ok(updated);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/assign-rules/delete")
    public ApiResult<Map<String, Object>> deleteAssignRule(HttpServletRequest request,
                                                           @RequestBody AssignRuleDeleteRequest body) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            if (body == null || body.id() == null) {
                return ApiResult.fail("A0400", "缺少规则 id");
            }
            leaderGroupAssignRuleService.deleteRule(body.id());
            return ApiResult.ok(Map.of("ok", true, "id", body.id()));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/assign-rules/rebalance")
    public ApiResult<Map<String, Object>> rebalanceAssignDay(HttpServletRequest request,
                                                             @RequestBody AssignRuleRebalanceRequest body) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            LocalDate day = parseRequiredDate(body == null ? null : body.deliveryDate());
            return ApiResult.ok(leaderGroupAssignRuleService.rebalanceDay(day, body == null ? null : body.assignBy()));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @GetMapping("/leader-group/editor-members")
    public ApiResult<Map<String, Object>> listEditorMembers(HttpServletRequest request) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            return ApiResult.ok(editorGroupMemberService.listMembers());
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/editor-members/update")
    public ApiResult<Map<String, Object>> updateEditorMember(HttpServletRequest request,
                                                              @RequestBody EditorMemberUpdateRequest body) {
        try {
            OpsAuthService.Session admin = requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            Map<String, Object> updated = editorGroupMemberService.updateMember(
                    body == null ? null : body.editorName(),
                    body == null ? null : body.groupName(),
                    admin.displayName()
            );
            String group = body == null ? "" : trim(body.groupName());
            if (!group.isBlank() && !OpsEditorGroupMemberService.GROUP_DEFERRED.equals(group)) {
                Map<String, Object> poolRefresh = leaderGroupService.afterEditorJoinedCreateAssignPool();
                updated = new LinkedHashMap<>(updated);
                updated.put("createAssignPoolRefresh", poolRefresh);
            }
            return ApiResult.ok(updated);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    @PostMapping("/leader-group/sync-editor-groups")
    public ApiResult<Map<String, Object>> syncEditorGroups(HttpServletRequest request) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            return ApiResult.ok(leaderGroupService.syncGroupsFromAssignedEditors());
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    /** 暂停恢复上线：同步剪辑→小组，再补空位（不挪动已手动分配/已填剪辑的行）。 */
    @PostMapping("/leader-group/resume-after-pause")
    public ApiResult<Map<String, Object>> resumeAfterPause(HttpServletRequest request,
                                                           @RequestBody(required = false) ResumeAfterPauseRequest body) {
        try {
            requireMenu(request, OpsRoleService.MENU_CODES.get(1));
            LocalDate start = parseOptionalDate(body == null ? null : body.startDate());
            LocalDate end = parseOptionalDate(body == null ? null : body.endDate());
            if (body != null && body.deliveryDate() != null && !body.deliveryDate().isBlank()) {
                LocalDate day = parseRequiredDate(body.deliveryDate());
                start = day;
                end = day;
            }
            return ApiResult.ok(leaderGroupService.resumeAfterPause(start, end));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail("A0400", e.getMessage());
        }
    }

    private OpsAuthService.Session requireAdmin(HttpServletRequest request) {
        return opsAuthService.requireAdminSession(extractToken(request), tenantMode(request));
    }

    private OpsAuthService.Session requireValid(HttpServletRequest request) {
        return opsAuthService.requireValidSession(extractToken(request), tenantMode(request));
    }

    private OpsAuthService.Session requireMenu(HttpServletRequest request, String menu) {
        if (roleService == null) return requireAdmin(request);
        OpsAuthService.Session session = requireValid(request);
        roleService.requireMenu(session, menu);
        return session;
    }

    private Map<String, Object> sessionView(OpsAuthService.Session session) {
        Map<String, Object> data = new LinkedHashMap<>(opsAuthService.sessionView(session));
        if (roleService != null) {
            data.put("menuPermissions", roleService.menuPermissions(session));
            String roleKey = session.role();
            if (!OpsAuthService.ROLE_ADMIN.equals(roleKey)) {
                roleKey = roleService.roleKeyForAccount(session.displayName());
            }
            data.put("roleKey", roleKey);
        }
        return data;
    }

    private String tenantMode(HttpServletRequest request) {
        return opsTenantService.resolveMode(request);
    }

    private String resolveCreateOrgType(HttpServletRequest request, AccountCreateRequest body) {
        if (body != null && body.orgType() != null && !body.orgType().isBlank()) {
            return OpsTenantService.normalizeOrg(body.orgType(), OpsAuthService.ORG_HQ);
        }
        return OpsAuthService.ORG_HQ;
    }

    private static String normalizeOrgFilter(String orgType) {
        String v = orgType == null ? "" : orgType.trim().toLowerCase();
        if ("agency".equals(v) || "代投".equals(orgType)) {
            return OpsAuthService.ORG_AGENCY;
        }
        if ("hq".equals(v) || "非代投".equals(orgType) || "自投".equals(orgType)) {
            return OpsAuthService.ORG_HQ;
        }
        return "";
    }

    private String extractToken(HttpServletRequest request) {
        String header = trim(request.getHeader("X-Ops-Token"));
        if (!header.isBlank()) {
            return header;
        }
        String auth = trim(request.getHeader("Authorization"));
        if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return trim(request.getParameter("token"));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static LocalDate parseDate(String text) {
        String v = trim(text);
        if (v.isBlank()) {
            return null;
        }
        return LocalDate.parse(v);
    }

    private static LocalDate parseRequiredDate(String text) {
        LocalDate day = parseDate(text);
        if (day == null) {
            throw new IllegalArgumentException("请选择日期");
        }
        return day;
    }

    private static LocalDate parseOptionalDate(String text) {
        return parseDate(text);
    }

    public record LoginRequest(String displayName, String password) {
    }

    public record AccountCreateRequest(String displayName, String initials, String password, Boolean enabled,
                                       String remark, String orgType, String personnelType,
                                       Boolean screeningAssignEnabled, String roleKey) {
    }

    public record EnabledRequest(String displayName, Boolean enabled) {
    }

    public record PasswordRequest(String displayName, String newPassword) {
    }

    public record NameRequest(String displayName) {
    }

    public record InitialsRequest(String displayName, String initials) {
    }

    public record OrgTypeRequest(String displayName, String orgType) {
    }

    public record PersonnelTypeRequest(String displayName, String personnelType, Boolean screeningAssignEnabled) {
    }

    public record FeatureFlagsRequest(String displayName, Boolean promoTable) {
    }

    public record AssignRuleRequest(String deliveryDate,
                                    String assignBy,
                                    List<String> excludedGroups,
                                    Integer expectedDramaCount,
                                    String remark,
                                    Boolean enabled) {
    }

    public record AssignRuleUpdateRequest(Long id,
                                          String deliveryDate,
                                          String assignBy,
                                          List<String> excludedGroups,
                                          Integer expectedDramaCount,
                                          String remark,
                                          Boolean enabled) {
    }

    public record AssignRuleDeleteRequest(Long id) {
    }

    public record AssignRuleRebalanceRequest(String deliveryDate, String assignBy) {
    }

    public record EditorMemberUpdateRequest(String editorName, String groupName) {
    }

    public record ResumeAfterPauseRequest(String deliveryDate, String startDate, String endDate) {
    }
}
