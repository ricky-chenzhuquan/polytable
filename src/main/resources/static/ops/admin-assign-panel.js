/**
 * 中文备注：成员分配入口（人员分配 + 按发布时间分配 + 按创建时间分配），单 Vue 实例避免嵌套挂载错乱。
 */
(function (global) {
  "use strict";

  var DEBOUNCE_MS = 300;
  var RANGE_STORAGE_KEY = "ops.admin.assignDateRange";
  var CREATE_RANGE_STORAGE_KEY = "ops.admin.assignCreateDateRange";
  var SUB_TAB_STORAGE_KEY = "ops.admin.assignSubTab";
  var DEFERRED_GROUP = (global.OpsAdminEditorGroupPanel && global.OpsAdminEditorGroupPanel.DEFERRED_GROUP) || "暂不分配";
  var MEMBER_PAGE_SIZE = 10;
  var TASK_PAGE_SIZE = 10;

  function formatDateInput(d) {
    var y = d.getFullYear();
    var m = String(d.getMonth() + 1).padStart(2, "0");
    var day = String(d.getDate()).padStart(2, "0");
    return y + "-" + m + "-" + day;
  }

  function defaultAssignDateRange() {
    var today = new Date();
    var from = new Date(today);
    from.setDate(from.getDate() - 30);
    var to = new Date(today);
    to.setDate(to.getDate() + 90);
    return { from: formatDateInput(from), to: formatDateInput(to) };
  }

  function defaultCreateAssignDateRange() {
    var today = new Date();
    var from = new Date(today);
    from.setDate(from.getDate() - 30);
    return { from: formatDateInput(from), to: formatDateInput(today) };
  }

  function loadPersistedDateRange(storageKey, fallbackFn) {
    try {
      var raw = localStorage.getItem(storageKey);
      if (!raw) return fallbackFn();
      var parsed = JSON.parse(raw);
      var from = String(parsed.from || "").trim();
      var to = String(parsed.to || "").trim();
      if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
        return fallbackFn();
      }
      if (from > to) return fallbackFn();
      return { from: from, to: to };
    } catch (_) {
      return fallbackFn();
    }
  }

  function persistDateRange(storageKey, from, to) {
    try {
      localStorage.setItem(storageKey, JSON.stringify({ from: from, to: to }));
    } catch (_) {}
  }

  function loadPersistedSubTab() {
    try {
      var tab = localStorage.getItem(SUB_TAB_STORAGE_KEY);
      if (tab === "delivery" || tab === "create" || tab === "tasks") return tab === "tasks" ? "delivery" : tab;
      return "members";
    } catch (_) {
      return "members";
    }
  }

  function groupDisplayLabel(groupName, deferredLabel) {
    var g = (groupName || "").trim();
    var deferred = (deferredLabel || DEFERRED_GROUP).trim();
    if (!g) return { text: "未分组", muted: true, deferred: false };
    if (g === deferred) return { text: deferred, muted: false, deferred: true };
    return { text: g, muted: false, deferred: false };
  }

  function createAssignPanel(deps) {
    var api = deps.api;
    var toast = deps.toast;
    var Vue = deps.Vue;
    var mountEl = deps.mountEl || "#assignPanelApp";
    var onRulesLoaded = deps.onRulesLoaded || function () {};

    var vm = null;
    var loadTimer = null;
    var deliveryRange = loadPersistedDateRange(RANGE_STORAGE_KEY, defaultAssignDateRange);
    var createRange = loadPersistedDateRange(CREATE_RANGE_STORAGE_KEY, defaultCreateAssignDateRange);

    function ensureInit() {
      if (vm || !Vue) return vm;

      vm = new Vue({
        el: mountEl,
        template: [
          "<div class=\"assign-panel-root\">",
          "  <div class=\"assign-sub-tabs\">",
          "    <el-button round size=\"small\" :type=\"subTab === 'members' ? 'primary' : 'default'\" @click=\"pickSubTab('members')\">人员分配</el-button>",
          "    <el-button round size=\"small\" :type=\"subTab === 'delivery' ? 'primary' : 'default'\" @click=\"pickSubTab('delivery')\">按发布时间分配</el-button>",
          "    <el-button round size=\"small\" :type=\"subTab === 'create' ? 'primary' : 'default'\" @click=\"pickSubTab('create')\">按创建时间分配</el-button>",
          "  </div>",
          "  <div v-show=\"subTab === 'members'\">",
          "    <div class=\"toolbar assign-toolbar\">",
          "      <el-button size=\"small\" :loading=\"memberLoading\" @click=\"loadMembersNow\">刷新</el-button>",
          "      <span style=\"color:#6b7280;font-size:12px\">均分/任务配置读后台启用账户（工作区「负责人」下拉仍由字段配置维护）</span>",
          "    </div>",
          "    <div class=\"table-wrap assign-table-wrap\">",
          "      <el-table :data=\"memberPageRows\" size=\"small\" border stripe v-loading=\"memberLoading\" empty-text=\"暂无成员\" style=\"width:100%\">",
          "        <el-table-column label=\"成员\" min-width=\"120\"><template slot-scope=\"{ row }\"><strong>{{ row.editorName }}</strong></template></el-table-column>",
          "        <el-table-column label=\"所属分组\" min-width=\"140\">",
          "          <template slot-scope=\"{ row }\"><span :style=\"rowGroupStyle(row)\">{{ rowGroupText(row) }}</span></template>",
          "        </el-table-column>",
          "        <el-table-column label=\"更新时间\" min-width=\"160\">",
          "          <template slot-scope=\"{ row }\">",
          "            <span style=\"color:#6b7280;white-space:nowrap\">{{ row.updateTime || '-' }}</span>",
          "            <div v-if=\"row.updatedBy\" style=\"font-size:11px;color:#9ca3af\">{{ row.updatedBy }}</div>",
          "          </template>",
          "        </el-table-column>",
          "        <el-table-column label=\"操作\" min-width=\"80\" fixed=\"right\">",
          "          <template slot-scope=\"{ row }\"><el-button type=\"text\" size=\"mini\" @click=\"openMemberEdit(row)\">编辑</el-button></template>",
          "        </el-table-column>",
          "      </el-table>",
          "    </div>",
          "    <div class=\"pager-wrap assign-pager-wrap\" v-if=\"memberRows.length > 0\">",
          "      <el-pagination background layout=\"total, prev, pager, next, jumper\" :current-page=\"memberPage\" :page-size=\"memberPageSize\" :total=\"memberRows.length\" @current-change=\"onMemberPageChange\"></el-pagination>",
          "    </div>",
          "    <el-dialog title=\"编辑分组\" :visible.sync=\"memberDialogVisible\" width=\"420px\" append-to-body custom-class=\"admin-account-dialog\" :close-on-click-modal=\"false\">",
          "      <el-form label-width=\"88px\" size=\"small\" @submit.native.prevent=\"submitMemberDialog\">",
          "        <el-form-item label=\"成员\"><el-input v-model=\"memberForm.editorName\" disabled></el-input></el-form-item>",
          "        <el-form-item label=\"分组\">",
          "          <el-select v-model=\"memberForm.groupName\" placeholder=\"选择分组\" filterable style=\"width:100%\">",
          "            <el-option :label=\"deferredGroupLabel\" :value=\"deferredGroupLabel\"></el-option>",
          "            <el-option v-for=\"g in memberGroupOptions\" :key=\"g\" :label=\"g\" :value=\"g\"></el-option>",
          "          </el-select>",
          "        </el-form-item>",
          "      </el-form>",
          "      <span slot=\"footer\" class=\"dialog-footer\">",
          "        <el-button @click=\"memberDialogVisible = false\">取消</el-button>",
          "        <el-button type=\"primary\" :loading=\"memberDialogSaving\" @click=\"submitMemberDialog\">确认</el-button>",
          "      </span>",
          "    </el-dialog>",
          "  </div>",
          "  <div v-show=\"subTab === 'delivery' || subTab === 'create'\">",
          "    <div v-if=\"assignConfigBanner\" class=\"form-tip\" style=\"margin:0 0 10px;padding:8px 12px;background:#fffbeb;border-radius:8px;color:#92400e\">{{ assignConfigBanner }}</div>",
          "    <div class=\"toolbar assign-toolbar\">",
          "      <el-date-picker v-model=\"taskDateFrom\" type=\"date\" value-format=\"yyyy-MM-dd\" :placeholder=\"taskDateFromPlaceholder\" size=\"small\" @change=\"scheduleTaskLoad\"></el-date-picker>",
          "      <el-date-picker v-model=\"taskDateTo\" type=\"date\" value-format=\"yyyy-MM-dd\" :placeholder=\"taskDateToPlaceholder\" size=\"small\" @change=\"scheduleTaskLoad\"></el-date-picker>",
          "      <el-button size=\"small\" :loading=\"taskLoading\" @click=\"loadTasksNow\">刷新</el-button>",
          "      <el-button type=\"primary\" size=\"small\" @click=\"openTaskCreate\">新增任务</el-button>",
          "    </div>",
          "    <div class=\"table-wrap assign-table-wrap\">",
          "      <el-table :data=\"taskPageRows\" size=\"small\" border stripe v-loading=\"taskLoading\" empty-text=\"暂无任务\" style=\"width:100%\">",
          "        <el-table-column :label=\"taskDateColumnLabel\" min-width=\"120\"><template slot-scope=\"{ row }\"><strong>{{ row.deliveryDate }}</strong></template></el-table-column>",
          "        <el-table-column :label=\"taskTargetColumnLabel\" min-width=\"140\"><template slot-scope=\"{ row }\">{{ row.group }}</template></el-table-column>",
          "        <el-table-column label=\"分配状态\" min-width=\"100\">",
          "          <template slot-scope=\"{ row }\"><el-tag :type=\"row.paused ? 'info' : 'success'\" size=\"mini\">{{ row.statusLabel }}</el-tag></template>",
          "        </el-table-column>",
          "        <el-table-column label=\"操作\" min-width=\"80\" fixed=\"right\">",
          "          <template slot-scope=\"{ row }\"><el-button type=\"text\" size=\"mini\" @click=\"openTaskEdit(row)\">编辑</el-button></template>",
          "        </el-table-column>",
          "      </el-table>",
          "    </div>",
          "    <div class=\"pager-wrap assign-pager-wrap\" v-if=\"taskRows.length > 0\">",
          "      <el-pagination background layout=\"total, prev, pager, next, jumper\" :current-page=\"taskPage\" :page-size=\"taskPageSize\" :total=\"taskRows.length\" @current-change=\"onTaskPageChange\"></el-pagination>",
          "    </div>",
          "    <el-dialog :title=\"taskDialogTitle\" :visible.sync=\"taskDialogVisible\" width=\"420px\" append-to-body custom-class=\"admin-account-dialog\" :close-on-click-modal=\"false\">",
          "      <el-form label-width=\"88px\" size=\"small\" @submit.native.prevent=\"submitTaskDialog\">",
          "        <el-form-item :label=\"taskTargetColumnLabel\">",
          "          <el-select v-model=\"taskForm.targets\" :placeholder=\"taskTargetSelectPlaceholder\" filterable multiple collapse-tags style=\"width:100%\">",
          "            <el-option v-for=\"g in groupOptions\" :key=\"g\" :label=\"g\" :value=\"g\"></el-option>",
          "          </el-select>",
          "        </el-form-item>",
          "        <el-form-item :label=\"taskDateColumnLabel\">",
          "          <el-date-picker v-model=\"taskForm.deliveryDate\" type=\"date\" value-format=\"yyyy-MM-dd\" :placeholder=\"taskDatePickerPlaceholder\" style=\"width:100%\"></el-date-picker>",
          "        </el-form-item>",
          "        <el-form-item label=\"分配状态\">",
          "          <el-radio-group v-model=\"taskForm.statusKey\" size=\"small\">",
          "            <el-radio-button label=\"active\">启用</el-radio-button>",
          "            <el-radio-button label=\"pause\">暂停</el-radio-button>",
          "          </el-radio-group>",
          "        </el-form-item>",
          "      </el-form>",
          "      <span slot=\"footer\" class=\"dialog-footer\">",
          "        <el-button @click=\"taskDialogVisible = false\">取消</el-button>",
          "        <el-button type=\"primary\" :loading=\"taskDialogSaving\" @click=\"submitTaskDialog\">确定</el-button>",
          "      </span>",
          "    </el-dialog>",
          "  </div>",
          "</div>"
        ].join("\n"),
        data: function () {
          return {
            subTab: loadPersistedSubTab(),
            memberLoading: false,
            members: [],
            memberGroupOptions: [],
            deferredGroupLabel: DEFERRED_GROUP,
            memberDialogVisible: false,
            memberDialogSaving: false,
            memberForm: { editorName: "", groupName: "" },
            memberPage: 1,
            memberPageSize: MEMBER_PAGE_SIZE,
            taskLoading: false,
            taskPage: 1,
            taskPageSize: TASK_PAGE_SIZE,
            deliveryDateFrom: deliveryRange.from,
            deliveryDateTo: deliveryRange.to,
            createDateFrom: createRange.from,
            createDateTo: createRange.to,
            rules: [],
            groupOptions: [],
            assignConfig: {},
            dateFieldLabel: "",
            taskDialogVisible: false,
            taskDialogSaving: false,
            taskDialogMode: "create",
            taskDialogTitle: "",
            taskDialogOrigin: null,
            taskForm: { targets: [], deliveryDate: "", statusKey: "pause" }
          };
        },
        computed: {
          taskAssignBy: function () {
            return this.subTab === "create" ? "create" : "delivery";
          },
          taskDateFrom: {
            get: function () {
              return this.subTab === "create" ? this.createDateFrom : this.deliveryDateFrom;
            },
            set: function (v) {
              if (this.subTab === "create") this.createDateFrom = v;
              else this.deliveryDateFrom = v;
            }
          },
          taskDateTo: {
            get: function () {
              return this.subTab === "create" ? this.createDateTo : this.deliveryDateTo;
            },
            set: function (v) {
              if (this.subTab === "create") this.createDateTo = v;
              else this.deliveryDateTo = v;
            }
          },
          taskDateColumnLabel: function () {
            return this.subTab === "create" ? "承接日期（请假日期）" : "发布时间";
          },
          taskDateFromPlaceholder: function () {
            return this.subTab === "create" ? "起始承接日期" : "起始发布日";
          },
          taskDateToPlaceholder: function () {
            return this.subTab === "create" ? "结束承接日期" : "结束发布日";
          },
          taskDatePickerPlaceholder: function () {
            return this.subTab === "create" ? "选择请假/承接日期" : "选择发布时间";
          },
          taskTargetColumnLabel: function () {
            return this.subTab === "create" ? "成员（可多选）" : "小组（可多选）";
          },
          taskTargetSelectPlaceholder: function () {
            return this.subTab === "create" ? "批量选择成员" : "批量选择小组";
          },
          assignConfigBanner: function () {
            var cfg = this.assignConfig || {};
            if (this.subTab === "create" && cfg.createEditorAssignEnabled === false) {
              return "自动分配，保存后不生效。";
            }
            if (this.subTab === "delivery" && cfg.groupAssignEnabled === false) {
              return "小组自动分配已关闭，保存后不生效。";
            }
            return "";
          },
          memberRows: function () {
            var deferred = this.deferredGroupLabel;
            var rows = (this.members || []).slice();
            rows.sort(function (a, b) {
              function rank(row) {
                var g = (row.groupName || "").trim();
                if (!g) return 2;
                if (g === deferred) return 1;
                return 0;
              }
              var ra = rank(a);
              var rb = rank(b);
              if (ra !== rb) return ra - rb;
              var ga = a.groupName || "";
              var gb = b.groupName || "";
              if (ga !== gb) {
                var cg = ga.localeCompare(gb, "zh");
                if (cg !== 0) return cg;
              }
              return (a.editorName || "").localeCompare(b.editorName || "", "zh");
            });
            return rows;
          },
          memberPageRows: function () {
            var page = Math.max(1, this.memberPage || 1);
            var size = this.memberPageSize || MEMBER_PAGE_SIZE;
            var start = (page - 1) * size;
            return this.memberRows.slice(start, start + size);
          },
          taskPageRows: function () {
            var page = Math.max(1, this.taskPage || 1);
            var size = this.taskPageSize || TASK_PAGE_SIZE;
            var start = (page - 1) * size;
            return this.taskRows.slice(start, start + size);
          },
          taskRows: function () {
            var rows = [];
            var rules = this.rules || [];
            for (var i = 0; i < rules.length; i++) {
              var rule = rules[i];
              var groupsInRule = Array.isArray(rule.excludedGroups) ? rule.excludedGroups : [];
              for (var j = 0; j < groupsInRule.length; j++) {
                var group = groupsInRule[j];
                rows.push({
                  ruleId: rule.id,
                  assignBy: rule.assignBy || this.taskAssignBy,
                  group: group,
                  deliveryDate: rule.deliveryDate || "",
                  paused: rule.paused != null ? !!rule.paused : !!rule.enabled,
                  statusLabel: rule.statusLabel || (rule.enabled ? "暂停" : "启用"),
                  excludedGroups: groupsInRule.slice()
                });
              }
            }
            rows.sort(function (a, b) {
              var d = (b.deliveryDate || "").localeCompare(a.deliveryDate || "");
              if (d !== 0) return d;
              return a.group.localeCompare(b.group, "zh");
            });
            return rows;
          }
        },
        methods: {
          pickSubTab: function (name) {
            var next = name === "delivery" || name === "create" ? name : "members";
            this.subTab = next;
            try { localStorage.setItem(SUB_TAB_STORAGE_KEY, this.subTab); } catch (_) {}
            if (this.subTab === "members") this.loadMembersNow();
            else this.loadTasksNow();
          },
          rowGroupText: function (row) {
            return groupDisplayLabel(row.groupName, this.deferredGroupLabel).text;
          },
          rowGroupStyle: function (row) {
            var info = groupDisplayLabel(row.groupName, this.deferredGroupLabel);
            if (info.muted) return "color:#9ca3af";
            if (info.deferred) return "color:#a16207";
            return "";
          },
          onMemberPageChange: function (page) {
            this.memberPage = page || 1;
          },
          onTaskPageChange: function (page) {
            this.taskPage = page || 1;
          },
          loadMembersNow: function () {
            return this._loadMembers();
          },
          _loadMembers: async function () {
            if (this.memberLoading) return;
            this.memberLoading = true;
            try {
              var data = await api("/api/v1/ops/admin/leader-group/editor-members");
              this.members = data.members || [];
              this.memberGroupOptions = data.editGroups || [];
              if (data.assignConfig) this.assignConfig = data.assignConfig;
              if (data.deferredGroupLabel) this.deferredGroupLabel = data.deferredGroupLabel;
              this.memberPage = 1;
            } catch (e) {
              toast(e.message, true);
            } finally {
              this.memberLoading = false;
            }
          },
          openMemberEdit: function (row) {
            var g = (row.groupName || "").trim();
            this.memberForm = {
              editorName: row.editorName || "",
              groupName: g || this.deferredGroupLabel
            };
            this.memberDialogVisible = true;
          },
          submitMemberDialog: async function () {
            if (this.memberDialogSaving) return;
            var editor = (this.memberForm.editorName || "").trim();
            var group = (this.memberForm.groupName || "").trim();
            if (!editor) { toast("缺少成员", true); return; }
            if (!group) { toast("请选择分组", true); return; }
            this.memberDialogSaving = true;
            try {
              await api("/api/v1/ops/admin/leader-group/editor-members/update", {
                method: "POST",
                body: JSON.stringify({ editorName: editor, groupName: group })
              });
              toast("分组已保存");
              this.memberDialogVisible = false;
              await this._loadMembers();
            } catch (e) {
              toast(e.message || "保存失败", true);
            } finally {
              this.memberDialogSaving = false;
            }
          },
          scheduleTaskLoad: function () {
            var self = this;
            var storageKey = self.subTab === "create" ? CREATE_RANGE_STORAGE_KEY : RANGE_STORAGE_KEY;
            persistDateRange(storageKey, self.taskDateFrom || "", self.taskDateTo || "");
            clearTimeout(loadTimer);
            loadTimer = setTimeout(function () { self.loadTasksNow(); }, DEBOUNCE_MS);
          },
          loadTasksNow: function () {
            return this._loadTasks();
          },
          _loadTasks: async function () {
            if (this.taskLoading) return;
            this.taskLoading = true;
            try {
              var qs = new URLSearchParams();
              if (this.taskDateFrom) qs.set("from", this.taskDateFrom);
              if (this.taskDateTo) qs.set("to", this.taskDateTo);
              qs.set("assignBy", this.taskAssignBy);
              var data = await api("/api/v1/ops/admin/leader-group/assign-rules?" + qs.toString());
              this.rules = data.rules || [];
              this.groupOptions = data.assignTargets || data.editGroups || [];
              this.assignConfig = data.assignConfig || {};
              this.dateFieldLabel = data.dateFieldLabel || this.taskDateColumnLabel;
              onRulesLoaded(this.rules, this.groupOptions);
              this.taskPage = 1;
            } catch (e) {
              toast(e.message, true);
            } finally {
              this.taskLoading = false;
            }
          },
          openTaskCreate: function () {
            this.taskDialogMode = "create";
            this.taskDialogTitle = this.subTab === "create" ? "批量新增创建日任务" : "批量新增发布日任务";
            this.taskDialogOrigin = null;
            this.taskForm = { targets: [], deliveryDate: "", statusKey: "pause" };
            this.taskDialogVisible = true;
          },
          openTaskEdit: function (row) {
            this.taskDialogMode = "edit";
            this.taskDialogTitle = this.subTab === "create" ? "编辑创建日任务" : "编辑发布日任务";
            this.taskDialogOrigin = row;
            var rule = this.findRuleById(row.ruleId);
            var targets = rule && Array.isArray(rule.excludedGroups) ? rule.excludedGroups.slice() : [];
            if (!targets.length && row.group) targets = [row.group];
            this.taskForm = {
              targets: targets,
              deliveryDate: row.deliveryDate || "",
              statusKey: row.paused ? "pause" : "active"
            };
            this.taskDialogVisible = true;
          },
          normalizeTargets: function (targets) {
            var out = [];
            var seen = {};
            (targets || []).forEach(function (t) {
              var v = String(t || "").trim();
              if (!v || seen[v]) return;
              seen[v] = true;
              out.push(v);
            });
            return out;
          },
          filterValidTargets: function (targets) {
            var allowed = {};
            (this.groupOptions || []).forEach(function (g) { allowed[g] = true; });
            return this.normalizeTargets(targets).filter(function (t) { return !!allowed[t]; });
          },
          findRuleById: function (id) {
            return (this.rules || []).find(function (r) { return Number(r.id) === Number(id); }) || null;
          },
          buildTaskPayload: function (targets, day, statusKey) {
            return {
              deliveryDate: day,
              assignBy: this.taskAssignBy,
              excludedGroups: this.normalizeTargets(targets),
              expectedDramaCount: null,
              remark: "",
              enabled: statusKey === "pause"
            };
          },
          submitTaskDialog: async function () {
            if (this.taskDialogSaving) return;
            var rawTargets = this.normalizeTargets(this.taskForm.targets);
            var targets = this.filterValidTargets(rawTargets);
            var day = (this.taskForm.deliveryDate || "").trim();
            var dayLabel = this.dateFieldLabel || (this.subTab === "create" ? "承接日期（请假日期）" : "发布时间");
            var targetLabel = this.subTab === "create" ? "成员" : "小组";
            if (!targets.length) {
              toast(rawTargets.length ? "所选" + targetLabel + "不在当前可选列表，请刷新后重试" : "请至少选择一个" + targetLabel, true);
              return;
            }
            if (!day) { toast("请选择" + dayLabel, true); return; }
            if (targets.length !== rawTargets.length) {
              toast("已忽略无效" + targetLabel + "选项", true);
            }
            this.taskDialogSaving = true;
            try {
              var origin = this.taskDialogOrigin;
              var payload = this.buildTaskPayload(targets, day, this.taskForm.statusKey);
              if (this.taskDialogMode === "edit" && origin) {
                var originRule = this.findRuleById(origin.ruleId);
                if (!originRule) {
                  toast("任务已失效，请刷新后重试", true);
                  return;
                }
                if ((originRule.deliveryDate || "") === day) {
                  await api("/api/v1/ops/admin/leader-group/assign-rules/update", {
                    method: "POST",
                    body: JSON.stringify(Object.assign({ id: originRule.id }, payload))
                  });
                } else {
                  var remain = (Array.isArray(originRule.excludedGroups) ? originRule.excludedGroups : [])
                    .filter(function (g) { return g !== origin.group; });
                  if (remain.length === 0) {
                    await api("/api/v1/ops/admin/leader-group/assign-rules/delete", {
                      method: "POST",
                      body: JSON.stringify({ id: Number(originRule.id) })
                    });
                  } else {
                    await api("/api/v1/ops/admin/leader-group/assign-rules/update", {
                      method: "POST",
                      body: JSON.stringify({
                        id: originRule.id,
                        deliveryDate: originRule.deliveryDate,
                        assignBy: originRule.assignBy || this.taskAssignBy,
                        excludedGroups: remain,
                        expectedDramaCount: null,
                        remark: "",
                        enabled: !!originRule.enabled
                      })
                    });
                  }
                  await api("/api/v1/ops/admin/leader-group/assign-rules/create", {
                    method: "POST",
                    body: JSON.stringify(payload)
                  });
                }
              } else {
                await api("/api/v1/ops/admin/leader-group/assign-rules/create", {
                  method: "POST",
                  body: JSON.stringify(payload)
                });
              }
              toast("任务已保存，将自动重分配该" + (this.subTab === "create" ? "创建窗口" : "发布日"));
              this.taskDialogVisible = false;
              await this._loadTasks();
            } catch (e) {
              toast(e.message || "保存失败", true);
            } finally {
              this.taskDialogSaving = false;
            }
          },
          removeGroupFromRule: async function (rule, group) {
            var gs = (Array.isArray(rule.excludedGroups) ? rule.excludedGroups : []).filter(function (g) {
              return g !== group;
            });
            if (gs.length === 0) {
              await api("/api/v1/ops/admin/leader-group/assign-rules/delete", {
                method: "POST",
                body: JSON.stringify({ id: Number(rule.id) })
              });
              return;
            }
            await api("/api/v1/ops/admin/leader-group/assign-rules/update", {
              method: "POST",
              body: JSON.stringify({
                id: rule.id,
                deliveryDate: rule.deliveryDate,
                assignBy: rule.assignBy || this.taskAssignBy,
                excludedGroups: gs,
                expectedDramaCount: null,
                remark: "",
                enabled: !!rule.enabled
              })
            });
          }
        },
        mounted: function () {
          if (this.subTab === "members") this.loadMembersNow();
          else this.loadTasksNow();
        }
      });
      return vm;
    }

    return {
      ensureInit: ensureInit,
      loadTasks: function () {
        ensureInit();
        if (!vm) return Promise.resolve();
        if (vm.subTab === "members") return vm.loadMembersNow();
        return vm.loadTasksNow();
      }
    };
  }

  global.OpsAdminAssignPanel = { create: createAssignPanel, formatDateInput: formatDateInput };
})(typeof window !== "undefined" ? window : globalThis);
