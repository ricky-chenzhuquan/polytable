# PolyTable 架构

> 本文档基于 PolyTable 在真实生产负载（数万行规模表格、百人并发协作场景）下的长期演进与实测经验整理。

PolyTable 是一个自托管多维表格（对标飞书多维表格 / Bitable 的公开可观察行为），采用 Spring Boot 3 + MySQL 后端与 Canvas 渲染的浏览器前端。本文描述它的三层架构、三条数据加载协议、协作机制，以及我们在"抄底"飞书底层能力时做出的关键取舍。

## 1. 总体架构

```
浏览器（ops/index.html 单页应用 + sheet-canvas.js Canvas 渲染引擎）
   │  fetch + JSON / gzip 二进制 / NDJSON 流
   ▼
OpsTableController（/api/v1/ops/**，约 60 个端点）
   │  JdbcTemplate + Repository 层
   ▼
MySQL：ops_sheet_row（行存 JSON）/ ops_sheet_field / ops_field_config / ops_table_views / ...
```

核心设计决策（与飞书 Bitable 对齐的部分）：

| 能力 | PolyTable 实现 | 飞书对应 |
|---|---|---|
| 数据模型 | `ops_sheet_row.cells_json` 每行一个 JSON 文档（EAV 文档式） | Bitable Record（服务端同样是文档式存储） |
| 字段元数据 | `ops_sheet_field` 按表隔离，field_type 32 种 | Field 元数据 |
| 视图 | `ops_table_views` 共享视图 + 个人视图 | View |
| 公式 | `OpsFormulaEvalService` 只读求值，表达式编译缓存，≥512 行并行 | 公式字段 |
| 关联 | 主表关联 + 跨表 lookup 引用 | 引用字段 / 查找引用 |
| 协作感知 | `sheetSyncToken`（COUNT+SUM(version)+MAX(update_time)）+ metaOnly 轮询 + SSE dirty/op 推送 | OT / 协作者光标（PolyTable 无真 OT，见 §6） |
| 分页 | 键集分页（sort_order, create_time, row_id），禁止 OFFSET | 分片读取 |

## 2. 数据加载协议（三条路径）

### 2.1 `/sheets/{tableId}/rows`（JSON 全量路径）

- 每行 `toSheetCanvasRow()`：cells_json 反序列化 → LinkedHashMap（所有值转 String）。
- 公式 enrich：`OpsFormulaEvalService.enrichFormulaValues`。
- 首页才做 COUNT + 主表关联 + lookup 关联。
- **已知瓶颈**：5 万行 × 70 字段时该路径产出约 68MB JSON，8 并发即可触发堆内存不足（详见 [performance.md](performance.md)）。它是小表通用路径，不是大表默认路径。

### 2.2 `/sheets/{tableId}/records`（飞书式稀疏列式 gzip，默认主力路径）

- `encoding=2`：稀疏列式 + gzip 二进制。
- JSON 信封只带 `payloadNonce`，gzip 块走 `/sheets/{tableId}/records/payload?nonce=` 单独拉取（信封小、二进制块可缓存 90s TTL）。
- 行→稀疏对 `[colIdx, value][]`，只传有值单元格；viewId 列投影会裁掉当前视图不可见的列。
- 首页 4000 行/块（`RECORDS_CHUNK_ROW_LIMIT`），后续页直接返回 rows 数组。
- 前端用浏览器原生 `DecompressionStream` 解压，无需任何 JS 压缩库。

### 2.3 主表 NDJSON 流式路径

业务主表示例的全量加载走 NDJSON（newline-delimited JSON）单响应流：

- `meta` 帧（keys/total）→ `row` 帧（values 数组按 keys 对齐）→ `end` 帧（count/watermark 校验）。
- 前端每 128 行一批渐进 attach 到行模型，不缓冲整个响应体；解码器复用同一行数组，只为新批次更新 key 集合（见 `ops-sheet-records.js` 的 `consumeNdjsonResponse`）。

### 2.4 增量协作（高频请求核心）

- 前端每 5s（`listDeltaPollIntervalMs` × 负载系数）调 `/sheets/{tableId}/rows?metaOnly=1` 拿 `sheetSyncToken`。
- token 变化才拉 `/rows?sinceMs=`（update_time 增量 + deletedRowIds）。
- 编辑中 / 粘贴中 / 脏行未保存时跳过轮询。
- 空白表不打增量接口，系统表走 listVersion 整表水位。
- 在此之上叠加 SSE 推送（§5）：服务端写入后主动广播 dirty/op 信号，轮询保留为断线兜底。

## 3. 渲染引擎（sheet-canvas.js）

- Canvas 2D 手绘网格（非 DOM 表格）：默认行高 36px，表头 40px。
- 虚拟滚动：只画 viewport 内的行列；滚动经 RAF 节流重绘。
- 分组渲染（`__group` 行 + 块索引 createGroupBlockIndex）。
- 单元格类型：文本 / 数字 / 日期 / 选择器 / 标签（drawHeaderTypeIcon、drawSelectCaret、drawTagRemoveMark）。
- 撤销/重做、拖拽填充（drawFillPreview）、查找高亮、冻结列阴影。

5 万行 × 70 列滚动压测数据见 [performance.md](performance.md#8-canvas-渲染基准)：每帧绘制 p50 5.4ms / p95 8.7ms，零超 16ms 帧。

## 4. 存储层

- 键集分页：`WHERE table_id=? AND deleted=0 AND (sort_order > ? OR (...)) ORDER BY sort_order, create_time, row_id LIMIT n+1`。
- 索引：`idx_ops_sheet_row_table_deleted_sort(table_id, deleted, sort_order)`；另有 delta 增量覆盖索引 `idx_sheet_row_tbl_del_updated(table_id, deleted, update_time)` 与键集分页 tiebreaker 索引 `idx_sheet_row_sort_tiebreak(table_id, deleted, sort_order, create_time, row_id)`（消除深翻页 filesort，均已固化进 `schema.sql` 与启动迁移 `SchemaMigration`）。
- 行级乐观锁：`UPDATE ... WHERE version=?`（0 行受影响 = 版本冲突，错误码 A0409）。
- 粘贴批量：JDBC batch insert / 400 行一批软删。

## 5. 协作变更推送（SSE）

### 5.1 实现

- `OpsSheetSyncPushService`：每表 SseEmitter 注册表（CopyOnWriteArrayList），单线程异步广播。
- `GET /sheets/{tableId}/sync/stream?token=`（EventSource 不能带自定义头，鉴权支持 query param）。
- 推送的是 **dirty / op 信号而非数据**：写入 → evict 缓存 → SSE 广播 → 客户端走既有 metaOnly（缓存命中约 1ms）+ sinceMs 增量拉取。
- 广播独立线程池，不阻塞写入事务；断线后 EventSource 自动重连，5s 轮询保留兜底。
- op 帧带单调 seq（服务端 op_log），EventSource 重连自动回传 Last-Event-ID，服务端按 seq 升序回放缺口（上限 500 条），缺口过大转全量增量拉取。
- payload 示例：`{"t":"op","seq":1421,"row":"rec_x","fld":"fld_p","v":17,"val":3200,"old":3000,"by":"user-a","ts":...}`；粘贴合并为 ops 数组；全新行走 `{"t":"dirty",...}` 信号。

### 5.2 推送实测（100 并发订阅者）

| 指标 | 结果 |
|---|---|
| 100 条 SSE 并发订阅，一次写入 | 100/100 全部收到信号 |
| 写完成 → 客户端收到推送 | p50 0.1ms / max 0.2ms |
| 对比纯轮询的变更可见延迟（均值） | 约 2.5s → 0.1ms（约 25,000×） |

写入扇出成本：1 次写 = 100 次 emitter.send（异步单线程），写事务零阻塞。

### 5.3 架构演进路径

```
轮询版：    客户端 --5s metaOnly--> 服务端 COUNT+SUM+MAX 全表扫描
缓存版：    客户端 --5s metaOnly--> 内存缓存命中 ~1ms（写路径主动 evict）
推送版：    服务端写 --SSE dirty/op(0.1ms)--> 客户端 --metaOnly ~1ms + delta--> 数据
            （轮询降级兜底保留）
```

## 6. 协作编辑内核：不做真 OT 的决策

这是 PolyTable 最重要的一次架构取舍。我们实证考察了多个同类系统的公开实现：

- **Teable 并非用 yjs，而是 ShareDB（json0 OT）**：自研 DB 适配器，快照带版本号；单元格 set 是唯一原子写（`{p:['fields',fieldId], od, oi}`，无字符 offset）。
- **APITable 同为 json0 OT**（引擎 Rust→wasm，客户端/服务端共用）。
- **SeaTable / Figma 证明可以更简单**：SeaTable 的 dtable-server 无 transform，服务端权威顺序 + 原样广播；Figma 是集中式服务端 + 按属性 LWW。

**路线决策：结构化表格没有富文本，不做真 OT。** 单元格 set + 版本号 LWW（现有乐观锁的升级形态）即可覆盖全部场景；仅当未来引入富文本单元格时，才考虑 per-cell 引入 ShareDB/yjs。

配套机制：

- **单元格 op 日志**：`ops_sheet_op_log`（seq 单调）与行 UPDATE 同事务写入；SSE 推 op 帧；客户端维护 lastSeq，出现 gap 即转 delta 增量拉取（delta 为权威）。变更可见延迟从 5s 轮询压到 <100ms。
- **协作撤销/重做**：远端 op 到达时对 undo 栈做冲突处理。单元格 op 无位置语义，因此退化为**版本守卫**：`UPDATE ... WHERE version=? AND fld=?`，0 行受影响 = 已被他人覆盖 → 丢弃该条目继续（飞书同语义）。行删除 undo 用墓碑整行快照恢复。
- **协作光标（presence）**：仅在线 + 选区；上报节流 100ms（服务端内存 Map TTL 30s），SSE 合并 200ms 广播一帧，不落库不保序，客户端动画补帧。

## 7. 字段类型全集（规划）

- **附件**：两段式直传对象存储（POST 换预签名 → PUT 直传 → 回执通知，hash 去重）；单元格 JSON `{id,name,token,size,mime,width,height}`，缩略 URL 读取时计算。飞书对大文件走 upload_prepare 固定 4MB 分片（公开文档行为）。
- **人员**：值 = `[{userId}]` 只存 id（读时 join 用户目录出姓名/头像），单格上限 1000。
- **关联**：junction 中间表为主、cells_json 存展示缓存 `[{recordId,title}]`（Teable 的 link field 同构：many-to-many 走中间表，many-to-one 在多方建 FK 列）；删除主记录 = detach 不级联删行；循环检测 = 字段级 DFS 染色。
- **lookup / rollup**：起步读时计算（数据量小时够用），link 变更打脏位；rollup 函数集 sum/avg/min/max/count/counta/array_join 等；量大后再写时物化（沿依赖图拓扑序重算）。
- **公式依赖图**：`ops_field_dep(from,to)` DAG 表 + MySQL 8 `WITH RECURSIVE` 求影响闭包，写事务后拓扑序只重算受影响字段，替换当前"每请求全行重算"。
- **新表规划**：ops_attachment / ops_attachment_ref / ops_link / ops_field_dep。

## 8. 权限体系（规划）

飞书高级权限是四级控制（表/记录/字段/视图），且**协议层真裁剪**：未授权字段根本不进响应 payload（表级 perm + 记录规则 + 字段 perm 矩阵；自定义角色数量与权限不得超过表级；开放平台 API 同受控）。

PolyTable 的落地设计：

- 表结构：角色表（table_id、perm、allow_add/delete、记录编辑/读取规则 JSON、字段 perm JSON、视图 perm JSON）+ 成员表 + 协作区表；行表补 created_by。
- 记录规则编译成 SQL WHERE 追加进现有键集分页；字段列裁剪并入视图投影解析与 field perm 交集（稀疏列式编码天然不下发未授权列）。
- **五个写入口统一过权限检查**（save / paste / delete / restore / rows）；delta 增量拉取必须同样过滤，否则增量泄露。
- 缓存失效 = 权限矩阵版本号 bump 优于纯 TTL；**列级裁剪必须并入列表缓存 key**（快照 key 不含用户会泄露全量列——这是最容易踩的坑）。

## 9. 视图全家桶（规划）

- **共性**：视图配置沿用 `view{id,type,name,filter,sort,options,hiddenFields}`；一份行缓存多视图派生——数据共享、渲染状态（滚动/展开）按 viewId 独立；万行内筛选排序前端做。
- **看板**：分组字段限单选/人员；全量拉取后前端分桶（null → "未分组"桶恒在前列）；列计数走独立聚合接口并节流；拖拽 = 普通 record 更新改单选字段；卡片用 DOM（富文本/HTML5 dnd 生态）。
- **甘特**：与表格共用同一行缓存，追加时间条派生；起止 = 两个日期字段；依赖线源 link 字段，非法/成环画虚线。
- **日历**：无服务端月窗口查询——视图 filter 全量 + 前端按 [月首-7d, 月末+7d] 重叠切片；拖拽/resize 直写起止字段。
- **画廊**：封面字段必须为附件；CSS columns 简易瀑布流。
- **表单**：配置存视图 JSON；提交 = record create + typecast；防重 = pending 锁 + 服务端限流 + 幂等键。

## 10. 传输层决策：SSE 够用

- 飞书开放平台官方长连接是 WebSocket 模式（WSS 全双工、鉴权内置、消息超时重推；官方 SDK 心跳与重连参数不可照抄）。社区资料证实飞书客户端信封为 protobuf over WSS/QUIC，后端为开源的 CloudWeGo 体系。Bitable Web 端同步协议细节无公开资料，我们不猜测。
- **SSE 现状够用**：公开压测数据显示 1 万–10 万连接量级 SSE 内存比 WS 少约 40%；百人规模远低于 Tomcat maxConnections。升级 WS 的触发条件：需要上行低延迟（协作光标/编辑流）、并发超过 3000、或需要二进制帧。
- **升级路径**：抽象 `PushTransport` 接口（register/broadcast/remove），SseEmitter 实现保留 + Spring WebSocketHandler 实现同注册表，前端特性探测 WS → 回落 SSE，payload 不变业务零改动。
- **二进制结论：全量块不引入 protobuf**——文本密集数据 gzip JSON 体积打平甚至反超；仅 delta 帧可选变长信封 `[1B type][4B len][4B watermark][gzip payload]`（低优先级）。
- **断线握手**：SSE 原生 Last-Event-ID 重连携带水位 → 服务端按 seq 升序回放 delta，缺口过大（>5%）转全量流。
- **在线态**：心跳 25–30s、连续 2 次未到判离线；mode = idle/editing/away（输入防抖上报、无交互/页面隐藏转 away）；presence 写合并批量，editing 变化去抖推送。
- 不做 HTTP/2 Push（浏览器已移除）与全量 protobuf。

## 11. 平台能力（规划）

- **回收站**：独立回收站表（整行 payload JSON、删除人/时间、过期时间），保留 30 天；恢复冲突（行已被重建）→ 409 二选一：恢复为新行 / 覆盖当前行；每日任务清理。飞书云文档口径为 30 天/管理端 90 天。
- **记录历史**：每字段变更一条；读接口内嵌 fieldMeta（防字段删除后 diff 渲染不出）+ 用户映射 + cursor；只记真实内容变化，公式/查找间接变化不入史。
- **快照**：结构级 `table_snapshot(fields JSON, views JSON)`，恢复 = 按字段 id diff 应用，数据不回滚；数据级降级靠回收站 + 历史。
- **自动化**：规则表（trigger JSON + actions JSON）+ 运行表（dedupe key 唯一）；trigger = 记录满足条件（复用 filters）/ 定时 cron / 按钮；action = 改字段 / 通知 / webhook / 汇总推送；事件型去重 = recordId+数据版本，定时型 = 分钟时间片，失败防抖重试一次。
- **仪表盘**：component query DSL `{tableId, filters, groupBy, metrics, topN}`，白名单函数编译为单条聚合 SQL；短 TTL 缓存 + 写事件 bump data_version 失效。
- **分享外链**：随机 token + 范围 + filters + 可见字段 + 水印 + 口令哈希 + 过期时间 → 只读 scope JWT；服务端按 filters/列裁剪收敛一切查询；外链禁改禁历史。
- **全文搜索**：MySQL ngram FULLTEXT 起步即可；ES 触发线 = 千万行/分词需求；统一 filter→WHERE 编译器供视图/自动化/图表/分享复用。

## 12. 与飞书多维表格的差距清单（诚实的 roadmap）

PolyTable 刻意不做、或尚未做以下事情。我们把这些差距当作公开 roadmap 的卖点而非缺陷来管理（见 [roadmap.md](roadmap.md)）：

1. **无真 OT/CRDT**：并发编辑同格靠"单元格 LWW + 版本守卫"冲突拒绝，无自动字符级合并；飞书有服务端 OT。结构化表格场景下我们论证过这已覆盖全部需求（§6），富文本单元格出现前不会引入 OT。
2. **推送粒度**：SSE 已做到表级 dirty + 单元格级 op 帧，但粘贴批量的逐格 op 帧尚未全覆盖；飞书是全量单元格级推送。
3. **行文档无 schema 校验**：cells_json 是自由 JSON，类型约束靠 field 元数据约定而非存储层强制；飞书有更严格的内部 schema。
4. **权限未到列/记录级**：当前为表级角色雏形；飞书有四级权限且协议层真裁剪（§8 是落地设计，尚未实现）。
5. **公式无依赖图增量计算**：当前每次请求全行重算（有编译缓存与行级物化缓存，无脏行追踪）；依赖图 DAG 方案见 §7。
6. **附件/人员/单向关联等字段类型未覆盖**：字段类型全集在规划中（§7）。
7. **传输层无二进制协议**：gzip JSON 已满足当前规模，protobuf 仅作为 delta 帧的可选演进（§10）。

## 相关文档

- 性能演进与基准数据：[performance.md](performance.md)
- 公式引擎设计：[design/formula-engine.md](design/formula-engine.md)
- 性能护栏（改热路径前必读）：[performance-guardrails.md](performance-guardrails.md)
- 路线图：[roadmap.md](roadmap.md)
- 公式兼容目录：[formula-catalog.md](formula-catalog.md)
