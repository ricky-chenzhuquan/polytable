/**
 * 中文备注：协作表日期时间——Element UI datetime（单元格编辑 / 字段默认值 / 新增行）。
 * 依赖全局 Vue + ELEMENT；API 值格式 yyyy-MM-dd HH:mm:ss。
 * 整框可点、单元格编辑自动弹出面板（对齐原先 datetime-local 一点即编）。
 */
(function (global) {
  function ensureLocale() {
    if (!global.ELEMENT) return;
    if (global.ELEMENT.lang && global.ELEMENT.lang.zhCN) {
      global.ELEMENT.locale(global.ELEMENT.lang.zhCN);
    }
  }

  function normalizeApiValue(raw) {
    const text = String(raw || "").trim().replace("T", " ");
    if (!text) return "";
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return `${text} 00:00:00`;
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(text)) return `${text}:00`;
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(text)) return text.slice(0, 19);
    return "";
  }

  function syncHidden(hidden, value) {
    if (!hidden) return;
    hidden.value = value || "";
    hidden.dispatchEvent(new Event("input", { bubbles: true }));
    hidden.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function destroyVm(vm) {
    if (!vm) return;
    try { vm.$destroy(); } catch (_) {}
  }

  function openPickerPanel(vm) {
    const dp = vm?.$refs?.dp;
    if (!dp) return;
    try { dp.focus(); } catch (_) {}
    try { dp.pickerVisible = true; } catch (_) {}
  }

  function formatDateParts(d) {
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function formatDisplayValue(apiVal, format) {
    const n = normalizeApiValue(apiVal);
    if (!n) return "";
    const m = n.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})(?::(\d{2}))?/);
    if (!m) return n;
    const fmt = format || "yyyy/MM/dd HH:mm:ss";
    return fmt
      .replace(/yyyy/g, m[1])
      .replace(/MM/g, m[2])
      .replace(/dd/g, m[3])
      .replace(/HH/g, m[4])
      .replace(/mm/g, m[5])
      .replace(/ss/g, m[6] || "00");
  }

  function createPickerVm(mountEl, {
    value = "",
    hidden = null,
    size = "small",
    autoOpen = false,
    stopPropagation = false,
    onChange = null,
    displayFormat = "yyyy/MM/dd HH:mm:ss",
    compact = false
  } = {}) {
    if (!global.Vue || !global.ELEMENT || !mountEl) return null;
    ensureLocale();
    const initial = normalizeApiValue(value);
    const changeFn = typeof onChange === "function" ? onChange : null;
    const vm = new global.Vue({
      el: mountEl,
      data() {
        const timeFromStored = (() => {
          const m = String(initial || "").match(/(\d{2}:\d{2}:\d{2})/);
          return m ? m[1] : "00:00:00";
        })();
        return {
          dtValue: initial || "",
          stored: initial || "",
          displayText: initial ? formatDisplayValue(initial, displayFormat) : "",
          pickerSize: size || "small",
          pickerFormat: displayFormat || "yyyy/MM/dd HH:mm:ss",
          pickerCompact: !!compact,
          pickerDefaultTime: timeFromStored,
          panelDateUnwatch: null
        };
      },
      watch: {
        dtValue(next) {
          if (normalizeApiValue(next) === this.stored) return;
          this.applyValue(next);
        }
      },
      mounted() {
        this.applyValue(this.dtValue, { silent: !initial });
        this.$nextTick(() => {
          const dp = this.$refs.dp;
          if (dp?.$el) {
            dp.$el.classList.add("fr-dt-editor");
            if (this.pickerCompact) dp.$el.classList.add("fr-dt-compact");
            dp.$el.addEventListener("mousedown", (e) => {
              if (stopPropagation) e.stopPropagation();
              this.openPickerPanel();
            }, true);
          }
          if (dp) {
            this.$watch(() => dp.pickerVisible, (open) => {
              if (this.panelDateUnwatch) {
                this.panelDateUnwatch();
                this.panelDateUnwatch = null;
              }
              if (!open) {
                this.syncFromOpenPanel();
                return;
              }
              this.$nextTick(() => this.bindPanelDateWatch());
            });
          }
          if (autoOpen) this.openPickerPanel();
        });
      },
      beforeDestroy() {
        if (this.panelDateUnwatch) this.panelDateUnwatch();
      },
      methods: {
        syncDisplayText(normalized) {
          this.displayText = normalized
            ? formatDisplayValue(normalized, this.pickerFormat)
            : "";
        },
        applyValue(next, { silent = false } = {}) {
          const normalized = normalizeApiValue(next);
          if (normalized === this.stored && normalized === this.dtValue) {
            this.syncDisplayText(this.stored);
            syncHidden(hidden, this.stored);
            if (!silent && changeFn) changeFn(this.stored);
            return;
          }
          this.stored = normalized;
          if (this.dtValue !== normalized) this.dtValue = normalized;
          this.syncDisplayText(normalized);
          const tm = String(normalized || "").match(/(\d{2}:\d{2}:\d{2})/);
          if (tm) this.pickerDefaultTime = tm[1];
          syncHidden(hidden, this.stored);
          if (!silent && changeFn) changeFn(this.stored);
          this.$nextTick(() => this.forceInputDisplay(normalized));
        },
        previewValue(next) {
          const normalized = normalizeApiValue(next);
          if (!normalized) return;
          this.syncDisplayText(normalized);
          if (changeFn) changeFn(normalized);
        },
        bindPanelDateWatch() {
          const dp = this.$refs.dp;
          const picker = dp?.picker;
          if (!picker || this.panelDateUnwatch) return;
          this.panelDateUnwatch = picker.$watch("date", (d) => {
            if (!d || !dp.pickerVisible) return;
            const val = formatDateParts(d instanceof Date ? d : new Date(d));
            this.previewValue(val);
          }, { deep: true });
        },
        syncFromOpenPanel() {
          const dp = this.$refs.dp;
          const picker = dp?.picker;
          if (!picker?.date) return;
          const val = formatDateParts(picker.date instanceof Date ? picker.date : new Date(picker.date));
          this.applyValue(val);
        },
        forceInputDisplay(normalized) {
          const dp = this.$refs.dp;
          const inner = dp?.$el?.querySelector(".el-input__inner");
          if (!inner) return;
          inner.value = normalized ? formatDisplayValue(normalized, this.pickerFormat) : "";
        },
        onPickerPick(date) {
          if (!date) return;
          const d = date instanceof Date ? date : new Date(date);
          if (Number.isNaN(d.getTime())) return;
          this.applyValue(formatDateParts(d));
        },
        onPickerInput(val) {
          this.applyValue(val);
        },
        onPickerChange(val) {
          this.applyValue(val);
        },
        openPickerPanel() {
          openPickerPanel(this);
        },
        focusInput() {
          this.openPickerPanel();
        }
      },
      template: compact ? `
        <div class="fr-dt-compact-wrap">
          <div class="fr-dt-compact-display" :class="{ 'is-empty': !displayText }">{{ displayText || '选择日期时间' }}</div>
          <el-date-picker
            ref="dp"
            v-model="dtValue"
            type="datetime"
            align="right"
            :append-to-body="true"
            placeholder="选择日期时间"
            :format="pickerFormat"
            value-format="yyyy-MM-dd HH:mm:ss"
            :size="pickerSize"
            :editable="false"
            clearable
            :default-time="pickerDefaultTime"
            @focus="openPickerPanel"
            @input="onPickerInput"
            @pick="onPickerPick"
            @change="onPickerChange"
          />
        </div>
      ` : `
        <el-date-picker
          ref="dp"
          v-model="dtValue"
          type="datetime"
          align="right"
          :append-to-body="true"
          placeholder="选择日期时间"
          :format="pickerFormat"
          value-format="yyyy-MM-dd HH:mm:ss"
          :size="pickerSize"
          :editable="false"
          clearable
          :default-time="pickerDefaultTime"
          @focus="openPickerPanel"
          @input="onPickerInput"
          @pick="onPickerPick"
          @change="onPickerChange"
        />
      `
    });
    return vm;
  }

  let sheetVm = null;
  let sheetMountEl = null;

  function destroySheet() {
    destroyVm(sheetVm);
    sheetVm = null;
    if (sheetMountEl) sheetMountEl.innerHTML = "";
    sheetMountEl = null;
  }

  function mountSheet(container, { value = "", onChange = null } = {}) {
    if (!global.Vue || !global.ELEMENT) return false;
    const host = typeof container === "string" ? document.querySelector(container) : container;
    if (!host) return false;
    destroySheet();
    sheetMountEl = host;
    host.innerHTML = "";
    const inner = document.createElement("div");
    host.appendChild(inner);
    sheetVm = createPickerVm(inner, {
      value,
      size: "mini",
      autoOpen: true,
      stopPropagation: true,
      onChange,
      displayFormat: "yyyy-MM-dd HH:mm",
      compact: true
    });
    return !!sheetVm;
  }

  function getSheetValue() {
    if (!sheetVm) return "";
    return normalizeApiValue(sheetVm.stored || sheetVm.dtValue || "");
  }

  function focusSheet() {
    openPickerPanel(sheetVm);
  }

  function destroyInline(mountEl) {
    if (!mountEl) return;
    destroyVm(mountEl.__frDtVm);
    mountEl.__frDtVm = null;
    mountEl.innerHTML = "";
    mountEl.removeAttribute("data-fr-dt-mounted");
  }

  function mountInline(mountEl, hiddenEl, { value = "" } = {}) {
    if (!mountEl || mountEl.getAttribute("data-fr-dt-mounted") === "1") return;
    destroyInline(mountEl);
    mountEl.setAttribute("data-fr-dt-mounted", "1");
    mountEl.innerHTML = "";
    const inner = document.createElement("div");
    mountEl.appendChild(inner);
    const initial = normalizeApiValue(value || mountEl.getAttribute("data-rc-value") || hiddenEl?.value || "");
    mountEl.__frDtVm = createPickerVm(inner, {
      value: initial,
      hidden: hiddenEl,
      size: "small",
      autoOpen: false,
      stopPropagation: false
    });
  }

  function mountAllInline(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll(".rc-datetime-mount:not([data-fr-dt-mounted]), .fe-datetime-mount:not([data-fr-dt-mounted])").forEach((el) => {
      const key = el.getAttribute("data-rc-field") || "";
      const hidden = key
        ? scope.querySelector(`[data-rc-field-hidden="${key}"]`)
        : scope.querySelector("#fieldEditDefault");
      mountInline(el, hidden, { value: el.getAttribute("data-rc-value") || hidden?.value || "" });
    });
  }

  function destroyAllInline(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll(".rc-datetime-mount, .fe-datetime-mount").forEach((el) => destroyInline(el));
  }

  global.FrDateTimePicker = {
    mountSheet,
    destroySheet,
    getSheetValue,
    focusSheet,
    mountInline,
    destroyInline,
    mountAllInline,
    destroyAllInline,
    normalizeApiValue,
    openPickerPanel
  };
})(typeof window !== "undefined" ? window : globalThis);
