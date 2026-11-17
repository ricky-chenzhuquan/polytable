/**
 * PolyTable 外观服务：深浅模式（浅色/深色/跟随系统）+ 主题色 DIY + 语言偏好。
 * 页面在 ops-auth.js 之后、sheet-canvas.js 之前加载本文件，保证首帧即生效。
 * 画布配色由 sheet-canvas.js 暴露的 window.OpsCanvasTheme.apply(mode, accent) 承接。
 */
(function (global) {
  const THEME_KEY = "polytable_theme";
  const ACCENT_KEY = "polytable_accent";
  const LANG_KEY = "polytable_lang";

  // 中文备注：主题色预设——语义四件套（主色/hover/柔底/柔底hover）+ 光环 + 渐变尾色，
  // 写入 CSS 变量后全站组件与画布统一跟随，无需逐处改样式。
  const ACCENTS = [
    { id: "indigo", label: "靛蓝", color: "#4f46e5", hover: "#4338ca", soft: "#eef2ff", softHover: "#e0e7ff", ring: "rgba(79,70,229,.35)", grad: "#7c3aed" },
    { id: "blue", label: "蔚蓝", color: "#2563eb", hover: "#1d4ed8", soft: "#eff6ff", softHover: "#dbeafe", ring: "rgba(37,99,235,.35)", grad: "#0ea5e9" },
    { id: "teal", label: "松石", color: "#0d9488", hover: "#0f766e", soft: "#f0fdfa", softHover: "#ccfbf1", ring: "rgba(13,148,136,.35)", grad: "#34d399" },
    { id: "violet", label: "紫罗兰", color: "#7c3aed", hover: "#6d28d9", soft: "#f5f3ff", softHover: "#ede9fe", ring: "rgba(124,58,237,.35)", grad: "#c026d3" },
    { id: "rose", label: "玫红", color: "#e11d48", hover: "#be123c", soft: "#fff1f2", softHover: "#ffe4e6", ring: "rgba(225,29,72,.35)", grad: "#fb7185" }
  ];

  const state = {
    mode: readStore(THEME_KEY) || "auto",
    accent: readStore(ACCENT_KEY) || ACCENTS[0].id,
    lang: readStore(LANG_KEY) || ""
  };

  function readStore(key) {
    try { return global.localStorage.getItem(key); } catch (_) { return null; }
  }

  function writeStore(key, value) {
    try {
      if (value == null) global.localStorage.removeItem(key);
      else global.localStorage.setItem(key, value);
    } catch (_) { /* 隐私模式等场景静默降级 */ }
  }

  function systemDark() {
    return !!(global.matchMedia && global.matchMedia("(prefers-color-scheme: dark)").matches);
  }

  function resolveMode() {
    return state.mode === "auto" ? (systemDark() ? "dark" : "light") : state.mode;
  }

  function accentById(id) {
    return ACCENTS.find((a) => a.id === id) || ACCENTS[0];
  }

  function apply() {
    const root = document.documentElement;
    const mode = resolveMode();
    root.dataset.theme = mode;
    root.style.colorScheme = mode;
    const a = accentById(state.accent);
    root.style.setProperty("--accent", a.color);
    root.style.setProperty("--accent-hover", a.hover);
    root.style.setProperty("--accent-soft", a.soft);
    root.style.setProperty("--accent-soft-hover", a.softHover);
    root.style.setProperty("--accent-ring", a.ring);
    root.style.setProperty("--accent-grad-to", a.grad);
    if (global.OpsCanvasTheme && typeof global.OpsCanvasTheme.apply === "function") {
      try { global.OpsCanvasTheme.apply(mode, a.color); } catch (_) { /* 画布未就绪时由其自行初始化 */ }
    }
    try { document.dispatchEvent(new CustomEvent("polytable-theme-change", { detail: { mode, accent: a.id } })); } catch (_) {}
    return { mode, accent: a.id };
  }

  function setMode(mode) {
    if (!["light", "dark", "auto"].includes(mode)) return apply();
    state.mode = mode;
    writeStore(THEME_KEY, mode === "auto" ? null : mode);
    return apply();
  }

  function setAccent(id) {
    state.accent = accentById(id).id;
    writeStore(ACCENT_KEY, state.accent);
    return apply();
  }

  function setLang(lang) {
    state.lang = lang || "";
    writeStore(LANG_KEY, state.lang || null);
    return state.lang;
  }

  // 中文备注：跟随系统时，监听系统深浅切换实时跟随。
  if (global.matchMedia) {
    try {
      global.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
        if (state.mode === "auto") apply();
      });
    } catch (_) { /* 旧浏览器无 addEventListener 版本则忽略 */ }
  }

  // 中文备注：DOM 就绪后兜底再应用一次（覆盖脚本加载顺序异常的情况）。
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", apply);
  } else {
    apply();
  }

  global.OpsTheme = {
    current() { return { mode: resolveMode(), modeSetting: state.mode, accent: accentById(state.accent).id, accentHex: accentById(state.accent).color, lang: state.lang }; },
    accents: ACCENTS,
    accentById,
    setMode,
    setAccent,
    setLang,
    apply
  };
})(window);
