# 公式引擎设计

> 本文档基于 PolyTable 在真实生产负载（数万行规模表格、百人并发协作场景）下的长期演进与实测经验整理。

PolyTable 的公式引擎把公式字段从"字符串替换求值"重构为完整的"词法解析 → AST → 类型化求值"管线，并对齐飞书多维表格官方公开文档描述的语法与可观察结果。本文描述该引擎的设计与取舍。

源码位置：

- 引擎核心：`src/main/java/com/polytable/formula/`
- 服务门面：`src/main/java/com/polytable/service/OpsFormulaEvalService.java`
- 兼容目录：`src/main/resources/formula/bitable-formula-catalog.json`（见 [formula-catalog.md](../formula-catalog.md)）

## 1. 目标与范围

- 工作台场景约 70 字段、单表数万行；公式需要在全量加载、筛选、排序路径上正确且可缓存地求值。
- 对齐截至 2026-11-04 可查到的飞书官方多维表格公式文档所描述的语法与可观察结果。**本设计只对齐公开文档行为，不声称复刻飞书未公开的引擎、调度、存储或缓存实现。**
- 一次查询完整返回（单请求流式），前端在同一响应流内逐步解码和绘制，不为每批记录再发分页请求。

### 方案比较（为什么自研）

1. **自研解析器与类型化求值（采用）**：公式解析为 AST，以类型化值和明确错误值执行；按公开目录注册函数。可处理字段/表引用、`CurrentValue`、列表与跨表方法，错误和缓存语义由本项目掌控。代价是需要建完整语法、类型与函数用例。
2. 字符串替换求值：改动最小，但嵌套函数、比较/逻辑运算、列表和跨表表达式无法可靠解析，前后端语义漂移风险高。不采用。
3. 接入 Excel/通用表格公式库：可借用大量基础函数，但其单元格引用模型不能代表多维表格记录、关联字段、`CurrentValue` 与跨表列表，且不能据此宣称 Bitable 兼容。不采用。

不新增第三方依赖；实现文件控制在词法/解析/值模型/函数执行及数据源适配范围内。

## 2. 兼容基线与覆盖边界

- 兼容基线是 Lark/飞书官方帮助中心的《多维表格公式函数大全》等官方页面：截至 2026-11-04 的页面快照提取到 **119 个不同函数名 + 14 个运算符**，冻结为机器可读目录 `src/main/resources/formula/bitable-formula-catalog.json`（含每条目的官方来源 URL、参数形状、返回类型、错误规则与可执行示例）。
- 官方资料没有定义的边界行为，一律记录为"未公开/不保证一致"，不得凭猜测标成兼容。
- 电子表格（Sheets）专属函数、低代码平台公式、第三方教程不纳入；普通飞书表格"500 多个函数"不作为多维表格目录的依据。
- 官方公开资料明确的变化点按公开语义实现：删除被引用字段时 `#REF!`、循环引用时 `#CIRCLE!`、`IFS` 无条件命中时 `#N/A`、`FILTER`/查找引用的结果顺序与随机函数种子可能变化（验收只检查公开保证，如成员集合与重算条件）。

主要官方来源（公开页面）：

- 《多维表格公式函数大全》：https://www.larksuite.com/hc/zh-CN/articles/914838661184
- 《多维表格函数常见问题》：https://www.larksuite.com/hc/zh-CN/articles/023659126283
- 《多维表格公式字段概述》：https://www.larksuite.com/hc/zh-CN/articles/360048488187
- 若干官方案例页（IF/IFS、CONTAIN、COUNTIF、ISBLANK/IFBLANK、人员字段等）——完整溯源见目录 JSON 的 `sources` 字段。

## 3. 引擎架构总览

```
公式文本
  │ FormulaLexer（词法）
  ▼
FormulaParser（语法，处理括号/引号/逗号/字段引用/表引用/方法调用/运算符优先级）
  ▼
FormulaAst（抽象语法树，解析缓存：规范化文本 + 解析器版本 → AST）
  ▼
FormulaEvaluator（AST + FormulaEvaluationContext → FormulaValue）
  │  函数执行经 FormulaFunctionRegistry 注册表
  │  跨表/列表经 FormulaDataSource 适配接口批量取数
  ▼
FormulaValue（类型化值：blank/text/number/bool/date/list/record ref/error）
  ▼
FormulaDependencyGraph（依赖闭包）+ BoundedFormulaResultCache（版本化有界缓存）
```

类与职责对照（包 `com.polytable.formula`，服务在 `com.polytable.service`）：

| 组件 | 类 | 职责 |
|---|---|---|
| 词法分析 | `FormulaLexer` | 令牌扫描：标识符、数字、字符串、运算符、括号/逗号 |
| 语法解析 | `FormulaParser` | 递归下降解析，产出 `FormulaAst`；语法错误/未知函数报可区分错误 |
| AST | `FormulaAst` | 常量、字段引用、当前列表元素、单目/二元运算、函数调用、表/字段路径与方法调用 |
| 求值器 | `FormulaEvaluator` | AST → `FormulaValue`；错误显式传播 |
| 求值上下文 | `FormulaEvaluationContext` | 当前行、`CurrentValue` 绑定、时钟（Clock 注入）、数据源引用 |
| 值模型 | `FormulaValue` | 类型化值与错误值（`#N/A` / `#REF!` / `#CIRCLE!` 等） |
| 函数注册表 | `FormulaFunctionRegistry` | 每函数：名称、参数个数/可选参数、输入/返回类型、空值处理、错误传播、用例 |
| 依赖图 | `FormulaDependencyGraph` | 公式字段 → 本行字段/其他公式字段/引用表字段的依赖索引与影响闭包 |
| 结果缓存 | `BoundedFormulaResultCache` | 版本化、容量有界的求值结果缓存（见 §6） |
| 数据源适配 | `FormulaDataSource` | 跨表/列表批量读取接口：批量读取、来源版本/水位、字段类型信息 |
| 服务门面 | `OpsFormulaEvalService` | 行级物化、批量 enrich、并行调度（见 §7） |

## 4. 解析与值模型

- 公式文本先经 tokenizer 和 parser 转换为 AST；解析阶段完成括号、引号、逗号、字段引用、表引用、方法调用和运算符优先级处理，**不再用正则替换模拟完整语法**。
- 解析缓存键由规范化公式文本 + 解析器版本组成；字段/表元信息变化时递增元数据版本，使旧 AST 失效。
- 计算值明确区分空值、文本、数字、布尔值、日期/时间、列表/记录引用和公式错误。**不得把错误悄悄压成空字符串，也不得在类型转换失败时默认为 0。**
- 错误值至少覆盖官方明确说明的 `#N/A`、`#REF!`、`#CIRCLE!` 及兼容目录确认的其他错误；语法错误、未知函数和参数类型错误使用可区分的错误类型，显示文案遵守公开语义。
- 日期时区使用部署方配置的业务时区（默认 Asia/Shanghai），通过注入 `Clock` 在测试中锁定边界；`TODAY`/`NOW` 等易变值必须有特殊缓存键（时间桶）或不缓存策略。

## 5. 函数注册表与跨表执行

- 每个兼容函数在 `FormulaFunctionRegistry` 注册：名称（大小写规则以官方文档为准）、参数个数/可选参数、输入类型、返回类型、空值处理、错误传播和可执行用例。
- 同时支持普通函数调用与官方文档使用的链式列表/表方法形式；`CurrentValue` 仅在列表谓词执行上下文中绑定，非法上下文返回可诊断错误。
- 跨表访问经 `FormulaDataSource` 适配接口进入表查询层；求值器**不直接拼 SQL、不持有 JDBC 连接、不逐条发起 N+1 查询**。适配器需提供批量读取、稳定的来源版本/更新时间和字段类型信息。
- 对关联与列表过滤，执行计划一次收集所需字段与源表范围，批量取数并在请求上下文内复用。
- 官方目录无法在当前数据模型表示的字段类型，在目录中标记不支持，不假报通过。

## 6. 依赖图与版本化有界缓存

- `FormulaDependencyGraph` 记录公式字段对本行字段、其他公式字段和引用表/字段的依赖；单行字段修改后只重算受影响的依赖闭包，字段公式变更时重建该表依赖图。
- `BoundedFormulaResultCache` 的缓存键包含：table/row 标识、行版本或更新水位、公式/元数据版本、跨表数据水位、易变函数时间桶。`NOW` 与随机函数不得错误复用过期结果。
- 进程缓存有容量上限（按条目与权重双重限额，LRU 逐出）；缓存只是优化——**缓存未命中不影响正确性**，服务端查询在任何缓存状态下都返回相同的公开语义。
- 首期不引入数据库结构迁移或持久化公式列；若数万行全量公式筛选/排序压测表明必须物化，先提交单独的表结构变更方案。

## 7. 服务端求值服务（OpsFormulaEvalService）

`com.polytable.service.OpsFormulaEvalService` 是公式求值的唯一服务端入口：

- **行级物化**：`enrichRowCached`——缓存键 = 公式版本 | 上下文签名 | 行标识，指纹 = 引用字段值拼接。命中即免重算；未命中求值后写回。
- **批量 enrich**：`enrichFormulaValues` 对整批行求值；≥512 行时并行执行。缓存容器必须是 `ConcurrentHashMap`——同步 LRU 在并行流下会锁竞争（实测曾造成 119s 的求值事故），勿回退。
- **引用枚举**：公式引用字段（`[字段名]`）必须能被完整枚举，公式引用公式递归展开；新字段类型若不写入引用枚举，指纹会失效漏更新 → 脏读。
- **元数据版本**：字段配置变更入口必须使公式缓存与列表快照缓存同步失效；完整快照缓存把公式元数据版本纳入 key，避免公式改完仍短暂返回旧 `formulaValues`。
- **前端契约**：前端公式列只读 `row.formulaValues`，禁止前端重新求值公式。

## 8. 全量快照、缓存与前端绘制

- 每次查询返回完整快照（上限 `ops.list.max-page-size`，默认 50,000 行），单请求流式：服务端按确定性记录帧发出同一快照，前端增量解码、尽早绘制前几屏并继续填充同一数据模型；传输分帧不是多次请求。超出能力上限时必须提示完整性不足，不得静默截断。
- 服务端缓存 key 包含：日期窗/查询范围、关键字、全部服务端过滤/排序、视图/字段投影、数据版本水位、公式元数据版本。任何写入、字段定义变更、水位变化、跨日或公式更新都失效对应缓存；命中先核对版本。
- 容量受限的完整查询快照缓存 + 相同 key 的 single-flight（并发请求合并），避免多用户同时打开同一窗口时重复计算全量快照。
- 浏览器只保留当前会话内存快照，不持久化表格数据到 localStorage/IndexedDB（避免敏感数据残留与陈旧副本）。
- 复用 Canvas 虚拟化与滚动帧合并：即使全量记录在同一响应中，DOM/画布仍只绘制视口；接收和建模不得阻塞主线程后才允许首次绘制。
- 公式编辑器基于同一 `FormulaFunctionRegistry` 提供函数补全/参数说明，预览请求走同一 AST 求值——**预览与列表计算共用同一实现**。

## 9. golden test 验收

测试位于 `src/test/java/com/polytable/formula/`，全部使用 fake repository、内存数据源或合成数据：

| 测试 | 验收内容 |
|---|---|
| `FormulaCatalogTest` / `FormulaCatalogCoverageTest` | 目录 JSON 结构完整；兼容目录中每个函数/运算符有注册与语义用例（覆盖率门槛） |
| `FormulaOfficialExamplesTest` | 目录中 119 个函数的官方公开示例全部有结果断言（确定性输入；随机函数按范围/长度/去重约束校验；时间函数用固定时钟） |
| `FormulaEvaluatorGoldenTest` | 端到端金测：语法、类型转换、空值、错误值、日期边界、列表与跨表示例 |
| `FormulaParserTest` / `FormulaValueTest` | 解析器与值模型单元语义 |
| `FormulaDependencyGraphTest` | 循环引用（`#CIRCLE!`）、被删字段引用（`#REF!`）、依赖闭包重算 |
| `BoundedFormulaResultCacheTest` | 版本失效、易变函数时间桶、容量逐出 |
| `FormulaDataSourceTest` | 跨表批量读取与水位失效 |

验收要点：

1. 兼容目录中每个函数、运算符、引用形式及错误场景均有官方来源和自动化语义用例；每个可执行官方示例都有对应 golden test。
2. 公式预览与列表计算共用同一解析/求值实现。
3. 对数万行查询，一个查询会话只有一个数据请求、无游标续页；公式筛选/排序对全候选集正确；错误不会被转为空白或零。
4. warm cache 命中时不重复扫描/计算全量快照；版本变化后不返回旧数据；并发相同查询合并执行；进程内存有界。
5. 合成负载下记录 cold/warm cache 基准（见 [performance.md §8](../performance.md#8-公式与编码基准jvm-内存合成负载)），不设未经测量的硬延迟承诺。

## 10. 非目标

- 不调用飞书服务、不探测飞书线上行为、不逐项黑盒对拍未公开实现。
- 不做富文本单元格公式；不在本期做持久化公式列与依赖图的写时物化。
- 不改权限模型、协作锁或审计策略；这些不是公式性能设计可自动扩大出的范围。

## 相关文档

- 公式兼容目录：[../formula-catalog.md](../formula-catalog.md)
- 性能基准：[../performance.md](../performance.md)
- 架构总览：[../architecture.md](../architecture.md)
