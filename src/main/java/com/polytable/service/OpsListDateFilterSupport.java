package com.polytable.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 中文备注：列表筛选日期字段 SQL 片段，与前端 resolveDateFilterRange / normalizeFilterDateTime 对齐。
 */
final class OpsListDateFilterSupport {

    static final Set<String> DATE_FILTER_FIELDS = Set.of(
            "estimatePublishTime", "publishTime", "changduCreateTime"
    );
    static final Set<String> DATE_FILTER_OPS = Set.of(
            "eq", "after", "before", "empty", "not_empty"
    );

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PRESETS = Set.of(
            "today", "tomorrow", "yesterday",
            "this_week", "last_week", "this_month", "last_month",
            "past_7_days", "next_7_days", "past_30_days", "next_30_days"
    );

    private OpsListDateFilterSupport() {
    }

    record DateRange(LocalDate start, LocalDate end) {
    }

    record SqlBit(String sql, List<Object> args) {
        static SqlBit empty() {
            return new SqlBit("", List.of());
        }
    }

    static String columnExpr(String field, String seriesPrefix) {
        String sp = seriesPrefix == null ? "" : seriesPrefix;
        return switch (field) {
            case "estimatePublishTime" -> sp.isBlank()
                    ? "COALESCE(estimate_publish_time, publish_time)"
                    : "COALESCE(" + sp + "estimate_publish_time, " + sp + "publish_time)";
            case "publishTime" -> sp + "publish_time";
            case "changduCreateTime" -> sp + "changdu_create_time";
            default -> null;
        };
    }

    static SqlBit buildClause(String field, String seriesPrefix, String op, String rawValue, LocalDate anchor) {
        String column = columnExpr(field, seriesPrefix);
        if (column == null) {
            return SqlBit.empty();
        }
        String sqlOp = op == null ? "" : op.trim().toLowerCase();
        List<Object> args = new ArrayList<>();
        return switch (sqlOp) {
            case "empty" -> new SqlBit("AND (" + column + " IS NULL)", args);
            case "not_empty" -> new SqlBit("AND (" + column + " IS NOT NULL)", args);
            case "eq" -> buildEq(column, rawValue, anchor, args);
            case "after" -> buildCompare(column, rawValue, ">", args);
            case "before" -> buildCompare(column, rawValue, "<", args);
            default -> SqlBit.empty();
        };
    }

    private static SqlBit buildEq(String column, String rawValue, LocalDate anchor, List<Object> args) {
        DateRange range = resolveDateFilterRange(rawValue, anchor);
        if (range == null) {
            return SqlBit.empty();
        }
        args.add(range.start());
        args.add(range.end());
        return new SqlBit("AND (DATE(" + column + ") >= ? AND DATE(" + column + ") <= ?)", args);
    }

    private static SqlBit buildCompare(String column, String rawValue, String sqlOp, List<Object> args) {
        LocalDateTime ts = parseFilterDateTime(rawValue);
        if (ts == null) {
            return SqlBit.empty();
        }
        args.add(java.sql.Timestamp.valueOf(ts));
        return new SqlBit("AND (" + column + " " + sqlOp + " ?)", args);
    }

    static DateRange resolveDateFilterRange(String raw, LocalDate anchor) {
        String token = raw == null ? "" : raw.trim();
        if (token.isEmpty()) {
            return null;
        }
        LocalDate today = anchor == null ? LocalDate.now(ZONE) : anchor;
        if (PRESETS.contains(token)) {
            return switch (token) {
                case "today" -> new DateRange(today, today);
                case "tomorrow" -> new DateRange(today.plusDays(1), today.plusDays(1));
                case "yesterday" -> new DateRange(today.minusDays(1), today.minusDays(1));
                case "this_week" -> {
                    LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    yield new DateRange(mon, mon.plusDays(6));
                }
                case "last_week" -> {
                    LocalDate thisMon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    LocalDate mon = thisMon.minusDays(7);
                    yield new DateRange(mon, mon.plusDays(6));
                }
                case "this_month" -> new DateRange(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
                case "last_month" -> {
                    LocalDate firstThis = today.withDayOfMonth(1);
                    LocalDate firstLast = firstThis.minusMonths(1);
                    yield new DateRange(firstLast, firstLast.withDayOfMonth(firstLast.lengthOfMonth()));
                }
                case "past_7_days" -> new DateRange(today.minusDays(6), today);
                case "next_7_days" -> new DateRange(today, today.plusDays(6));
                case "past_30_days" -> new DateRange(today.minusDays(29), today);
                case "next_30_days" -> new DateRange(today, today.plusDays(29));
                default -> null;
            };
        }
        LocalDate day = parseDateOnly(token);
        return day == null ? null : new DateRange(day, day);
    }

    static LocalDateTime parseFilterDateTime(String raw) {
        String text = raw == null ? "" : raw.trim().replace('T', ' ');
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return LocalDate.parse(text).atStartOfDay();
        }
        if (text.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")) {
            text = text + ":00";
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (DateTimeParseException e) {
            LocalDate day = parseDateOnly(text);
            return day == null ? null : day.atStartOfDay();
        }
    }

    private static LocalDate parseDateOnly(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
