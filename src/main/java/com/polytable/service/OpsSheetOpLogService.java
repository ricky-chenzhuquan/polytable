package com.polytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：W1 单元格级推送——协作表行保存时 diff 新旧 cells，写 ops_sheet_op_log（seq 单调），
 * 并生成 SSE op 帧载荷 {row, fld, val, old, ver}。客户端按 seq 应用到本地行；
 * seq 出现缺口立即回退既有 delta 增量，保证最终一致。
 */
@Service
public class OpsSheetOpLogService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OpsSheetOpLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record OpDiff(long seq, String opsJson, int changed) {
    }

    public record OpReplay(long seq, String opsJson, int count) {
    }

    /** 中文备注：断线握手——按 Last-Event-ID 回放该表 id>lastSeq 的 op(升序,限 limit 条),
     *  返回末尾 seq 与合并 op 帧;超出 limit 视为缺口过大,返回 count=-1 让客户端整体走 delta。 */
    public OpReplay replayAfter(String tableId, long lastSeq, int limit) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, row_id, field_key, old_val, new_val, row_version FROM ops_sheet_op_log "
                            + "WHERE table_id = ? AND id > ? ORDER BY id ASC LIMIT " + Math.max(1, limit + 1),
                    tableId, lastSeq);
            if (rows.size() > limit) {
                return new OpReplay(0L, "[]", -1);
            }
            if (rows.isEmpty()) {
                return new OpReplay(lastSeq, "[]", 0);
            }
            List<Map<String, Object>> ops = new ArrayList<>();
            long maxId = lastSeq;
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("id")).longValue();
                if (id > maxId) maxId = id;
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("row", String.valueOf(r.get("row_id")));
                op.put("fld", String.valueOf(r.get("field_key")));
                op.put("val", r.get("new_val") == null ? "" : String.valueOf(r.get("new_val")));
                op.put("old", r.get("old_val") == null ? "" : String.valueOf(r.get("old_val")));
                op.put("ver", r.get("row_version") == null ? 0 : ((Number) r.get("row_version")).intValue());
                ops.add(op);
            }
            return new OpReplay(maxId, objectMapper.writeValueAsString(ops), ops.size());
        } catch (Exception ignored) {
            return new OpReplay(0L, "[]", 0);
        }
    }

    /**
     * diff 新旧 cells → 写 op_log（batch）→ 返回末尾 seq 与 op 帧 JSON。
     * 解析失败等异常一律吞掉返回空 diff（SSE 走 dirty 兜底，不影响保存主流程）。
     */
    public OpDiff diffAndRecord(String tableId, String rowId, String operator,
                                String oldCellsJson, String newCellsJson, int newRowVersion) {
        long seq = 0L;
        String opsJson = "[]";
        int changed = 0;
        try {
            Map<String, Object> oldCells = parseCells(oldCellsJson);
            Map<String, Object> newCells = parseCells(newCellsJson);
            List<Map<String, Object>> ops = new ArrayList<>();
            List<Object[]> batchArgs = new ArrayList<>();
            for (Map.Entry<String, Object> e : newCells.entrySet()) {
                String fieldKey = e.getKey();
                String newVal = stringify(e.getValue());
                String oldVal = stringify(oldCells.get(fieldKey));
                if (oldVal.equals(newVal)) continue;
                ops.add(opEntry(rowId, fieldKey, newVal, oldVal, newRowVersion));
                batchArgs.add(new Object[]{tableId, rowId, operator, fieldKey,
                        truncate(oldVal), truncate(newVal), newRowVersion});
            }
            changed = ops.size();
            if (!batchArgs.isEmpty()) {
                jdbcTemplate.batchUpdate("""
                        INSERT INTO ops_sheet_op_log (
                            table_id, row_id, operator, field_key, old_val, new_val, row_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, batchArgs);
                Long max = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM ops_sheet_op_log WHERE table_id = ?",
                        Long.class, tableId);
                seq = max == null ? 0L : max;
            }
            opsJson = objectMapper.writeValueAsString(ops);
        } catch (Exception ignored) {
            return new OpDiff(0L, "[]", 0);
        }
        return new OpDiff(seq, opsJson, changed);
    }

    /** 中文备注：批量路径公开入口——解析 cells JSON(空串安全)。 */
    public Map<String, Object> parseCellsForBatch(String json) {
        return parseCells(json);
    }

    /** 中文备注：批量路径公开入口——op 数组序列化为 SSE 帧载荷。 */
    public String serializeOps(List<Map<String, Object>> ops) {
        try {
            return objectMapper.writeValueAsString(ops == null ? List.of() : ops);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> opEntry(String rowId, String fieldKey, String val, String old, int ver) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("row", rowId);
        op.put("fld", fieldKey);
        op.put("val", val == null ? "" : val);
        op.put("old", old == null ? "" : old);
        op.put("ver", ver);
        return op;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCells(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String stringify(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String truncate(String v) {
        if (v == null) return null;
        return v.length() > 4000 ? v.substring(0, 4000) : v;
    }
}
