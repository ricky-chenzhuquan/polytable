# 数据迁移指南（PolyTable）

本目录提供两套迁移能力：

1. **从旧部署迁移**：把既有部署（内部 auto-play 或任意旧版 PolyTable）中的协作表数据迁入 PolyTable —— 见 [migrate-from-legacy-mysql8.sql](migrate-from-legacy-mysql8.sql)
2. **版本升级**：PolyTable 自身升级无需手工迁移 —— 启动时 `SchemaMigration`（`src/main/java/com/polytable/config/SchemaMigration.java`）自动做 `addColumnIfNotExists` / `addIndexIfNotExists` / 建表，旧数据不受影响

---

## 一、从旧部署迁移（MySQL 8 → MySQL 8）

### 迁移范围

| 表 | 处理 | 说明 |
|---|---|---|
| `ops_table_registry` | ✅ 仅 `kind IN ('blank','filter')` 且未删除 | 用户自建的空白/筛选表；**内置业务表（tbl_all 等系统表）不迁移**——PolyTable 不含采集业务 |
| `ops_sheet_field` | ✅ 仅迁移范围内的表 | 列定义（含选项 JSON） |
| `ops_sheet_row` | ✅ 仅迁移范围内的表 | 行数据（cells_json 列存） |
| `ops_table_views` | ✅ 仅迁移范围内的表 | 共享视图 |
| `ops_sheet_op_log` | ✅ 仅迁移范围内的表 | 行操作日志（撤销依赖它） |
| `ops_sheet_paste_batch` | ✅ 仅迁移范围内的表 | 粘贴批次（撤销粘贴依赖它） |
| `ops_account` | ✅ 全量 | 账户；`role` 自动映射（见下）；口令哈希方案与旧版一致，**登录口令不变** |
| `ops_role` | ✅ 全量 | 角色键 `drama-data`/`user` 自动映射为 `data-user` |
| `ops_field_config` / `ops_field_option` | ✅ 全量 | 全局字段配置与选项字典 |
| `ops_user_presence` | ⏭ 默认跳过 | 在场状态属临时态，脚本中留有可选段落 |
| 采集业务表（drama_series 等） | ⏭ 不迁移 | 与多维表格无关 |

### 角色映射

| 旧 role | 新 role |
|---|---|
| `drama-data` / `user` / 空 | `data-user` |
| `admin` | `admin`（不变） |

### 操作步骤

> 全程幂等（`INSERT IGNORE`），重复执行不会产生重复数据；只增不改，可随时 DROP 目标库回滚。

```bash
# 0. 假设：旧库叫 auto_play（按实际替换），新库叫 polytable
#    旧库与新库可以在同一台 MySQL，也可以不同实例（dump 后导入为 legacy_auto_play）

# 1. 首次启动 PolyTable 一次，让 SchemaMigration 建好新库结构
#    （可选：导入前不想看到示例表，就先 DEMO_SEED_ENABLED=false）
#    注意：注册表非空时示例表种子会自动跳过，先迁移数据再长期使用亦可
DB_USERNAME=root DB_PASSWORD=*** mvn spring-boot:run   # 或 docker compose up -d

# 2. 导出旧库（跨实例时）
mysqldump -h <旧实例> -u root -p --no-tablespaces --single-transaction \
  auto_play > /tmp/auto_play-dump.sql

# 3. 导入为迁移源 schema（同实例则跳过 2-3，直接 USE auto_play）
mysql -h 127.0.0.1 -u root -p -e "CREATE DATABASE IF NOT EXISTS legacy_auto_play DEFAULT CHARSET utf8mb4"
mysql -h 127.0.0.1 -u root -p legacy_auto_play < /tmp/auto_play-dump.sql

# 4. 执行迁移（脚本头部把源 schema 名改成你的，默认 legacy_auto_play）
mysql -h 127.0.0.1 -u root -p polytable < migration/migrate-from-legacy-mysql8.sql

# 5. 校验
mysql -h 127.0.0.1 -u root -p polytable < migration/verify-migration.sql

# 6. 重启 PolyTable，用旧账户原口令登录验证
```

### 安全提醒（重要）

- 迁移会把**真实姓名、口令哈希、角色**一起带进新系统——这是预期行为（同一组织自托管），但迁移完成后请：
  1. 确认 `OPS_AUTH_PASSWORD_SUFFIX` 与旧系统保持一致（否则「首字母+后缀」类账户无法登录）
  2. 提醒用户首次登录后改密
  3. 旧库的 dump 文件（含真实数据）用完即删，不要留在新服务器上
- 若旧数据里混有你不希望带入的用户，先在旧库 `DELETE`/停用后再迁移，或迁移后在 `/ops/admin.html` 停用账户

### 常见问题

- **示例表和旧表混在一起？** 先迁移数据（注册表非空）则示例表种子自动跳过；若先启动过一次已生成示例表，可在界面里删除它。
- **旧表里出现 `tbl_all/tbl_created` 等系统表？** 脚本按 `kind` 过滤，不会迁入；若旧库把它们登记进了 registry 且 kind=blank，请先在旧库修正 kind 或迁移后删除。
- **登录不上？** 99% 是口令后缀不一致——`OPS_AUTH_PASSWORD_SUFFIX` 必须与旧系统一致（或让用户走「重置口令」）。

## 二、版本升级（PolyTable → PolyTable）

直接替换 jar / 镜像重启即可。`SchemaMigration` 幂等：

- 新增列/索引：`addColumnIfNotExists` / `addIndexIfNotExists`
- 新建表：`CREATE TABLE IF NOT EXISTS`
- 关闭自动迁移（不推荐）：`SCHEMA_MIGRATION_ENABLED=false`，升级前自行执行等价 DDL

升级前建议：`mysqldump polytable > backup-$(date +%F).sql` 留一份快照。
