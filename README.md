# PolyTable

**自托管多维表格（Bitable）· Self-hosted multi-dimensional table**

> 把「飞书多维表格」式的协作表格装进你自己的服务器：Canvas 渲染的十万行级大表、
> 119 个飞书兼容公式函数、SSE 实时协作、MySQL 存储，一个 Spring Boot JAR 即可部署。

<div align="center">

**Canvas 大表引擎** · **飞书兼容公式** · **实时协作** · **轻量自托管**

</div>

> TODO(开源发布前)：补充badge（CI / License / Java 版本）与界面截图（放 `docs/imgs/`）。

## 它是什么

PolyTable 是一个**自托管的多维表格服务**，对标飞书多维表格（Bitable）的核心体验：

| 能力 | 说明 |
|---|---|
| 🖥 Canvas 大表引擎 | 10 万行级滚动流畅（50k 行 × 70 列滚动 p95 8.7ms），稀疏列式 gzip 传输、键集分页 |
| 🧮 公式引擎 | 119 个函数 + 14 个运算符，兼容飞书多维表格公式目录（含官方文档溯源与 golden test） |
| 🤝 实时协作 | SSE 单元格级推送、在场状态（谁在编辑哪格）、操作日志与撤销 |
| 🗂 视图与字段 | 文本 / 单选 / 多选 / 日期 / 复选框 / 公式 / 关联字段，筛选、排序、分组、多视图共享 |
| 🔐 权限与账户 | HMAC 无状态 token、角色菜单权限、账户管理面板，口令全部由环境变量注入 |
| 📦 轻量自托管 | 单个 Spring Boot JAR + 一个 MySQL，Docker Compose 一键启动 |

**性能与架构细节见 [docs/architecture.md](docs/architecture.md) 与 [docs/performance.md](docs/performance.md)。**

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
git clone https://github.com/YOUR_GITHUB_NAME/polytable.git   # TODO(仓库名确认后替换)
cd polytable
cp .env.example .env          # 修改其中的口令配置
docker compose up -d
# 打开 http://localhost:8080/ops/
```

### 方式二：源码运行

依赖：JDK 17+、Maven 3.8+、MySQL 8。

```bash
git clone https://github.com/YOUR_GITHUB_NAME/polytable.git   # TODO(仓库名确认后替换)
cd polytable
mvn spring-boot:run -Dspring-boot.run.arguments="--DB_USERNAME=root --DB_PASSWORD=你的密码"
# 打开 http://localhost:8080/ops/
```

首次启动会自动建表（`ops_*` 系列表）、创建演示账户与示例表：

- **演示账户**：`张三` / `李四`，默认口令 = 姓名拼音首字母 + `OPS_AUTH_PASSWORD_SUFFIX`（未设置后缀时口令即首字母 `ZS` / `LS`，仅限本地体验）
- **示例表** `tbl_demo_projects`：含单选、多选、日期、复选框、公式列的演示数据
- **系统管理员**：配置 `OPS_ADMIN_USERNAME` / `OPS_ADMIN_PASSWORD` 后用 `/ops/admin.html` 管理账户与角色

### 关键环境变量

| 变量 | 说明 | 必填 |
|---|---|---|
| `DB_URL` | MySQL 连接串（默认 `jdbc:mysql://localhost:3306/polytable?createDatabaseIfNotExist=true&...`） | 否 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库账号 | 建议 |
| `OPS_AUTH_PASSWORD_SUFFIX` | 初始口令后缀（初始口令 = 姓名拼音首字母 + 后缀） | 生产必填 |
| `OPS_AUTH_TOKEN_SECRET` | 登录 token 的 HMAC 签名密钥；不设置则每次启动随机生成，**重启后所有登录态失效** | 生产必填 |
| `OPS_ADMIN_USERNAME` / `OPS_ADMIN_PASSWORD` | 账户管理页（`/ops/admin.html`）管理员账号 | 生产必填 |
| `OPS_LIST_MAX_PAGE_SIZE` | 单次列表拉取行数上限（默认 50000） | 否 |
| `DEMO_SEED_ENABLED` | 首次启动是否创建示例表（默认 `true`） | 否 |
| `OPS_TENANT_ENABLED` | 多租户分域（按 Host 区分），默认关闭 | 否 |

完整配置见 [src/main/resources/application.yml](src/main/resources/application.yml)（每项都有注释）。

## 功能清单

- 表格：新建 / 重命名 / 删除空白表，注册表统一管理（`ops_table_registry`）
- 字段：文本、单选、多选、日期、复选框、公式、关联（lookup）；自定义字段、选项与颜色
- 行：单元格编辑、批量粘贴（含撤销批次）、软删除 / 恢复、乐观锁版本守卫
- 视图：筛选 / 排序 / 分组 / 多视图，全员共享（`ops_table_views`）
- 公式：119 函数 + 14 运算符，依赖图级联重算，官方示例 golden test 全覆盖
- 协作：SSE 单元格推送 + 心跳、在场状态、行级操作日志、增量 delta 轮询
- 工程化：列式 gzip 编码（dense/sparse v2/二进制三档）、metaOnly 轮询、stale-while-revalidate 缓存、负载降级保护

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 三层架构、三条数据加载协议、协作内核设计（含与飞书多维表格的差距清单） |
| [docs/performance.md](docs/performance.md) | 50k 行 × 70 列压测实录：根因分析与修复前后数据 |
| [docs/performance-guardrails.md](docs/performance-guardrails.md) | 热路径红线、缓存失效契约、无节流测量方法论 |
| [docs/design/formula-engine.md](docs/design/formula-engine.md) | 公式引擎设计：AST、类型化值模型、依赖图与缓存 |
| [docs/formula-catalog.md](docs/formula-catalog.md) | 119 函数兼容目录说明与溯源 |
| [docs/roadmap.md](docs/roadmap.md) | 已完成能力与公开路线图 |

## 官网

静态官网在 [website/](website/index.html)，配置 GitHub Pages 后自动部署（见 `.github/workflows/deploy-pages.yml`）。

## 安全说明

- 所有凭据类配置只从环境变量读取，代码与配置文件中**不内置任何默认口令**
- 登录 token 为 HMAC-SHA256 签名的无状态 token，默认 24h 过期
- 已知限制：密码摘要当前为 SHA-256 方案（见 `OpsAuthService#hashPassword`），计划在路线图中升级为 BCrypt；生产部署请确保 MySQL 与服务网络隔离，并强制用户首次登录后改密
- 漏洞报告请走 [SECURITY.md](SECURITY.md) 的私下披露流程

## 免责声明

PolyTable 与飞书 / Airtable 无任何隶属或合作关系。「飞书多维表格公式兼容目录」来自其官方公开文档的独立整理（含溯源引用），仅用于兼容性描述。

## License

[MIT](LICENSE) © 2026 PolyTable contributors
