package com.polytable.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * 协作表 records 块：列式 JSON → gzip → Base64（encoding=0）。
 */
@Component
public class OpsSheetRecordsEncoder {

    public static final int ENCODING_GZIP_COLUMNAR_JSON = 0;
    /** 列式 JSON v2：每行仅非空单元格 [[colIndex, value], ...] → gzip → Base64。 */
    public static final int ENCODING_GZIP_SPARSE_COLUMNAR_JSON = 1;
    /** 与 encoding=1 同结构，gzip 原始字节（另端点拉取，无 Base64）。 */
    public static final int ENCODING_GZIP_SPARSE_BINARY = 2;

    private final ObjectMapper objectMapper;

    public OpsSheetRecordsEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(int encoding, List<String> keys, List<List<Object>> matrix) {
        if (encoding == ENCODING_GZIP_COLUMNAR_JSON) {
            return encodeDense(keys, matrix);
        }
        if (encoding == ENCODING_GZIP_SPARSE_COLUMNAR_JSON) {
            throw new IllegalArgumentException("use encodeSparse for encoding 1");
        }
        throw new IllegalArgumentException("unsupported encoding: " + encoding);
    }

    private String encodeDense(List<String> keys, List<List<Object>> matrix) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("keys required");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", 1);
            payload.put("keys", keys);
            payload.put("rows", matrix == null ? List.of() : matrix);
            return gzipBase64(payload);
        } catch (Exception e) {
            throw new IllegalStateException("encode sheet records failed: " + e.getMessage(), e);
        }
    }

    /** 每行 [[index, value], ...] 稀疏对。 */
    public String encodeSparse(List<String> keys, List<List<List<Object>>> sparseRows) {
        return Base64.getEncoder().encodeToString(encodeSparseGzipBytes(keys, sparseRows));
    }

    public byte[] encodeSparseGzipBytes(List<String> keys, List<List<List<Object>>> sparseRows) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("keys required");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("v", 2);
            payload.put("keys", keys);
            payload.put("rows", sparseRows == null ? List.of() : sparseRows);
            return gzip(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalStateException("encode sparse sheet records failed: " + e.getMessage(), e);
        }
    }

    private String gzipBase64(Map<String, Object> payload) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(payload);
        byte[] gz = gzip(json);
        return Base64.getEncoder().encodeToString(gz);
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(256, input.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos) {
            {
                def.setLevel(Deflater.BEST_SPEED);
            }
        }) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }

    /** 中文备注：从 canvas 行 Map 按 keys 顺序抽列，缺省填空串。 */
    public static List<Object> rowToVector(Map<String, Object> row, List<String> keys) {
        return keys.stream()
                .map(k -> {
                    if (row == null || !row.containsKey(k)) {
                        return "";
                    }
                    Object v = row.get(k);
                    return v == null ? "" : v;
                })
                .toList();
    }

    /** 稀疏行：跳过空串；opsKey 始终保留。 */
    public static List<List<Object>> rowToSparsePairs(Map<String, Object> row, List<String> keys) {
        List<List<Object>> pairs = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return pairs;
        }
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            Object v = row == null ? null : row.get(k);
            if (v == null) {
                v = "";
            }
            boolean always = "opsKey".equals(k) || "bookId".equals(k);
            if (!always && isBlankCell(v)) {
                continue;
            }
            pairs.add(List.of(i, v));
        }
        return pairs;
    }

    private static boolean isBlankCell(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.isEmpty();
        }
        return false;
    }
}
