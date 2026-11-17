package com.polytable.service;

import com.polytable.repository.OpsTableViewsRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 协作表 records 列投影：按视图 hiddenFields 裁剪列，缩小 gzip 块（对齐飞书 view 首屏）。
 */
@Service
public class OpsSheetViewRecordsProjection {

    private static final List<String> CORE_ROW_KEYS = List.of(
            "opsKey", "bookId", "dramaType", "version", "updatedBy", "updateTimeMs", "formulaValues");

    private static final String AGENCY_PROMO_TABLE_ID = "tbl_agency_promo";

    private static final List<String> AGENCY_PROMO_LINK_COLUMN_KEYS = List.of(
            "iaaDefaultUrl", "iaaCard2Url", "iaaEpisode2Url",
            "iapLink1", "iapLink2", "iapLink3", "iapLink4", "iapLink5",
            "iapLink6", "iapLink7", "iapLink8", "iapLink9", "iapLink10",
            "iapPanel1", "iapPanel2", "iapPanel3", "iapPanel4", "iapPanel5",
            "iapPanel6", "iapPanel7", "iapPanel8", "iapPanel9", "iapPanel10");

    private final OpsTableViewsRepository tableViewsRepository;
    private final ObjectMapper objectMapper;

    public OpsSheetViewRecordsProjection(OpsTableViewsRepository tableViewsRepository,
                                         ObjectMapper objectMapper) {
        this.tableViewsRepository = tableViewsRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param viewId 空则仅 fieldMetas + 行内孤儿 key（与旧行为兼容）；非空则按视图 hiddenFields 裁剪且不再扫全行 key。
     */
    public List<String> resolveColumnKeys(String tableId,
                                         String viewId,
                                         List<Map<String, Object>> fieldMetas,
                                         List<Map<String, Object>> sampleRows) {
        ViewColumns viewColumns = resolveViewColumns(tableId, viewId);
        Set<String> hidden = viewColumns.hidden();
        boolean projected = viewId != null && !viewId.isBlank();
        LinkedHashSet<String> keys = new LinkedHashSet<>(CORE_ROW_KEYS);
        if (AGENCY_PROMO_TABLE_ID.equals(stringOrEmpty(tableId))) {
            keys.addAll(AGENCY_PROMO_LINK_COLUMN_KEYS);
        }
        // 中文备注：系统表自定义列不在 sheet fieldMetas 中；必须以视图列顺序为准，
        // 不能因首 12 行恰好为空就把整批 records 的列裁掉。
        for (String key : viewColumns.ordered()) {
            if (!key.isBlank() && !hidden.contains(key)) {
                keys.add(key);
            }
        }
        if (fieldMetas != null) {
            for (Map<String, Object> f : fieldMetas) {
                String k = stringOrEmpty(f.get("fieldKey"));
                if (k.isBlank()) {
                    k = stringOrEmpty(f.get("key"));
                }
                if (k.isBlank() || hidden.contains(k)) {
                    continue;
                }
                keys.add(k);
            }
        }
        if (sampleRows != null && !sampleRows.isEmpty()) {
            // 中文备注：有 viewId 时只扫前几行取列名（减 gzip 体积），仍要包含系统列；勿只信 fieldMetas。
            int scanRows = projected ? Math.min(12, sampleRows.size()) : sampleRows.size();
            for (int ri = 0; ri < scanRows; ri++) {
                Map<String, Object> row = sampleRows.get(ri);
                if (row == null) {
                    continue;
                }
                for (String k : row.keySet()) {
                    if (k.startsWith("__") || hidden.contains(k)) {
                        continue;
                    }
                    keys.add(k);
                }
            }
        }
        return List.copyOf(keys);
    }

    private ViewColumns resolveViewColumns(String tableId, String viewId) {
        LinkedHashSet<String> hidden = new LinkedHashSet<>();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String vid = stringOrEmpty(viewId);
        if (vid.isBlank()) {
            return new ViewColumns(ordered, hidden);
        }
        String json = tableViewsRepository.findViewsJson(tableId);
        if (json == null || json.isBlank()) {
            return new ViewColumns(ordered, hidden);
        }
        try {
            List<Map<String, Object>> views = objectMapper.readValue(json, new TypeReference<>() {});
            if (views == null) {
                return new ViewColumns(ordered, hidden);
            }
            for (Map<String, Object> view : views) {
                if (view == null) {
                    continue;
                }
                String id = stringOrEmpty(view.get("viewId"));
                if (id.isBlank()) {
                    id = stringOrEmpty(view.get("id"));
                }
                if (!vid.equals(id)) {
                    continue;
                }
                Object raw = view.get("hiddenFields");
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        String k = stringOrEmpty(item);
                        if (!k.isBlank()) {
                            hidden.add(k);
                        }
                    }
                }
                Object rawOrder = view.get("columnOrder");
                if (rawOrder instanceof List<?> list) {
                    for (Object item : list) {
                        String k = stringOrEmpty(item);
                        if (!k.isBlank()) {
                            ordered.add(k);
                        }
                    }
                }
                break;
            }
        } catch (Exception ignored) {
            // 中文备注：视图 JSON 损坏时不裁剪，避免丢列。
        }
        return new ViewColumns(ordered, hidden);
    }

    private record ViewColumns(Set<String> ordered, Set<String> hidden) {}

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
