## 这个 PR 做了什么

<!-- 一段话概括：动机 → 改动 → 效果 -->

## 改动类型

- [ ] feat / fix / perf / docs / refactor / chore（commit message 请用 `type(scope): 摘要`）
- [ ] 性能改动（必须附优化前后实测数字，测量方法见 docs/performance-guardrails.md）
- [ ] 触碰热路径（排序比较器 / 分组循环 / Canvas draw）——已读性能护栏红线

## 自查清单

- [ ] `mvn test` 通过（公式引擎覆盖率 + golden test 必须全绿）
- [ ] 改过 `index.html` / `admin.html`：提取最后 `<script>` 块 `node --check` 通过
- [ ] **没有**在代码、注释、日志、截图、commit message 中提交任何口令 / token / cookie / 内部地址 / 真实用户数据
- [ ] 凭据类配置只走环境变量（`${VAR:}` 形式），未新增内置默认口令
- [ ] URL 路径与请求/响应字段名未做不兼容变更（或已在描述中说明迁移方案）
