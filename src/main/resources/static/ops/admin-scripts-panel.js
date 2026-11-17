(function (global) {
  "use strict";

  // 中文备注：示例脚本清单。开源版不内置任何内部采集脚本，
  // 部署方可将 SCRIPT_PATHS 指向自己的 scripts/*.sh（面板只生成命令文本，不会执行）。
  var SCRIPT_PATHS = {
    comic: "scripts/example-collect-a.sh",
    real: "scripts/example-collect-b.sh"
  };

  function validDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ""))) return false;
    var parts = value.split("-").map(Number);
    var date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    return date.getUTCFullYear() === parts[0]
      && date.getUTCMonth() === parts[1] - 1
      && date.getUTCDate() === parts[2];
  }

  function buildCommand(type, startTime, endTime, publishStatus) {
    var script = SCRIPT_PATHS[type];
    if (!script || !validDate(startTime) || !validDate(endTime) || endTime < startTime) return "";
    var command = "bash " + script + " --start-time " + startTime + " --end-time " + endTime;
    if (publishStatus === "0" || publishStatus === "1") {
      command += " --publish-status " + publishStatus;
    } else if (publishStatus !== "all") {
      return "";
    }
    return command;
  }

  function create(options) {
    var Vue = options.Vue;
    var toast = options.toast || function () {};
    return new Vue({
      el: "#scriptsPanelApp",
      template: `
        <section class="admin-scripts-panel">
          <div class="scripts-panel-intro">
            <div>
              <h2>脚本命令生成</h2>
              <p>生成本地运行命令。命令只会复制到剪贴板，不会由管理页面执行。</p>
            </div>
            <span class="scripts-panel-state">示例配置</span>
          </div>
          <div class="scripts-panel-filters">
            <el-date-picker v-model="startTime" type="date" value-format="yyyy-MM-dd" placeholder="开始日期"
              :picker-options="{disabledDate: disableFuture}" size="small"></el-date-picker>
            <span class="scripts-range-separator">至</span>
            <el-date-picker v-model="endTime" type="date" value-format="yyyy-MM-dd" placeholder="结束日期"
              :picker-options="{disabledDate: disableFuture}" size="small"></el-date-picker>
            <el-radio-group v-model="publishStatus" size="small" aria-label="状态筛选">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="0">未处理</el-radio-button>
              <el-radio-button label="1">已处理</el-radio-button>
            </el-radio-group>
          </div>
          <p class="scripts-panel-hint" v-if="publishStatus === 'all'">全部状态不会在请求中发送 publish_status。</p>
          <p class="scripts-panel-hint" v-if="publishStatus === '0'">未处理使用 publish_status=0。</p>
          <p class="scripts-panel-hint" v-if="publishStatus === '1'">已处理使用 publish_status=1。</p>
          <div class="scripts-panel-grid">
            <article class="scripts-command-card" v-for="script in scripts" :key="script.type">
              <div class="scripts-command-head">
                <div>
                  <h3>{{ script.name }}</h3>
                  <p>{{ script.description }}</p>
                </div>
                <el-button size="mini" type="primary" plain :disabled="!commandFor(script.type)"
                  @click="copyCommand(script.type)">复制命令</el-button>
              </div>
              <el-input type="textarea" :rows="2" readonly resize="none"
                :value="commandFor(script.type) || '先选择有效的开始日期和结束日期'"></el-input>
            </article>
          </div>
          <div class="scripts-panel-footnote">
            <span>日期参数支持跨日区间。</span>
            <span>请先把 SCRIPT_PATHS 配置为部署方自己的脚本路径。</span>
          </div>
        </section>`,
      data: function () {
        return {
          startTime: "",
          endTime: "",
          publishStatus: "all",
          scripts: [
            { type: "comic", name: "示例脚本 A", description: "按日期区间生成命令并复制到剪贴板。" },
            { type: "real", name: "示例脚本 B", description: "按日期区间生成命令并复制到剪贴板。" }
          ]
        };
      },
      methods: {
        disableFuture: function (date) {
          return date.getTime() > Date.now();
        },
        commandFor: function (type) {
          return buildCommand(type, this.startTime, this.endTime, this.publishStatus);
        },
        copyCommand: async function (type) {
          var command = this.commandFor(type);
          if (!command) return;
          try {
            if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
              await global.navigator.clipboard.writeText(command);
            } else {
              var field = global.document.createElement("textarea");
              field.value = command;
              field.setAttribute("readonly", "");
              field.style.position = "fixed";
              field.style.left = "-10000px";
              global.document.body.appendChild(field);
              field.select();
              var copied = global.document.execCommand("copy");
              global.document.body.removeChild(field);
              if (!copied) throw new Error("浏览器未允许复制");
            }
            toast("命令已复制");
          } catch (error) {
            toast(error.message || "复制失败，请选中命令手动复制", true);
          }
        }
      }
    });
  }

  global.OpsAdminScriptsPanel = { create: create, buildCommand: buildCommand };
})(typeof window !== "undefined" ? window : globalThis);
