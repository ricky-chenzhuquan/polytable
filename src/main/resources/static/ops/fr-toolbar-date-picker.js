/**
 * 中文备注：工具栏日期——近三天 + Element UI daterange（左侧快捷项，最长 31 天）。
 */
(function (global) {
  const presets = global.OpsDatePresets;
  if (!presets?.toolbarShortcuts) {
    throw new Error("fr-toolbar-date-picker: 请先加载 ops-date-presets.js");
  }
  const PRESET_LABELS = presets.LABELS;
  const PRESET_SET = presets.PRESET_SET;
  const SHORTCUTS = presets.toolbarShortcuts();

  let vm = null;
  let mountEl = null;
  let onChangeCb = null;
  let resolveRangeCb = null;
  let maxRangeDays = 31;
  let onClampCb = null;
  let defaultTodayCb = null;

  function defaultTodayIso() {
    const d = new Date();
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  }

  function addDaysIso(iso, days) {
    const d = new Date(`${iso}T00:00:00`);
    d.setDate(d.getDate() + days);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  }

  function clampRange(start, end) {
    if (!start) return { start: "", end: "", clamped: false };
    let s = String(start).trim();
    let e = String(end || s).trim();
    if (e < s) { const t = s; s = e; e = t; }
    const a = new Date(`${s}T00:00:00`);
    const b = new Date(`${e}T00:00:00`);
    const span = Math.max(1, Math.round((b - a) / 86400000) + 1);
    if (span <= maxRangeDays) return { start: s, end: e, clamped: false };
    return { start: s, end: addDaysIso(s, maxRangeDays - 1), clamped: true };
  }

  function destroy() {
    if (vm) {
      try { vm.$destroy(); } catch (_) {}
      vm = null;
    }
    if (mountEl) mountEl.innerHTML = "";
  }

  function mount(selector, {
    onChange,
    resolveRange,
    maxRangeDays: maxDays,
    onClamp,
    defaultToday,
    shortcutsEnabled: initialShortcutsEnabled = true,
    shortcutValues: initialShortcutValues = null
  } = {}) {
    if (!global.Vue || !global.ELEMENT) return false;
    mountEl = typeof selector === "string" ? document.querySelector(selector) : selector;
    if (!mountEl) return false;
    onChangeCb = typeof onChange === "function" ? onChange : null;
    resolveRangeCb = typeof resolveRange === "function" ? resolveRange : null;
    maxRangeDays = Math.max(1, Number(maxDays) || 31);
    onClampCb = typeof onClamp === "function" ? onClamp : null;
    defaultTodayCb = typeof defaultToday === "function" ? defaultToday : defaultTodayIso;
    if (global.ELEMENT.lang && global.ELEMENT.lang.zhCN) {
      global.ELEMENT.locale(global.ELEMENT.lang.zhCN);
    }
    destroy();
    const inner = document.createElement("div");
    mountEl.appendChild(inner);
    vm = new global.Vue({
      el: inner,
      data() {
        return {
          rangeValue: null,
          disabled: false,
          shortcutsEnabled: initialShortcutsEnabled !== false,
          shortcutValues: Array.isArray(initialShortcutValues) ? initialShortcutValues.map(String) : null,
          muteChange: false
        };
      },
      computed: {
        pickerOptions() {
          const self = this;
          const allowed = Array.isArray(this.shortcutValues) ? new Set(this.shortcutValues) : null;
          const shortcuts = allowed ? SHORTCUTS.filter((s) => allowed.has(s.value)) : SHORTCUTS;
          return {
            shortcuts: this.shortcutsEnabled ? shortcuts.map((s) => ({
              text: s.text,
              onClick(picker) {
                const range = resolveRangeCb ? resolveRangeCb(s.value) : null;
                if (!range?.start || !range?.end) return;
                const clamped = clampRange(range.start, range.end);
                if (clamped.clamped && onClampCb) onClampCb(maxRangeDays);
                const start = new Date(`${clamped.start}T00:00:00`);
                const end = new Date(`${clamped.end}T00:00:00`);
                self.muteChange = true;
                picker.$emit("pick", [start, end]);
                self.rangeValue = [clamped.start, clamped.end];
                self.$nextTick(() => { self.muteChange = false; });
                self.emitChange({ preset: s.value, start: clamped.start, end: clamped.end });
              }
            })) : []
          };
        }
      },
      watch: {
        rangeValue(val) {
          if (this.muteChange || this.disabled) return;
          if (!val || !Array.isArray(val) || val.length !== 2 || !val[0] || !val[1]) {
            this.resetToToday({ emit: false });
          }
        }
      },
      methods: {
        resetToToday({ emit = true } = {}) {
          const t = defaultTodayCb ? defaultTodayCb() : defaultTodayIso();
          if (!t) return;
          this.muteChange = true;
          this.rangeValue = [t, t];
          this.$nextTick(() => {
            this.rangeValue = [t, t];
            this.muteChange = false;
            if (emit) this.emitChange({ preset: "", start: t, end: t });
          });
        },
        applyRangePair(pair, preset) {
          const clamped = clampRange(pair[0], pair[1]);
          if (clamped.clamped && onClampCb) onClampCb(maxRangeDays);
          this.muteChange = true;
          this.rangeValue = [clamped.start, clamped.end];
          this.$nextTick(() => { this.muteChange = false; });
          this.emitChange({ preset: preset || "", start: clamped.start, end: clamped.end });
        },
        onClear() {
          if (this.disabled) return;
          this.resetToToday();
        },
        onRangeChange(val) {
          if (this.muteChange || this.disabled) return;
          if (!val || !Array.isArray(val) || val.length !== 2 || !val[0] || !val[1]) {
            this.resetToToday();
            return;
          }
          this.applyRangePair([String(val[0]), String(val[1])], "");
        },
        emitChange(payload) {
          if (onChangeCb) onChangeCb(payload);
        },
        setState({
          disabled,
          shortcutsEnabled = true,
          shortcutValues = null,
          maxDays,
          preset = "",
          start = "",
          end = "",
          date = ""
        } = {}) {
          this.disabled = !!disabled;
          this.shortcutsEnabled = shortcutsEnabled !== false;
          this.shortcutValues = Array.isArray(shortcutValues) ? shortcutValues.map(String) : null;
          if (Number.isFinite(Number(maxDays)) && Number(maxDays) > 0) {
            maxRangeDays = Math.max(1, Number(maxDays));
          }
          const s0 = String(start || date || "").trim();
          const e0 = String(end || s0).trim();
          // 中文备注：近三天等只读模式仍展示起止日，不再靠外侧 hint 文本。
          if (disabled) {
            if (s0 && e0) {
              const clamped = clampRange(s0, e0);
              this.muteChange = true;
              this.rangeValue = [clamped.start, clamped.end];
              this.$nextTick(() => { this.muteChange = false; });
            } else {
              this.rangeValue = null;
            }
            return;
          }
          const p = String(preset || "").trim();
          if (p && resolveRangeCb) {
            const range = resolveRangeCb(p);
            if (range?.start && range?.end) {
              const clamped = clampRange(range.start, range.end);
              this.rangeValue = [clamped.start, clamped.end];
              return;
            }
          }
          const s = String(start || date || "").trim();
          const e = String(end || s).trim();
          if (!s) {
            this.resetToToday({ emit: false });
            return;
          }
          const clamped = clampRange(s, e);
          this.rangeValue = [clamped.start, clamped.end];
        }
      },
      template: `
        <el-date-picker
          ref="dp"
          v-model="rangeValue"
          type="daterange"
          align="right"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          format="yyyy-MM-dd"
          value-format="yyyy-MM-dd"
          :picker-options="pickerOptions"
          :popper-class="shortcutsEnabled ? '' : 'ops-date-picker-no-shortcuts'"
          :disabled="disabled"
          :clearable="!disabled"
          @change="onRangeChange"
          @clear="onClear"
        />
      `
    });
    return true;
  }

  function syncState(opts) {
    if (vm && typeof vm.setState === "function") vm.setState(opts || {});
  }

  function focusInput() {
    if (!vm || vm.disabled) return;
    vm.$nextTick(() => {
      const dp = vm.$refs.dp;
      const input = dp?.$refs?.reference?.$refs?.input;
      if (input && typeof input.focus === "function") input.focus();
    });
  }

  global.ToolbarDatePicker = { mount, destroy, syncState, focusInput, PRESET_LABELS, PRESET_SET };
})(typeof window !== "undefined" ? window : globalThis);
