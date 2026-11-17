package com.polytable.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中文备注：运营工作台「撤回」纯逻辑——只认本人日志；右侧对比用 before/after；
 * 不可撤回他人操作、配置类日志、以及撤回记录本身。
 */
public final class OpsUndoSupport {

    public enum Kind {
        OPS_FIELD,
        PROMO_FIELD,
        SERIES_FIELD,
        CUSTOM_FIELD,
        SOFT_HIDE,
        SOFT_UNHIDE,
        CREATE_ROW,
        UNSUPPORTED
    }

    public record FieldTarget(Kind kind, String saveKey, String label) {
    }

    public record Eligibility(boolean structurallyUndoable,
                              boolean mine,
                              boolean canUndo,
                              String blockReason) {
    }

    private static final Map<String, FieldTarget> FIELD_TARGETS = buildFieldTargets();
    private static final Set<String> MULTI_OPS_KEYS = Set.of(
            "editorAssignee", "deliveryTest", "deliveryAssignee"
    );
    private static final Set<String> TIME_KEYS = Set.of(
            "estimatePublishTime", "publishTime"
    );
    private static final Set<String> NON_UNDO_PREFIXES = Set.of(
            "FIELD_OPTION:", "FIELD_CREATE:", "FIELD_CONFIG:", "FIELD_DELETE:"
    );

    private OpsUndoSupport() {
    }

    public static FieldTarget resolveTarget(String fieldName) {
        String label = trim(fieldName);
        if (label.isBlank()) {
            return new FieldTarget(Kind.UNSUPPORTED, "", label);
        }
        if (label.startsWith("撤回")) {
            return new FieldTarget(Kind.UNSUPPORTED, "", label);
        }
        if ("删除记录".equals(label)) {
            return new FieldTarget(Kind.SOFT_HIDE, "hide", label);
        }
        if ("撤销删除".equals(label)) {
            return new FieldTarget(Kind.SOFT_UNHIDE, "unhide", label);
        }
        if ("新增行".equals(label)) {
            return new FieldTarget(Kind.CREATE_ROW, "create", label);
        }
        for (String prefix : NON_UNDO_PREFIXES) {
            if (label.startsWith(prefix)) {
                return new FieldTarget(Kind.UNSUPPORTED, "", label);
            }
        }
        FieldTarget known = FIELD_TARGETS.get(label);
        if (known != null) {
            return known;
        }
        // 中文备注：自定义列日志用字段标题；具体 key 由调用方按标题反查。
        return new FieldTarget(Kind.CUSTOM_FIELD, "", label);
    }

    public static Eligibility eligibility(String logOperator,
                                          String currentUser,
                                          String fieldName,
                                          boolean customKeyResolved) {
        String who = trim(logOperator);
        String me = trim(currentUser);
        FieldTarget target = resolveTarget(fieldName);
        boolean mine = !me.isBlank() && Objects.equals(who, me);
        if (target.kind() == Kind.UNSUPPORTED) {
            return new Eligibility(false, mine, false, "该类型操作不支持撤回");
        }
        if (target.kind() == Kind.CUSTOM_FIELD && !customKeyResolved) {
            return new Eligibility(false, mine, false, "无法定位自定义字段，暂不支持撤回");
        }
        if (!mine) {
            return new Eligibility(true, false, false, "只能撤回自己的操作");
        }
        return new Eligibility(true, true, true, "");
    }

    /**
     * 中文备注：当前值须仍等于日志 newValue，才允许撤回，避免覆盖他人后续修改。
     * 兼容：日志截断 1000、时间 T/空格、多选顿号顺序。
     */
    public static boolean currentMatchesLoggedNew(String currentValue, String loggedNewValue) {
        return currentMatchesLoggedNew(currentValue, loggedNewValue, null);
    }

    public static boolean currentMatchesLoggedNew(String currentValue,
                                                  String loggedNewValue,
                                                  FieldTarget target) {
        return currentMatchesLoggedNew(currentValue, loggedNewValue, target, false);
    }

    public static boolean currentMatchesLoggedNew(String currentValue,
                                                  String loggedNewValue,
                                                  FieldTarget target,
                                                  boolean treatAsCheckbox) {
        String cur = trim(currentValue);
        String neu = trim(loggedNewValue);
        if (Objects.equals(cur, neu)) {
            return true;
        }
        if (neu.length() >= 1000 && cur.startsWith(neu)) {
            return true;
        }
        if (neu.length() >= 1000 && neu.equals(cur.length() > 1000 ? cur.substring(0, 1000) : cur)) {
            return true;
        }
        if (target != null && isTimeField(target) && normalizeTimeToken(cur).equals(normalizeTimeToken(neu))) {
            return true;
        }
        if (target != null && isMultiOpsField(target)
                && normalizeMultiToken(cur).equals(normalizeMultiToken(neu))) {
            return true;
        }
        if (treatAsCheckbox && checkboxValuesEquivalent(cur, neu)) {
            return true;
        }
        return false;
    }

    /** 中文备注：复选框落库形态可能是 是/true/1/✓ 等，撤回冲突检查须视为等价。 */
    public static boolean checkboxValuesEquivalent(String a, String b) {
        return normalizeCheckboxToken(a).equals(normalizeCheckboxToken(b));
    }

    public static String normalizeCheckboxToken(String raw) {
        String text = trim(raw);
        if (text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase();
        if (Set.of("是", "true", "1", "yes", "y", "on", "勾选", "已勾选", "选中", "对").contains(lower)) {
            return "是";
        }
        if (Set.of("否", "false", "0", "no", "n", "off", "unchecked", "未勾选", "未选中", "未选", "无").contains(lower)) {
            return "";
        }
        if (Set.of("✓", "√", "☑", "✔").contains(text)) {
            return "是";
        }
        if (Set.of("×", "✗", "✘", "✕").contains(text)) {
            return "";
        }
        return text;
    }

    /** 中文备注：撤回落库前规范化恢复值，避免多选/时间形态和日常保存不一致导致后续保存失败。 */
    public static String normalizeRestoreValue(FieldTarget target, String restoreValue) {
        return normalizeRestoreValue(target, restoreValue, false);
    }

    public static String normalizeRestoreValue(FieldTarget target, String restoreValue, boolean treatAsCheckbox) {
        String restore = restoreValue == null ? "" : restoreValue.trim();
        if (target == null) {
            return restore;
        }
        if (treatAsCheckbox) {
            return normalizeCheckboxToken(restore);
        }
        if (isMultiOpsField(target)) {
            return String.join("、", splitMulti(restore));
        }
        if (isTimeField(target)) {
            return normalizeTimeToken(restore);
        }
        return restore;
    }

    public static boolean isMultiOpsField(FieldTarget target) {
        return target != null && target.kind() == Kind.OPS_FIELD && MULTI_OPS_KEYS.contains(target.saveKey());
    }

    public static boolean isTimeField(FieldTarget target) {
        return target != null && TIME_KEYS.contains(target.saveKey());
    }

    public static Map<String, Object> contrast(String fieldName, String beforeValue, String afterValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fieldName", trim(fieldName));
        map.put("beforeValue", beforeValue == null ? "" : beforeValue);
        map.put("afterValue", afterValue == null ? "" : afterValue);
        map.put("beforeDisplay", display(beforeValue));
        map.put("afterDisplay", display(afterValue));
        return map;
    }

    public static String display(String value) {
        String v = trim(value);
        return v.isBlank() ? "(空)" : v;
    }

    public static String undoLogFieldName(String originalFieldName) {
        String label = trim(originalFieldName);
        if (label.isBlank()) {
            return "撤回";
        }
        if (label.startsWith("撤回·")) {
            return label;
        }
        return "撤回·" + label;
    }

    public static String normalizeTimeToken(String raw) {
        String text = trim(raw).replace('T', ' ');
        if (text.isBlank()) {
            return "";
        }
        // 中文备注：统一到秒级，避免 17:30 vs 17:30:00 误判冲突。
        if (text.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$")) {
            return text + ":00";
        }
        if (text.matches("\\d{4}-\\d{2}-\\d{2}$")) {
            return text + " 00:00:00";
        }
        return text;
    }

    public static String normalizeMultiToken(String raw) {
        List<String> parts = splitMulti(raw);
        return String.join("、", parts);
    }

    private static List<String> splitMulti(String raw) {
        String text = trim(raw);
        if (text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,，、/|;；]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static Map<String, FieldTarget> buildFieldTargets() {
        Map<String, FieldTarget> map = new LinkedHashMap<>();
        put(map, "名称", Kind.OPS_FIELD, "seriesName");
        put(map, "剧目类型", Kind.OPS_FIELD, "dramaTypeLabel");
        put(map, "版权", Kind.OPS_FIELD, "copyrightOwner");
        put(map, "初审评级", Kind.OPS_FIELD, "screeningRating");
        put(map, "最终评级", Kind.OPS_FIELD, "finalRating");
        put(map, "执行人", Kind.OPS_FIELD, "editorAssignee");
        put(map, "素材数", Kind.OPS_FIELD, "materialCount");
        put(map, "测试分配", Kind.OPS_FIELD, "deliveryTest");
        put(map, "网盘链接", Kind.OPS_FIELD, "cloudDiskUrl");
        put(map, "优先级", Kind.OPS_FIELD, "priorityLevel");
        put(map, "协作评级", Kind.OPS_FIELD, "bidderRating");
        put(map, "协作成员", Kind.OPS_FIELD, "deliveryAssignee");
        return Map.copyOf(map);
    }

    private static void put(Map<String, FieldTarget> map, String label, Kind kind, String key) {
        map.put(label, new FieldTarget(kind, key, label));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

}
