/**
 * 中文备注：剪辑人员分组面板（名单来自后台启用账户，归属可编辑）。
 */
(function (global) {
  "use strict";

  var DEFERRED_GROUP = "暂不分配";

  function groupDisplayLabel(groupName, deferredLabel) {
    var g = (groupName || "").trim();
    var deferred = (deferredLabel || DEFERRED_GROUP).trim();
    if (!g) return { text: "未分组", muted: true, deferred: false };
    if (g === deferred) return { text: deferred, muted: false, deferred: true };
    return { text: g, muted: false, deferred: false };
  }

  function createEditorGroupPanel(deps) {
    var api = deps.api;
    var toast = deps.toast;
    var Vue = deps.Vue;
    var mountEl = deps.mountEl;

    var vm = null;

    function ensureInit() {
      if (vm || !Vue || !mountEl) return vm;
      vm = new Vue({
        el: mountEl,
        template: [
          "<div>",
          "  <div class=\"toolbar assign-toolbar\">",
          "    <el-button size=\"small\" :loading=\"loading\" @click=\"loadMembersNow\">刷新</el-button>",
          "    <span style=\"color:#6b7280;font-size:12px\">剪辑名单来自后台启用账户；账户停用后不再参与分配</span>",
          "  </div>",
          "  <div class=\"table-wrap assign-table-wrap\">",
          "    <el-table :data=\"memberRows\" size=\"small\" v-loading=\"loading\" empty-text=\"暂无剪辑\" style=\"width:100%\">",
          "      <el-table-column label=\"剪辑\" min-width=\"120\"><template slot-scope=\"{ row }\"><strong>{{ row.editorName }}</strong></template></el-table-column>",
          "      <el-table-column label=\"所属分组\" min-width=\"140\">",
          "        <template slot-scope=\"{ row }\">",
          "          <span :style=\"rowGroupStyle(row)\">{{ rowGroupText(row) }}</span>",
          "        </template>",
          "      </el-table-column>",
          "      <el-table-column label=\"更新时间\" min-width=\"160\">",
          "        <template slot-scope=\"{ row }\">",
          "          <span style=\"color:#6b7280;white-space:nowrap\">{{ row.updateTime || '-' }}</span>",
          "          <div v-if=\"row.updatedBy\" style=\"font-size:11px;color:#9ca3af\">{{ row.updatedBy }}</div>",
          "        </template>",
          "      </el-table-column>",
          "      <el-table-column label=\"操作\" min-width=\"80\" fixed=\"right\">",
          "        <template slot-scope=\"{ row }\"><el-button type=\"text\" size=\"mini\" @click=\"openEdit(row)\">编辑</el-button></template>",
          "      </el-table-column>",
          "    </el-table>",
          "  </div>",
          "  <el-dialog title=\"编辑分组\" :visible.sync=\"dialogVisible\" width=\"420px\" append-to-body custom-class=\"admin-account-dialog\" :close-on-click-modal=\"false\">",
          "    <el-form label-width=\"88px\" size=\"small\" @submit.native.prevent=\"submitDialog\">",
          "      <el-form-item label=\"剪辑\">",
          "        <el-input v-model=\"form.editorName\" disabled></el-input>",
          "      </el-form-item>",
          "      <el-form-item label=\"分组\">",
          "        <el-select v-model=\"form.groupName\" placeholder=\"选择分组\" filterable style=\"width:100%\">",
          "          <el-option :label=\"deferredGroupLabel\" :value=\"deferredGroupLabel\"></el-option>",
          "          <el-option v-for=\"g in groupOptions\" :key=\"g\" :label=\"g\" :value=\"g\"></el-option>",
          "        </el-select>",
          "      </el-form-item>",
          "      <div class=\"form-tip\">「暂不分配」：该剪辑尚未划入小组，不参与按人锁定剪辑小组。</div>",
          "    </el-form>",
          "    <span slot=\"footer\" class=\"dialog-footer\">",
          "      <el-button @click=\"dialogVisible = false\">取消</el-button>",
          "      <el-button type=\"primary\" :loading=\"dialogSaving\" @click=\"submitDialog\">确认</el-button>",
          "    </span>",
          "  </el-dialog>",
          "</div>"
        ].join("\n"),
        data: function () {
          return {
            loading: false,
            members: [],
            groupOptions: [],
            deferredGroupLabel: DEFERRED_GROUP,
            dialogVisible: false,
            dialogSaving: false,
            form: { editorName: "", groupName: "" }
          };
        },
        computed: {
          memberRows: function () {
            var self = this;
            var deferred = self.deferredGroupLabel;
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
          }
        },
        methods: {
          rowGroupText: function (row) {
            return groupDisplayLabel(row.groupName, this.deferredGroupLabel).text;
          },
          rowGroupStyle: function (row) {
            var info = groupDisplayLabel(row.groupName, this.deferredGroupLabel);
            if (info.muted) return "color:#9ca3af";
            if (info.deferred) return "color:#a16207";
            return "";
          },
          loadMembersNow: function () {
            return this._loadMembers();
          },
          _loadMembers: async function () {
            if (this.loading) return;
            this.loading = true;
            try {
              var data = await api("/api/v1/ops/admin/leader-group/editor-members");
              this.members = data.members || [];
              this.groupOptions = data.editGroups || [];
              if (data.deferredGroupLabel) {
                this.deferredGroupLabel = data.deferredGroupLabel;
              }
            } catch (e) {
              toast(e.message, true);
            } finally {
              this.loading = false;
            }
          },
          openEdit: function (row) {
            var g = (row.groupName || "").trim();
            this.form = {
              editorName: row.editorName || "",
              groupName: g || this.deferredGroupLabel
            };
            this.dialogVisible = true;
          },
          submitDialog: async function () {
            if (this.dialogSaving) return;
            var editor = (this.form.editorName || "").trim();
            if (!editor) {
              toast("缺少剪辑", true);
              return;
            }
            var group = (this.form.groupName || "").trim();
            if (!group) {
              toast("请选择分组", true);
              return;
            }
            this.dialogSaving = true;
            try {
              await api("/api/v1/ops/admin/leader-group/editor-members/update", {
                method: "POST",
                body: JSON.stringify({
                  editorName: editor,
                  groupName: group
                })
              });
              toast("分组已保存");
              this.dialogVisible = false;
              await this._loadMembers();
            } catch (e) {
              toast(e.message || "保存失败", true);
            } finally {
              this.dialogSaving = false;
            }
          }
        }
      });
      return vm;
    }

    return {
      ensureInit: ensureInit,
      loadMembers: function () {
        ensureInit();
        if (!vm) return Promise.resolve();
        return vm.loadMembersNow();
      }
    };
  }

  global.OpsAdminEditorGroupPanel = { create: createEditorGroupPanel, DEFERRED_GROUP: DEFERRED_GROUP };
})(typeof window !== "undefined" ? window : globalThis);
