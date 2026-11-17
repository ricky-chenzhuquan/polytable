package com.polytable.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工作区账户功能权限（Admin 勾选）。 */
public final class OpsAccountFeatureFlags {

    public static final String PROMO_TABLE = "promoTable";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpsAccountFeatureFlags() {
    }

    public static Map<String, Boolean> parse(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        String text = String.valueOf(raw).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return Map.of();
        }
        try {
            Map<String, Object> map = MAPPER.readValue(text, new TypeReference<>() {
            });
            Map<String, Boolean> out = new LinkedHashMap<>();
            if (map != null) {
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) {
                        continue;
                    }
                    Object v = e.getValue();
                    if (v instanceof Boolean b) {
                        out.put(e.getKey(), b);
                    } else if (v != null) {
                        out.put(e.getKey(), Boolean.parseBoolean(String.valueOf(v)));
                    }
                }
            }
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    public static boolean enabled(Map<String, Boolean> flags, String key) {
        if (flags == null || key == null) {
            return false;
        }
        return Boolean.TRUE.equals(flags.get(key));
    }

    public static String serialize(Map<String, Boolean> flags) {
        try {
            Map<String, Boolean> safe = flags == null ? Map.of() : flags;
            return MAPPER.writeValueAsString(safe);
        } catch (Exception e) {
            return "{}";
        }
    }
}
