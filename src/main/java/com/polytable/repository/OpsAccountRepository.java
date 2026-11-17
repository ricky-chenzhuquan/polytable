package com.polytable.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class OpsAccountRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Map<String, Object>> rowMapper = (rs, rowNum) -> {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("displayName", rs.getString("display_name"));
        row.put("initials", rs.getString("initials"));
        row.put("passwordHash", rs.getString("password_hash"));
        row.put("hasCustomPassword", rs.getString("password_hash") != null
                && !rs.getString("password_hash").isBlank());
        row.put("enabled", rs.getInt("enabled") == 1);
        row.put("role", rs.getString("role"));
        row.put("orgType", rs.getString("org_type"));
        row.put("personnelType", rs.getString("personnel_type"));
        Object screeningAssignEnabled = rs.getObject("screening_assign_enabled");
        row.put("screeningAssignEnabled", screeningAssignEnabled == null ? null
                : screeningAssignEnabled instanceof Boolean value ? value
                : ((Number) screeningAssignEnabled).intValue() != 0);
        row.put("featureFlags", rs.getString("feature_flags"));
        row.put("remark", rs.getString("remark"));
        row.put("updatedBy", rs.getString("updated_by"));
        Timestamp createTime = rs.getTimestamp("create_time");
        Timestamp updateTime = rs.getTimestamp("update_time");
        row.put("createTime", createTime == null ? "" : createTime.toLocalDateTime().toString().replace('T', ' '));
        row.put("updateTime", updateTime == null ? "" : updateTime.toLocalDateTime().toString().replace('T', ' '));
        return row;
    };

    public OpsAccountRepository(@Qualifier("opsAgencyLocalJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> listAll() {
        return jdbcTemplate.query("""
                SELECT id, display_name, initials, password_hash, enabled, role, org_type, personnel_type, screening_assign_enabled, feature_flags, remark, updated_by, create_time, update_time
                FROM ops_account
                ORDER BY enabled DESC, org_type ASC, role ASC, display_name ASC
                """, rowMapper);
    }

    /**
     * 启用中的协作账户 display_name（用于剪辑下拉与均分名单；不含 admin）。
     */
    public List<String> listEnabledAssigneeNames() {
        return listEnabledAssigneeNames("hq");
    }

    public List<String> listEnabledAssigneeNames(String orgTypeFilter) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT display_name
                FROM ops_account
                WHERE enabled = 1
                  AND IFNULL(role, 'user') <> 'admin'
                """);
        String org = orgTypeFilter == null ? "" : orgTypeFilter.trim().toLowerCase();
        if ("hq".equals(org) || "agency".equals(org)) {
            sql.append(" AND org_type = ?");
            args.add(org);
        }
        sql.append(" ORDER BY display_name ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String name = rs.getString("display_name");
            return name == null ? "" : name.trim();
        }, args.toArray()).stream().filter(s -> !s.isBlank()).toList();
    }

    /** 筛剧池账号：启用的总部剪辑师，历史 NULL 继续按默认允许处理。 */
    public List<String> listScreeningAssignableEditorNames() {
        return jdbcTemplate.query("""
                SELECT display_name
                FROM ops_account
                WHERE enabled = 1
                  AND IFNULL(role, 'user') <> 'admin'
                  AND org_type = 'hq'
                  AND personnel_type = 'editor'
                  AND (screening_assign_enabled IS NULL OR screening_assign_enabled = 1)
                ORDER BY display_name ASC
                """, (rs, rowNum) -> {
            String name = rs.getString("display_name");
            return name == null ? "" : name.trim();
        }).stream().filter(name -> !name.isBlank()).toList();
    }

    public boolean isEnabledAssignee(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return false;
        }
        Integer hit = jdbcTemplate.queryForObject("""
                SELECT COUNT(1)
                FROM ops_account
                WHERE display_name = ?
                  AND enabled = 1
                  AND IFNULL(role, 'user') <> 'admin'
                """, Integer.class, displayName.trim());
        return hit != null && hit > 0;
    }

    public List<Map<String, Object>> listByOrgType(String orgType) {
        String org = orgType == null || orgType.isBlank() ? "hq" : orgType.trim().toLowerCase();
        return jdbcTemplate.query("""
                SELECT id, display_name, initials, password_hash, enabled, role, org_type, personnel_type, screening_assign_enabled, feature_flags, remark, updated_by, create_time, update_time
                FROM ops_account
                WHERE org_type = ?
                ORDER BY enabled DESC, role ASC, display_name ASC
                """, rowMapper, org);
    }

    public Map<String, Object> findByDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, display_name, initials, password_hash, enabled, role, org_type, personnel_type, screening_assign_enabled, feature_flags, remark, updated_by, create_time, update_time
                FROM ops_account
                WHERE display_name = ?
                LIMIT 1
                """, rowMapper, displayName.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int insertIgnore(String displayName, String initials, String role, boolean enabled) {
        return insertIgnore(displayName, initials, role, enabled, "hq");
    }

    public int insertIgnore(String displayName, String initials, String role, boolean enabled, String orgType) {
        return jdbcTemplate.update("""
                INSERT IGNORE INTO ops_account (display_name, initials, role, enabled, org_type)
                VALUES (?, ?, ?, ?, ?)
                """,
                displayName.trim(),
                initials.trim().toUpperCase(),
                role == null || role.isBlank() ? "user" : role.trim().toLowerCase(),
                enabled ? 1 : 0,
                normalizeOrgType(orgType));
    }

    public int create(String displayName, String initials, String passwordHash, String role, boolean enabled, String remark, String updatedBy) {
        return create(displayName, initials, passwordHash, role, enabled, remark, updatedBy, "hq");
    }

    public int create(String displayName, String initials, String passwordHash, String role, boolean enabled, String remark, String updatedBy, String orgType) {
        return create(displayName, initials, passwordHash, role, enabled, remark, updatedBy, orgType, null, null);
    }

    public int create(String displayName, String initials, String passwordHash, String role, boolean enabled,
                      String remark, String updatedBy, String orgType, String personnelType,
                      Boolean screeningAssignEnabled) {
        return jdbcTemplate.update("""
                INSERT INTO ops_account (display_name, initials, password_hash, role, enabled, remark, updated_by,
                                         org_type, personnel_type, screening_assign_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''), ?)
                """,
                displayName.trim(),
                initials.trim().toUpperCase(),
                blankToNull(passwordHash),
                role == null || role.isBlank() ? "user" : role.trim().toLowerCase(),
                enabled ? 1 : 0,
                blankToNull(remark),
                blankToNull(updatedBy),
                normalizeOrgType(orgType),
                personnelType == null ? "" : personnelType.trim(),
                screeningAssignEnabled == null ? null : (screeningAssignEnabled ? 1 : 0));
    }

    public int setEnabled(String displayName, boolean enabled, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET enabled = ?, updated_by = ?
                WHERE display_name = ?
                """, enabled ? 1 : 0, blankToNull(updatedBy), displayName.trim());
    }

    public int setPasswordHash(String displayName, String passwordHash, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET password_hash = ?, updated_by = ?
                WHERE display_name = ?
                """, blankToNull(passwordHash), blankToNull(updatedBy), displayName.trim());
    }

    public int resetPassword(String displayName, String updatedBy) {
        return setPasswordHash(displayName, null, updatedBy);
    }

    public int updateInitials(String displayName, String initials, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET initials = ?, updated_by = ?
                WHERE display_name = ?
                """, initials.trim().toUpperCase(), blankToNull(updatedBy), displayName.trim());
    }

    public int countAll() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ops_account", Integer.class);
        return count == null ? 0 : count;
    }

    public int countFiltered(String orgTypeFilter, String keyword) {
        return countFiltered(orgTypeFilter, "", keyword);
    }

    public int countFiltered(String orgTypeFilter, String personnelTypeFilter, String keyword) {
        FilterClause filter = buildFilter(orgTypeFilter, personnelTypeFilter, keyword);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM ops_account WHERE 1=1" + filter.sql,
                Integer.class,
                filter.args.toArray());
        return count == null ? 0 : count;
    }

    public List<Map<String, Object>> listPaged(String orgTypeFilter, String keyword, int page, int pageSize) {
        return listPaged(orgTypeFilter, "", keyword, page, pageSize);
    }

    public List<Map<String, Object>> listPaged(String orgTypeFilter, String personnelTypeFilter, String keyword, int page, int pageSize) {
        FilterClause filter = buildFilter(orgTypeFilter, personnelTypeFilter, keyword);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safeSize;
        List<Object> args = new ArrayList<>(filter.args);
        args.add(safeSize);
        args.add(offset);
        return jdbcTemplate.query("""
                SELECT id, display_name, initials, password_hash, enabled, role, org_type, personnel_type, screening_assign_enabled, feature_flags, remark, updated_by, create_time, update_time
                FROM ops_account
                WHERE 1=1
                """ + filter.sql + """
                ORDER BY enabled DESC, org_type ASC, role ASC, display_name ASC
                LIMIT ? OFFSET ?
                """, rowMapper, args.toArray());
    }

    public int updateOrgType(String displayName, String orgType, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET org_type = ?, updated_by = ?
                WHERE display_name = ?
                """, normalizeOrgType(orgType), blankToNull(updatedBy), displayName.trim());
    }

    public int updatePersonnelType(String displayName, String personnelType, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET personnel_type = NULLIF(?, ''), updated_by = ?
                WHERE display_name = ?
                """, personnelType == null ? "" : personnelType.trim(), blankToNull(updatedBy), displayName.trim());
    }

    public int updatePersonnelSettings(String displayName, String personnelType,
                                       Boolean screeningAssignEnabled, String updatedBy) {
        Integer setting = screeningAssignEnabled == null ? null : (screeningAssignEnabled ? 1 : 0);
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET personnel_type = NULLIF(?, ''),
                    screening_assign_enabled = CASE WHEN ? IS NULL THEN screening_assign_enabled ELSE ? END,
                    updated_by = ?
                WHERE display_name = ?
                """, personnelType == null ? "" : personnelType.trim(), setting, setting,
                blankToNull(updatedBy), displayName.trim());
    }

    /** 启动补种只填空值，禁止覆盖管理员已经保存的人员类型。 */
    public int updatePersonnelTypeIfMissing(String displayName, String personnelType, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET personnel_type = NULLIF(?, ''), updated_by = ?
                WHERE display_name = ?
                  AND (personnel_type IS NULL OR TRIM(personnel_type) = '')
                """, personnelType == null ? "" : personnelType.trim(), blankToNull(updatedBy), displayName.trim());
    }

    public int clearPersonnelTypeIfMatches(String displayName, String expectedPersonnelType, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET personnel_type = NULL, updated_by = ?
                WHERE display_name = ? AND personnel_type = ?
                """, blankToNull(updatedBy), displayName.trim(), expectedPersonnelType.trim());
    }

    public int updateFeatureFlags(String displayName, String featureFlagsJson, String updatedBy) {
        return jdbcTemplate.update("""
                UPDATE ops_account
                SET feature_flags = ?, updated_by = ?
                WHERE display_name = ?
                """, blankToNull(featureFlagsJson), blankToNull(updatedBy), displayName.trim());
    }

    /** 总部推送 / 登录拉回：按 display_name upsert 全字段。 */
    public int upsertFromSync(String displayName,
                              String initials,
                              String passwordHash,
                              boolean enabled,
                              String role,
                              String remark,
                              String updatedBy,
                              String orgType,
                              String featureFlagsJson) {
        return jdbcTemplate.update("""
                INSERT INTO ops_account
                  (display_name, initials, password_hash, enabled, role, remark, updated_by, org_type, feature_flags)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  initials = VALUES(initials),
                  password_hash = VALUES(password_hash),
                  enabled = VALUES(enabled),
                  role = VALUES(role),
                  remark = VALUES(remark),
                  updated_by = VALUES(updated_by),
                  org_type = VALUES(org_type),
                  feature_flags = VALUES(feature_flags)
                """,
                displayName.trim(),
                initials == null ? "" : initials.trim().toUpperCase(),
                blankToNull(passwordHash),
                enabled ? 1 : 0,
                role == null || role.isBlank() ? "user" : role.trim().toLowerCase(),
                blankToNull(remark),
                blankToNull(updatedBy),
                normalizeOrgType(orgType),
                blankToNull(featureFlagsJson));
    }

    private static FilterClause buildFilter(String orgTypeFilter, String personnelTypeFilter, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        String org = orgTypeFilter == null ? "" : orgTypeFilter.trim().toLowerCase();
        if ("hq".equals(org) || "agency".equals(org)) {
            sql.append(" AND org_type = ?");
            args.add(org);
        }
        String personnelType = personnelTypeFilter == null ? "" : personnelTypeFilter.trim().toLowerCase();
        if ("editor".equals(personnelType) || "optimizer".equals(personnelType) || "management".equals(personnelType)) {
            sql.append(" AND personnel_type = ?");
            args.add(personnelType);
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isBlank()) {
            sql.append(" AND (display_name LIKE ? ESCAPE '\\\\' OR initials LIKE ? ESCAPE '\\\\')");
            String like = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        return new FilterClause(sql.toString(), args);
    }

    private record FilterClause(String sql, List<Object> args) {
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeOrgType(String orgType) {
        String v = orgType == null ? "" : orgType.trim().toLowerCase();
        return "agency".equals(v) ? "agency" : "hq";
    }
}
