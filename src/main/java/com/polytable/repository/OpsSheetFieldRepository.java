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
 * 中文备注：空白表列定义，按 table_id 隔离。
 */
@Repository
public class OpsSheetFieldRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsSheetFieldRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listActive(String tableId) {
        return jdbcTemplate.query("""
                SELECT table_id, field_key, field_title, field_type, default_value, options_json, master_link_json,
                       sort_order, deleted, created_by, updated_by, create_time, update_time
                FROM ops_sheet_field
                WHERE table_id = ? AND deleted = 0
                ORDER BY sort_order ASC, field_key ASC
                """, (rs, i) -> mapRow(rs), tableId);
    }

    public Map<String, Object> findActive(String tableId, String fieldKey) {
        var rows = jdbcTemplate.query("""
                SELECT table_id, field_key, field_title, field_type, default_value, options_json, master_link_json,
                       sort_order, deleted, created_by, updated_by, create_time, update_time
                FROM ops_sheet_field
                WHERE table_id = ? AND field_key = ? AND deleted = 0
                LIMIT 1
                """, (rs, i) -> mapRow(rs), tableId, fieldKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int nextSortOrder(String tableId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM ops_sheet_field WHERE table_id = ? AND deleted = 0",
                Integer.class, tableId);
        return (max == null ? 0 : max) + 10;
    }

    public void insert(String tableId,
                       String fieldKey,
                       String fieldTitle,
                       String fieldType,
                       String defaultValue,
                       int sortOrder,
                       String operator) {
        insert(tableId, fieldKey, fieldTitle, fieldType, defaultValue, null, sortOrder, operator);
    }

    public void insert(String tableId,
                       String fieldKey,
                       String fieldTitle,
                       String fieldType,
                       String defaultValue,
                       String optionsJson,
                       int sortOrder,
                       String operator) {
        jdbcTemplate.update("""
                INSERT INTO ops_sheet_field (
                    table_id, field_key, field_title, field_type, default_value, options_json, sort_order,
                    deleted, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                tableId, fieldKey, fieldTitle, fieldType, defaultValue, optionsJson, sortOrder, operator, operator);
    }

    public boolean rename(String tableId, String fieldKey, String fieldTitle, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_sheet_field
                SET field_title = ?, updated_by = ?
                WHERE table_id = ? AND field_key = ? AND deleted = 0
                """, fieldTitle, operator, tableId, fieldKey) > 0;
    }

    public boolean updateMeta(String tableId,
                              String fieldKey,
                              String fieldTitle,
                              String fieldType,
                              String defaultValue,
                              String optionsJson,
                              String masterLinkJson,
                              String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_sheet_field
                SET field_title = ?, field_type = ?, default_value = ?, options_json = ?, master_link_json = ?, updated_by = ?
                WHERE table_id = ? AND field_key = ? AND deleted = 0
                """, fieldTitle, fieldType, defaultValue, optionsJson, masterLinkJson, operator, tableId, fieldKey) > 0;
    }

    public boolean updateMeta(String tableId,
                              String fieldKey,
                              String fieldTitle,
                              String fieldType,
                              String defaultValue,
                              String optionsJson,
                              String operator) {
        return updateMeta(tableId, fieldKey, fieldTitle, fieldType, defaultValue, optionsJson, null, operator);
    }

    public boolean softDelete(String tableId, String fieldKey, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_sheet_field
                SET deleted = 1, updated_by = ?
                WHERE table_id = ? AND field_key = ? AND deleted = 0
                """, operator, tableId, fieldKey) > 0;
    }

    public int softDeleteAll(String tableId, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_sheet_field
                SET deleted = 1, updated_by = ?
                WHERE table_id = ? AND deleted = 0
                """, operator, tableId);
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableId", rs.getString("table_id"));
        row.put("fieldKey", rs.getString("field_key"));
        row.put("fieldTitle", rs.getString("field_title"));
        row.put("fieldType", rs.getString("field_type"));
        row.put("defaultValue", rs.getString("default_value"));
        try {
            row.put("optionsJson", rs.getString("options_json"));
        } catch (SQLException ignored) {
            row.put("optionsJson", null);
        }
        try {
            row.put("masterLinkJson", rs.getString("master_link_json"));
        } catch (SQLException ignored) {
            row.put("masterLinkJson", null);
        }
        row.put("sortOrder", rs.getInt("sort_order"));
        row.put("deleted", rs.getInt("deleted"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("updatedBy", rs.getString("updated_by"));
        return row;
    }
}
