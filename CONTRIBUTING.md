# 贡献指南

感谢关注 PolyTable！欢迎以 Issue / PR 的形式参与贡献。

## 开发环境

- JDK 17+、Maven 3.8+、MySQL 8（本地或 Docker）
- 前端为 `src/main/resources/static/ops/` 下的单页应用（Vue2 + ElementUI 经 CDN 引入 + 自研 Canvas 渲染器），无需前端构建步骤

```bash
# 本地跑起来
mvn spring-boot:run
# 跑测试（公式引擎有完整的目录覆盖率 + golden test）
mvn test
```

## 提交规范

- commit message：`type(scope): 摘要`，type 取 `feat / fix / perf / docs / refactor / chore`
- **性能改动必须附上优化前后实测数字**（测量方法见 [docs/performance-guardrails.md](docs/performance-guardrails.md)），无数字不合并
- 中文或英文皆可，同一提交内保持一致

## 代码约定

- 服务端遵循现有分层：`controller`（端点）→ `service`（编排）→ `repository`（JdbcTemplate SQL）
- **改前端 `index.html` 后必须做语法校验**：提取最后一个 `<script>` 块（用 `lastIndexOf("<script>") + 8` 偏移提取，不要用 sed 范围提取——会被字符串里的 `</script>` 截断）后 `node --check`
- 改动若触碰热路径（排序比较器、分组循环、Canvas draw），先读 [docs/performance-guardrails.md](docs/performance-guardrails.md) 的红线
- 不引入新的第三方框架依赖（保持"单 JAR + 一个 MySQL"的轻量约束）；确需引入请在 Issue 中先讨论

## 安全红线（贡献者必读）

- **永远不要**在 issue / PR / commit message / 代码注释中粘贴真实口令、token、cookie、内部地址或任何真实用户数据
- 所有凭据类配置一律走环境变量（`${VAR:}` 形式），禁止内置默认口令
- 修改鉴权/口令/token 逻辑前先读 [SECURITY.md](SECURITY.md)

## Issue 建议

- Bug 报告请附：复现步骤、期望/实际行为、`mvn test` 结果、相关日志片段（**先脱敏**）
- 功能建议请先查 [docs/roadmap.md](docs/roadmap.md)，避免与已规划项重复
