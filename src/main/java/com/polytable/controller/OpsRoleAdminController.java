package com.polytable.controller;

import com.polytable.service.OpsAuthService;
import com.polytable.service.OpsRoleService;
import com.polytable.service.OpsTenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ops/admin")
public class OpsRoleAdminController {

    private final OpsAuthService authService;
    private final OpsRoleService roleService;
    private final OpsTenantService tenantService;

    public OpsRoleAdminController(OpsAuthService authService, OpsRoleService roleService,
                                  OpsTenantService tenantService) {
        this.authService = authService;
        this.roleService = roleService;
        this.tenantService = tenantService;
    }

    @GetMapping("/roles")
    public ApiResult<Map<String, Object>> listRoles(HttpServletRequest request) {
        try {
            requireAdmin(request);
            return ApiResult.ok(Map.of("roles", roleService.listRoles(), "assignableMenus", OpsRoleService.ASSIGNABLE_MENUS));
        } catch (IllegalArgumentException error) {
            return ApiResult.fail("A0230", error.getMessage());
        }
    }

    @PostMapping("/roles/create")
    public ApiResult<Map<String, Object>> createRole(HttpServletRequest request, @RequestBody RoleRequest body) {
        try {
            requireAdmin(request);
            if (body == null) return ApiResult.fail("A0400", "缺少角色信息");
            return ApiResult.ok(Map.of("role", roleService.create(body.name(), body.menus())));
        } catch (IllegalArgumentException error) {
            return ApiResult.fail("A0400", error.getMessage());
        } catch (DataIntegrityViolationException error) {
            return ApiResult.fail("A0400", "角色名称已存在");
        }
    }

    @PostMapping("/roles/update")
    public ApiResult<Map<String, Object>> updateRole(HttpServletRequest request, @RequestBody RoleRequest body) {
        try {
            requireAdmin(request);
            if (body == null) return ApiResult.fail("A0400", "缺少角色信息");
            return ApiResult.ok(Map.of("role", roleService.update(body.roleKey(), body.name(), body.menus())));
        } catch (IllegalArgumentException error) {
            return ApiResult.fail("A0400", error.getMessage());
        } catch (DataIntegrityViolationException error) {
            return ApiResult.fail("A0400", "角色名称已存在");
        }
    }

    @DeleteMapping("/roles/{roleKey}")
    public ApiResult<Map<String, Object>> deleteRole(HttpServletRequest request, @PathVariable String roleKey) {
        try {
            requireAdmin(request);
            roleService.delete(roleKey);
            return ApiResult.ok(Map.of("ok", true, "roleKey", roleKey));
        } catch (IllegalArgumentException error) {
            return ApiResult.fail("A0400", error.getMessage());
        }
    }

    @PostMapping("/accounts/role")
    public ApiResult<Map<String, Object>> assignAccountRole(HttpServletRequest request,
                                                            @RequestBody AccountRoleRequest body) {
        try {
            OpsAuthService.Session admin = requireAdmin(request);
            if (body == null) return ApiResult.fail("A0400", "缺少账户角色信息");
            roleService.assignAccountRole(body.displayName(), body.roleKey(), admin.displayName());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("displayName", body.displayName());
            result.put("roleKey", body.roleKey());
            return ApiResult.ok(result);
        } catch (IllegalArgumentException error) {
            return ApiResult.fail("A0400", error.getMessage());
        }
    }

    private OpsAuthService.Session requireAdmin(HttpServletRequest request) {
        return authService.requireAdminSession(extractToken(request), tenantService.resolveMode(request));
    }

    private static String extractToken(HttpServletRequest request) {
        String token = request.getHeader("X-Ops-Token");
        if (token != null && !token.isBlank()) return token.trim();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }

    public record RoleRequest(String roleKey, String name, List<String> menus) {}

    public record AccountRoleRequest(String displayName, String roleKey) {}
}
