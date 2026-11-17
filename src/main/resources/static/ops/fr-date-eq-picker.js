/**
 * 中文备注：筛选日期值控件——Element UI date（无快捷项，等于/晚于/早于样式一致）。
 * 依赖全局 Vue + ELEMENT；值写入 hidden .fr-date-eq-value（yyyy-MM-dd）。
 * 整框可点打开日历，避免只能点角落图标。
 */
(function (global) {
  const presets = global.OpsDatePresets;
  if (!presets?.LABELS) {
    throw new Error("fr-date-eq-picker: 请先加载 ops-date-presets.js");
  }
  const PRESET_LABELS = presets.LABELS;
  const PRESET_SET = presets.PRESET_SET;

  function pad2(n) { return String(n).padStart(2, "0"); }
  function formatIso(d) {
    if (!(d instanceof Date) || Number.isNaN(d.getTime())) return "";
    return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  }
  function parseIso(text) {
    const m = String(text || "").trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!m) return null;
    const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
    return Number.isNaN(d.getTime()) ? null : d;
  }

  function ensureLocale() {
    if (!global.ELEMENT) return;
    if (global.ELEMENT.lang && global.ELEMENT.lang.zhCN) {
      global.ELEMENT.locale(global.ELEMENT.lang.zhCN);
    }
  }

  function openPickerPanel(vm) {
    const dp = vm?.$refs?.dp;
    if (!dp) return;
    try { dp.focus(); } catch (_) {}
    try { dp.pickerVisible = true; } catch (_) {}
  }

  function destroyMount(el) {
    if (!el) return;
    if (el.__frDateVm) {
      try { el.__frDateVm.$destroy(); } catch (_) {}
      el.__frDateVm = null;
    }
    el.innerHTML = "";
  }

  function mountOne(el, onChange) {
    if (!global.Vue || !global.ELEMENT) return;
    ensureLocale();
    destroyMount(el);
    const initialRaw = String(el.getAttribute("data-fr-date-value") || "").trim();
    const disabled = el.getAttribute("data-disabled") === "1";
    const parsed = parseIso(initialRaw);

    const hidden = document.createElement("input");
    hidden.type = "hidden";
    hidden.className = "fr-date-eq-value";
    el.appendChild(hidden);

    const mount = document.createElement("div");
    el.appendChild(mount);

    const vm = new global.Vue({
      el: mount,
      data() {
        const iso = parsed ? initialRaw.match(/^(\d{4}-\d{2}-\d{2})/)[1] : "";
        return { stored: iso, dateValue: iso, disabled };
      },
      watch: {
        dateValue(next) {
          if (!next) {
            this.stored = "";
            this.syncHidden();
            this.emitChange();
            return;
          }
          this.stored = next;
          this.syncHidden();
          this.emitChange();
        }
      },
      mounted() {
        this.syncHidden();
        this.$nextTick(() => {
          const dp = this.$refs.dp;
          if (dp?.$el) {
            dp.$el.classList.add("fr-dt-editor");
            dp.$el.addEventListener("mousedown", () => this.openPickerPanel(), true);
          }
        });
      },
      methods: {
        openPickerPanel() {
          openPickerPanel(this);
        },
        syncHidden() {
          hidden.value = this.stored || "";
        },
        emitChange() {
          this.syncHidden();
          hidden.dispatchEvent(new Event("input", { bubbles: true }));
          hidden.dispatchEvent(new Event("change", { bubbles: true }));
          if (typeof onChange === "function") onChange(hidden.value);
        },
        onPick(val) {
          if (!val) {
            this.stored = "";
            this.dateValue = "";
            this.syncHidden();
            this.emitChange();
            return;
          }
          const iso = typeof val === "string" ? val : formatIso(val);
          if (!iso) return;
          this.stored = iso;
          this.dateValue = iso;
          this.syncHidden();
          this.emitChange();
        }
      },
      template: `
        <el-date-picker
          ref="dp"
          v-model="dateValue"
          type="date"
          align="right"
          placeholder="选择日期"
          format="yyyy/MM/dd"
          value-format="yyyy-MM-dd"
          :disabled="disabled"
          size="small"
          :editable="false"
          clearable
          @change="onPick"
          @focus="openPickerPanel"
        />
      `
    });
    el.__frDateVm = vm;
  }

  function mountAll(root, onChange) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll(".fr-date-eq-mount:not([data-fr-mounted])").forEach((el) => {
      el.setAttribute("data-fr-mounted", "1");
      mountOne(el, onChange);
    });
  }

  function destroyAll(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll(".fr-date-eq-mount").forEach((el) => {
      el.removeAttribute("data-fr-mounted");
      destroyMount(el);
    });
  }

  global.FrDateEqPicker = { mountAll, destroyAll, PRESET_SET, PRESET_LABELS };
})(typeof window !== "undefined" ? window : globalThis);
