package com.polytable.service;

import com.polytable.repository.OpsSheetRowRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * 协作表 records 协议：分页 + 列式 gzip 块（与 /rows JSON 语义对齐）。
 */
@Service
public class OpsSheetRowRecordsService {

    private final OpsSheetRowListService sheetRowListService;
    private final OpsSheetRecordsEncoder recordsEncoder;
    private final OpsSheetViewRecordsProjection viewProjection;

    public OpsSheetRowRecordsService(OpsSheetRowListService sheetRowListService,
                                     OpsSheetRecordsEncoder recordsEncoder,
                                     OpsSheetViewRecordsProjection viewProjection) {
        this.sheetRowListService = sheetRowListService;
        this.recordsEncoder = recordsEncoder;
        this.viewProjection = viewProjection;
    }

    public record SheetRecordsChunk(
            byte[] gzipPayload,
            int encoding,
            List<String> keys,
            int rowCount,
            String nextCursor,
            boolean hasMore,
            int pageLimit
    ) {
        /** 兼容旧 Base64 信封（encoding=1）。 */
        public String recordsBase64(OpsSheetRecordsEncoder encoder) {
            if (encoding == OpsSheetRecordsEncoder.ENCODING_GZIP_SPARSE_COLUMNAR_JSON
                    && gzipPayload != null && gzipPayload.length > 0) {
                return java.util.Base64.getEncoder().encodeToString(gzipPayload);
            }
            return "";
        }
    }

    public SheetRecordsChunk buildChunkFromRows(
            String tableId,
            String viewId,
            List<Map<String, Object>> canvasRows,
            List<Map<String, Object>> fieldMetas,
            String nextCursor,
            boolean hasMore,
            int pageLimit) {
        List<String> keys = viewProjection.resolveColumnKeys(
                tableId, trim(viewId), fieldMetas, canvasRows);
        List<List<List<Object>>> sparseRows = new ArrayList<>();
        for (Map<String, Object> row : canvasRows) {
            sparseRows.add(OpsSheetRecordsEncoder.rowToSparsePairs(row, keys));
        }
        int encoding = OpsSheetRecordsEncoder.ENCODING_GZIP_SPARSE_BINARY;
        byte[] gzip = recordsEncoder.encodeSparseGzipBytes(keys, sparseRows);
        return new SheetRecordsChunk(
                gzip,
                encoding,
                keys,
                canvasRows.size(),
                nextCursor,
                hasMore,
                pageLimit
        );
    }

    public SheetRecordsChunk buildChunk(
            String tableId,
            String viewId,
            List<Map<String, Object>> canvasRows,
            List<Map<String, Object>> fieldMetas,
            OpsSheetRowRepository.SheetRowPage page,
            int pageLimit) {
        return buildChunkFromRows(
                tableId,
                viewId,
                canvasRows,
                fieldMetas,
                page.nextCursor(),
                page.hasMore(),
                pageLimit);
    }

    /** 飞书式分片：协作表 records 单块行数上限（与前端 BLANK_SHEET_RECORDS_CHUNK 对齐）。 */
    public static final int RECORDS_CHUNK_ROW_LIMIT = 4000;

    public int capRecordsPageLimit(Integer limit) {
        int capped = capPageLimit(limit);
        return Math.min(RECORDS_CHUNK_ROW_LIMIT, capped);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public int capPageLimit(Integer limit) {
        return sheetRowListService.capPageLimit(limit);
    }

    public static List<String> buildColumnKeys(List<Map<String, Object>> fieldMetas, List<Map<String, Object>> sampleRows) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("opsKey");
        keys.add("bookId");
        keys.add("dramaType");
        keys.add("version");
        keys.add("updatedBy");
        keys.add("updateTimeMs");
        if (fieldMetas != null) {
            for (Map<String, Object> f : fieldMetas) {
                String k = stringOrEmpty(f.get("fieldKey"));
                if (k.isBlank()) {
                    k = stringOrEmpty(f.get("key"));
                }
                if (!k.isBlank()) {
                    keys.add(k);
                }
            }
        }
        if (sampleRows != null) {
            for (Map<String, Object> row : sampleRows) {
                if (row == null) {
                    continue;
                }
                for (String k : row.keySet()) {
                    if (k.startsWith("__")) {
                        continue;
                    }
                    keys.add(k);
                }
            }
        }
        return List.copyOf(keys);
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
