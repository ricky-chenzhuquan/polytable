package com.polytable.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：工作台协作在场落库。多实例 / 重启后，只要心跳未过期，其他登录用户仍能看见谁在哪张表、哪个格子。
 */
@Repository
public class OpsPresenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpsPresenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String displayName,
                       String orgType,
                       String tableId,
                       String tableLabel,
                       String dramaType,
                       String opsKey,
                       String seriesName,
                       String colKey,
                       String colLabel,
                       String mode,
                       String selJson,
                       Instant lastSeen) {
        Timestamp ts = Timestamp.from(lastSeen == null ? Instant.now() : lastSeen);
        String org = normalizeOrgType(orgType);
        int updated = jdbcTemplate.update("""
                UPDATE ops_user_presence
                SET org_type = ?, table_id = ?, table_label = ?, drama_type = ?, ops_key = ?, series_name = ?,
                    col_key = ?, col_label = ?, mode = ?, sel_json = ?, last_seen = ?
                WHERE display_name = ?
                """, org, tableId, tableLabel, dramaType, opsKey, seriesName, colKey, colLabel, mode, selJson, ts, displayName);
        if (updated > 0) return;
        jdbcTemplate.update("""
                INSERT INTO ops_user_presence
                (display_name, org_type, table_id, table_label, drama_type, ops_key, series_name, col_key, col_label, mode, sel_json, last_seen)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, displayName, org, tableId, tableLabel, dramaType, opsKey, seriesName, colKey, colLabel, mode, selJson, ts);
    }

    /**
     * 中文备注：仅续心跳，不改焦点字段（任意鉴权请求触发时用）。
     */
    public void touchLastSeen(String displayName, String orgType, Instant lastSeen) {
        Timestamp ts = Timestamp.from(lastSeen == null ? Instant.now() : lastSeen);
        String org = normalizeOrgType(orgType);
        int updated = jdbcTemplate.update("""
                UPDATE ops_user_presence SET org_type = ?, last_seen = ? WHERE display_name = ?
                """, org, ts, displayName);
        if (updated > 0) return;
        jdbcTemplate.update("""
                INSERT INTO ops_user_presence
                (display_name, org_type, table_id, table_label, drama_type, ops_key, series_name, col_key, col_label, mode, last_seen)
                VALUES (?, ?, '', '', '', '', '', '', '', 'idle', ?)
                """, displayName, org, ts);
    }

    public void remove(String displayName) {
        jdbcTemplate.update("DELETE FROM ops_user_presence WHERE display_name = ?", displayName);
    }

    public void deleteOlderThan(Instant cutoff) {
        jdbcTemplate.update("DELETE FROM ops_user_presence WHERE last_seen < ?", Timestamp.from(cutoff));
    }

    /**
     * @param orgTypeFilter null 或空表示不过滤（租户关闭时）；否则只返回同 org 在线用户。
     */
    public List<Map<String, Object>> listSince(Instant cutoff, String orgTypeFilter) {
        String org = orgTypeFilter == null ? "" : orgTypeFilter.trim().toLowerCase();
        boolean filterOrg = !org.isBlank();
        String sql = """
                SELECT display_name, org_type, table_id, table_label, drama_type, ops_key, series_name,
                       col_key, col_label, mode, sel_json, last_seen
                FROM ops_user_presence
                WHERE last_seen >= ?
                """;
        Object[] args;
        if (filterOrg) {
            sql += " AND org_type = ?";
            args = new Object[]{Timestamp.from(cutoff), normalizeOrgType(org)};
        } else {
            args = new Object[]{Timestamp.from(cutoff)};
        }
        sql += " ORDER BY last_seen DESC, display_name ASC";
        return jdbcTemplate.query(sql, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("displayName", rs.getString("display_name"));
            row.put("orgType", nullToEmpty(rs.getString("org_type")));
            row.put("tableId", nullToEmpty(rs.getString("table_id")));
            row.put("tableLabel", nullToEmpty(rs.getString("table_label")));
            row.put("dramaType", nullToEmpty(rs.getString("drama_type")));
            row.put("opsKey", nullToEmpty(rs.getString("ops_key")));
            row.put("seriesName", nullToEmpty(rs.getString("series_name")));
            row.put("colKey", nullToEmpty(rs.getString("col_key")));
            row.put("colLabel", nullToEmpty(rs.getString("col_label")));
            String mode = nullToEmpty(rs.getString("mode"));
            row.put("mode", mode.isBlank() ? "idle" : mode);
            row.put("selJson", nullToEmpty(rs.getString("sel_json")));
            Timestamp ts = rs.getTimestamp("last_seen");
            row.put("lastSeenInstant", ts == null ? Instant.EPOCH : ts.toInstant());
            return row;
        }, args);
    }

    public Map<String, Object> find(String displayName) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT display_name, org_type, table_id, table_label, drama_type, ops_key, series_name,
                       col_key, col_label, mode, sel_json, last_seen
                FROM ops_user_presence
                WHERE display_name = ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("displayName", rs.getString("display_name"));
            row.put("orgType", nullToEmpty(rs.getString("org_type")));
            row.put("tableId", nullToEmpty(rs.getString("table_id")));
            row.put("tableLabel", nullToEmpty(rs.getString("table_label")));
            row.put("dramaType", nullToEmpty(rs.getString("drama_type")));
            row.put("opsKey", nullToEmpty(rs.getString("ops_key")));
            row.put("seriesName", nullToEmpty(rs.getString("series_name")));
            row.put("colKey", nullToEmpty(rs.getString("col_key")));
            row.put("colLabel", nullToEmpty(rs.getString("col_label")));
            String mode = nullToEmpty(rs.getString("mode"));
            row.put("mode", mode.isBlank() ? "idle" : mode);
            row.put("selJson", nullToEmpty(rs.getString("sel_json")));
            return row;
        }, displayName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String normalizeOrgType(String orgType) {
        String v = orgType == null ? "" : orgType.trim().toLowerCase();
        return "agency".equals(v) ? "agency" : "hq";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
