-- ============================================================================
-- 迁移结果校验（在 PolyTable 目标库上执行）
-- 每行给出：迁移范围表 → 源行数 vs 目标行数（应相等）；账号/角色映射抽查。
-- ============================================================================

-- 1. 迁移范围（目标库里 kind ∈ blank/filter 的表）
SELECT 'registry: migrated tables' AS item, COUNT(*) AS target_cnt
FROM ops_table_registry WHERE deleted = 0 AND kind IN ('blank', 'filter');

-- 2. 各表源/目标行数对比（把 legacy_auto_play 换成你的源 schema 名）
SELECT 'ops_sheet_field' AS tbl_,
       (SELECT COUNT(*) FROM legacy_auto_play.ops_sheet_field f
         JOIN legacy_auto_play.ops_table_registry r ON r.table_id = f.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')) AS src_cnt,
       (SELECT COUNT(*) FROM ops_sheet_field f
         JOIN ops_table_registry r ON r.table_id = f.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')) AS target_cnt
UNION ALL
SELECT 'ops_sheet_row',
       (SELECT COUNT(*) FROM legacy_auto_play.ops_sheet_row w
         JOIN legacy_auto_play.ops_table_registry r ON r.table_id = w.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')),
       (SELECT COUNT(*) FROM ops_sheet_row w
         JOIN ops_table_registry r ON r.table_id = w.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter'))
UNION ALL
SELECT 'ops_table_views',
       (SELECT COUNT(*) FROM legacy_auto_play.ops_table_views v
         JOIN legacy_auto_play.ops_table_registry r ON r.table_id = v.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')),
       (SELECT COUNT(*) FROM ops_table_views v
         JOIN ops_table_registry r ON r.table_id = v.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter'))
UNION ALL
SELECT 'ops_sheet_op_log',
       (SELECT COUNT(*) FROM legacy_auto_play.ops_sheet_op_log l
         JOIN legacy_auto_play.ops_table_registry r ON r.table_id = l.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')),
       (SELECT COUNT(*) FROM ops_sheet_op_log l
         JOIN ops_table_registry r ON r.table_id = l.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter'))
UNION ALL
SELECT 'ops_sheet_paste_batch',
       (SELECT COUNT(*) FROM legacy_auto_play.ops_sheet_paste_batch b
         JOIN legacy_auto_play.ops_table_registry r ON r.table_id = b.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter')),
       (SELECT COUNT(*) FROM ops_sheet_paste_batch b
         JOIN ops_table_registry r ON r.table_id = b.table_id
        WHERE r.deleted = 0 AND r.kind IN ('blank','filter'));

-- 3. 账户：源行数 vs 目标行数；角色映射不应再有旧键
SELECT 'accounts: source' AS item, COUNT(*) AS cnt FROM legacy_auto_play.ops_account
UNION ALL
SELECT 'accounts: target', COUNT(*) FROM ops_account;
SELECT 'legacy role keys remaining (should be 0)' AS item, COUNT(*) AS cnt
FROM ops_account WHERE role IN ('drama-data', 'user') OR role IS NULL OR TRIM(role) = '';

-- 4. 抽样：每张迁移表一行
SELECT table_id, label, kind, base_type FROM ops_table_registry
WHERE deleted = 0 AND kind IN ('blank','filter') ORDER BY sort_order LIMIT 10;
SELECT table_id, COUNT(*) AS fields_cnt FROM ops_sheet_field GROUP BY table_id LIMIT 10;
SELECT table_id, COUNT(*) AS rows_cnt FROM ops_sheet_row GROUP BY table_id LIMIT 10;
