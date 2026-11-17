package com.polytable.repository;

import com.polytable.service.OpsMenuPermissions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class OpsRoleRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;

    public OpsRoleRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listRoles() {
        return jdbcTemplate.query("""
                SELECT r.role_key, r.role_name, r.menu_permissions_json, r.builtin,
                       (SELECT COUNT(1) FROM ops_account a WHERE a.role = r.role_key) AS member_count
                FROM ops_role r
                ORDER BY r.builtin DESC, r.role_name ASC
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roleKey", rs.getString("role_key"));
            row.put("name", rs.getString("role_name"));
            row.put("menus", parseMenus(rs.getString("menu_permissions_json")));
            row.put("builtin", rs.getInt("builtin") == 1);
            row.put("memberCount", rs.getInt("member_count"));
            return row;
        });
    }

    public Map<String, Object> findRole(String roleKey) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT role_key, role_name, menu_permissions_json, builtin,
                       (SELECT COUNT(1) FROM ops_account a WHERE a.role = ops_role.role_key) AS member_count
                FROM ops_role WHERE role_key = ? LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("roleKey", rs.getString("role_key"));
            row.put("name", rs.getString("role_name"));
            row.put("menus", parseMenus(rs.getString("menu_permissions_json")));
            row.put("builtin", rs.getInt("builtin") == 1);
            row.put("memberCount", rs.getInt("member_count"));
            return row;
        }, roleKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String roleKeyForAccount(String displayName) {
        List<String> keys = jdbcTemplate.query("SELECT role FROM ops_account WHERE display_name = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), displayName);
        return keys.isEmpty() ? "" : keys.get(0);
    }

    public boolean accountExists(String displayName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ops_account WHERE display_name = ?", Integer.class, displayName);
        return count != null && count > 0;
    }

    public void createRole(String roleKey, String name, List<String> menus) {
        jdbcTemplate.update("""
                INSERT INTO ops_role (role_key, role_name, menu_permissions_json, builtin)
                VALUES (?, ?, ?, 0)
                """, roleKey, name, serializeMenus(menus));
    }

    public int updateRole(String roleKey, String name, List<String> menus) {
        return jdbcTemplate.update("""
                UPDATE ops_role SET role_name = ?, menu_permissions_json = ? WHERE role_key = ?
                """, name, serializeMenus(menus), roleKey);
    }

    public int deleteRole(String roleKey) {
        return jdbcTemplate.update("DELETE FROM ops_role WHERE role_key = ? AND builtin = 0", roleKey);
    }

    public int assignRole(String displayName, String roleKey, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account SET role = ?, updated_by = ?
                WHERE display_name = ? AND IFNULL(role, 'user') <> 'admin'
                """, roleKey, updatedBy, displayName);
    }

    public int memberCount(String roleKey) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ops_account WHERE role = ?",
                Integer.class, roleKey);
        return count == null ? 0 : count;
    }

    private static List<String> parseMenus(String value) {
        try {
            return OpsMenuPermissions.normalize(MAPPER.readValue(value, STRING_LIST));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String serializeMenus(List<String> menus) {
        try {
            return MAPPER.writeValueAsString(OpsMenuPermissions.normalize(menus));
        } catch (Exception error) {
            throw new IllegalStateException("角色菜单权限序列化失败", error);
        }
    }
}
