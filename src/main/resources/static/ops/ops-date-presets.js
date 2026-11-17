/**
 * 工作区日期相对项：筛选、工具栏快捷、resolveDateFilterRange 共用 token。
 * 须在 fr-date-eq-picker / fr-toolbar-date-picker 之前加载。
 */
(function (global) {
  const LABELS = {
    roll_3_before_5_after: "前3后5天",
    today: "今天",
    tomorrow: "明天",
    yesterday: "昨天",
    this_week: "本周",
    last_week: "上周",
    this_month: "本月",
    last_month: "上月",
    past_7_days: "过去 7 天内",
    next_7_days: "未来 7 天内",
    past_30_days: "过去 30 天内",
    next_30_days: "未来 30 天内"
  };
  const TOOLBAR_SHORTCUT_ORDER = [
    "roll_3_before_5_after",
    "today",
    "tomorrow",
    "yesterday",
    "this_week",
    "last_week",
    "this_month",
    "last_month",
    "past_7_days",
    "next_7_days",
    "past_30_days",
    "next_30_days"
  ];
  const PRESET_SET = new Set(Object.keys(LABELS));

  function toolbarShortcuts() {
    return TOOLBAR_SHORTCUT_ORDER.map((value) => ({ value, text: LABELS[value] }));
  }

  global.OpsDatePresets = {
    LABELS,
    PRESET_SET,
    TOOLBAR_SHORTCUT_ORDER,
    toolbarShortcuts
  };
  // 兼容旧引用
  global.FrDateEqPicker = global.FrDateEqPicker || {};
  global.FrDateEqPicker.PRESET_LABELS = LABELS;
  global.FrDateEqPicker.PRESET_SET = PRESET_SET;
})(typeof window !== "undefined" ? window : globalThis);
