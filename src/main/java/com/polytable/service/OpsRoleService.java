package com.polytable.service;

import com.polytable.repository.OpsRoleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OpsRoleService {

    public static final String DEFAULT_ROLE_KEY = "drama-data";
    public static final List<String> MENU_CODES = List.of(
            OpsMenuPermissions.DRAMA_DATA, OpsMenuPermissions.CLIPS, OpsMenuPermissions.ACCOUNTS);
    public static final List<String> ASSIGNABLE_MENUS = List.of(
            OpsMenuPermissions.CLIPS, OpsMenuPermissions.ACCOUNTS);
    public static final List<String> ADMIN_MENUS = List.of("home", "drama-data", "clips", "accounts", "scripts", "rules");

    private final OpsRoleRepository repository;

    public OpsRoleService(OpsRoleRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> listRoles() {
        return repository.listRoles();
    }

    public String roleKeyForAccount(String displayName) {
        return repository.roleKeyForAccount(displayName);
    }

    public Map<String, Object> create(String name, List<String> menus) {
        String normalizedName = normalizeName(name);
        String roleKey = "role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        repository.createRole(roleKey, normalizedName, normalizeRoleMenus(menus));
        return repository.findRole(roleKey);
    }

    public Map<String, Object> update(String roleKey, String name, List<String> menus) {
        Map<String, Object> current = requireRole(roleKey);
        if (Boolean.TRUE.equals(current.get("builtin")) && !DEFAULT_ROLE_KEY.equals(roleKey)) {
            throw new IllegalArgumentException("内置角色不可修改");
        }
        repository.updateRole(roleKey, normalizeName(name), normalizeRoleMenus(menus));
        return repository.findRole(roleKey);
    }

    public void delete(String roleKey) {
        Map<String, Object> role = requireRole(roleKey);
        if (Boolean.TRUE.equals(role.get("builtin"))) {
            throw new IllegalArgumentException("内置角色不可删除");
        }
        if (repository.memberCount(roleKey) > 0) {
            throw new IllegalArgumentException("该角色仍分配给账户，不能删除");
        }
        if (repository.deleteRole(roleKey) <= 0) {
            throw new IllegalArgumentException("删除角色失败");
        }
    }

    public void assignAccountRole(String displayName, String roleKey, String operator) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isBlank()) throw new IllegalArgumentException("账户姓名不能为空");
        requireRole(roleKey);
        String currentRole = repository.roleKeyForAccount(name);
        if (OpsAuthService.ROLE_ADMIN.equalsIgnoreCase(currentRole)) {
            throw new IllegalArgumentException("系统管理员账户不可调整角色");
        }
        if (roleKey.equals(currentRole)) return;
        if (!repository.accountExists(name) || repository.assignRole(name, roleKey,
                operator == null || operator.isBlank() ? "admin" : operator.trim()) <= 0) {
            throw new IllegalArgumentException("账户不存在或角色未变更");
        }
    }

    public List<String> menuPermissions(OpsAuthService.Session session) {
        if (session != null && OpsAuthService.ROLE_ADMIN.equalsIgnoreCase(session.role())) {
            return ADMIN_MENUS;
        }
        if (session == null) return List.of();
        String roleKey = repository.roleKeyForAccount(session.displayName());
        Map<String, Object> role = roleKey.isBlank() ? null : repository.findRole(roleKey);
        Object menus = role == null ? null : role.get("menus");
        if (!(menus instanceof List<?> values)) return List.of();
        List<String> normalized = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String menu) normalized.add(menu);
        }
        return normalizeRoleMenus(normalized);
    }

    public void requireMenu(OpsAuthService.Session session, String menu) {
        if (!OpsMenuPermissions.canAccess(session == null ? "" : session.role(), menuPermissions(session), menu)) {
            throw new IllegalArgumentException("当前账户没有该菜单权限");
        }
    }

    private Map<String, Object> requireRole(String roleKey) {
        String key = roleKey == null ? "" : roleKey.trim();
        if (key.isBlank()) throw new IllegalArgumentException("角色不能为空");
        Map<String, Object> role = repository.findRole(key);
        if (role == null) throw new IllegalArgumentException("角色不存在");
        return role;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 40) throw new IllegalArgumentException("角色名称需为 1 到 40 个字符");
        if (value.equalsIgnoreCase(OpsAuthService.ROLE_ADMIN)) throw new IllegalArgumentException("不能创建 admin 角色");
        return value;
    }

    private static List<String> normalizeRoleMenus(List<String> menus) {
        List<String> normalized = OpsMenuPermissions.normalize(menus);
        if (!normalized.contains(OpsMenuPermissions.DRAMA_DATA)) {
            List<String> withDramaData = new ArrayList<>();
            withDramaData.add(OpsMenuPermissions.DRAMA_DATA);
            withDramaData.addAll(normalized);
            return List.copyOf(withDramaData);
        }
        return normalized;
    }
}
