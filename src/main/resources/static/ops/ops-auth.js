/**
 * 协作台登录态（login.html 与 index.html 共用）。
 */
(function (global) {
  const TOKEN_KEY = "ops.token";
  const DISPLAY_NAME_KEY = "ops.displayName";

  function getToken() {
    return localStorage.getItem(TOKEN_KEY) || "";
  }

  function setToken(token) {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  }

  function getDisplayName() {
    return localStorage.getItem(DISPLAY_NAME_KEY) || "";
  }

  function setDisplayName(name) {
    if (name) localStorage.setItem(DISPLAY_NAME_KEY, String(name).trim());
    else localStorage.removeItem(DISPLAY_NAME_KEY);
  }

  function safeRedirectTarget(raw) {
    if (!raw) return "index.html";
    try {
      const u = new URL(raw, global.location.origin);
      if (u.origin !== global.location.origin) return "index.html";
      const path = u.pathname || "";
      if (path.endsWith("/login.html") || path.endsWith("/login")) return "index.html";
      if (path.includes("/ops/")) return u.pathname + u.search + u.hash;
      if (path.endsWith("/index.html") || path.endsWith("/ops") || path.endsWith("/ops/")) {
        return u.pathname + u.search + u.hash;
      }
      return "index.html";
    } catch (_) {
      return "index.html";
    }
  }

  function buildLoginUrl(msg, redirect) {
    const params = new URLSearchParams();
    const target = redirect || (global.location.pathname + global.location.search + global.location.hash);
    if (target && target !== "/ops/login.html" && !target.endsWith("/login.html")) {
      params.set("redirect", target);
    }
    if (msg) params.set("msg", msg);
    const qs = params.toString();
    return "login.html" + (qs ? "?" + qs : "");
  }

  function redirectToLogin(msg, redirect) {
    global.location.replace(buildLoginUrl(msg, redirect));
  }

  function readLoginMessage() {
    try {
      return new URLSearchParams(global.location.search).get("msg") || "";
    } catch (_) {
      return "";
    }
  }

  function readRedirectTarget() {
    try {
      const raw = new URLSearchParams(global.location.search).get("redirect") || "";
      return safeRedirectTarget(raw);
    } catch (_) {
      return "index.html";
    }
  }

  global.OpsAuth = {
    TOKEN_KEY,
    DISPLAY_NAME_KEY,
    getToken,
    setToken,
    getDisplayName,
    setDisplayName,
    safeRedirectTarget,
    buildLoginUrl,
    redirectToLogin,
    readLoginMessage,
    readRedirectTarget
  };
})(window);
