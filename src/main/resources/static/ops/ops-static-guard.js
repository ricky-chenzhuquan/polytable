/**
 * 中文备注：静态页版本探测，避免 HTML/JS 混版本导致「页面异常/包损坏」体感。
 */
(function (global) {
  "use strict";

  function readRevFromHtml(html) {
    var m = String(html || "").match(/ops-static-rev:(\S+)/);
    return m ? m[1] : "";
  }

  function ensureBanner() {
    var id = "opsStaticGuardBanner";
    var el = document.getElementById(id);
    if (el) return el;
    el = document.createElement("div");
    el.id = id;
    el.style.cssText = "position:fixed;left:0;right:0;top:0;z-index:9999;background:#b45309;color:#fff;padding:10px 16px;font-size:13px;display:flex;align-items:center;justify-content:center;gap:12px;";
    el.innerHTML = "<span>检测到新版本，请刷新页面以避免功能异常</span><button type=\"button\" style=\"border:0;border-radius:6px;padding:6px 12px;cursor:pointer\">立即刷新</button>";
    el.querySelector("button").onclick = function () { location.reload(); };
    document.body.appendChild(el);
    return el;
  }

  function start(options) {
    var opts = options || {};
    var localRev = String(opts.rev || readRevFromHtml(document.documentElement.outerHTML) || "").trim();
    if (!localRev) return;
    var path = opts.path || location.pathname;
    var intervalMs = opts.intervalMs || 120000;
    var shown = false;

    async function probe() {
      if (document.visibilityState === "hidden") return;
      try {
        var res = await fetch(path + "?_opsRev=" + Date.now(), { cache: "no-store" });
        if (!res.ok) return;
        var remoteRev = readRevFromHtml(await res.text());
        if (remoteRev && remoteRev !== localRev && !shown) {
          shown = true;
          if (typeof opts.onNewVersion === "function") {
            opts.onNewVersion(remoteRev, localRev);
          } else {
            ensureBanner().style.display = "flex";
          }
        }
      } catch (_) {}
    }

    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") void probe();
    });
    window.addEventListener("focus", function () { void probe(); });
    setInterval(function () { void probe(); }, intervalMs);
  }

  global.OpsStaticGuard = { start: start, readRevFromHtml: readRevFromHtml };
})(typeof window !== "undefined" ? window : globalThis);
