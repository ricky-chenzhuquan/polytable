package com.polytable.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文备注：协作工作台字段选项的「首次入库种子」——只在库空时写入 ops_field_config；
 * 运行时一律读库，不在接口层写死名单。
 */
public final class OpsFieldOptionDefaults {

    public record FieldSeed(String fieldKey, String fieldType, String defaultValue, List<String> options) {
    }

    private static final List<String> EDITOR_ASSIGNEE = List.of(
            "马一然", "文雪", "余德鑫", "蒋志鹏", "余金金", "黄春苗", "周龙娟", "余尔涵", "罗佳", "周纾意",
            "任曦晨", "姜成胜", "舒浩", "林魏魏", "张朝辉", "申时吉", "张琪", "赵萍", "李彬", "王同庆",
            "李燕华", "刘豪武", "郑涛", "邹欣雨", "程志远", "崔书瑞", "谭椅文", "袁世宇", "么孝天", "韩铁峰",
            "王梓洋", "吕炎炎", "刘宏宇", "江昕桐", "胡艳虹"
    );
    private static final List<String> DELIVERY_TEST = List.of(
            "周群博", "舒芯男", "李姓亮", "谢琴祖", "刘国权", "仇星星", "仇庆庆", "李世龙", "何泽鹏", "杨静雯",
            "张琳杰", "卜晓鹏", "不可投放"
    );
    private static final List<String> FINAL_RATING = List.of(
            "A", "S", "B", "自制剧", "不可投放", "取消重点", "无片源", "审核不通过", "审核中", "上线两小时"
    );
    private static final List<String> SCREENING_RATING = List.of(
            "王宇鹏-S", "王宇鹏-A", "王宇鹏-B", "备选-王宇鹏", "王宇鹏-C", "王宇鹏-C二轮",
            "连载中", "上线超过2小时", "首发不对",
            "舒浩-B", "舒浩-C", "无授权",
            "华宁峰-S", "华宁峰-A", "华宁峰-B", "备选-华宁峰", "华宁峰-C", "华宁峰-C二轮",
            "自制剧"
    );
    private static final List<String> DRAMA_TYPE_LABEL = List.of(
            "真人剧", "AI剧", "动态漫", "抖小真人剧", "点众AI剧", "抖音小程序付费短剧", "点众抖小AI剧"
    );
    private static final List<String> COPYRIGHT_OWNER = List.of(
            "番茄", "点众", "刚刚好", "灵漫", "灵矩", "阅友", "阅文", "鱼戏", "诸云", "明星说",
            "萤火快创", "百川", "星忆", "风行", "猫眼", "壹个筋斗", "新领路", "稻谷", "蛮又多", "新页",
            "湖北来潮", "星火", "漫谭", "优阅", "乐游", "九州", "新漫", "魔人", "容量", "17K", "雷加"
    );

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
        map.put("editorAssignee", new FieldSeed("editorAssignee", "multi", "", EDITOR_ASSIGNEE));
        map.put("deliveryAssignee", new FieldSeed("deliveryAssignee", "multi", "", List.of()));
        map.put("deliveryTest", new FieldSeed("deliveryTest", "multi", "", DELIVERY_TEST));
        map.put("finalRating", new FieldSeed("finalRating", "select", "", FINAL_RATING));
        map.put("screeningRating", new FieldSeed("screeningRating", "select", "", SCREENING_RATING));
        map.put("dramaTypeLabel", new FieldSeed("dramaTypeLabel", "select", "", DRAMA_TYPE_LABEL));
        map.put("copyrightOwner", new FieldSeed("copyrightOwner", "select", "番茄", COPYRIGHT_OWNER));
        return Map.copyOf(map);
    }
}
