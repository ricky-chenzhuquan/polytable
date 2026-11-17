package com.polytable.repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 中文备注：空白表行游标，与 ORDER BY sort_order, create_time, row_id 对齐。
 */
final class OpsSheetRowCursor {

    private OpsSheetRowCursor() {
    }

    record Parsed(int sortOrder, long createTimeMs, String rowId) {
    }

    static Parsed decode(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\u0001", 3);
            if (parts.length < 3) {
                return null;
            }
            int sortOrder = Integer.parseInt(parts[0]);
            long createTimeMs = Long.parseLong(parts[1]);
            String rowId = parts[2];
            if (rowId.isBlank()) {
                return null;
            }
            return new Parsed(sortOrder, createTimeMs, rowId);
        } catch (Exception e) {
            return null;
        }
    }

    static String encode(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        int sortOrder = row.get("sortOrder") instanceof Number n ? n.intValue() : 0;
        long createTimeMs = row.get("createTimeMs") instanceof Number n ? n.longValue() : 0L;
        String rowId = String.valueOf(row.get("rowId") == null ? "" : row.get("rowId")).trim();
        if (rowId.isEmpty()) {
            return "";
        }
        String payload = sortOrder + "\u0001" + createTimeMs + "\u0001" + rowId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
