package com.polytable.ops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文备注：协作表日期列单元格值规范化为 yyyy-MM-dd HH:mm:ss，兼容文本列同步过来的时间。
 */
public final class SheetCellDateSupport {

    private static final Pattern DATE_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    private SheetCellDateSupport() {
    }

    public static String normalizeDateTime(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().replace('T', ' ');
        if (text.isEmpty() || "-".equals(text)) {
            return "";
        }
        if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return text + " 00:00:00";
        }
        if (text.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")) {
            return text + ":00";
        }
        Matcher m = DATE_TIME.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
