package com.polytable.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中文备注：每张数据表的共享视图列表（含顺序）。
 * 拖拽排序 / 新建 / 删除后落库，其他登录用户刷新即可看到同一套 tabs。
 */
@Repository
public class OpsTableViewsRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsTableViewsRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String findViewsJson(String tableId) {
        var rows = jdbcTemplate.query(
                "SELECT views_json FROM ops_table_views WHERE table_id = ?",
                (rs, i) -> rs.getString("views_json"),
                tableId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 中文备注：已删除视图 id 列表（JSON 数组）；无行或空列时返回 null。
     */
    public String findTombstoneJson(String tableId) {
        var rows = jdbcTemplate.query(
                "SELECT tombstone_json FROM ops_table_views WHERE table_id = ?",
                (rs, i) -> rs.getString("tombstone_json"),
                tableId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 中文备注：给前端做轻量变更探测；无行时返回 null。
     */
    public java.time.LocalDateTime findUpdateTime(String tableId) {
        var rows = jdbcTemplate.query(
                "SELECT update_time FROM ops_table_views WHERE table_id = ?",
                (rs, i) -> rs.getTimestamp("update_time") == null
                        ? null
                        : rs.getTimestamp("update_time").toLocalDateTime(),
                tableId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(String tableId, String viewsJson, String operator) {
        upsert(tableId, viewsJson, null, operator);
    }

    /**
     * @param tombstoneJson null 表示不改墓碑列；非 null 则一并写入（可为 "[]"）
     */
    public void upsert(String tableId, String viewsJson, String tombstoneJson, String operator) {
        int updated;
        if (tombstoneJson != null) {
            updated = jdbcTemplate.update("""
                    UPDATE ops_table_views
                    SET views_json = ?, tombstone_json = ?, updated_by = ?, update_time = CURRENT_TIMESTAMP
                    WHERE table_id = ?
                    """, viewsJson, tombstoneJson, operator, tableId);
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE ops_table_views
                    SET views_json = ?, updated_by = ?, update_time = CURRENT_TIMESTAMP
                    WHERE table_id = ?
                    """, viewsJson, operator, tableId);
        }
        if (updated > 0) return;
        jdbcTemplate.update("""
                INSERT INTO ops_table_views (table_id, views_json, tombstone_json, updated_by)
                VALUES (?, ?, ?, ?)
                """, tableId, viewsJson, tombstoneJson == null ? "[]" : tombstoneJson, operator);
    }

    public List<String> listTableIds() {
        return jdbcTemplate.queryForList("SELECT table_id FROM ops_table_views ORDER BY table_id ASC", String.class);
    }

    public boolean deleteByTableId(String tableId) {
        return jdbcTemplate.update("DELETE FROM ops_table_views WHERE table_id = ?", tableId) > 0;
    }
}
