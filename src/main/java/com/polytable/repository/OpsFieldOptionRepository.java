package com.polytable.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：工作台「字段选项」自定义项。
 * 表头「修改字段/列」里新增的选项落这张表，
 * 所有人下次进页面都会拉到，等于全员共享；删除做软删，保留操作痕迹。
 */
@Repository
public class OpsFieldOptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsFieldOptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 返回 fieldKey -> 自定义选项列表（按加入顺序），只含未删除项。
     */
    public Map<String, List<String>> listActive() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT field_key, option_value
                FROM ops_field_option
                WHERE deleted = 0
                ORDER BY id ASC
                """, rs -> {
            String key = rs.getString("field_key");
            String value = rs.getString("option_value");
            if (key == null || value == null) return;
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        });
        return result;
    }

    public List<String> listActive(String fieldKey) {
        return jdbcTemplate.queryForList("""
                SELECT option_value
                FROM ops_field_option
                WHERE field_key = ? AND deleted = 0
                ORDER BY id ASC
                """, String.class, fieldKey);
    }

    public boolean exists(String fieldKey, String value) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM ops_field_option
                WHERE field_key = ? AND option_value = ? AND deleted = 0
                """, Integer.class, fieldKey, value);
        return count != null && count > 0;
    }

    public boolean existsDeleted(String fieldKey, String value) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM ops_field_option
                WHERE field_key = ? AND option_value = ? AND deleted = 1
                """, Integer.class, fieldKey, value);
        return count != null && count > 0;
    }

    /**
     * 新增或恢复（曾被删除过则复活并记录本次操作人）。
     */
    public void add(String fieldKey, String value, String operator) {
        int restored = jdbcTemplate.update("""
                UPDATE ops_field_option
                SET deleted = 0, updated_by = ?
                WHERE field_key = ? AND option_value = ? AND deleted = 1
                """, operator, fieldKey, value);
        if (restored > 0) return;
        jdbcTemplate.update("""
                INSERT INTO ops_field_option (field_key, option_value, created_by, updated_by, deleted)
                VALUES (?, ?, ?, ?, 0)
                """, fieldKey, value, operator, operator);
    }

    public int remove(String fieldKey, String value, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_field_option
                SET deleted = 1, updated_by = ?
                WHERE field_key = ? AND option_value = ? AND deleted = 0
                """, operator, fieldKey, value);
    }

    /**
     * 中文备注：删除自定义字段时，软删该 key 下全部选项痕迹。
     */
    public int removeAll(String fieldKey, String operator) {
        return jdbcTemplate.update("""
                UPDATE ops_field_option
                SET deleted = 1, updated_by = ?
                WHERE field_key = ? AND deleted = 0
                """, operator, fieldKey);
    }
}
