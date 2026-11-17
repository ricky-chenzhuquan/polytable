-- ============================================================================
-- PolyTable 旧部署数据迁移脚本（MySQL 8）
-- 源：旧部署 schema（默认 legacy_auto_play；执行前全文替换为你的源 schema 名，
--     例如：sed 's/legacy_auto_play/auto_play/g' migrate-from-legacy-mysql8.sql > migrate.sql）
-- 目标：当前连接的 PolyTable 库（执行时 USE polytable，或 mysql polytable < 本文件）
--
-- 特性：
--   * 只迁协作表（blank/filter），不迁采集业务表与系统表
--   * 角色自动映射：drama-data / user / 空 → data-user
--   * 全程 INSERT IGNORE 幂等，可重复执行
--   * 不修改源库任何数据
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. 迁移范围：未删除且 kind ∈ (blank, filter) 的协作表
-- ----------------------------------------------------------------------------
CREATE TEMPORARY TABLE IF NOT EXISTS _pt_migrate_tables (
    table_id VARCHAR(64) PRIMARY KEY
);
DELETE FROM _pt_migrate_tables;
INSERT INTO _pt_migrate_tables (table_id)
SELECT table_id
FROM `legacy_auto_play`.ops_table_registry
WHERE deleted = 0 AND kind IN ('blank', 'filter')
  AND table_id IS NOT NULL AND table_id <> '';

-- ----------------------------------------------------------------------------
-- 1. 表注册表
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_table_registry
    (table_id, label, kind, base_type, default_filters_json, sort_order,
     owner, created_by, updated_by, deleted, org_type)
SELECT r.table_id, r.label, r.kind, r.base_type, r.default_filters_json, r.sort_order,
       r.owner, r.created_by, r.updated_by, r.deleted,
       COALESCE(r.org_type, 'hq')
FROM `legacy_auto_play`.ops_table_registry r
WHERE r.deleted = 0 AND r.kind IN ('blank', 'filter')
  AND r.table_id IS NOT NULL AND r.table_id <> '';

-- ----------------------------------------------------------------------------
-- 2. 列定义
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_sheet_field
    (table_id, field_key, field_title, field_type, default_value, options_json,
     sort_order, deleted, created_by, updated_by, create_time, update_time)
SELECT f.table_id, f.field_key, f.field_title, f.field_type, f.default_value, f.options_json,
       f.sort_order, f.deleted, f.created_by, f.updated_by, f.create_time, f.update_time
FROM `legacy_auto_play`.ops_sheet_field f
JOIN _pt_migrate_tables t ON t.table_id = f.table_id;

-- ----------------------------------------------------------------------------
-- 3. 行数据
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_sheet_row
    (table_id, row_id, cells_json, sort_order, version, deleted, updated_by,
     create_time, update_time)
SELECT w.table_id, w.row_id, w.cells_json, w.sort_order, w.version, w.deleted, w.updated_by,
       w.create_time, w.update_time
FROM `legacy_auto_play`.ops_sheet_row w
JOIN _pt_migrate_tables t ON t.table_id = w.table_id;

-- ----------------------------------------------------------------------------
-- 4. 共享视图
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_table_views
    (table_id, views_json, updated_by, create_time, update_time)
SELECT v.table_id, v.views_json, v.updated_by, v.create_time, v.update_time
FROM `legacy_auto_play`.ops_table_views v
JOIN _pt_migrate_tables t ON t.table_id = v.table_id;

-- ----------------------------------------------------------------------------
-- 5. 行操作日志（撤销依赖；量大时可按 create_time 截断）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_sheet_op_log
    (table_id, row_id, operator, field_key, old_val, new_val, row_version, create_time)
SELECT l.table_id, l.row_id, l.operator, l.field_key, l.old_val, l.new_val, l.row_version, l.create_time
FROM `legacy_auto_play`.ops_sheet_op_log l
JOIN _pt_migrate_tables t ON t.table_id = l.table_id;

-- ----------------------------------------------------------------------------
-- 6. 粘贴批次（撤销粘贴依赖）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_sheet_paste_batch
    (batch_id, table_id, operator, source_table_id, target_range,
     before_fields_json, before_rows_json, created_row_ids_json, summary_json,
     undone, create_time, update_time)
SELECT b.batch_id, b.table_id, b.operator, b.source_table_id, b.target_range,
       b.before_fields_json, b.before_rows_json, b.created_row_ids_json, b.summary_json,
       b.undone, b.create_time, b.update_time
FROM `legacy_auto_play`.ops_sheet_paste_batch b
JOIN _pt_migrate_tables t ON t.table_id = b.table_id;

-- ----------------------------------------------------------------------------
-- 7. 账户（role 映射：drama-data / user / 空 → data-user）
--    password_hash 为空 → 登录口令 = 姓名拼音首字母 + OPS_AUTH_PASSWORD_SUFFIX（与旧版规则一致）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_account
    (display_name, initials, password_hash, enabled, role, remark, updated_by,
     create_time, update_time, org_type)
SELECT a.display_name, a.initials, a.password_hash, a.enabled,
       CASE
           WHEN a.role IS NULL OR TRIM(a.role) = '' THEN 'data-user'
           WHEN a.role IN ('drama-data', 'user') THEN 'data-user'
           ELSE a.role
       END,
       a.remark, a.updated_by, a.create_time, a.update_time,
       COALESCE(a.org_type, 'hq')
FROM `legacy_auto_play`.ops_account a;

-- ----------------------------------------------------------------------------
-- 8. 角色（键映射同上；已存在的内置角色不覆盖）
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_role (role_key, role_name, menu_permissions_json, builtin)
SELECT CASE
           WHEN r.role_key IN ('drama-data', 'user') THEN 'data-user'
           ELSE r.role_key
       END,
       r.role_name, r.menu_permissions_json, r.builtin
FROM `legacy_auto_play`.ops_role r;

-- ----------------------------------------------------------------------------
-- 9. 全局字段配置 / 选项字典
-- ----------------------------------------------------------------------------
INSERT IGNORE INTO ops_field_config
    (field_key, field_type, default_value, options_json, is_custom, field_title,
     updated_by, create_time, update_time)
SELECT c.field_key, c.field_type, c.default_value, c.options_json, c.is_custom, c.field_title,
       c.updated_by, c.create_time, c.update_time
FROM `legacy_auto_play`.ops_field_config c;

INSERT IGNORE INTO ops_field_option
    (field_key, option_value, color, sort_order, deleted, created_by, updated_by,
     create_time, update_time)
SELECT o.field_key, o.option_value, o.color, o.sort_order, o.deleted, o.created_by, o.updated_by,
       o.create_time, o.update_time
FROM `legacy_auto_play`.ops_field_option o;

-- ----------------------------------------------------------------------------
-- 10.（可选）在场状态——默认注释：临时态，一般不需要迁移
-- ----------------------------------------------------------------------------
-- INSERT IGNORE INTO ops_user_presence
--     (display_name, table_id, table_label, drama_type, ops_key, series_name,
--      col_key, col_label, mode, last_seen)
-- SELECT p.display_name, p.table_id, p.table_label, p.drama_type, p.ops_key, p.series_name,
--        p.col_key, p.col_label, p.mode, p.last_seen
-- FROM `legacy_auto_play`.ops_user_presence p;

-- ----------------------------------------------------------------------------
-- 清理临时表
-- ----------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS _pt_migrate_tables;

-- 完成后执行 migration/verify-migration.sql 校验
SELECT 'migration done' AS result;
