package com.polytable.service;

import com.polytable.repository.OpsFieldConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：库内尚无选项时，把种子名单一次性写入 ops_field_config；已有配置则不覆盖。
 */
@Service
public class OpsFieldOptionSeedService {

    private final OpsFieldConfigRepository fieldConfigRepository;
    private final ObjectMapper objectMapper;

    public OpsFieldOptionSeedService(OpsFieldConfigRepository fieldConfigRepository,
                                       ObjectMapper objectMapper) {
        this.fieldConfigRepository = fieldConfigRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> seedEmptyFieldConfigs() {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> seeded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (OpsFieldOptionDefaults.FieldSeed seed : OpsFieldOptionDefaults.allSeeds()) {
            if (seed.options().isEmpty()) {
                skipped.add(seed.fieldKey());
                continue;
            }
            Map<String, Object> existing = fieldConfigRepository.find(seed.fieldKey());
            if (hasSavedOptions(existing)) {
                skipped.add(seed.fieldKey());
                continue;
            }
            try {
                List<Map<String, Object>> options = new ArrayList<>();
                int i = 0;
                for (String value : seed.options()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("value", value);
                    item.put("sortOrder", i++);
                    options.add(item);
                }
                String optionsJson = objectMapper.writeValueAsString(options);
                String defaultValue = seed.defaultValue() == null || seed.defaultValue().isBlank()
                        ? null
                        : seed.defaultValue();
                fieldConfigRepository.upsert(seed.fieldKey(), seed.fieldType(), defaultValue,
                        optionsJson, null, "system-seed");
                seeded.add(seed.fieldKey());
            } catch (Exception e) {
                summary.put("error:" + seed.fieldKey(), e.getMessage());
            }
        }
        summary.put("seeded", seeded);
        summary.put("skipped", skipped);
        return summary;
    }

    private boolean hasSavedOptions(Map<String, Object> existing) {
        if (existing == null) return false;
        Object raw = existing.get("optionsJson");
        if (raw == null) return false;
        String text = String.valueOf(raw).trim();
        return !text.isBlank() && !"[]".equals(text) && !"null".equalsIgnoreCase(text);
    }
}
