# 安全策略

## 报告漏洞

请**不要**通过公开 Issue 报告安全漏洞。请使用 GitHub 的 [私密安全报告](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)（仓库页 → Security → Report a vulnerability），或联系维护者（TODO：补充联系邮箱）。

我们会在 7 天内确认、30 天内给出修复或缓解方案。

## 当前安全设计

- 所有凭据类配置只从环境变量读取，代码与配置文件不内置任何默认口令
- 登录 token：HMAC-SHA256 签名的无状态 token，默认 24h 过期，支持主动登出黑名单与按用户吊销
- 管理面板（`/ops/admin.html`）与普通工作台登录是相互独立的账户体系
- 未设置 `OPS_AUTH_TOKEN_SECRET` 时，每次启动生成随机签名密钥（重启后所有登录态失效）——这是有意的安全兜底，生产必须显式配置

## 已知限制（欢迎贡献改进）

1. **密码摘要方案较弱**：口令哈希为 `SHA-256("ops-pwd::" + 姓名 + "::" + 口令)`（见 `OpsAuthService#hashPassword`），无盐、非慢哈希。这是从内部系统继承的兼容方案，路线图计划升级为 BCrypt（含存量哈希迁移）。
2. **初始口令可推导**：初始口令 = 姓名拼音首字母 + `OPS_AUTH_PASSWORD_SUFFIX`。部署后请立即要求用户改密，或先在账户管理页为每个账户设置自定义口令。
3. **无列级权限**：字段级裁剪在路线图中（见 docs/roadmap.md）。
4. **HTTP 假设反代终结 TLS**：服务本身不处理 TLS，公网部署请置于 Nginx / Caddy / 云负载均衡之后。

## 部署检查清单

- [ ] 设置 `OPS_AUTH_PASSWORD_SUFFIX`（非弱口令）与 `OPS_ADMIN_PASSWORD`
- [ ] 设置 `OPS_AUTH_TOKEN_SECRET`（≥32 字符随机串）
- [ ] MySQL 仅监听内网，应用账号最小权限
- [ ] TLS 由反代终结；CORS / Host 白名单按需收紧
