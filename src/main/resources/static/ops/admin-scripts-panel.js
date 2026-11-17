(function (global) {
  "use strict";

  var SCRIPT_PATHS = {
    comic: "scripts/changdu-paid-comic-range-collect.sh",
    real: "scripts/changdu-paid-real-drama-range-collect.sh"
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
              <h2>常读历史区间采集</h2>
              <p>生成本地运行命令。命令只会复制到剪贴板，不会由管理页面启动采集。</p>
            </div>
            <span class="scripts-panel-state">使用 HQ 常读登录态</span>
          </div>
          <div class="scripts-panel-filters">
            <el-date-picker v-model="startTime" type="date" value-format="yyyy-MM-dd" placeholder="开始日期"
              :picker-options="{disabledDate: disableFuture}" size="small"></el-date-picker>
            <span class="scripts-range-separator">至</span>
            <el-date-picker v-model="endTime" type="date" value-format="yyyy-MM-dd" placeholder="结束日期"
              :picker-options="{disabledDate: disableFuture}" size="small"></el-date-picker>
            <el-radio-group v-model="publishStatus" size="small" aria-label="发布状态">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="0">未发布</el-radio-button>
              <el-radio-button label="1">已发布</el-radio-button>
            </el-radio-group>
          </div>
          <p class="scripts-panel-hint" v-if="publishStatus === 'all'">全部状态不会在请求中发送 publish_status。</p>
          <p class="scripts-panel-hint" v-if="publishStatus === '0'">未发布使用 publish_status=0。</p>
          <p class="scripts-panel-hint" v-if="publishStatus === '1'">已发布使用 publish_status=1。</p>
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
            <span>日期按常读创建时间筛选，支持跨日。</span>
            <span>执行前请确认本机已有 HQ 登录态；登录命令：bash scripts/changdu-login-hq.sh</span>
          </div>
        </section>`,
      data: function () {
        return {
          startTime: "",
          endTime: "",
          publishStatus: "all",
          scripts: [
            { type: "comic", name: "付费漫剧", description: "按创建日期分页采集并保存历史数据。" },
            { type: "real", name: "付费真人剧", description: "按创建日期分页采集并保存历史数据。" }
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
