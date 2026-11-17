package com.polytable.service;

import com.polytable.repository.OpsAccountRepository;
import com.polytable.repository.OpsPresenceRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协作工作台鉴权：成员用「拼音首字母 + 后缀」登录，token 默认有效 1 天。
 * token 采用 HMAC 签名的无状态格式，服务重启后浏览器里已保存的 token 依然可用。
 */
@Service
@DependsOn("schemaMigration")
public class OpsAuthService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration DEFAULT_TOKEN_TTL = Duration.ofDays(1);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_VERSION = "v1";
    private static final String TOKEN_VERSION_V2 = "v2";
    private static final String TOKEN_VERSION_V3 = "v3";
    private static final String DEFAULT_SECRET_SALT = "ops-auth-token::";
    private static final String PASSWORD_HASH_PREFIX = "ops-pwd::";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_DEFAULT_DATA = "data-user";
    public static final String ORG_HQ = OpsTenantService.ORG_HQ;
    public static final String ORG_AGENCY = OpsTenantService.ORG_AGENCY;
    public static final String PERSONNEL_EDITOR = "editor";
    public static final String PERSONNEL_OPTIMIZER = "optimizer";
    public static final String PERSONNEL_MANAGEMENT = "management";

    // 中文备注：姓名 → 拼音首字母（大写），种子写入 ops_account；登录优先读库。
    // 开源版不内置任何真实名单，仅提供两个演示账户；正式名单由部署方通过账户管理接口维护。
    private static final Map<String, String> NAME_INITIALS = buildNameInitials();
    private static final Set<String> EDITOR_PERSONNEL_NAMES = Set.of("张三", "李四");
    private static final Set<String> LEGACY_EDITOR_PERSONNEL_NAMES = Set.of();

    // 中文备注：主动登出的 token 加入黑名单；重启后清空，靠 token 自身过期时间兜底。
    private final ConcurrentHashMap<String, Instant> revoked = new ConcurrentHashMap<>();
    // 中文备注：改密/重置/停用时按姓名吊销「此时间之前签发」的 token（用 expireAt - ttl 反推签发点）。
    private final ConcurrentHashMap<String, Instant> credentialsRevokedAt = new ConcurrentHashMap<>();
    private static final Duration ONLINE_IDLE_TTL = Duration.ofSeconds(90);
    // 中文备注：普通请求续心跳的落库节流——同一人 30s 内只写一次 last_seen，
    // 避免每个请求都 UPDATE ops_user_presence 同一行，把行锁排队放大成连接池耗尽。
    private static final long PRESENCE_TOUCH_MIN_INTERVAL_MS = 30_000L;
    private final ConcurrentHashMap<String, Long> presenceTouchAtMs = new ConcurrentHashMap<>();
    private static final int MIN_PASSWORD_LENGTH = 6;
    private final OpsPresenceRepository presenceRepository;
    private final OpsAccountRepository opsAccountRepository;
    private final OpsTenantService opsTenantService;
    private final ServerLoadGuard serverLoadGuard;
    private final String passwordSuffix;
    private final String masterAccessKey;
    private final String adminUsername;
    private final String adminPassword;
    private final Duration tokenTtl;
    private final byte[] tokenSecret;
    private volatile Map<String, List<Map<String, Object>>> onlineUsersCache = Map.of();
    private volatile Map<String, Long> onlineUsersCacheExpireAtMs = Map.of();

    public OpsAuthService(
            OpsPresenceRepository presenceRepository,
            OpsAccountRepository opsAccountRepository,
            OpsTenantService opsTenantService,
            ServerLoadGuard serverLoadGuard,
            @Value("${ops.auth.password-suffix:}") String passwordSuffix,
            @Value("${ops.access-key:}") String masterAccessKey,
            @Value("${ops.admin.username:admin}") String adminUsername,
            @Value("${ops.admin.password:}") String adminPassword,
            @Value("${ops.auth.token-ttl-hours:24}") long tokenTtlHours,
            @Value("${ops.auth.token-secret:}") String tokenSecret) {
        this.presenceRepository = presenceRepository;
        this.opsAccountRepository = opsAccountRepository;
        this.opsTenantService = opsTenantService;
        this.serverLoadGuard = serverLoadGuard;
        this.passwordSuffix = passwordSuffix == null ? "" : passwordSuffix.trim();
        this.masterAccessKey = masterAccessKey == null ? "" : masterAccessKey.trim();
        this.adminUsername = adminUsername == null || adminUsername.isBlank() ? "admin" : adminUsername.trim();
        this.adminPassword = adminPassword == null ? "" : adminPassword.trim();
        this.tokenTtl = tokenTtlHours <= 0 ? DEFAULT_TOKEN_TTL : Duration.ofHours(tokenTtlHours);
        // 开源安全兜底：未配置签名密钥时不再回退到可预测的静态盐（源码公开后等于无签名），
        // 改为每次启动生成随机密钥——重启后旧 token 全部失效；生产必须设置 OPS_AUTH_TOKEN_SECRET。
        if (tokenSecret == null || tokenSecret.isBlank()) {
            String generated = DEFAULT_SECRET_SALT + UUID.randomUUID() + ":" + System.nanoTime();
            System.out.println("[PolyTable] 警告：未设置 OPS_AUTH_TOKEN_SECRET，本次启动使用随机签名密钥，"
                    + "重启后所有登录态将失效。生产部署请显式设置该环境变量。");
            this.tokenSecret = generated.getBytes(StandardCharsets.UTF_8);
        } else {
            this.tokenSecret = tokenSecret.trim().getBytes(StandardCharsets.UTF_8);
        }
    }

    @PostConstruct
    public void seedAccounts() {
        try {
            int seeded = 0;
            // 中文备注：代投独立库不种子全员 hq 账户，避免 ad 域登录误判为总部账号。
            boolean agencyInstance = opsTenantService.enabled()
                    && ORG_AGENCY.equals(opsTenantService.resolveMode(null));
            if (!agencyInstance) {
                for (Map.Entry<String, String> entry : NAME_INITIALS.entrySet()) {
                    seeded += opsAccountRepository.insertIgnore(entry.getKey(), entry.getValue(), ROLE_DEFAULT_DATA, true);
                    if (EDITOR_PERSONNEL_NAMES.contains(entry.getKey())) {
                        opsAccountRepository.updatePersonnelTypeIfMissing(
                                entry.getKey(), PERSONNEL_EDITOR, "system-editor-roster");
                    }
                }
                for (String legacyName : LEGACY_EDITOR_PERSONNEL_NAMES) {
                    opsAccountRepository.clearPersonnelTypeIfMatches(
                            legacyName, PERSONNEL_EDITOR, "system-editor-roster");
                }
            }
            // 中文备注：系统管理员账户；口令优先 ops.admin.password，否则用 access-key（若有）。
            String bootstrapPwd = !adminPassword.isBlank() ? adminPassword
                    : (!masterAccessKey.isBlank() ? masterAccessKey : "");
            String seedOrg = opsTenantService.enabled()
                    && ORG_AGENCY.equals(opsTenantService.resolveMode(null))
                    ? ORG_AGENCY
                    : ORG_HQ;
            if (!bootstrapPwd.isBlank()) {
                Map<String, Object> admin = opsAccountRepository.findByDisplayName(adminUsername);
                if (admin == null) {
                    opsAccountRepository.create(
                            adminUsername,
                            "ADMIN",
                            hashPassword(adminUsername, bootstrapPwd),
                            ROLE_ADMIN,
                            true,
                            "系统管理员",
                            "system",
                            seedOrg
                    );
                    seeded++;
                } else if (ORG_AGENCY.equals(seedOrg)) {
                    opsAccountRepository.updateOrgType(adminUsername, ORG_AGENCY, "system");
                }
            }
            if (seeded > 0) {
                System.out.println("协作台账户种子同步：新增=" + seeded);
                System.out.println("[PolyTable] 已创建演示账户：张三、李四。默认口令 = 姓名拼音首字母 + OPS_AUTH_PASSWORD_SUFFIX"
                        + (passwordSuffix.isBlank()
                                ? "（未设置后缀：口令即首字母 ZS / LS，仅限本地体验，请尽快改密）"
                                : "，例如 " + NAME_INITIALS.get("张三") + passwordSuffix + "。"));
                System.out.println("[PolyTable] 系统管理员：账户管理页 /ops/admin.html 用 OPS_ADMIN_USERNAME / OPS_ADMIN_PASSWORD 配置的口令登录。");
            }
        } catch (Exception e) {
            System.out.println("协作台账户种子同步跳过：" + e.getMessage());
        }
    }

    public LoginResult login(String displayName, String password) {
        return login(displayName, password, false, ORG_HQ);
    }

    public LoginResult login(String displayName, String password, String requestMode) {
        return login(displayName, password, false, requestMode);
    }

    /**
     * @param adminOnly true 时仅允许系统管理员角色登录（账户管理页）。
     */
    public LoginResult login(String displayName, String password, boolean adminOnly) {
        return login(displayName, password, adminOnly, ORG_HQ);
    }

    /**
     * @param adminOnly true 时仅允许系统管理员角色登录（账户管理页）。
     * @param requestMode 当前访问入口租户：hq / agency。
     */
    public LoginResult login(String displayName, String password, boolean adminOnly, String requestMode) {
        String provided = trimToEmpty(password);
        String name = normalizePersonName(displayName);
        String tenantMode = OpsTenantService.normalizeOrg(requestMode, ORG_HQ);
        if (name.isBlank() || provided.isBlank()) {
            throw new IllegalArgumentException("请输入姓名和口令");
        }

        Map<String, Object> account = null;
        try {
            account = opsAccountRepository.findByDisplayName(name);
        } catch (Exception ignored) {
        }

        // 中文备注：配置管理员口令仅在「无自定义 hash」时可用；有自定义口令后必须走库内校验。
        if (name.equals(adminUsername) && isAdminBootstrapPassword(provided)) {
            if (account != null) {
                if (!Boolean.TRUE.equals(account.get("enabled"))) {
                    throw new IllegalArgumentException("账户已停用，请联系管理员");
                }
                String hash = trimToEmpty((String) account.get("passwordHash"));
                if (hash.isBlank()) {
                    assertTenantAccess(accountOrgType(account), tenantMode, name);
                    return issueToken(adminUsername, "ADMIN", ROLE_ADMIN, accountOrgType(account));
                }
                // 有自定义 hash：不短路，落入下方按 hash 校验（配置口令≠库内口令则失败）。
            } else {
                assertTenantAccess(ORG_HQ, tenantMode, name);
                return issueToken(adminUsername, "ADMIN", ROLE_ADMIN, ORG_HQ);
            }
        }

        if (!masterAccessKey.isBlank()
                && MessageDigest.isEqual(
                masterAccessKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            if (adminOnly) {
                if (!name.equals(adminUsername)) {
                    throw new IllegalArgumentException("仅系统管理员可登录此页面");
                }
                assertAccountEnabled(account);
                String orgType = account == null ? ORG_HQ : accountOrgType(account);
                assertTenantAccess(orgType, tenantMode, name);
                return issueToken(adminUsername, "MASTER", ROLE_ADMIN, orgType);
            }
            assertAccountEnabled(account);
            String role = account == null ? ROLE_USER : normalizeRole((String) account.get("role"));
            String orgType = account == null ? ORG_HQ : accountOrgType(account);
            assertTenantAccess(orgType, tenantMode, name.isBlank() ? "管理员" : name);
            return issueToken(name.isBlank() ? "管理员" : name, "MASTER", role, orgType);
        }

        String initials;
        String role = ROLE_USER;
        String orgType = ORG_HQ;
        boolean enabled = true;
        String passwordHash = "";
        if (account != null) {
            enabled = Boolean.TRUE.equals(account.get("enabled"));
            initials = trimToEmpty((String) account.get("initials"));
            role = normalizeRole((String) account.get("role"));
            orgType = accountOrgType(account);
            passwordHash = trimToEmpty((String) account.get("passwordHash"));
        } else {
            initials = NAME_INITIALS.get(name);
        }
        if (!enabled) {
            throw new IllegalArgumentException("账户已停用，请联系管理员");
        }
        if (initials == null || initials.isBlank()) {
            throw new IllegalArgumentException("姓名或口令错误");
        }
        // 中文备注：后台页只认库内 admin 角色，禁止靠同名用户抬权。
        if (adminOnly && !ROLE_ADMIN.equals(role)) {
            throw new IllegalArgumentException("仅系统管理员可登录此页面");
        }

        boolean passwordOk;
        if (!passwordHash.isBlank()) {
            passwordOk = MessageDigest.isEqual(
                    passwordHash.getBytes(StandardCharsets.UTF_8),
                    hashPassword(name, provided).getBytes(StandardCharsets.UTF_8));
        } else {
            String expected = initials + passwordSuffix;
            passwordOk = expected.equalsIgnoreCase(provided);
            // 中文备注：无自定义 hash 时，配置管理员口令也可登录（已在上方短路；此处兜底同值）。
            if (!passwordOk && name.equals(adminUsername) && isAdminBootstrapPassword(provided)) {
                passwordOk = true;
            }
        }
        if (!passwordOk) {
            throw new IllegalArgumentException("姓名或口令错误");
        }
        assertTenantAccess(orgType, tenantMode, name);
        return issueToken(name, initials, role, orgType);
    }

    public Session requireValidSession(String token) {
        return requireValidSession(token, false, ORG_HQ);
    }

    public Session requireValidSession(String token, String requestMode) {
        return requireValidSession(token, false, requestMode);
    }

    public Session requireAdminSession(String token) {
        return requireValidSession(token, true, ORG_HQ);
    }

    public Session requireAdminSession(String token, String requestMode) {
        return requireValidSession(token, true, requestMode);
    }

    public Session requireValidSession(String token, boolean adminOnly) {
        return requireValidSession(token, adminOnly, ORG_HQ);
    }

    public Session requireValidSession(String token, boolean adminOnly, String requestMode) {
        String key = trimToEmpty(token);
        if (key.isBlank()) {
            throw new IllegalArgumentException("未登录或登录已过期，请重新登录");
        }
        cleanupRevoked();
        if (revoked.containsKey(key)) {
            throw new IllegalArgumentException("登录已过期，请重新登录");
        }
        Session session = parseToken(key);
        if (session == null) {
            throw new IllegalArgumentException("未登录或登录已过期，请重新登录");
        }
        if (session.expireAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("登录已过期，请重新登录");
        }
        if (isCredentialsRevoked(session)) {
            throw new IllegalArgumentException("登录已失效，请重新登录");
        }
        if (adminOnly && !ROLE_ADMIN.equals(normalizeRole(session.role()))) {
            throw new IllegalArgumentException("需要系统管理员权限");
        }
        if (!opsTenantService.tokenMatchesRequest(session.orgType(), requestMode)) {
            throw new IllegalArgumentException(opsTenantService.loginDeniedMessage(session.orgType(), requestMode));
        }
        // 中文备注：每次校验再确认账户仍启用。
        try {
            Map<String, Object> account = opsAccountRepository.findByDisplayName(session.displayName());
            if (account != null && !Boolean.TRUE.equals(account.get("enabled"))) {
                throw new IllegalArgumentException("账户已停用，请联系管理员");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
        }
        touchPresence(key, session);
        return session;
    }

    public boolean isValidToken(String token) {
        return isValidToken(token, ORG_HQ);
    }

    public boolean isValidToken(String token, String requestMode) {
        try {
            requireValidSession(token, false, requestMode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 中文备注：活跃会话静默换发 token。旧 token 不延长生命周期，仍会在原到期时间失效；
     * 因此即使旧 token 泄露，也不会被该接口无限续期。
     */
    public LoginResult refreshToken(String token) {
        return refreshToken(token, ORG_HQ);
    }

    public LoginResult refreshToken(String token, String requestMode) {
        Session session = requireValidSession(token, false, requestMode);
        return issueToken(session.displayName(), session.initials(), session.role(), session.orgType());
    }

    public void logout(String token) {
        String key = trimToEmpty(token);
        if (key.isBlank()) {
            return;
        }
        cleanupRevoked();
        Session session = parseToken(key);
        revoked.put(key, session == null ? Instant.now().plus(tokenTtl) : session.expireAt());
        if (session != null) {
            String name = trimToEmpty(session.displayName());
            if (!name.isBlank()) {
                // 中文备注：登出连节流票一起清，下次登录可立即落库。
                presenceTouchAtMs.remove(name);
                try {
                    presenceRepository.remove(name);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void touchPresence(String token, Session session) {
        touchPresence(token, session, null);
    }

    /**
     * 中文备注：续心跳并落库协作焦点（当前表 / 选中或编辑单元格）。多实例部署时其他节点轮询也能看见。
     */
    public void touchPresence(String token, Session session, FocusUpdate focus) {
        String key = trimToEmpty(token);
        if (key.isBlank() || session == null) {
            return;
        }
        String name = trimToEmpty(session.displayName());
        if (name.isBlank()) {
            return;
        }
        try {
            Instant now = Instant.now();
            String orgType = OpsTenantService.normalizeOrg(session.orgType(), ORG_HQ);
            if (focus != null) {
                // 中文备注：焦点上报（在哪个表 / 哪一行 / 哪一格）必须实时落库，不参与节流。
                presenceTouchAtMs.put(name, System.currentTimeMillis());
                FocusState nextFocus = normalizeFocus(focus);
                presenceRepository.upsert(
                        name,
                        orgType,
                        nextFocus.tableId(),
                        nextFocus.tableLabel(),
                        nextFocus.dramaType(),
                        nextFocus.opsKey(),
                        nextFocus.seriesName(),
                        nextFocus.colKey(),
                        nextFocus.colLabel(),
                        nextFocus.mode(),
                        nextFocus.selJson(),
                        now
                );
            } else {
                // 中文备注：普通请求只续心跳；30s 内已写过就跳过写库，在线判定 TTL=90s 仍有富余。
                if (!claimPresenceTouch(name)) {
                    maybeCleanupOnline();
                    return;
                }
                presenceRepository.touchLastSeen(name, orgType, now);
            }
            // 中文备注：写过在场后清短缓存，让其他人尽快看见；清理仍节流。
            onlineUsersCache = Map.of();
            onlineUsersCacheExpireAtMs = Map.of();
            maybeCleanupOnline();
        } catch (Exception ignored) {
            // 中文备注：在场表异常不阻断登录/业务请求。
        }
    }

    /**
     * 中文备注：心跳落库取票——同一人 30s 内只放行一次写库。
     * 并发下最坏多放行一次，对在线状态无影响。
     */
    private boolean claimPresenceTouch(String name) {
        long nowMs = System.currentTimeMillis();
        Long prev = presenceTouchAtMs.get(name);
        if (prev != null && nowMs - prev < PRESENCE_TOUCH_MIN_INTERVAL_MS) {
            return false;
        }
        presenceTouchAtMs.put(name, nowMs);
        return true;
    }

    public List<Map<String, Object>> listOnlineUsers() {
        return listOnlineUsers(ORG_HQ);
    }

    /**
     * @param requestOrgType 当前入口租户；开启租户隔离时只统计同 org 在线用户。
     */
    public List<Map<String, Object>> listOnlineUsers(String requestOrgType) {
        String orgKey = opsTenantService.enabled()
                ? OpsTenantService.normalizeOrg(requestOrgType, ORG_HQ)
                : "*";
        long nowMs = System.currentTimeMillis();
        long ttlMs = serverLoadGuard.elevated() ? 5000L : 2500L;
        Long expireAt = onlineUsersCacheExpireAtMs.get(orgKey);
        List<Map<String, Object>> cached = onlineUsersCache.get(orgKey);
        if (expireAt != null && nowMs < expireAt && cached != null && !cached.isEmpty()) {
            return cached;
        }
        maybeCleanupOnline();
        List<Map<String, Object>> users = new ArrayList<>();
        try {
            String orgFilter = opsTenantService.enabled() ? orgKey : null;
            List<Map<String, Object>> rows = presenceRepository.listSince(Instant.now().minus(ONLINE_IDLE_TTL), orgFilter);
            for (Map<String, Object> row : rows) {
                String name = normalizePersonName(String.valueOf(row.getOrDefault("displayName", "")));
                if (name.isBlank()) continue;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("displayName", name);
                out.put("shortName", shortDisplayName(name));
                Instant lastSeen = (Instant) row.getOrDefault("lastSeenInstant", Instant.now());
                out.put("lastSeen", formatInstant(lastSeen));
                out.put("tableId", row.getOrDefault("tableId", ""));
                out.put("tableLabel", row.getOrDefault("tableLabel", ""));
                out.put("dramaType", row.getOrDefault("dramaType", ""));
                out.put("opsKey", row.getOrDefault("opsKey", ""));
                out.put("seriesName", row.getOrDefault("seriesName", ""));
                out.put("colKey", row.getOrDefault("colKey", ""));
                out.put("colLabel", row.getOrDefault("colLabel", ""));
                out.put("mode", row.getOrDefault("mode", "idle"));
                out.put("selJson", row.getOrDefault("selJson", ""));
                users.add(out);
            }
        } catch (Exception ignored) {
        }
        users.sort(Comparator
                .comparing((Map<String, Object> u) -> String.valueOf(u.getOrDefault("lastSeen", "")), Comparator.reverseOrder())
                .thenComparing(u -> String.valueOf(u.getOrDefault("displayName", ""))));
        List<Map<String, Object>> frozen = List.copyOf(users);
        Map<String, List<Map<String, Object>>> nextCache = new LinkedHashMap<>(onlineUsersCache);
        Map<String, Long> nextExpire = new LinkedHashMap<>(onlineUsersCacheExpireAtMs);
        nextCache.put(orgKey, frozen);
        nextExpire.put(orgKey, nowMs + ttlMs);
        onlineUsersCache = Map.copyOf(nextCache);
        onlineUsersCacheExpireAtMs = Map.copyOf(nextExpire);
        return frozen;
    }

    private FocusState normalizeFocus(FocusUpdate focus) {
        if (focus == null) return FocusState.EMPTY;
        String mode = trimToEmpty(focus.mode()).toLowerCase();
        if (!mode.equals("select") && !mode.equals("edit")) {
            mode = "idle";
        }
        String tableId = trimToEmpty(focus.tableId());
        String tableLabel = trimToEmpty(focus.tableLabel());
        String dramaType = trimToEmpty(focus.dramaType());
        String opsKey = trimToEmpty(focus.opsKey());
        String seriesName = trimToEmpty(focus.seriesName());
        String colKey = trimToEmpty(focus.colKey());
        String colLabel = trimToEmpty(focus.colLabel());
        // W2 presence 选区——{"r":[opsKey...],"c":[fieldKey...]};非法/超长直接丢弃(宁缺勿脏)。
        String selJson = trimToEmpty(focus.selJson());
        if (selJson.length() > 4000 || !selJson.startsWith("{")) {
            selJson = "";
        }
        if (tableId.length() > 64) tableId = tableId.substring(0, 64);
        if (tableLabel.length() > 40) tableLabel = tableLabel.substring(0, 40);
        if (dramaType.length() > 32) dramaType = dramaType.substring(0, 32);
        if (opsKey.length() > 128) opsKey = opsKey.substring(0, 128);
        if (seriesName.length() > 80) seriesName = seriesName.substring(0, 80);
        if (colKey.length() > 64) colKey = colKey.substring(0, 64);
        if (colLabel.length() > 40) colLabel = colLabel.substring(0, 40);
        if (tableId.isBlank() && opsKey.isBlank() && colKey.isBlank()) {
            mode = "idle";
        }
        if ("idle".equals(mode)) {
            selJson = "";
        }
        return new FocusState(tableId, tableLabel, dramaType, opsKey, seriesName, colKey, colLabel, mode, selJson);
    }

    public static String shortDisplayName(String displayName) {
        String name = normalizePersonName(displayName);
        if (name.isEmpty()) {
            return "?";
        }
        // 中文备注：2 字全显；3 字及以上取后两字（如示例三字名取后两字）。
        if (name.length() <= 2) {
            return name;
        }
        return name.substring(name.length() - 2);
    }

    /**
     * 中文备注：历史评级选项常带「备选-」前缀与 -S/-A/-B/-C 等级后缀，登录/展示只保留人名部分。
     */
    static String normalizePersonName(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            return "";
        }
        if (name.startsWith("备选-")) {
            name = name.substring("备选-".length()).trim();
        }
        name = name.replaceFirst("(?i)-[SABC](?:二轮)?$", "").trim();
        return name;
    }

    public Map<String, Object> sessionView(Session session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("displayName", session.displayName());
        data.put("initials", session.initials());
        data.put("role", normalizeRole(session.role()));
        data.put("orgType", OpsTenantService.normalizeOrg(session.orgType(), ORG_HQ));
        data.put("admin", ROLE_ADMIN.equals(normalizeRole(session.role())));
        data.putAll(opsTenantService.publicConfig(session.orgType()));
        data.put("expiresAt", formatInstant(session.expireAt()));
        data.put("expiresInSeconds", Math.max(0, Duration.between(Instant.now(), session.expireAt()).getSeconds()));
        return data;
    }

    public long tokenTtlHours() {
        return Math.max(1, tokenTtl.toHours());
    }

    public String passwordSuffixHint() {
        return passwordSuffix;
    }

    public List<Map<String, Object>> listAccountsForAdmin() {
        return listAccountsForAdmin(ORG_HQ);
    }

    public List<Map<String, Object>> listAccountsForAdmin(String requestMode) {
        Map<String, Object> page = listAccountsPage("", "", 1, Integer.MAX_VALUE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.getOrDefault("accounts", List.of());
        return rows;
    }

    public Map<String, Object> listAccountsPage(String orgTypeFilter, String keyword, int page, int pageSize) {
        return listAccountsPage(orgTypeFilter, "", keyword, page, pageSize);
    }

    public Map<String, Object> listAccountsPage(String orgTypeFilter, String personnelTypeFilter,
                                                 String keyword, int page, int pageSize) {
        String personnelType = normalizePersonnelType(personnelTypeFilter);
        int total = opsAccountRepository.countFiltered(orgTypeFilter, personnelType, keyword);
        int safeSize = Math.min(100, Math.max(1, pageSize <= 0 ? 20 : pageSize));
        int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1L) / safeSize);
        int safePage = Math.max(1, page);
        if (totalPages > 0 && safePage > totalPages) {
            safePage = totalPages;
        }
        List<Map<String, Object>> rows = opsAccountRepository.listPaged(orgTypeFilter, personnelType, keyword, safePage, safeSize);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(toAdminAccountView(row));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("page", safePage);
        data.put("pageSize", safeSize);
        data.put("totalPages", totalPages);
        data.put("accounts", out);
        return data;
    }

    public Map<String, Object> updateAccountOrgType(String displayName, String orgType, String operator) {
        String name = normalizePersonName(displayName);
        requireAccount(name);
        String next = OpsTenantService.normalizeOrg(orgType, ORG_HQ);
        if (opsAccountRepository.updateOrgType(name, next, operator) <= 0) {
            throw new IllegalArgumentException("更新归属失败：" + name);
        }
        revokeCredentials(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", name);
        out.put("orgType", next);
        out.put("orgTypeLabel", ORG_AGENCY.equals(next) ? "代投" : "自投");
        out.put("message", "归属已更新，该用户需重新登录");
        return out;
    }

    public Map<String, Object> updateAccountFeatureFlags(String displayName,
                                                         Map<String, Boolean> flags,
                                                         String operator) {
        String name = normalizePersonName(displayName);
        Map<String, Object> account = requireAccount(name);
        Map<String, Boolean> merged = new LinkedHashMap<>(OpsAccountFeatureFlags.parse(account.get("featureFlags")));
        if (flags != null) {
            for (Map.Entry<String, Boolean> entry : flags.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    merged.remove(entry.getKey());
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        String json = OpsAccountFeatureFlags.serialize(merged);
        if (opsAccountRepository.updateFeatureFlags(name, json, operator) <= 0) {
            throw new IllegalArgumentException("更新功能权限失败：" + name);
        }
        revokeCredentials(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", name);
        out.put("featureFlags", merged);
        out.put("promoTable", OpsAccountFeatureFlags.enabled(merged, OpsAccountFeatureFlags.PROMO_TABLE));
        out.put("message", "功能权限已更新，该用户需重新登录");
        return out;
    }

    private Map<String, Object> toAdminAccountView(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>(row);
        item.remove("passwordHash");
        boolean custom = Boolean.TRUE.equals(item.get("hasCustomPassword"));
        item.put("passwordMode", custom ? "custom" : "default");
        String initials = String.valueOf(item.getOrDefault("initials", ""));
        item.put("defaultPasswordHint", custom ? "已自定义" : (initials + passwordSuffix));
        String org = OpsTenantService.normalizeOrg(String.valueOf(item.getOrDefault("orgType", ORG_HQ)), ORG_HQ);
        item.put("orgType", org);
        item.put("orgTypeLabel", ORG_AGENCY.equals(org) ? "代投" : "自投");
        Object personnelRaw = item.get("personnelType");
        String personnelType = normalizePersonnelType(personnelRaw == null ? "" : String.valueOf(personnelRaw));
        item.put("personnelType", personnelType);
        item.put("personnelTypeLabel", personnelTypeLabel(personnelType));
        Object screeningRaw = item.get("screeningAssignEnabled");
        Boolean screeningSetting = screeningRaw instanceof Boolean value ? value : null;
        item.put("screeningAssignEnabled", effectiveScreeningAssignEnabled(personnelType, screeningSetting));
        Map<String, Boolean> flags = OpsAccountFeatureFlags.parse(item.get("featureFlags"));
        item.put("featureFlags", flags);
        item.put("promoTable", OpsAccountFeatureFlags.enabled(flags, OpsAccountFeatureFlags.PROMO_TABLE));
        return item;
    }

    public Map<String, Object> createAccount(String displayName,
                                             String initials,
                                             String password,
                                             boolean enabled,
                                             String remark,
                                             String operator) {
        return createAccount(displayName, initials, password, enabled, remark, operator, ORG_HQ);
    }

    public Map<String, Object> createAccount(String displayName,
                                             String initials,
                                             String password,
                                             boolean enabled,
                                             String remark,
                                             String operator,
                                             String orgType) {
        return createAccount(displayName, initials, password, enabled, remark, operator, orgType, "", null);
    }

    public Map<String, Object> createAccount(String displayName,
                                             String initials,
                                             String password,
                                             boolean enabled,
                                             String remark,
                                             String operator,
                                             String orgType,
                                             String personnelType,
                                             Boolean screeningAssignEnabled) {
        String tenantOrg = OpsTenantService.normalizeOrg(orgType, ORG_HQ);
        String nextPersonnelType = normalizePersonnelType(personnelType);
        Boolean nextScreeningAssignEnabled = defaultScreeningAssignSettingForCreate(nextPersonnelType, screeningAssignEnabled);
        String name = normalizePersonName(displayName);
        String init = trimToEmpty(initials).toUpperCase(Locale.ROOT);
        if (name.isBlank()) {
            throw new IllegalArgumentException("姓名不能为空");
        }
        if (init.isBlank() || !init.matches("[A-Z]{1,16}")) {
            throw new IllegalArgumentException("首字母须为 1～16 位大写英文字母");
        }
        if (opsAccountRepository.findByDisplayName(name) != null) {
            throw new IllegalArgumentException("账户已存在：" + name);
        }
        String rawPassword = trimToEmpty(password);
        String hash = null;
        if (!rawPassword.isBlank()) {
            if (rawPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("口令至少 " + MIN_PASSWORD_LENGTH + " 位");
            }
            hash = hashPassword(name, rawPassword);
        }
        try {
            opsAccountRepository.create(name, init, hash, ROLE_DEFAULT_DATA, enabled, remark, operator,
                    tenantOrg, nextPersonnelType, nextScreeningAssignEnabled);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("账户已存在：" + name);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("账户已存在：" + name);
        }
        Map<String, Object> created = opsAccountRepository.findByDisplayName(name);
        if (created != null) {
            created = toAdminAccountView(created);
            if (created.get("message") == null && Boolean.FALSE.equals(created.get("agencySyncSuccess"))) {
                created.put("message", "账户已创建");
            }
        }
        return created == null ? Map.of("displayName", name) : created;
    }

    public Map<String, Object> updateAccountPersonnelType(String displayName, String personnelType, String operator) {
        return updateAccountPersonnelSettings(displayName, personnelType, null, operator);
    }

    public Map<String, Object> updateAccountPersonnelSettings(String displayName, String personnelType,
                                                              Boolean screeningAssignEnabled, String operator) {
        String name = normalizePersonName(displayName);
        Map<String, Object> existing = requireAccount(name);
        String next = normalizePersonnelType(personnelType);
        Object previousRaw = existing.get("personnelType");
        String previous = normalizePersonnelType(previousRaw == null ? "" : String.valueOf(previousRaw));
        Boolean storedScreeningSetting = existing.get("screeningAssignEnabled") instanceof Boolean value ? value : null;
        boolean previouslyScreeningEnabled = effectiveScreeningAssignEnabled(previous, storedScreeningSetting);
        Boolean nextScreeningSetting = screeningSettingForPersonnelTypeUpdate(
                previous, next, storedScreeningSetting, screeningAssignEnabled);
        if (opsAccountRepository.updatePersonnelSettings(name, next, nextScreeningSetting, operator) <= 0) {
            throw new IllegalArgumentException("更新人员设置失败：" + name);
        }
        Map<String, Object> persisted = opsAccountRepository.findByDisplayName(name);
        if (persisted == null) {
            throw new IllegalStateException("人员类型已写入但重新读取账户失败：" + name);
        }
        Map<String, Object> out = toAdminAccountView(persisted);
        if (!next.equals(String.valueOf(out.getOrDefault("personnelType", "")))
                || !Objects.equals(effectiveScreeningAssignEnabled(next,
                (Boolean) persisted.get("screeningAssignEnabled")), out.get("screeningAssignEnabled"))) {
            throw new IllegalStateException("人员设置保存后校验不一致：" + name);
        }
        boolean screeningEnabled = Boolean.TRUE.equals(out.get("screeningAssignEnabled"));
        out.put("screeningAssignmentEnabledTransitionedToEnabled",
                shouldScheduleScreeningAssignment(previouslyScreeningEnabled, screeningEnabled, next));
        return out;
    }

    public static boolean shouldScheduleScreeningAssignment(boolean previouslyEnabled,
                                                            boolean currentlyEnabled,
                                                            String personnelType) {
        return !previouslyEnabled && currentlyEnabled
                && PERSONNEL_EDITOR.equals(normalizePersonnelType(personnelType));
    }

    public static boolean effectiveScreeningAssignEnabled(String personnelType, Boolean storedValue) {
        if (storedValue != null) {
            return storedValue;
        }
        return PERSONNEL_EDITOR.equals(normalizePersonnelType(personnelType));
    }

    public static Boolean defaultScreeningAssignSettingForCreate(String personnelType, Boolean requestedValue) {
        String type = normalizePersonnelType(personnelType);
        if (PERSONNEL_EDITOR.equals(type)) {
            return Boolean.TRUE.equals(requestedValue);
        }
        return requestedValue;
    }

    public static Boolean screeningSettingForPersonnelTypeUpdate(String previousType, String nextType,
                                                                 Boolean storedValue, Boolean requestedValue) {
        if (requestedValue != null) {
            return requestedValue;
        }
        String previous = normalizePersonnelType(previousType);
        String next = normalizePersonnelType(nextType);
        if (PERSONNEL_EDITOR.equals(next) && !PERSONNEL_EDITOR.equals(previous) && storedValue == null) {
            return false;
        }
        return null;
    }

    public static String normalizePersonnelType(String personnelType) {
        String value = personnelType == null ? "" : personnelType.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || PERSONNEL_EDITOR.equals(value)
                || PERSONNEL_OPTIMIZER.equals(value) || PERSONNEL_MANAGEMENT.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("无效人员类型：" + personnelType);
    }

    public static String personnelTypeLabel(String personnelType) {
        return switch (normalizePersonnelType(personnelType)) {
            case PERSONNEL_EDITOR -> "执行成员";
            case PERSONNEL_OPTIMIZER -> "优化师";
            case PERSONNEL_MANAGEMENT -> "管理";
            default -> "未标记";
        };
    }

    static Set<String> editorPersonnelNames() {
        return EDITOR_PERSONNEL_NAMES;
    }

    static String defaultInitialsForName(String displayName) {
        return NAME_INITIALS.getOrDefault(normalizePersonName(displayName), "");
    }

    public void setAccountEnabled(String displayName, boolean enabled, String operator) {
        String name = normalizePersonName(displayName);
        Map<String, Object> account = requireAccount(name);
        if (ROLE_ADMIN.equals(normalizeRole((String) account.get("role"))) && !enabled) {
            throw new IllegalArgumentException("不能停用系统管理员账户");
        }
        if (opsAccountRepository.setEnabled(name, enabled, operator) <= 0) {
            throw new IllegalArgumentException("更新失败：" + name);
        }
        if (!enabled) {
            revokeCredentials(name);
        }
    }

    public void changeAccountPassword(String displayName, String newPassword, String operator) {
        String name = normalizePersonName(displayName);
        requireAccount(name);
        String pwd = trimToEmpty(newPassword);
        if (pwd.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("新口令至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (opsAccountRepository.setPasswordHash(name, hashPassword(name, pwd), operator) <= 0) {
            throw new IllegalArgumentException("改密失败：" + name);
        }
        revokeCredentials(name);
    }

    public Map<String, Object> resetAccountPassword(String displayName, String operator) {
        String name = normalizePersonName(displayName);
        Map<String, Object> account = requireAccount(name);
        if (opsAccountRepository.resetPassword(name, operator) <= 0) {
            throw new IllegalArgumentException("重置失败：" + name);
        }
        revokeCredentials(name);
        String initials = trimToEmpty((String) account.get("initials"));
        String hint = initials + passwordSuffix;
        System.out.println("账户口令已重置为默认规则：name=" + name + "，操作人=" + operator);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", name);
        out.put("defaultPasswordHint", hint);
        out.put("message", "已重置为默认口令（拼音首字母 + 后缀）");
        return out;
    }

    public Map<String, Object> updateAccountInitials(String displayName, String initials, String operator) {
        String name = normalizePersonName(displayName);
        Map<String, Object> account = requireAccount(name);
        String init = trimToEmpty(initials).toUpperCase(Locale.ROOT);
        if (init.isBlank() || !init.matches("[A-Z]{1,16}")) {
            throw new IllegalArgumentException("首字母须为 1～16 位大写英文字母");
        }
        if (opsAccountRepository.updateInitials(name, init, operator) <= 0) {
            throw new IllegalArgumentException("更新首字母失败：" + name);
        }
        boolean custom = Boolean.TRUE.equals(account.get("hasCustomPassword"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("displayName", name);
        out.put("initials", init);
        if (custom) {
            out.put("defaultPasswordHint", "已自定义");
            out.put("message", "首字母已更新（自定义口令不变）");
        } else {
            // 中文备注：默认口令随首字母变化，吊销旧会话。
            revokeCredentials(name);
            out.put("defaultPasswordHint", init + passwordSuffix);
            out.put("message", "首字母已更新，默认口令已变更");
        }
        if (out.get("message") != null && Boolean.FALSE.equals(out.get("agencySyncSuccess"))) {
            
        }
        return out;
    }

    private Map<String, Object> requireAccount(String displayName) {
        Map<String, Object> account = opsAccountRepository.findByDisplayName(displayName);
        if (account == null) {
            throw new IllegalArgumentException("账户不存在：" + displayName);
        }
        return account;
    }

    private LoginResult issueToken(String displayName, String initials) {
        return issueToken(displayName, initials, ROLE_USER, ORG_HQ);
    }

    private LoginResult issueToken(String displayName, String initials, String role) {
        return issueToken(displayName, initials, role, ORG_HQ);
    }

    private LoginResult issueToken(String displayName, String initials, String role, String orgType) {
        cleanupRevoked();
        Instant expireAt = Instant.now().plus(tokenTtl);
        Session session = new Session(displayName, initials, expireAt, normalizeRole(role), OpsTenantService.normalizeOrg(orgType, ORG_HQ));
        String token = newToken(session);
        touchPresence(token, session);
        return new LoginResult(token, session);
    }

    private void assertTenantAccess(String accountOrg, String requestMode, String displayName) {
        if (opsTenantService.accountMatchesRequest(accountOrg, requestMode)) {
            return;
        }
        throw new IllegalArgumentException(opsTenantService.loginDeniedMessage(accountOrg, requestMode));
    }

    private static String accountOrgType(Map<String, Object> account) {
        if (account == null) {
            return ORG_HQ;
        }
        Object raw = account.get("orgType");
        if (raw == null) {
            raw = account.get("org_type");
        }
        return OpsTenantService.normalizeOrg(raw == null ? null : String.valueOf(raw), ORG_HQ);
    }

    private void cleanupRevoked() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        Instant staleBefore = now.minus(tokenTtl.plus(Duration.ofHours(1)));
        credentialsRevokedAt.entrySet().removeIf(entry -> entry.getValue().isBefore(staleBefore));
    }

    private void revokeCredentials(String displayName) {
        String name = normalizePersonName(displayName);
        if (name.isBlank()) {
            return;
        }
        credentialsRevokedAt.put(name, Instant.now());
    }

    private boolean isCredentialsRevoked(Session session) {
        if (session == null) {
            return false;
        }
        Instant revokedAt = credentialsRevokedAt.get(normalizePersonName(session.displayName()));
        if (revokedAt == null) {
            return false;
        }
        // 中文备注：签发点 ≈ expireAt - ttl；签发早于吊销点则失效。
        Instant issuedAt = session.expireAt().minus(tokenTtl);
        return issuedAt.isBefore(revokedAt);
    }

    private static void assertAccountEnabled(Map<String, Object> account) {
        if (account != null && !Boolean.TRUE.equals(account.get("enabled"))) {
            throw new IllegalArgumentException("账户已停用，请联系管理员");
        }
    }

    private void cleanupOnline() {
        maybeCleanupOnline();
    }

    private void maybeCleanupOnline() {
        if (!serverLoadGuard.shouldRunPresenceCleanup()) {
            return;
        }
        try {
            presenceRepository.deleteOlderThan(Instant.now().minus(ONLINE_IDLE_TTL));
        } catch (Exception ignored) {
        }
    }

    /**
     * token = base64url(payload).base64url(hmacSha256(payload))，payload 内含姓名/首字母/过期时间。
     */
    private String newToken(Session session) {
        String payload = TOKEN_VERSION_V3 + "|" + session.displayName() + "|" + session.initials()
                + "|" + session.expireAt().toEpochMilli() + "|" + normalizeRole(session.role())
                + "|" + OpsTenantService.normalizeOrg(session.orgType(), ORG_HQ);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(payloadBytes) + "." + encoder.encodeToString(sign(payloadBytes));
    }

    private Session parseToken(String token) {
        int dot = token.lastIndexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            payloadBytes = decoder.decode(token.substring(0, dot));
            signature = decoder.decode(token.substring(dot + 1));
        } catch (RuntimeException e) {
            return null;
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), signature)) {
            return null;
        }
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        try {
            if (parts.length == 6 && TOKEN_VERSION_V3.equals(parts[0])) {
                return new Session(parts[1], parts[2], Instant.ofEpochMilli(Long.parseLong(parts[3])),
                        normalizeRole(parts[4]), OpsTenantService.normalizeOrg(parts[5], ORG_HQ));
            }
            if (parts.length == 5 && TOKEN_VERSION_V2.equals(parts[0])) {
                return new Session(parts[1], parts[2], Instant.ofEpochMilli(Long.parseLong(parts[3])), normalizeRole(parts[4]), ORG_HQ);
            }
            if (parts.length == 4 && TOKEN_VERSION.equals(parts[0])) {
                return new Session(parts[1], parts[2], Instant.ofEpochMilli(Long.parseLong(parts[3])), ROLE_USER, ORG_HQ);
            }
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isAdminBootstrapPassword(String provided) {
        if (!adminPassword.isBlank()
                && MessageDigest.isEqual(
                adminPassword.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return !masterAccessKey.isBlank()
                && MessageDigest.isEqual(
                masterAccessKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        return ROLE_ADMIN.equals(value) ? ROLE_ADMIN : ROLE_USER;
    }

    static String hashPassword(String displayName, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = PASSWORD_HASH_PREFIX + normalizePersonName(displayName) + "::" + password;
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("口令摘要失败：" + e.getMessage(), e);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(tokenSecret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("token 签名失败: " + e.getMessage(), e);
        }
    }

    private String formatInstant(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DATE_TIME_FORMATTER);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> buildNameInitials() {
        Map<String, String> map = new LinkedHashMap<>();
        // 中文备注：演示账户；缺失账号按默认口令规则（拼音首字母 + 后缀）创建。
        put(map, "张三", "ZS");
        put(map, "李四", "LS");
        return Map.copyOf(map);
    }

    private static void put(Map<String, String> map, String name, String initials) {
        map.put(name, initials);
    }

    public record Session(String displayName, String initials, Instant expireAt, String role, String orgType) {
        public Session(String displayName, String initials, Instant expireAt) {
            this(displayName, initials, expireAt, ROLE_USER, ORG_HQ);
        }

        public Session(String displayName, String initials, Instant expireAt, String role) {
            this(displayName, initials, expireAt, role, ORG_HQ);
        }
    }

    public record LoginResult(String token, Session session) {
    }

    private record FocusState(String tableId,
                              String tableLabel,
                              String dramaType,
                              String opsKey,
                              String seriesName,
                              String colKey,
                              String colLabel,
                              String mode,
                              String selJson) {
        static final FocusState EMPTY = new FocusState("", "", "", "", "", "", "", "idle", "");
    }

    public record FocusUpdate(String tableId,
                              String tableLabel,
                              String dramaType,
                              String opsKey,
                              String seriesName,
                              String colKey,
                              String colLabel,
                              String mode,
                              String selJson) {
    }
}
