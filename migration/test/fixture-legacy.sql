-- ============================================================================
-- 旧部署（legacy）测试夹具：按旧版最终表结构造一个样例库，
-- 用于演练 migrate-from-legacy-mysql8.sql。全部为虚构数据。
-- 用法：mysql -u root -e "CREATE DATABASE legacy_auto_play DEFAULT CHARSET utf8mb4"
--       mysql -u root legacy_auto_play < fixture-legacy.sql
-- ============================================================================

-- 1. 表注册（2 张有效协作表 + 1 张已删除表[应被排除]）
CREATE TABLE IF NOT EXISTS ops_table_registry (
    table_id VARCHAR(64) PRIMARY KEY,
    label VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'blank',
    base_type VARCHAR(32) NOT NULL DEFAULT 'all',
    default_filters_json LONGTEXT,
    sort_order INT NOT NULL DEFAULT 0,
    owner VARCHAR(64),
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    deleted TINYINT NOT NULL DEFAULT 0,
    org_type VARCHAR(16) NOT NULL DEFAULT 'hq',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_table_registry (table_id, label, kind, base_type, sort_order, owner, created_by, org_type) VALUES
('tbl_u_legacy_a', '旧项目跟踪', 'blank', 'all', 1, '测试员', '测试员', 'hq'),
('tbl_u_legacy_b', '旧筛选工作表', 'filter', 'all', 2, '测试员', '测试员', 'hq'),
('tbl_u_legacy_dead', '已删除旧表', 'blank', 'all', 3, '测试员', '测试员', 'hq');

UPDATE ops_table_registry SET deleted = 1 WHERE table_id = 'tbl_u_legacy_dead';

-- 2. 列定义
CREATE TABLE IF NOT EXISTS ops_sheet_field (
    table_id VARCHAR(64) NOT NULL,
    field_key VARCHAR(64) NOT NULL,
    field_title VARCHAR(128) NOT NULL,
    field_type VARCHAR(32) NOT NULL DEFAULT 'text',
    default_value TEXT,
    options_json LONGTEXT,
    master_link_json LONGTEXT,
    sort_order INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (table_id, field_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_sheet_field (table_id, field_key, field_title, field_type, sort_order, created_by) VALUES
('tbl_u_legacy_a', 'title', '事项名称', 'text', 0, '测试员'),
('tbl_u_legacy_a', 'owner', '负责人', 'text', 1, '测试员'),
('tbl_u_legacy_a', 'status', '状态', 'select', 2, '测试员'),
('tbl_u_legacy_a', 'due', '截止日期', 'date', 3, '测试员'),
('tbl_u_legacy_b', 'keyword', '关键词', 'text', 0, '测试员');

UPDATE ops_sheet_field SET options_json = '[{"value":"进行中","color":"blue"},{"value":"已完成","color":"green"}]'
WHERE table_id = 'tbl_u_legacy_a' AND field_key = 'status';

-- 3. 行数据（含 1 行软删除[应随表迁入，可恢复]）
CREATE TABLE IF NOT EXISTS ops_sheet_row (
    table_id VARCHAR(64) NOT NULL,
    row_id VARCHAR(64) NOT NULL,
    cells_json LONGTEXT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    updated_by VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (table_id, row_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_sheet_row (table_id, row_id, cells_json, sort_order, version, deleted, updated_by) VALUES
('tbl_u_legacy_a', 'r_leg_0001', '{"title":"旧系统事项一","owner":"测试员","status":"进行中","due":"2027-03-01"}', 0, 2, 0, '测试员'),
('tbl_u_legacy_a', 'r_leg_0002', '{"title":"旧系统事项二","owner":"测试员","status":"已完成","due":"2027-03-15"}', 1, 1, 0, '测试员'),
('tbl_u_legacy_a', 'r_leg_0003', '{"title":"旧系统事项三（已删）","owner":"测试员","status":"进行中","due":"2027-04-01"}', 2, 1, 1, '测试员'),
('tbl_u_legacy_b', 'r_leg_b001', '{"keyword":"样例关键词"}', 0, 1, 0, '测试员');

-- 4. 共享视图
CREATE TABLE IF NOT EXISTS ops_table_views (
    table_id VARCHAR(64) PRIMARY KEY,
    views_json LONGTEXT NOT NULL,
    updated_by VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_table_views (table_id, views_json, updated_by) VALUES
('tbl_u_legacy_a', '[{"viewId":"vew_legacy_main","name":"主视图","locked":true}]', '测试员');

-- 5. 行操作日志
CREATE TABLE IF NOT EXISTS ops_sheet_op_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    table_id VARCHAR(64) NOT NULL,
    row_id VARCHAR(64) NOT NULL,
    operator VARCHAR(64),
    field_key VARCHAR(64) NOT NULL,
    old_val TEXT,
    new_val TEXT,
    row_version INT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_sheet_op_log (table_id, row_id, operator, field_key, old_val, new_val, row_version) VALUES
('tbl_u_legacy_a', 'r_leg_0001', '测试员', 'title', '旧标题', '旧系统事项一', 2);

-- 6. 粘贴批次
CREATE TABLE IF NOT EXISTS ops_sheet_paste_batch (
    batch_id VARCHAR(96) PRIMARY KEY,
    table_id VARCHAR(64) NOT NULL,
    operator VARCHAR(64),
    source_table_id VARCHAR(64),
    target_range VARCHAR(128),
    before_fields_json LONGTEXT NOT NULL,
    before_rows_json LONGTEXT NOT NULL,
    created_row_ids_json LONGTEXT NOT NULL,
    summary_json LONGTEXT NOT NULL,
    undone TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_sheet_paste_batch (batch_id, table_id, operator, before_fields_json, before_rows_json, created_row_ids_json, summary_json)
VALUES ('batch_legacy_1', 'tbl_u_legacy_a', '测试员', '[]', '[]', '["r_leg_0002"]', '{"added":1}');

-- 7. 账户（drama-data 旧角色应映射为 data-user；无 hash → 首字母+后缀口令规则）
CREATE TABLE IF NOT EXISTS ops_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    display_name VARCHAR(64) NOT NULL,
    initials VARCHAR(16) NOT NULL,
    password_hash VARCHAR(128) NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    role VARCHAR(64) NOT NULL DEFAULT 'drama-data',
    remark VARCHAR(255) NULL,
    updated_by VARCHAR(64) NULL,
    org_type VARCHAR(16) NOT NULL DEFAULT 'hq',
    feature_flags JSON NULL,
    personnel_type VARCHAR(16) NULL,
    screening_assign_enabled TINYINT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ops_account_name(display_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_account (display_name, initials, password_hash, enabled, role, remark, org_type) VALUES
('测试员', 'CSY', NULL, 1, 'drama-data', '夹具账户', 'hq'),
('旧管理员', 'OADM', NULL, 1, 'admin', '夹具账户', 'hq');

-- 8. 角色
CREATE TABLE IF NOT EXISTS ops_role (
    role_key VARCHAR(64) PRIMARY KEY,
    role_name VARCHAR(80) NOT NULL,
    menu_permissions_json LONGTEXT NOT NULL,
    builtin TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_role (role_key, role_name, menu_permissions_json, builtin) VALUES
('drama-data', '剧目数据用户', '["drama-data"]', 1);

-- 9. 全局字段配置 / 选项
CREATE TABLE IF NOT EXISTS ops_field_config (
    field_key VARCHAR(64) PRIMARY KEY,
    field_type VARCHAR(32) NOT NULL DEFAULT 'text',
    default_value TEXT,
    options_json LONGTEXT,
    is_custom TINYINT NOT NULL DEFAULT 0,
    field_title VARCHAR(64),
    master_link_json LONGTEXT,
    updated_by VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_field_config (field_key, field_type, is_custom, field_title) VALUES
('owner', 'text', 0, '负责人');

CREATE TABLE IF NOT EXISTS ops_field_option (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    field_key VARCHAR(64) NOT NULL,
    option_value VARCHAR(128) NOT NULL,
    color VARCHAR(16),
    sort_order INT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_field_value(field_key, option_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO ops_field_option (field_key, option_value, color, sort_order) VALUES
('status', '进行中', 'blue', 0),
('status', '已完成', 'green', 1);

-- 10. 在场状态（迁移脚本默认跳过）
CREATE TABLE IF NOT EXISTS ops_user_presence (
    display_name VARCHAR(64) PRIMARY KEY,
    table_id VARCHAR(64),
    table_label VARCHAR(40),
    drama_type VARCHAR(32),
    ops_key VARCHAR(128),
    series_name VARCHAR(80),
    col_key VARCHAR(64),
    col_label VARCHAR(40),
    mode VARCHAR(16) NOT NULL DEFAULT 'idle',
    last_seen DATETIME NOT NULL,
    org_type VARCHAR(16) NOT NULL DEFAULT 'hq',
    sel_json TEXT NULL,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
