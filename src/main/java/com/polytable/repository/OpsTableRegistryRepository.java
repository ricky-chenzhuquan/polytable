package com.polytable.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：用户新建表格注册表。kind=filter 为历史筛选工作表（兼容保留，新表一律 blank）；kind=blank 预留二期空白表。
 */
@Repository
public class OpsTableRegistryRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsTableRegistryRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listActive() {
        return jdbcTemplate.query("""
                SELECT table_id, label, kind, base_type, default_filters_json, sort_order,
                       owner, org_type, created_by, updated_by, deleted, create_time, update_time
                FROM ops_table_registry
                WHERE deleted = 0
                ORDER BY sort_order ASC, create_time ASC, table_id ASC
                """, (rs, i) -> mapRow(rs));
    }

    public Map<String, Object> findActive(String tableId) {
        var rows = jdbcTemplate.query("""
                SELECT table_id, label, kind, base_type, default_filters_json, sort_order,
                       owner, org_type, created_by, updated_by, deleted, create_time, update_time
                FROM ops_table_registry
                WHERE table_id = ? AND deleted = 0
                LIMIT 1
                """, (rs, i) -> mapRow(rs), tableId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int nextSortOrder() {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM ops_table_registry WHERE deleted = 0",
                Integer.class);
        return (max == null ? 0 : max) + 10;
    }

    public void insert(String tableId,
                       String label,
                       String kind,
                       String baseType,
                       String defaultFiltersJson,
                       int sortOrder,
                       String owner,
                       String operator,
                       String orgType) {
        jdbcTemplate.update("""
                INSERT INTO ops_table_registry (
                    table_id, label, kind, base_type, default_filters_json, sort_order,
                    owner, created_by, updated_by, deleted, org_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                tableId, label, kind, baseType, defaultFiltersJson, sortOrder,
                owner, operator, operator, orgType == null || orgType.isBlank() ? "hq" : orgType.trim());
    }

    public boolean rename(String tableId, String label, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_table_registry
                SET label = ?, updated_by = ?
                WHERE table_id = ? AND deleted = 0
                """, label, operator, tableId) > 0;
    }

    public boolean softDelete(String tableId, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_table_registry
                SET deleted = 1, updated_by = ?
                WHERE table_id = ? AND deleted = 0
                """, operator, tableId) > 0;
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableId", rs.getString("table_id"));
        row.put("label", rs.getString("label"));
        row.put("kind", rs.getString("kind"));
        row.put("baseType", rs.getString("base_type"));
        row.put("defaultFiltersJson", rs.getString("default_filters_json"));
        row.put("sortOrder", rs.getInt("sort_order"));
        row.put("owner", rs.getString("owner"));
        row.put("orgType", rs.getString("org_type"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("updatedBy", rs.getString("updated_by"));
        row.put("deleted", rs.getInt("deleted"));
        row.put("createTime", rs.getTimestamp("create_time") == null ? null
                : rs.getTimestamp("create_time").toLocalDateTime().toString());
        row.put("updateTime", rs.getTimestamp("update_time") == null ? null
                : rs.getTimestamp("update_time").toLocalDateTime().toString());
        return row;
    }
}
