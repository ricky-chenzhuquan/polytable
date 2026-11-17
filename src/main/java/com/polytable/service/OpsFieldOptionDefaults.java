package com.polytable.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：协作工作台字段选项的「首次入库种子」——只在库空时写入 ops_field_config；
 * 运行时一律读库，不在接口层写死名单。
 * 开源版不内置任何真实名单：种子字段仅保留 key 与类型，选项为空，由部署方通过接口维护。
 */
public final class OpsFieldOptionDefaults {

    public record FieldSeed(String fieldKey, String fieldType, String defaultValue, List<String> options) {
    }

    private static final Map<String, FieldSeed> SEEDS = buildSeeds();

    private OpsFieldOptionDefaults() {
    }

    public static List<FieldSeed> allSeeds() {
        return List.copyOf(SEEDS.values());
    }

    public static List<String> seedOptions(String fieldKey) {
        FieldSeed seed = SEEDS.get(fieldKey);
        return seed == null ? List.of() : seed.options();
    }

    private static Map<String, FieldSeed> buildSeeds() {
        Map<String, FieldSeed> map = new LinkedHashMap<>();
        map.put("editorAssignee", new FieldSeed("editorAssignee", "multi", "", List.of()));
        map.put("deliveryAssignee", new FieldSeed("deliveryAssignee", "multi", "", List.of()));
        map.put("deliveryTest", new FieldSeed("deliveryTest", "multi", "", List.of()));
        map.put("finalRating", new FieldSeed("finalRating", "select", "", List.of()));
        map.put("screeningRating", new FieldSeed("screeningRating", "select", "", List.of()));
        map.put("dramaTypeLabel", new FieldSeed("dramaTypeLabel", "select", "", List.of()));
        map.put("copyrightOwner", new FieldSeed("copyrightOwner", "select", "", List.of()));
        return Map.copyOf(map);
    }
}
