package com.polytable.service;

import com.polytable.repository.OpsFieldConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中文备注：把误删的字段选项补回 ops_field_config（只追加缺失项，不重写整表种子）。
 */
@Service
public class OpsFieldOptionsRestoreService {

    private final OpsFieldConfigRepository fieldConfigRepository;
    private final ObjectMapper objectMapper;

    public OpsFieldOptionsRestoreService(OpsFieldConfigRepository fieldConfigRepository,
                                         ObjectMapper objectMapper) {
        this.fieldConfigRepository = fieldConfigRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> restoreEditorAssigneeMissing(boolean dryRun) {
        return restoreMissingOptions("editorAssignee", "multi", List.of("崔书瑞", "谭椅文"), dryRun);
    }

    public Map<String, Object> restoreMissingOptions(String fieldKey,
                                                       String defaultFieldType,
                                                       List<String> names,
                                                       boolean dryRun) {
        Map<String, Object> existing = fieldConfigRepository.find(fieldKey);
        List<Map<String, Object>> saved = parseOptionsJson(existing == null
                ? ""
                : String.valueOf(existing.getOrDefault("optionsJson", "")));
        List<Map<String, Object>> merged = appendMissingNames(saved, names);
        Set<String> before = optionValues(saved);
        Set<String> after = optionValues(merged);
        Set<String> added = new LinkedHashSet<>(after);
        added.removeAll(before);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fieldKey", fieldKey);
        result.put("dryRun", dryRun);
        result.put("requested", names);
        result.put("beforeCount", before.size());
        result.put("afterCount", after.size());
        result.put("added", new ArrayList<>(added));
        result.put("hasConfig", existing != null);

        if (added.isEmpty()) {
            result.put("changed", false);
            result.put("message", "无需变更，选项已在库中");
            return result;
        }

        if (dryRun) {
            result.put("changed", true);
            result.put("message", "dry-run：将补回选项 " + String.join("、", added));
            return result;
        }

        String fieldType = existing == null
                ? defaultFieldType
                : String.valueOf(existing.getOrDefault("fieldType", defaultFieldType));
        String defaultValue = existing == null || existing.get("defaultValue") == null
                ? null
                : String.valueOf(existing.get("defaultValue"));
        if (defaultValue != null && defaultValue.isBlank()) defaultValue = null;
        try {
            String optionsJson = objectMapper.writeValueAsString(merged);
            fieldConfigRepository.upsert(fieldKey, fieldType, defaultValue, optionsJson, null, "system-restore");
            result.put("changed", true);
            result.put("message", "已补回选项：" + String.join("、", added));
        } catch (Exception e) {
            result.put("changed", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    static List<Map<String, Object>> appendMissingNames(List<Map<String, Object>> saved, List<String> names) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (saved != null) {
            for (Map<String, Object> opt : saved) {
                String v = trim(opt.get("value"));
                if (v.isBlank() || !seen.add(v)) continue;
                merged.add(new LinkedHashMap<>(opt));
            }
        }
        int sort = merged.size();
        for (String name : names) {
            String v = trim(name);
            if (v.isBlank() || !seen.add(v)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", v);
            item.put("sortOrder", sort++);
            merged.add(item);
        }
        return merged;
    }

    private Set<String> optionValues(List<Map<String, Object>> options) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> opt : options) {
            String v = trim(opt.get("value"));
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private List<Map<String, Object>> parseOptionsJson(String raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || "null".equals(raw)) return out;
        try {
            JsonNode arr = objectMapper.readTree(raw);
            if (!arr.isArray()) return out;
            int i = 0;
            for (JsonNode n : arr) {
                String value = trim(n.path("value").asText(n.path("label").asText("")));
                if (value.isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", value);
                String color = trim(n.path("color").asText(""));
                if (!color.isBlank()) item.put("color", color);
                item.put("sortOrder", n.path("sortOrder").asInt(i));
                out.add(item);
                i++;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
