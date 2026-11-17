package com.polytable.repository;

import com.polytable.service.OpsSheetMetaCacheService;
import com.polytable.service.OpsSheetOpLogService;
import com.polytable.service.OpsSheetRowListService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：空白表行存储；row_id 对应前端 opsKey。
 */
@Repository
public class OpsSheetRowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final OpsSheetMetaCacheService metaCacheService;

    public OpsSheetRowRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 OpsSheetMetaCacheService metaCacheService,
                                 com.polytable.service.OpsSheetOpLogService opLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.metaCacheService = metaCacheService;
        this.opLogService = opLogService;
    }

    private final com.polytable.service.OpsSheetOpLogService opLogService;

    public List<Map<String, Object>> listActive(String tableId) {
        return jdbcTemplate.query("""
                SELECT table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
                       create_time, update_time
                FROM ops_sheet_row
                WHERE table_id = ? AND deleted = 0
                ORDER BY sort_order ASC, create_time ASC, row_id ASC
                """, (rs, i) -> mapRow(rs), tableId);
    }

    public record SheetRowPage(List<Map<String, Object>> rows, String nextCursor, boolean hasMore) {
    }

    /**
     * 中文备注：键集分页，禁止 OFFSET；keyword 在 row_id / cells_json 上 LIKE（全表搜索走服务端）。
     */
    public SheetRowPage listActivePage(String tableId, String cursor, int limit, String keywordLikeEscaped) {
        int safeLimit = Math.max(1, Math.min(OpsSheetRowListService.MAX_PAGE_LIMIT, limit));
        int fetch = safeLimit + 1;
        OpsSheetRowCursor.Parsed parsed = OpsSheetRowCursor.decode(cursor);
        boolean hasKw = keywordLikeEscaped != null && !keywordLikeEscaped.isBlank();
        String likePattern = hasKw ? ("%" + keywordLikeEscaped + "%") : "";

        StringBuilder sql = new StringBuilder("""
                SELECT table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
                       create_time, update_time
                FROM ops_sheet_row
                WHERE table_id = ? AND deleted = 0
                """);
        List<Object> args = new ArrayList<>();
        args.add(tableId);
        if (hasKw) {
            sql.append(" AND (row_id LIKE ? ESCAPE '\\\\' OR cells_json LIKE ? ESCAPE '\\\\') ");
            args.add(likePattern);
            args.add(likePattern);
        }
        if (parsed != null) {
            sql.append("""
                     AND (
                       sort_order > ? OR
                       (sort_order = ? AND create_time > FROM_UNIXTIME(? / 1000.0)) OR
                       (sort_order = ? AND create_time = FROM_UNIXTIME(? / 1000.0) AND row_id > ?)
                     )
                    """);
            args.add(parsed.sortOrder());
            args.add(parsed.sortOrder());
            args.add(parsed.createTimeMs());
            args.add(parsed.sortOrder());
            args.add(parsed.createTimeMs());
            args.add(parsed.rowId());
        }
        sql.append(" ORDER BY sort_order ASC, create_time ASC, row_id ASC LIMIT ? ");
        args.add(fetch);

        List<Map<String, Object>> fetched = jdbcTemplate.query(sql.toString(), (rs, i) -> mapRow(rs), args.toArray());
        boolean hasMore = fetched.size() > safeLimit;
        List<Map<String, Object>> page = hasMore ? fetched.subList(0, safeLimit) : fetched;
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = OpsSheetRowCursor.encode(page.get(page.size() - 1));
        }
        return new SheetRowPage(page, nextCursor, hasMore);
    }

    public int countActive(String tableId, String keywordLikeEscaped) {
        boolean hasKw = keywordLikeEscaped != null && !keywordLikeEscaped.isBlank();
        if (!hasKw) {
            return countActive(tableId);
        }
        String likePattern = "%" + keywordLikeEscaped + "%";
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ops_sheet_row
                WHERE table_id = ? AND deleted = 0
                  AND (row_id LIKE ? ESCAPE '\\\\' OR cells_json LIKE ? ESCAPE '\\\\')
                """, Integer.class, tableId, likePattern, likePattern);
        return n == null ? 0 : n;
    }

    /** 中文备注：协作轮询增量——只拉 update_time 晚于 sinceMs 的可见行。 */
    public List<Map<String, Object>> listActiveUpdatedSince(String tableId, long sinceMs) {
        if (sinceMs <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
                       create_time, update_time
                FROM ops_sheet_row
                WHERE table_id = ? AND deleted = 0
                  AND update_time > FROM_UNIXTIME(? / 1000.0)
                ORDER BY sort_order ASC, create_time ASC, row_id ASC
                """, (rs, i) -> mapRow(rs), tableId, sinceMs);
    }

    public List<String> listDeletedRowIdsSince(String tableId, long sinceMs) {
        if (sinceMs <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT row_id FROM ops_sheet_row
                WHERE table_id = ? AND deleted = 1
                  AND update_time > FROM_UNIXTIME(? / 1000.0)
                ORDER BY update_time ASC
                """, (rs, i) -> rs.getString("row_id"), tableId, sinceMs);
    }

    public Map<String, Object> findActive(String tableId, String rowId) {
        var rows = jdbcTemplate.query("""
                SELECT table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
                       create_time, update_time
                FROM ops_sheet_row
                WHERE table_id = ? AND row_id = ? AND deleted = 0
                LIMIT 1
                """, (rs, i) -> mapRow(rs), tableId, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 中文备注：空白表内容水位；前端轮询对比，有变化才合并行数据（对端实时看到字段修改）。
     */
    public String contentSyncToken(String tableId) {
        try {
            return jdbcTemplate.query("""
                    SELECT COUNT(*) AS cnt,
                           COALESCE(SUM(version), 0) AS ver_sum,
                           COALESCE(MAX(UNIX_TIMESTAMP(update_time)), 0) AS max_ts
                    FROM ops_sheet_row
                    WHERE table_id = ? AND deleted = 0
                    """, rs -> {
                if (!rs.next()) {
                    return "0:0:0";
                }
                return rs.getLong("cnt") + ":" + rs.getLong("ver_sum") + ":" + rs.getLong("max_ts");
            }, tableId);
        } catch (Exception e) {
            return "";
        }
    }

    public int countActive(String tableId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ops_sheet_row WHERE table_id = ? AND deleted = 0",
                Integer.class, tableId);
        return n == null ? 0 : n;
    }

    public int nextSortOrder(String tableId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM ops_sheet_row WHERE table_id = ? AND deleted = 0",
                Integer.class, tableId);
        return (max == null ? 0 : max) + 10;
    }

    public void insert(String tableId, String rowId, String cellsJson, int sortOrder, String operator) {
        jdbcTemplate.update("""
                INSERT INTO ops_sheet_row (
                    table_id, row_id, cells_json, sort_order, version, deleted, updated_by
                ) VALUES (?, ?, ?, ?, 1, 0, ?)
                """, tableId, rowId, cellsJson, sortOrder, operator);
        metaCacheService.evictRows(tableId);
    }

    /** 中文备注：粘贴新增行走 JDBC batch，避免大批量逐行往返数据库。 */
    public void insertBatch(List<Object[]> batchArgs) {
        if (batchArgs == null || batchArgs.isEmpty()) return;
        jdbcTemplate.batchUpdate("""
                INSERT INTO ops_sheet_row (
                    table_id, row_id, cells_json, sort_order, version, deleted, updated_by
                ) VALUES (?, ?, ?, ?, 1, 0, ?)
                """, batchArgs);
        for (Object[] row : batchArgs) {
            if (row != null && row.length > 0 && row[0] != null) {
                metaCacheService.evictRows(String.valueOf(row[0]));
            }
        }
    }

    /**
     * @return 更新行数；0 表示版本冲突或不存在
     */
    public int save(String tableId, String rowId, String cellsJson, int expectedVersion, String operator) {
        // 中文备注：W1 单元格级推送——先取旧 cells,diff 出变更字段,写 op_log 并把 op 帧随 SSE 推给订阅者。
        String oldCellsJson = null;
        try {
            oldCellsJson = jdbcTemplate.queryForObject(
                    "SELECT cells_json FROM ops_sheet_row WHERE table_id = ? AND row_id = ? AND deleted = 0",
                    String.class, tableId, rowId);
        } catch (Exception ignored) {
        }
        int updated = jdbcTemplate.update("""
                UPDATE ops_sheet_row
                SET cells_json = ?, version = version + 1, updated_by = ?
                WHERE table_id = ? AND row_id = ? AND deleted = 0 AND version = ?
                """, cellsJson, operator, tableId, rowId, expectedVersion);
        if (updated > 0) {
            long seq = 0L;
            String opsJson = "[]";
            try {
                var diff = opLogService.diffAndRecord(tableId, rowId, operator, oldCellsJson, cellsJson, expectedVersion + 1);
                seq = diff.seq();
                opsJson = diff.opsJson();
            } catch (Exception ignored) {
            }
            metaCacheService.evictRowOps(tableId, rowId, operator, seq, opsJson);
        }
        return updated;
    }

    /**
     * 中文备注：大批量粘贴混合路径(更新已有行)批处理——一次 SELECT 老 cells、一次 batchUpdate
     * (各行乐观锁 WHERE version=?)、op_log 一次批量 INSERT + 一次 MAX(id)。
     * 返回各行 updated 与合并 op 帧(整批一帧,seq=批内最大 id);失败行由调用方回滚/报冲突。
     * 基线:300 行逐行 saveSheetRow 13.3ms/行 → 批处理预期 <1ms/行。
     */
    public record BatchRowInput(String rowId, String cellsJson, int expectedVersion) {
    }

    public record BatchSaveResult(int[] updated, List<String> rowIds, String mergedOpsJson, long maxSeq, int changed) {
    }

    private static String truncate(String v) {
        if (v == null) return null;
        return v.length() > 4000 ? v.substring(0, 4000) : v;
    }

    public BatchSaveResult batchSaveWithOps(String tableId, List<BatchRowInput> rows, String operator) {
        // 1) 一次取全部老 cells
        Map<String, String> oldByRow = new LinkedHashMap<>();
        List<String> allIds = rows.stream().map(BatchRowInput::rowId).toList();
        for (int i = 0; i < allIds.size(); i += 500) {
            List<String> chunk = allIds.subList(i, Math.min(i + 500, allIds.size()));
            String placeholders = chunk.stream().map(b -> "?").collect(java.util.stream.Collectors.joining(","));
            try {
                jdbcTemplate.query(
                        "SELECT row_id, cells_json FROM ops_sheet_row WHERE table_id = ? AND row_id IN (" + placeholders + ") AND deleted = 0",
                        rs -> {
                            oldByRow.put(rs.getString(1), rs.getString(2));
                            return null;
                        }, tableId, chunk.toArray());
            } catch (Exception ignored) {
            }
        }
        // 2) 批量 UPDATE(各行乐观锁)
        Object[][] args = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            BatchRowInput r = rows.get(i);
            args[i] = new Object[]{r.cellsJson(), operator, tableId, r.rowId(), r.expectedVersion()};
        }
        List<Object[]> argsList = new ArrayList<>();
        for (Object[] a : args) argsList.add(a);
        int[] updated = jdbcTemplate.batchUpdate("""
                UPDATE ops_sheet_row
                SET cells_json = ?, version = version + 1, updated_by = ?
                WHERE table_id = ? AND row_id = ? AND deleted = 0 AND version = ?
                """, argsList);
        // 3) 集中 diff + op_log 批量 INSERT
        List<Object[]> logArgs = new ArrayList<>();
        List<Map<String, Object>> allOps = new ArrayList<>();
        List<String> changedRowIds = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (updated[i] <= 0) continue;
            BatchRowInput r = rows.get(i);
            Map<String, Object> oldCells = opLogService.parseCellsForBatch(oldByRow.get(r.rowId()));
            Map<String, Object> newCells = opLogService.parseCellsForBatch(r.cellsJson());
            for (Map.Entry<String, Object> e : newCells.entrySet()) {
                String newVal = e.getValue() == null ? "" : String.valueOf(e.getValue());
                String oldVal = oldCells.get(e.getKey()) == null ? "" : String.valueOf(oldCells.get(e.getKey()));
                if (oldVal.equals(newVal)) continue;
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("row", r.rowId());
                op.put("fld", e.getKey());
                op.put("val", newVal);
                op.put("old", oldVal);
                op.put("ver", r.expectedVersion() + 1);
                allOps.add(op);
                logArgs.add(new Object[]{tableId, r.rowId(), operator, e.getKey(),
                        truncate(oldVal), truncate(newVal), r.expectedVersion() + 1});
            }
            changedRowIds.add(r.rowId());
        }
        long maxSeq = 0L;
        String mergedOps = "[]";
        if (!logArgs.isEmpty()) {
            try {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO ops_sheet_op_log (
                            table_id, row_id, operator, field_key, old_val, new_val, row_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, logArgs);
            } catch (Exception ignored) {
            }
            try {
                Long max = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM ops_sheet_op_log WHERE table_id = ?",
                        Long.class, tableId);
                maxSeq = max == null ? 0L : max;
            } catch (Exception ignored) {
            }
            try {
                mergedOps = opLogService.serializeOps(allOps);
            } catch (Exception ignored) {
            }
        }
        return new BatchSaveResult(updated, changedRowIds, mergedOps, maxSeq, allOps.size());
    }

    /** 中文备注：批量软删粘贴新增行，避免 2000 次单条 UPDATE 导致撤回超时。 */
    public int softDeleteBatch(String tableId, List<String> rowIds, String operator) {
        if (rowIds == null || rowIds.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < rowIds.size(); i += 400) {
            List<String> chunk = rowIds.subList(i, Math.min(i + 400, rowIds.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(operator);
            args.add(tableId);
            args.addAll(chunk);
            total += jdbcTemplate.update(
                    "UPDATE ops_sheet_row SET deleted = 1, updated_by = ? WHERE table_id = ? AND deleted = 0 AND row_id IN ("
                            + placeholders + ")",
                    args.toArray());
        }
        if (total > 0) {
            metaCacheService.evictRows(tableId);
        }
        return total;
    }

    public boolean softDelete(String tableId, String rowId, String operator) {
        boolean ok = jdbcTemplate.update("""
                UPDATE ops_sheet_row
                SET deleted = 1, updated_by = ?
                WHERE table_id = ? AND row_id = ? AND deleted = 0
                """, operator, tableId, rowId) > 0;
        if (ok) {
            metaCacheService.evictRowBy(tableId, rowId, operator);
        }
        return ok;
    }

    /** 中文备注：软删撤销；把 deleted 行恢复为可见。 */
    public boolean restore(String tableId, String rowId, String operator) {
        boolean ok = jdbcTemplate.update("""
                UPDATE ops_sheet_row
                SET deleted = 0, updated_by = ?
                WHERE table_id = ? AND row_id = ? AND deleted = 1
                """, operator, tableId, rowId) > 0;
        if (ok) {
            metaCacheService.evictRowBy(tableId, rowId, operator);
        }
        return ok;
    }

    public Map<String, Object> findIncludingDeleted(String tableId, String rowId) {
        var rows = jdbcTemplate.query("""
                SELECT table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
                       create_time, update_time
                FROM ops_sheet_row
                WHERE table_id = ? AND row_id = ?
                LIMIT 1
                """, (rs, i) -> mapRow(rs), tableId, rowId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int softDeleteAll(String tableId, String operator) {
        int updated = jdbcTemplate.update("""
                UPDATE ops_sheet_row
                SET deleted = 1, updated_by = ?
                WHERE table_id = ? AND deleted = 0
                """, operator, tableId);
        if (updated > 0) {
            metaCacheService.evictRows(tableId);
        }
        return updated;
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableId", rs.getString("table_id"));
        row.put("rowId", rs.getString("row_id"));
        row.put("cellsJson", rs.getString("cells_json"));
        row.put("sortOrder", rs.getInt("sort_order"));
        row.put("version", rs.getInt("version"));
        row.put("deleted", rs.getInt("deleted"));
        row.put("updatedBy", rs.getString("updated_by"));
        java.sql.Timestamp ct = rs.getTimestamp("create_time");
        java.sql.Timestamp ut = rs.getTimestamp("update_time");
        row.put("createTimeMs", ct == null ? 0L : ct.getTime());
        row.put("updateTimeMs", ut == null ? 0L : ut.getTime());
        return row;
    }
}
