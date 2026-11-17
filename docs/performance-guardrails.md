# 性能护栏（改前端热路径前必读）

> 本文档基于 PolyTable 在真实生产负载（数万行规模表格、百人并发协作场景）下的长期演进与实测经验整理。
>
> 本文是切表/切视图卡顿根治之后固化的边界契约。所有结论都来自真实测量（CPU profiler / LoAF / longtask / 无节流 headless 基准），不是猜测。**改前端热路径前必读；违反任何一条都可能让 5 万行场景退回秒级卡顿。**

## 1. 数据一致性约束（优先级高于一切性能优化）

1. **按窗口加载**：主表数据池按日期/范围窗口加载，不是一次性全表。窗口边界与一致性水位是数据口径的一部分，任何"改成全量一次加载"的方案都必须先论证内存与一致性影响，不得顺手改掉。
2. **数据绝不堵塞**：用户操作（切表/切视图/筛选/排序）不允许长阻塞主线程；loading 遮罩必须释放（所有权机制见 `ops/index.html` 的 `releaseListLoadingMask` / `_pageMaskOwner`）。
3. **每人独立视图**：视图配置按人隔离（personal/locked 保护级别），云同步合并规则勿动。
4. **既有路径语义原样保留**：服务端筛选路径、水印键绑定 listVersion 等既有语义——性能改动不得触碰。

## 2. 热路径红线（5 万行级路径，禁止逐行/逐次调用）

以下函数**每次调用都有隐藏成本**（重建数组/新建对象），在比较器、分组循环、筛选循环、画布 draw 内逐行调用 = 百万次重建 = 秒级卡顿（本契约正是根治该类卡顿后固化下来的）：

| 函数 | 隐藏成本 | 红线 |
|---|---|---|
| `allColumnDefs()` | concat+filter 重建 70+ 列数组（有指纹缓存，但仍有 Map/指纹比较开销） | 比较器/分组/筛选内**禁止**；规则级取一次 |
| `allTableDefs()` | 重建全部表数组（引用指纹缓存） | 循环内禁止 |
| `resolveColumnDef(def)` | 新建对象 + effectiveFieldType 解析 + lookup 元数据 | 逐行/逐次禁止 |
| `sortFieldKind(field)` → `columnHeaderKind` → `resolvedColumn` | 同上链路 | 比较器内禁止 |
| `currentDraft(row)` → `baseDraft(row)` | **新建几十个字段的对象** + `Object.keys(fieldConfigs)` | 逐行/逐次禁止；只在编辑器/保存等低频路径用 |

### 2.1 排序比较器契约

- `applySortRulesToRows` 已实现**规则级预解析**（kind + 选项序一次算好，见 `ruleCtx`）。
- `compareSortValues(a, b, field, dir, kindHint, optionsHint)` 必须传 hints；新增比较分支**不得**在比较器内部调 `sortFieldKind`/`resolveColumnDef`/`sortSelectOptionsFor`（兜底路径仅限非排序热路径调用）。
- 公式列排序 = 读 `row.formulaValues` 数字比较（空值恒最后），**不得**逐次重新求值公式。

### 2.2 分组契约

- `buildGroupedDisplayRows.walk` 每层（depth）预解析 `ruleCol` + `ruleKind` 各一次，经 `groupFieldParts(field, raw, rule, preCol, preKind)` / `groupValueLabel(raw, field, preKind)` 传入。
- 新增分组语义字段时：列类型解析走 preCol；dateGranularity 判断走 preKind。
- 分组视图**禁用渐进上屏**（onRows 早退，流内累积，流结束一次渲染）——每批全量 regroup 是 O(n²) 卡死源（曾实测把页面卡死），勿"优化"回去。

### 2.3 rowFieldValue / 草稿语义对齐

- `rowFieldValue` 是快路径（直读行值 + 仅脏行读 dirty），取值语义必须与 `baseDraft` **逐字段对齐**：
  - `ROW_DIRECT_EMPTY_FIELDS` 集合内的字段（标题类等直拷字段）用 `row.x || ""` 空值语义——**给 `baseDraft` 新增直拷字段时必须同步这个 Set**；
  - 归一化类字段（责任人/状态标签等）分别走各自的 normalize/effective 帮助函数；
  - 数值型字段统一走 `String(Number(x||0))`。
- 改 `baseDraft` 字段集时：同步检查 `rowFieldValue` 的特殊分支。改完必须跑 §5 的回归基准。

### 2.4 公式物化契约

- 服务端公式求值唯一入口 = `OpsFormulaEvalService.enrichRowCached`（行级物化：键 = 公式版本 | 上下文签名 | 行标识，指纹 = 引用字段值拼接）。**任何新代码不得绕过它直接调全量重算。**
- 新公式引用字段：字段引用（`[字段名]`）枚举必须覆盖新字段类型——公式引用公式会递归展开，引用枚举漏类型 = 指纹失效漏更新 → 脏读。
- 缓存必须是 `ConcurrentHashMap`（同步 LRU 在并行流下锁竞争，曾造成单次求值 119s 的事故，勿回退）。
- 前端公式列只读 `row.formulaValues`，禁止前端重新求值公式。

## 3. 缓存失效契约（改了数据必须让缓存知道）

| 缓存 | 失效方式 | 注意 |
|---|---|---|
| `allTableDefs` | 引用指纹：`state.customTables` / `state.serverBuiltinTables` / `state.allowedBuiltinTableIds` 三个引用 | 新写入点必须**整体重赋值**（map/filter/new array），禁止 `arr[i].x = y` 原位改 |
| `allColumnDefs` | 指纹含上述三个 + `state.fieldConfigs` 引用 + **`state._fieldConfigsRev`** + `catalogHiddenFieldKeys` 引用 + `sheetFields` 引用 + `currentTableId()` | **fieldConfigs 原位写入（`state.fieldConfigs[k]=...` / delete）必须调 `bumpFieldConfigsRev()`**（现有调用点：列标题保存、lookup 保存、字段删除等） |
| 画布布局 | `invalidateLayoutCache()`（列宽拖拽等） | 高频事件（mousemove）禁止逐事件调用——RAF 合并 |
| 列表快照 | 水位（变更日志表的 MAX(id) 类信号） | `limit=1` 必然 hasMore=true，不能当回填条件 |
| 排序结果 | 本地排序后必须 `bumpDataGen`（filtered/display memo 不含排序签名） | 曾因漏调导致排序完全失效 |

## 4. 测量方法论（无节流）

1. **改 `ops/index.html` 后必须语法校验**：顶层脚本是**最后一个** `<script>` 块（`lastIndexOf("<script>")` + 偏移 8——`"<script>"` 是 8 个字符，按范围提取时会被 JS 字符串里的 `</script>` 截断，不可信）：
   ```bash
   node -e 'const fs=require("fs");const h=fs.readFileSync("src/main/resources/static/ops/index.html","utf8");const s=h.lastIndexOf("<script>");const e=h.indexOf("</"+"script>",s);fs.writeFileSync("/tmp/ops_check.js",h.slice(s+8,e))' && node --check /tmp/ops_check.js
   ```
2. **打包**：`mvn -q package`。
3. **重启本地服务**：先 `lsof -nP -iTCP:8000 -sTCP:LISTEN -t` 拿真实 PID 再 kill（kill 记忆里的旧编号 = 新构建没部署 = 测试全错）：
   ```bash
   nohup java -jar target/polytable-0.1.0.jar --spring.profiles.active=local > logs/local-8000.log 2>&1 &
   ```
4. **性能验证必须无节流**：Playwright headless + 反节流旗标。窗口被遮挡时 Chromium 的 1s 级节流会污染一切计时。
5. **信号甄别**：真实 JS 阻塞只认 longtask / LoAF（`PerformanceObserver` type `long-animation-frame`，给函数级归属）/ CDP CPU profiler（Playwright `newCDPSession` + Profiler.start/stop，self-time 榜单）。`setInterval` 间隔探测器的"1s 帧"是节流假象；WebAudio 保活因音量低于可闻阈值无效。
6. **诊断埋点**：应用自带 >1.5s 长任务 console.warn（`[诊断] 长任务`）+ 列表加载 timeline 埋点（注意 performance.mark 名带 JSON tag，`getEntriesByName("loadList-start")` 查不到）。
7. **改 SQL hint 必须核对真实索引名**（`SHOW INDEX`）并跑一次真实请求——曾因 FORCE INDEX 索引名写错导致全站窗口查询崩溃。
8. **读页面状态用裸 `state`**：顶层 `const state` 是 script 词法绑定**不在 window 上**；`window.state?.rows` 恒 undefined。

## 5. 回归基准（防性能回退的可复跑口径）

- 基准场景：Playwright 无节流脚本，覆盖登录 → 定位视图 → 切视图/切表全场景计时 + longtask/LoAF/加载 timeline 归因。期望：
  - **切视图（换数据池）约 2–3s 且零 longtask**；
  - **切表回缓存 <500ms**；
  - 切新表 <800ms。
  - 出现任何 longtask >300ms = 回退，按 §2 排查。
- 启动基准：CPU profiler 采样下 boot 期 index.html self-time 应 <1s（根治前为 14.4s）。
- 已知上限：切视图若两视图日期窗不同 = 换池整表重拉，约 2s 服务端流是当前架构底线（点击瞬间有池快照占位，体感不空白）。

## 6. 相关索引

- 架构与协作机制：[architecture.md](architecture.md)
- 性能演进与基准数据：[performance.md](performance.md)
- 公式引擎设计与公式物化契约的实现：[design/formula-engine.md](design/formula-engine.md)
