package com.polytable.config;

import com.polytable.service.OpsFieldOptionSeedService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 中文备注：多维表格通用表结构自动迁移（ops_* 协作表 / 账户 / 角色 / 在场 / 审计）。
 */
@Component
public class SchemaMigration {

    private final JdbcTemplate jdbcTemplate;
    private final OpsFieldOptionSeedService fieldOptionSeedService;
    private final boolean enabled;

    public SchemaMigration(JdbcTemplate jdbcTemplate, OpsFieldOptionSeedService fieldOptionSeedService,
                           @Value("${schema.migration.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.fieldOptionSeedService = fieldOptionSeedService;
        this.enabled = enabled;
    }

    @PostConstruct
    public void migrate() {
        if (!enabled) {
            return;
        }
        createOpsFieldOptionIfNotExists();
        createOpsTableViewsIfNotExists();
        // 中文备注：已删视图 id 墓碑，防止旧客户端把删除写回。
        addColumnIfNotExists("ops_table_views", "tombstone_json", "LONGTEXT");
        createOpsTableRegistryIfNotExists();
        createOpsSheetFieldIfNotExists();
        createOpsSheetRowIfNotExists();
        createOpsSheetPasteBatchIfNotExists();
        createOpsFieldConfigIfNotExists();
        createOpsUserPresenceIfNotExists();
        createOpsAccountIfNotExists();
        createOpsRoleIfNotExists();
        widenOpsAccountRole();
        seedDefaultOpsRole();
        backfillOpsAccountRole();
        // 中文备注：总部 hq / 分域 org；默认 hq，多域隔离上线前可预置列。
        addColumnIfNotExists("ops_account", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addColumnIfNotExists("ops_account", "feature_flags", "JSON NULL");
        // 中文备注：仅用于后台人员管理标识，不参与登录角色、权限或域隔离。
        addColumnIfNotExists("ops_account", "personnel_type", "VARCHAR(16) NULL");
        addIndexIfNotExists("ops_account", "idx_ops_account_personnel_type", "personnel_type");
        addColumnIfNotExists("ops_account", "screening_assign_enabled", "TINYINT NULL");
        addColumnIfNotExists("ops_user_presence", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addIndexIfNotExists("ops_user_presence", "idx_ops_presence_org_seen", "org_type, last_seen");
        // 在场广播——用户当前所在表 / 框选的行键/列键 JSON({"r":[opsKey...],"c":[fieldKey...]}),空串=无选区。
        addColumnIfNotExists("ops_user_presence", "sel_json", "TEXT NULL");
        addColumnIfNotExists("ops_table_registry", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addIndexIfNotExists("ops_table_registry", "idx_ops_table_registry_org_deleted", "org_type, deleted, sort_order");
        addColumnIfNotExists("ops_field_option", "color", "VARCHAR(16)");
        addColumnIfNotExists("ops_field_option", "sort_order", "INT NOT NULL DEFAULT 0");
        // 中文备注：协作表性能——增量轮询（update_time 覆盖索引）与键集分页 tiebreaker（sort_order 相同时免 filesort）。
        addIndexIfNotExists("ops_sheet_row", "idx_sheet_row_tbl_del_updated", "table_id, deleted, update_time");
        addIndexIfNotExists("ops_sheet_row", "idx_sheet_row_sort_tiebreak", "table_id, deleted, sort_order, create_time, row_id");
        // 单元格级推送：操作日志表（seq 单调，SSE op 帧 + lastSeq 缺口回放；同时承载行级审计 /logs）。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_sheet_op_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    table_id VARCHAR(64) NOT NULL,
                    row_id VARCHAR(64) NOT NULL,
                    operator VARCHAR(64),
                    field_key VARCHAR(64) NOT NULL,
                    old_val TEXT,
                    new_val TEXT,
                    row_version INT,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_op_log_table_id (table_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // 中文备注：空白表列选项（单选/多选），供筛选与条件填色绑定。
        addColumnIfNotExists("ops_sheet_field", "options_json", "LONGTEXT");
        // 中文备注：空白表列查找引用配置（源表/源字段/条件 JSON）。
        addColumnIfNotExists("ops_sheet_field", "master_link_json", "LONGTEXT");
        // 中文备注：公式字段表达式可能较长，default_value 放宽为 TEXT。
        widenOpsFieldConfigDefaultValue();
        // 中文备注：自定义字段值袋 + 字段配置标记。
        addColumnIfNotExists("ops_field_config", "is_custom", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfNotExists("ops_field_config", "field_title", "VARCHAR(64)");
        addColumnIfNotExists("ops_field_config", "master_link_json", "LONGTEXT");
        seedOpsFieldOptionsIfEmpty();
    }

    private void seedOpsFieldOptionsIfEmpty() {
        try {
            fieldOptionSeedService.seedEmptyFieldConfigs();
        } catch (Exception ignored) {
        }
    }

    private void widenOpsFieldConfigDefaultValue() {
        try {
            String dataType = jdbcTemplate.queryForObject("""
                    SELECT DATA_TYPE
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ops_field_config'
                      AND column_name = 'default_value'
                    """, String.class);
            if (dataType != null && !"text".equalsIgnoreCase(dataType) && !"longtext".equalsIgnoreCase(dataType)) {
                jdbcTemplate.execute("ALTER TABLE ops_field_config MODIFY default_value TEXT");
            }
        } catch (Exception ignored) {
        }
    }

    private void widenOpsAccountRole() {
        try {
            Number charLen = jdbcTemplate.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'ops_account'
                      AND column_name = 'role'
                    """, Number.class);
            if (charLen != null && charLen.intValue() < 64) {
                jdbcTemplate.execute("ALTER TABLE ops_account MODIFY role VARCHAR(64) NOT NULL DEFAULT 'data-user'");
            }
        } catch (Exception ignored) {
        }
    }

    private void createOpsRoleIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_role (
                    role_key VARCHAR(64) PRIMARY KEY,
                    role_name VARCHAR(80) NOT NULL,
                    menu_permissions_json LONGTEXT NOT NULL,
                    builtin TINYINT NOT NULL DEFAULT 0,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_ops_role_name(role_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void seedDefaultOpsRole() {
        jdbcTemplate.update("""
                INSERT INTO ops_role (role_key, role_name, menu_permissions_json, builtin)
                VALUES ('data-user', '数据用户', '["data-user"]', 1)
                ON DUPLICATE KEY UPDATE role_key = VALUES(role_key)
                """);
    }

    private void backfillOpsAccountRole() {
        jdbcTemplate.update("""
                UPDATE ops_account SET role = 'data-user'
                WHERE role IS NULL OR TRIM(role) = '' OR role = 'user'
                """);
    }

    private void createOpsUserPresenceIfNotExists() {
        // 中文备注：协作在场落库，多实例之间共享「谁在哪张表 / 哪个格子」。
        jdbcTemplate.execute("""
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
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_last_seen(last_seen)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsAccountIfNotExists() {
        // 中文备注：协作台登录账户；password_hash 空则走「首字母+后缀」默认口令。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_account (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    display_name VARCHAR(64) NOT NULL,
                    initials VARCHAR(16) NOT NULL,
                    password_hash VARCHAR(128) NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    role VARCHAR(64) NOT NULL DEFAULT 'data-user',
                    remark VARCHAR(255) NULL,
                    updated_by VARCHAR(64) NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_ops_account_name(display_name),
                    KEY idx_ops_account_enabled(enabled),
                    KEY idx_ops_account_role(role)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsFieldConfigIfNotExists() {
        // 中文备注：字段类型 + 默认值 + 选项(含颜色) 整包保存，供表头「修改字段/列」全员共享。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_field_config (
                    field_key VARCHAR(64) PRIMARY KEY,
                    field_type VARCHAR(32) NOT NULL DEFAULT 'text',
                    default_value TEXT,
                    options_json LONGTEXT,
                    is_custom TINYINT NOT NULL DEFAULT 0,
                    field_title VARCHAR(64),
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsTableViewsIfNotExists() {
        // 中文备注：表格共享视图（顺序/配置），拖拽排序后全员可见。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_table_views (
                    table_id VARCHAR(64) PRIMARY KEY,
                    views_json LONGTEXT NOT NULL,
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsTableRegistryIfNotExists() {
        // 中文备注：用户新建表格。kind=blank 空白协作表（新建入口）；kind=filter 历史 API 兼容。
        jdbcTemplate.execute("""
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
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_ops_table_registry_deleted_sort(deleted, sort_order)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsSheetFieldIfNotExists() {
        // 中文备注：空白表列定义（按 table_id 隔离，不占用全局 ops_field_config）。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_sheet_field (
                    table_id VARCHAR(64) NOT NULL,
                    field_key VARCHAR(64) NOT NULL,
                    field_title VARCHAR(128) NOT NULL,
                    field_type VARCHAR(32) NOT NULL DEFAULT 'text',
                    default_value TEXT,
                    options_json LONGTEXT,
                    sort_order INT NOT NULL DEFAULT 0,
                    deleted TINYINT NOT NULL DEFAULT 0,
                    created_by VARCHAR(64),
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (table_id, field_key),
                    KEY idx_ops_sheet_field_table_deleted_sort(table_id, deleted, sort_order)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsSheetRowIfNotExists() {
        // 中文备注：空白表行；row_id 对应前端 opsKey，cells_json 存各列值。
        jdbcTemplate.execute("""
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
                    PRIMARY KEY (table_id, row_id),
                    KEY idx_ops_sheet_row_table_deleted_sort(table_id, deleted, sort_order)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsSheetPasteBatchIfNotExists() {
        jdbcTemplate.execute("""
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
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_ops_sheet_paste_batch_table_time(table_id, create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsFieldOptionIfNotExists() {
        // 中文备注：工作台表头「修改字段/列」新增的选项存这里，全员共享。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_field_option (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    field_key VARCHAR(64) NOT NULL,
                    option_value VARCHAR(128) NOT NULL,
                    deleted TINYINT NOT NULL DEFAULT 0,
                    created_by VARCHAR(64),
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_field_value(field_key, option_value),
                    KEY idx_field_deleted(field_key, deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);

        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
        }
    }

    private void addIndexIfNotExists(String tableName, String indexName, String indexDefinition) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);

        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD INDEX " + indexName + " (" + indexDefinition + ")");
        }
    }
}
