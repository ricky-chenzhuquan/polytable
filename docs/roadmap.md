# PolyTable 路线图

> 本文档基于 PolyTable 在真实生产负载（数万行规模表格、百人并发协作场景）下的长期演进与实测经验整理。

PolyTable 按"先地基、后体验、再平台"的顺序演进。下面按主题列出已完成能力与公开规划；设计依据见 [architecture.md](architecture.md)，性能依据见 [performance.md](performance.md)。

## 已完成（v0.1 已包含）

- ✅ **Canvas 表格渲染引擎**：5 万行 × 70 列虚拟滚动（滚动绘制 p50 5.4ms、零超 16ms 帧）、分组渲染、单元格类型、撤销/重做、拖拽填充、查找高亮、冻结列。
- ✅ **公式引擎**：tokenizer/parser → AST → 类型化值模型，119 函数 + 14 运算符的飞书多维表格公式兼容目录与 golden test（见 [design/formula-engine.md](design/formula-engine.md)）。
- ✅ **表格与视图**：共享视图 + 个人视图、筛选/排序/分组、字段元数据管理。
- ✅ **数据加载协议**：稀疏列式 gzip 默认路径（`payloadNonce` 二进制块）、JSON 全量路径、NDJSON 单响应流式路径、键集分页（含 tiebreaker 覆盖索引）。
- ✅ **协作基础**：SSE 变更推送（表级 dirty + 单元格级 op 帧，写→可见 p50 0.1ms）、断线握手（Last-Event-ID 升序回放）、metaOnly 轮询兜底、presence 选区广播、协作撤销/重做（单元格 LWW + 版本守卫）。
- ✅ **记录详情抽屉**：字段总览 + 动态 + 行历史。
- ✅ **权限雏形**：账户/角色/表级权限管理。
- ✅ **性能底座**：表级水位/计数/元数据缓存、metaOnly 整响应缓存、首屏编码缓存 + stale-while-revalidate、重载闸门（100 人混合压测 0 错误 0 OOM，见 [performance.md](performance.md)）。

## 公开规划（按优先级主题排列）

### 1. 协作内核收尾

- 粘贴批量的逐格 op 帧全覆盖（当前批量更新路径已产生 op 帧，全新行走 dirty 信号）。
- 单元格级推送粒度持续细化（rowId + fieldKey 级，为 P2P 合并与精细审计打地基）。
- 依据：5s 轮询 → <100ms 推送的演进已完成大半，收尾后撤销/审计/推送共用同一份 op_log 数据源。

### 2. 权限体系与人员字段

- 表级 + 字段级权限裁剪（**协议层真裁剪**：未授权字段根本不进响应），记录级读写规则编译进键集分页 WHERE。
- delta 增量拉取同样过滤（防增量泄露）；列级裁剪并入列表缓存 key。
- 人员字段（值只存 userId，读时 join 用户目录）。
- 依据：企业/团队自托管落地的门槛能力，设计见 [architecture.md §8](architecture.md#8-权限体系规划)。

### 3. 视图全家桶

- 看板视图（分组分桶、列计数聚合、拖拽改单选字段）+ 日历视图（前端月切片、拖拽/resize 直写起止字段）——协同场景高频刚需。
- 表单视图（提交 = record create + typecast，防重与限流）。
- 画廊视图（CSS columns 瀑布流）、甘特视图（与表格共用行缓存 + 时间条派生 + 依赖线）。
- 依据：一份行缓存多视图派生，数据共享、渲染状态按 viewId 独立，设计见 [architecture.md §9](architecture.md#9-视图全家桶规划)。

### 4. 数据安全与对外协作

- 回收站（整行 payload 保留 30 天，恢复冲突 409 二选一）。
- 分享外链（只读 scope、filters/列裁剪收敛、口令与过期控制）。
- 记录历史 UI 完善（per-field diff + 字段元数据内嵌防删除后渲染不出）。

### 5. 附件字段与自动化

- 附件字段：两段式直传对象存储（预签名 → PUT 直传 → 回执，hash 去重，缩略 URL 读时算）。
- 自动化：规则表（触发器 = 记录满足条件 / 定时 / 按钮；动作 = 改字段 / 通知 / webhook / 汇总推送）+ 去重与失败重试。
- 依据：平台闭环能力，设计见 [architecture.md §7/§11](architecture.md#7-字段类型全集规划)。

### 6. 数据建模：关联 / lookup / rollup 与公式 DAG

- 字段关联（junction 中间表 + 展示缓存，删除主记录 = detach）、lookup 跨表引用、rollup 聚合函数集。
- 公式依赖图（DAG 表 + 递归 CTE 求影响闭包，写事务后拓扑序只重算受影响字段），替换当前"每请求全行重算"。

### 7. 平台能力

- 仪表盘（component query DSL 编译单条聚合 SQL + 写事件失效缓存）。
- 全文搜索（MySQL ngram FULLTEXT 起步，统一 filter→WHERE 编译器供视图/自动化/图表/分享复用）。
- 结构级快照（字段/视图配置历史，按字段 id diff 恢复）。

### 8. 传输层与性能演进

- `PushTransport` 抽象层：SSE 实现保留 + WebSocket 实现同注册表，前端特性探测回落（触发条件：上行低延迟、>3000 并发、二进制帧）。
- 增量编码：按 dirty 行合并重编码，进一步压低高频编辑场景的 p95。
- delta 增量路径缓存、`/rows` 全量路径流式/分块输出（消除高并发堆内存限制，见 [performance.md §11](performance.md#11-剩余已知限制)）。
- k6/JMeter 多进程压测验收（消除 Python GIL 测量失真）。

## 架构决策不变项

以下取舍经过实证论证，除非需求变化否则不推翻：

1. **结构化表格不做真 OT**：单元格 LWW + 版本守卫即飞书同语义；仅富文本单元格出现时才评估 per-cell OT/CRDT。
2. **SSE 够用，不急着换 WebSocket**：抽象层留好升级口（`PushTransport`），按触发条件演进。
3. **全量块不引入 protobuf**：文本密集数据 gzip JSON 体积打平甚至反超；仅 delta 帧可选二进制信封。
4. **权限的正解是协议层真裁剪**：未授权数据不进响应，而不是进响应后再藏。

## 参与贡献

路线图各项的设计依据都已在 [architecture.md](architecture.md) 中展开（含协作内核、字段类型、权限、视图、传输层的落地设计与工作量参考）。欢迎按主题认领：提 issue 讨论设计 → 按护栏（[performance-guardrails.md](performance-guardrails.md)）实现 → 附基准数据。

---

觉得 PolyTable 有用？欢迎[支持作者](donate.md) ☕
