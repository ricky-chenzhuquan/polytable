package com.polytable.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：工作台字段配置（类型 / 默认值 / 带颜色选项），表头「修改字段/列」确定后落库，全员共享。
 */
@Repository
public class OpsFieldConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsFieldConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplate;
    }

    public Map<String, Map<String, Object>> listAll() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        String table = "ops_field_config";
        jdbc().query("""
                SELECT field_key, field_type, default_value, options_json, is_custom, field_title, master_link_json, updated_by, update_time
                FROM %s
                ORDER BY field_key ASC
                """.formatted(table), rs -> {
            Map<String, Object> row = mapRow(rs.getString("field_key"),
                    rs.getString("field_type"),
                    rs.getString("default_value"),
                    rs.getString("options_json"),
                    rs.getInt("is_custom"),
                    rs.getString("field_title"),
                    rs.getString("master_link_json"),
                    rs.getString("updated_by"));
            out.put(rs.getString("field_key"), row);
        });
        return out;
    }

    public Map<String, Object> findByTitle(String fieldTitle) {
        if (fieldTitle == null || fieldTitle.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc().query("""
                SELECT field_key, field_type, default_value, options_json, is_custom, field_title, master_link_json, updated_by
                FROM ops_field_config
                WHERE field_title = ?
                ORDER BY is_custom DESC, field_key ASC
                LIMIT 1
                """, (rs, i) -> mapRow(rs.getString("field_key"),
                rs.getString("field_type"),
                rs.getString("default_value"),
                rs.getString("options_json"),
                rs.getInt("is_custom"),
                rs.getString("field_title"),
                rs.getString("master_link_json"),
                rs.getString("updated_by")), fieldTitle.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> find(String fieldKey) {
        List<Map<String, Object>> rows = jdbc().query("""
                SELECT field_key, field_type, default_value, options_json, is_custom, field_title, master_link_json, updated_by
                FROM ops_field_config
                WHERE field_key = ?
                """, (rs, i) -> mapRow(rs.getString("field_key"),
                rs.getString("field_type"),
                rs.getString("default_value"),
                rs.getString("options_json"),
                rs.getInt("is_custom"),
                rs.getString("field_title"),
                rs.getString("master_link_json"),
                rs.getString("updated_by")), fieldKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(String fieldKey,
                       String fieldType,
                       String defaultValue,
                       String optionsJson,
                       String masterLinkJson,
                       String operator) {
        int updated = jdbcTemplate.update("""
                UPDATE ops_field_config
                SET field_type = ?, default_value = ?, options_json = ?, master_link_json = ?, updated_by = ?
                WHERE field_key = ?
                """, fieldType, defaultValue, optionsJson, masterLinkJson, operator, fieldKey);
        if (updated > 0) return;
        jdbcTemplate.update("""
                INSERT INTO ops_field_config (field_key, field_type, default_value, options_json, master_link_json, is_custom, updated_by)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """, fieldKey, fieldType, defaultValue, optionsJson, masterLinkJson, operator);
    }

    /**
     * 中文备注：新建自定义字段；is_custom=1，标题单独存，值走 custom_fields_json。
     */
    public void insertCustom(String fieldKey,
                             String fieldTitle,
                             String fieldType,
                             String defaultValue,
                             String optionsJson,
                             String operator) {
        jdbcTemplate.update("""
                INSERT INTO ops_field_config
                    (field_key, field_type, default_value, options_json, is_custom, field_title, updated_by)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, fieldKey, fieldType, defaultValue, optionsJson, fieldTitle, operator);
    }

    public void updateTitle(String fieldKey, String fieldTitle, String operator) {
        jdbcTemplate.update("""
                UPDATE ops_field_config
                SET field_title = ?, updated_by = ?
                WHERE field_key = ?
                """, fieldTitle, operator, fieldKey);
    }

    /**
     * 中文备注：仅允许删除自定义字段（is_custom=1）；内置列配置不可删。
     *
     * @return 是否真正删到了行
     */
    public boolean deleteCustom(String fieldKey) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM ops_field_config
                WHERE field_key = ? AND is_custom = 1
                """, fieldKey);
        return deleted > 0;
    }

    private Map<String, Object> mapRow(String fieldKey,
                                       String fieldType,
                                       String defaultValue,
                                       String optionsJson,
                                       int isCustom,
                                       String fieldTitle,
                                       String masterLinkJson,
                                       String updatedBy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fieldKey", fieldKey);
        row.put("fieldType", fieldType);
        row.put("defaultValue", defaultValue);
        row.put("optionsJson", optionsJson);
        row.put("isCustom", isCustom == 1);
        row.put("fieldTitle", fieldTitle == null ? "" : fieldTitle);
        row.put("masterLinkJson", masterLinkJson);
        row.put("updatedBy", updatedBy);
        return row;
    }
}
