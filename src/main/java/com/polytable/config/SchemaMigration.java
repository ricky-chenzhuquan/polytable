package com.polytable.config;

import com.polytable.service.OpsFieldOptionSeedService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

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
        addColumnIfNotExists("drama_series", "publish_aweme_user", "VARCHAR(255)");
        addColumnIfNotExists("drama_series", "douyin_work_id", "VARCHAR(100)");
        addColumnIfNotExists("drama_series", "changdu_create_time", "DATETIME");
        addColumnIfNotExists("drama_series", "data_source", "VARCHAR(32)");
        addColumnIfNotExists("drama_series", "iap_panels_json", "LONGTEXT");
        addColumnIfNotExists("drama_series", "iaa_card2_url", "TEXT");
        addColumnIfNotExists("drama_series", "iaa_episode2_url", "TEXT");
        createRealDramaSeriesIfNotExists();
        createDouyinMiniPaidDramaSeriesIfNotExists();
        addColumnIfNotExists("real_drama_series", "douyin_work_id", "VARCHAR(100)");
        addColumnIfNotExists("real_drama_series", "changdu_create_time", "DATETIME");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "episode_price", "INT");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "free_episode_count", "INT");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "changdu_create_time", "DATETIME");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "douyin_album_id", "VARCHAR(100)");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "douyin_work_id", "VARCHAR(100)");
        makeDouyinMiniPaidDramaBookIdNullable();
        createHotQueryIndexesIfNotExists();
        backfillChangduCreateTime("drama_series");
        backfillChangduCreateTime("real_drama_series");
        backfillChangduCreateTime("douyin_mini_paid_drama_series");
        addColumnIfNotExists("start_time", "DATETIME");
        addColumnIfNotExists("end_time", "DATETIME");
        addColumnIfNotExists("insert_count", "INT");
        addColumnIfNotExists("task_time", "DATETIME");
        addColumnIfNotExists("new_count", "INT");
        addColumnIfNotExists("error_msg", "TEXT");
        createCrawlCursorIfNotExists();
        createPublishReminderLogIfNotExists();
        createPaidComicCreatedDateReconcileLogIfNotExists();
        createOpsFieldOptionIfNotExists();
        createOpsTableViewsIfNotExists();
        // 中文备注：已删视图 id 墓碑，防止旧客户端把删除写回。
        addColumnIfNotExists("ops_table_views", "tombstone_json", "LONGTEXT");
        createOpsTableRegistryIfNotExists();
        createOpsSheetFieldIfNotExists();
        createOpsSheetRowIfNotExists();
        createOpsSheetPasteBatchIfNotExists();
        createOpsFieldConfigIfNotExists();
        createOpsHiddenRowIfNotExists();
        createOpsAdxSeriesLinkIfNotExists();
        createOpsUserPresenceIfNotExists();
        createOpenApiAccountBindingIfNotExists();
        createOpsListChangeIfNotExists();
        createOpsAccountIfNotExists();
        createOpsRoleIfNotExists();
        widenOpsAccountRole();
        seedDefaultOpsRole();
        backfillOpsAccountRole();
        createOpsLeaderGroupAssignRuleIfNotExists();
        createOpsEditorGroupMemberIfNotExists();
        createAgencyDramaPromoLinkIfNotExists();
        addColumnIfNotExists("agency_drama_promo_link", "iaa_default_url", "TEXT");
        cleanupEditorGroupSystemSeed();
        backfillOpsCopyrightFromSeries();
        // 中文备注：总部 hq / 代投 agency；默认 hq，租户隔离上线前可预置列。
        addColumnIfNotExists("ops_account", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addColumnIfNotExists("ops_account", "feature_flags", "JSON NULL");
        // 中文备注：仅用于后台人员管理标识，不参与登录角色、权限或租户隔离。
        addColumnIfNotExists("ops_account", "personnel_type", "VARCHAR(16) NULL");
        addIndexIfNotExists("ops_account", "idx_ops_account_personnel_type", "personnel_type");
        addColumnIfNotExists("ops_account", "screening_assign_enabled", "TINYINT NULL");
        addColumnIfNotExists("ops_user_presence", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addIndexIfNotExists("ops_user_presence", "idx_ops_presence_org_seen", "org_type, last_seen");
        // W2 presence 选区广播——用户当前框选的行键/列键 JSON({"r":[opsKey...],"c":[fieldKey...]}),空串=无选区。
        addColumnIfNotExists("ops_user_presence", "sel_json", "TEXT NULL");
        addColumnIfNotExists("ops_table_registry", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addIndexIfNotExists("ops_table_registry", "idx_ops_table_registry_org_deleted", "org_type, deleted, sort_order");
        addColumnIfNotExists("ops_table_registry", "org_type", "VARCHAR(16) NOT NULL DEFAULT 'hq'");
        addIndexIfNotExists("ops_table_registry", "idx_ops_table_registry_org_deleted", "org_type, deleted, sort_order");
        addColumnIfNotExists("ops_field_option", "color", "VARCHAR(16)");
        addColumnIfNotExists("ops_field_option", "sort_order", "INT NOT NULL DEFAULT 0");
        // 中文备注：协作表性能——delta 增量轮询（update_time 覆盖索引）与键集分页 tiebreaker（sort_order 相同时免 filesort）。
        // 112 实测：delta 扫描 22,704 行→7 行 covering；深翻页 5 万行 filesort→3 行；在线 DDL 0.35s，写延迟影响 ~1ms。
        addIndexIfNotExists("ops_sheet_row", "idx_sheet_row_tbl_del_updated", "table_id, deleted, update_time");
        addIndexIfNotExists("ops_sheet_row", "idx_sheet_row_sort_tiebreak", "table_id, deleted, sort_order, create_time, row_id");
        // W1 单元格级推送：操作日志表（seq 单调，SSE op 帧 + lastSeq 缺口回退 delta）。
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
        // 中文备注：空白表列关联主表字段（按剧名/book_id 匹配，同步选项与只补空值）。
        addColumnIfNotExists("ops_sheet_field", "master_link_json", "LONGTEXT");
        // 中文备注：工作台「版权」允许人工覆盖，空值按默认「番茄」展示。
        addColumnIfNotExists("drama_operation", "copyright_owner", "VARCHAR(64)");
        // 中文备注：公式字段表达式可能较长，default_value 放宽为 TEXT。
        widenOpsFieldConfigDefaultValue();
        // 中文备注：空白表审计用 drama_type=sheet:{tableId}，需容纳较长 tableId。
        widenDramaOperationLogDramaType();
        // 中文备注：自定义字段值袋 + 字段配置标记；手动新增行用 data_source 区分采集。
        addColumnIfNotExists("drama_operation", "custom_fields_json", "LONGTEXT");
        addColumnIfNotExists("ops_field_config", "is_custom", "TINYINT NOT NULL DEFAULT 0");
        addColumnIfNotExists("ops_field_config", "field_title", "VARCHAR(64)");
        addColumnIfNotExists("ops_field_config", "master_link_json", "LONGTEXT");
        addColumnIfNotExists("drama_series", "data_source", "VARCHAR(32)");
        addColumnIfNotExists("real_drama_series", "data_source", "VARCHAR(32)");
        addColumnIfNotExists("douyin_mini_paid_drama_series", "data_source", "VARCHAR(32)");
        // 中文备注：手动新增行去重按剧名+版权查询，补索引避免大表扫。
        addIndexIfNotExists("drama_series", "idx_drama_series_name_copyright", "series_name, copyright_owner");
        addIndexIfNotExists("real_drama_series", "idx_real_series_name_copyright", "series_name, copyright_owner");
        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_series_name_copyright", "series_name, copyright_owner");
        addIndexIfNotExists("drama_operation", "idx_ops_type_name_copyright", "drama_type, series_name, copyright_owner");
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

    private void widenDramaOperationLogDramaType() {
        try {
            // 中文备注：MySQL 驱动常返回 Long，勿强绑 Integer，否则会静默跳过扩列。
            Number charLen = jdbcTemplate.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'drama_operation_log'
                      AND column_name = 'drama_type'
                    """, Number.class);
            if (charLen != null && charLen.intValue() < 64) {
                jdbcTemplate.execute("ALTER TABLE drama_operation_log MODIFY drama_type VARCHAR(64) NOT NULL");
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
                jdbcTemplate.execute("ALTER TABLE ops_account MODIFY role VARCHAR(64) NOT NULL DEFAULT 'drama-data'");
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
                VALUES ('drama-data', '剧目数据用户', '["drama-data"]', 1)
                ON DUPLICATE KEY UPDATE role_key = VALUES(role_key)
                """);
    }

    private void backfillOpsAccountRole() {
        jdbcTemplate.update("""
                UPDATE ops_account SET role = 'drama-data'
                WHERE role IS NULL OR TRIM(role) = '' OR role = 'user'
                """);
    }

    private void createOpenApiAccountBindingIfNotExists() {
        // 中文备注：开放平台渠道 ID + 密钥落库，补采时逐渠道查询；密钥不写配置文件。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS openapi_account_binding (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    platform VARCHAR(32) NOT NULL DEFAULT 'changdu',
                    distributor_id VARCHAR(64) NOT NULL,
                    secret_key VARCHAR(128) NOT NULL,
                    channel_type VARCHAR(32) NOT NULL DEFAULT 'COMIC_DRAMA',
                    delivery_type VARCHAR(16) NOT NULL DEFAULT 'IAA',
                    remark VARCHAR(128),
                    enabled TINYINT NOT NULL DEFAULT 1,
                    deleted TINYINT NOT NULL DEFAULT 0,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_platform_distributor(platform, distributor_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsListChangeIfNotExists() {
        // 中文备注：运营表增量水位；id 即 listVersion，入库命中近三天窗口时追加。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_list_change (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    drama_type VARCHAR(32) NOT NULL,
                    book_id VARCHAR(128) NOT NULL,
                    action VARCHAR(16) NOT NULL DEFAULT 'upsert',
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_id(id),
                    KEY idx_type_book(drama_type, book_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
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
                    role VARCHAR(64) NOT NULL DEFAULT 'drama-data',
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

    private void createOpsLeaderGroupAssignRuleIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_leader_group_assign_rule (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    delivery_date DATE NOT NULL,
                    window_start DATETIME NULL,
                    window_end DATETIME NULL,
                    excluded_groups_json LONGTEXT NOT NULL,
                    expected_drama_count INT NULL,
                    remark VARCHAR(255) NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    updated_by VARCHAR(64) NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_leader_group_rule_day(delivery_date, enabled),
                    KEY idx_leader_group_rule_window(window_start, window_end, enabled)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        addColumnIfNotExists("ops_leader_group_assign_rule", "window_start", "DATETIME NULL");
        addColumnIfNotExists("ops_leader_group_assign_rule", "window_end", "DATETIME NULL");
        addColumnIfNotExists("ops_leader_group_assign_rule", "assign_by", "VARCHAR(16) NOT NULL DEFAULT 'delivery'");
        try {
            jdbcTemplate.update("""
                    UPDATE ops_leader_group_assign_rule
                    SET assign_by = 'delivery'
                    WHERE assign_by IS NULL OR TRIM(assign_by) = ''
                    """);
        } catch (Exception ignored) {
        }
        try {
            jdbcTemplate.update("""
                    UPDATE ops_leader_group_assign_rule
                    SET window_start = CONCAT(delivery_date, ' 00:00:00'),
                        window_end = CONCAT(delivery_date, ' 23:59:59')
                    WHERE window_start IS NULL OR window_end IS NULL
                    """);
        } catch (Exception ignored) {
        }
    }

    private void createOpsEditorGroupMemberIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_editor_group_member (
                    editor_name VARCHAR(64) PRIMARY KEY,
                    group_name VARCHAR(64) NULL,
                    updated_by VARCHAR(64) NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    KEY idx_editor_group_member_group(group_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void cleanupEditorGroupSystemSeed() {
        try {
            jdbcTemplate.update("DELETE FROM ops_editor_group_member WHERE updated_by = 'system-seed'");
        } catch (Exception ignored) {
        }
    }

    /** 运营版权为空时从采集主表回补，避免剪辑小组自动分配建空行后列表版权全空白。 */
    private void backfillOpsCopyrightFromSeries() {
        for (String sql : List.of(
                """
                UPDATE drama_operation o
                INNER JOIN drama_series s ON o.drama_type = 'drama' AND o.book_id = s.book_id
                SET o.copyright_owner = COALESCE(NULLIF(TRIM(s.copyright_owner), ''), '番茄')
                WHERE o.copyright_owner IS NULL OR TRIM(o.copyright_owner) = ''
                """,
                """
                UPDATE drama_operation o
                INNER JOIN real_drama_series s ON o.drama_type = 'real' AND o.book_id = s.book_id
                SET o.copyright_owner = COALESCE(NULLIF(TRIM(s.copyright_owner), ''), '番茄')
                WHERE o.copyright_owner IS NULL OR TRIM(o.copyright_owner) = ''
                """,
                """
                UPDATE drama_operation o
                INNER JOIN douyin_mini_paid_drama_series s ON o.drama_type = 'douyin' AND o.book_id = s.book_id
                SET o.copyright_owner = COALESCE(NULLIF(TRIM(s.copyright_owner), ''), '番茄')
                WHERE o.copyright_owner IS NULL OR TRIM(o.copyright_owner) = ''
                """
        )) {
            try {
                jdbcTemplate.update(sql);
            } catch (Exception ignored) {
            }
        }
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
        // 中文备注：用户新建表格。kind=blank 空白协作表（本期新建入口）；kind=filter 历史筛选表兼容。
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
        // 中文备注：空白表行；row_id 前端映射为 opsKey，cells_json 存各列值。
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

    private void createOpsHiddenRowIfNotExists() {
        // 中文备注：工作台删除记录=软隐藏；全员列表过滤，撤销可恢复。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_hidden_row (
                    drama_type VARCHAR(32) NOT NULL,
                    book_id VARCHAR(128) NOT NULL,
                    hidden_by VARCHAR(64),
                    snapshot_json LONGTEXT,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (drama_type, book_id),
                    KEY idx_ops_hidden_row_time(create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createOpsAdxSeriesLinkIfNotExists() {
        // 中文备注：ADX 剧名 ↔ 主库 book_id 持久关联，对齐飞书「关联字段」。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ops_adx_series_link (
                    adx_series_name VARCHAR(255) NOT NULL,
                    normalized_name VARCHAR(255) NOT NULL,
                    source_drama_type VARCHAR(32) NOT NULL,
                    source_book_id VARCHAR(128) NOT NULL,
                    match_kind VARCHAR(32) NOT NULL DEFAULT 'EXACT',
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (adx_series_name),
                    KEY idx_ops_adx_link_normalized(normalized_name),
                    KEY idx_ops_adx_link_source(source_drama_type, source_book_id)
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

    private void addColumnIfNotExists(String columnName, String columnDefinition) {
        addColumnIfNotExists("crawl_task_log", columnName, columnDefinition);
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

    private void createHotQueryIndexesIfNotExists() {
        // 中文备注：DB 对账和主采集补推都会按发布时间窗口查，必须有单列时间索引，避免 COALESCE/全表扫把 MySQL 顶满。
        addIndexIfNotExists("drama_series", "idx_drama_estimate_publish_time", "estimate_publish_time");
        addIndexIfNotExists("drama_series", "idx_drama_publish_time", "publish_time");
        addIndexIfNotExists("drama_series", "idx_drama_changdu_create_time", "changdu_create_time");

        // 中文备注：列表窗口按 COALESCE(estimate,publish) 过滤+排序——函数索引让范围与排序都走索引,
        // 消掉 18 万行全表扫+filesort(MySQL 8.0.13+ 函数索引)。按日期加载是硬约束:窗口查询绝不能扫全表。
        addIndexIfNotExists("drama_series", "idx_drama_publish_key", "((COALESCE(estimate_publish_time, publish_time)))");
        addIndexIfNotExists("real_drama_series", "idx_real_publish_key", "((COALESCE(estimate_publish_time, publish_time)))");
        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_publish_key", "((COALESCE(estimate_publish_time, publish_time)))");

        addIndexIfNotExists("real_drama_series", "idx_real_estimate_publish_time", "estimate_publish_time");
        addIndexIfNotExists("real_drama_series", "idx_real_publish_time", "publish_time");
        addIndexIfNotExists("real_drama_series", "idx_real_changdu_create_time", "changdu_create_time");

        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_estimate_publish_time", "estimate_publish_time");
        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_publish_time", "publish_time");
        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_on_shelf_time", "on_shelf_time");
        addIndexIfNotExists("douyin_mini_paid_drama_series", "idx_douyin_changdu_create_time", "changdu_create_time");

        // 中文备注：推广链接参考按剧目类型过滤运营表；复合索引避免上线后运营表放大时扫全量。
        addIndexIfNotExists("drama_operation", "idx_ops_type_label", "drama_type, drama_type_label");
    }

    private void createCrawlCursorIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS crawl_cursor (
                    cursor_name VARCHAR(64) PRIMARY KEY,
                    cursor_time DATETIME,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createPublishReminderLogIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS drama_publish_reminder_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    book_id VARCHAR(64) NOT NULL,
                    reminder_type VARCHAR(32) NOT NULL,
                    remind_date DATE NOT NULL,
                    publish_time DATETIME,
                    status INT,
                    error_msg TEXT,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_book_reminder_date(book_id, reminder_type, remind_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createPaidComicCreatedDateReconcileLogIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS paid_comic_created_date_reconcile_log (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    run_mode VARCHAR(32) NOT NULL,
                    stage VARCHAR(32) NOT NULL,
                    business_date DATE NULL,
                    error_msg TEXT NOT NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_mode_stage_date_time(run_mode, stage, business_date, create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createRealDramaSeriesIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS real_drama_series (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    book_id VARCHAR(64) NOT NULL,
                    series_name VARCHAR(255),
                    episode_amount INT,
                    episode_price INT,
                    free_episode_count INT,
                    publish_time DATETIME,
                    estimate_publish_time DATETIME,
                    changdu_create_time DATETIME,
                    category_text VARCHAR(100),
                    copyright_owner VARCHAR(255),
                    publish_aweme_user VARCHAR(255),
                    douyin_work_id VARCHAR(100),
                    raw_json LONGTEXT,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_book_id(book_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void createDouyinMiniPaidDramaSeriesIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS douyin_mini_paid_drama_series (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    book_id VARCHAR(64) NULL,
                    series_name VARCHAR(255),
                    episode_amount INT,
                    publish_time DATETIME,
                    estimate_publish_time DATETIME,
                    changdu_create_time DATETIME,
                    on_shelf_time DATETIME,
                    latest_update_time DATETIME,
                    category_text VARCHAR(100),
                    copyright_owner VARCHAR(255),
                    publish_aweme_user VARCHAR(255),
                    douyin_album_id VARCHAR(100),
                    douyin_work_id VARCHAR(100),
                    permission_status INT,
                    raw_json LONGTEXT,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_book_id(book_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void makeDouyinMiniPaidDramaBookIdNullable() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'douyin_mini_paid_drama_series'
                  AND column_name = 'book_id'
                  AND is_nullable = 'NO'
                """, Integer.class);
        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE douyin_mini_paid_drama_series MODIFY book_id VARCHAR(64) NULL");
        }
    }

    private void createAgencyDramaPromoLinkIfNotExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agency_drama_promo_link (
                    drama_type VARCHAR(32) NOT NULL DEFAULT 'drama',
                    book_id VARCHAR(128) NOT NULL,
                    series_name VARCHAR(255),
                    delivery_date DATE NOT NULL,
                    iap_panels_json LONGTEXT,
                    iaa_card2_url TEXT,
                    iaa_episode2_url TEXT,
                    updated_by VARCHAR(64),
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (drama_type, book_id),
                    KEY idx_delivery_date (delivery_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void backfillChangduCreateTime(String tableName) {
        String sql = """
                UPDATE TABLE_NAME
                SET changdu_create_time = STR_TO_DATE(
                    JSON_UNQUOTE(JSON_EXTRACT(raw_json, '$.aweme_version_book_data.create_time')),
                    '%Y-%m-%d %H:%i:%s'
                )
                WHERE changdu_create_time IS NULL
                  AND raw_json IS NOT NULL
                  AND JSON_VALID(raw_json)
                """.replace("TABLE_NAME", tableName);
        jdbcTemplate.execute(sql);
    }
}
