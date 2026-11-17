package com.polytable.service;

import org.springframework.stereotype.Service;

/**
 * 中文备注：空白表列表分页参数（与前端 BLANK_SHEET_PAGE_LIMIT 对齐）。
 */
@Service
public class OpsSheetRowListService {

    public static final int DEFAULT_PAGE_LIMIT = 500;
    /** 中文备注：与前端 PAGED_POOL_MAX_ROWS、ops.list.max-page-size 硬顶对齐。 */
    public static final int MAX_PAGE_LIMIT = 50_000;

    public int capPageLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_PAGE_LIMIT;
        }
        return Math.min(MAX_PAGE_LIMIT, limit);
    }

    /** 中文备注：LIKE 通配符转义，避免 keyword 注入 %/_。 */
    public static String escapeLikeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String s = keyword.trim();
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
