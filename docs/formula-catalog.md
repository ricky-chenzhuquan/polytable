# 公式兼容目录

> 本文档基于 PolyTable 在真实生产负载（数万行规模表格、百人并发协作场景）下的长期演进与实测经验整理。

## 这是什么

`src/main/resources/formula/bitable-formula-catalog.json` 是 PolyTable 的公式兼容目录：一份机器可读的**飞书多维表格（Bitable）公式行为快照**，截至 **2026-11-04** 从官方公开帮助文档逐页核验冻结，包含：

- **119 个函数**（按官方目录的全部类别提取，每条含签名、参数说明、返回类型、错误规则、可执行示例）；
- **14 个运算符**（逻辑 `&&`/`||`、比较 `>`/`>=`/`<`/`<=`/`=`/`!=` 等，各标注类型与官方来源）；
- **`sources` 溯源字段**：每条目可回溯到具体官方页面 URL 与核验日期，官方未定义的边界行为一律标记"未公开/不保证一致"，不猜测。

快照的顶层 `scope` 声明了覆盖边界：仅公开行为；未公开内部实现不在范围内。电子表格（Sheets）专属函数与低代码平台公式不纳入。

## 主要官方来源（公开页面）

- 《多维表格公式函数大全》：https://www.larksuite.com/hc/zh-CN/articles/914838661184
- 《多维表格函数常见问题》：https://www.larksuite.com/hc/zh-CN/articles/023659126283
- 《多维表格公式字段概述》：https://www.larksuite.com/hc/zh-CN/articles/360048488187
- 官方案例页（IF/IFS、CONTAIN、COUNTIF、ISBLANK/IFBLANK、人员字段等）——完整清单见 JSON 的 `sources` 数组。

## 测试如何使用它

目录是公式引擎测试的规格来源（测试位于 `src/test/java/com/polytable/formula/`）：

- `FormulaCatalogTest` / `FormulaCatalogCoverageTest`：校验目录结构完整，并要求兼容目录中每个函数/运算符都有注册与语义用例（**覆盖率门槛**）。
- `FormulaOfficialExamplesTest`：对目录中 119 个函数的官方公开示例逐条做结果断言（**金测**；随机函数按范围/长度/去重约束校验，时间函数用固定时钟）。
- `FormulaEvaluatorGoldenTest`：端到端金测，覆盖语法、类型转换、空值、错误值（`#N/A`/`#REF!`/`#CIRCLE!`）、日期边界与跨表示例。

官方资料后续新增或变更函数时，更新目录快照并同步扩充用例（设计细节见 [design/formula-engine.md](design/formula-engine.md)）。

## 代表性函数举例

| 函数 | 官方语义（摘要） | 示例 |
|---|---|---|
| `IF` | 条件为真返回第二参数，否则返回第三参数 | `IF(成绩 >= 60, "及格", "不及格")` |
| `IFS` | 多条件分支；无条件命中时为 `#N/A` | `IFS(A > 90, "A", A > 60, "B")` |
| `CONCATENATE` | 拼接多个文本值 | `CONCATENATE(姓, 名)` |
| `CONTAIN` | 判断文本是否包含目标串 | `CONTAIN(标签, "urgent")` |
| `COUNTIF` | 统计满足条件的元素个数 | `COUNTIF(状态, "done")` |
| `SUM` | 求和（数字/列表/字段） | `SUM(金额)` |
| `FILTER` | 按条件过滤列表/记录（结果顺序以官方公开保证为准） | `FILTER(任务, CurrentValue.状态 = "open")` |
| `UNIQUE` | 列表去重 | `UNIQUE(标签列表)` |
| `TODAY` | 当前日期（易变函数，缓存按时间桶处理） | `TODAY()` |
| `TEXT` | 按格式化规则转文本（含中文星期等本地化格式） | `TEXT(日期, "ddd")` |

完整 119 函数 × 签名/示例请直接查阅目录 JSON；公式求值语义与缓存设计见 [design/formula-engine.md](design/formula-engine.md)，性能数据见 [performance.md](performance.md)。

---

觉得 PolyTable 有用？欢迎[支持作者](donate.md) ☕
