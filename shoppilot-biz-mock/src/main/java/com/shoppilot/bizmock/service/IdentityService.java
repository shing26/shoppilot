package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.Customer;
import com.shoppilot.bizmock.domain.RefreshToken;
import com.shoppilot.bizmock.domain.UserAccount;
import com.shoppilot.bizmock.repo.CustomerRepository;
import com.shoppilot.bizmock.repo.RefreshTokenRepository;
import com.shoppilot.bizmock.repo.UserAccountRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.RefreshSession;
import com.shoppilot.tool.identity.RefreshTokenFormat;
import com.shoppilot.tool.identity.UserRole;
import com.shoppilot.bizmock.audit.AuditService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 身份域的账号读写（round25 票 80 / ADR 0056、0058）。
 *
 * <p>round56 票 224 / ADR 0086 把这里从「演示账号」升级成可运营生命周期：
 * 失败锁定、refresh 轮换与复用撤销、改密、停用/换角色。仍然保留的简化只有一条：
 * 「用户不存在」与「口令不对」对调用方**同一句话**——这不是漏了，而是账号枚举防护的正面要求。
 *
 * <p>租户一律从 {@link TenantContextHolder} 取（本服务唯一的写入者是 {@code InternalAuthFilter}，
 * 即网关按已验签身份下发的请求头）。所以**登录是租户级路径**：用户名按 (租户, 用户名) 唯一，
 * 跨店同名是正常业务，见 ADR 0058 的实现期更正。
 */
@Service
public class IdentityService {

    /**
     * BCrypt cost。10 是 Spring Security 的默认值，也是本机一次校验约 50-100 ms 的量级。
     *
     * <p>刻意写成常量而不做成配置：量产系统要按机器调，演示系统调它没有消费者，
     * 而每加一个 {@code SHOPPILOT_*} 占位符都要显式登记进 {@code ConfigValidationTest} 的钉住清单。
     */
    private static final int BCRYPT_COST = 10;

    /** 演示口径的最短口令。ADR 0056 的非目标里写着「密码策略不做量产级」。 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * ADR 0086：默认窗口 15 分钟、连续 5 次失败锁定 15 分钟。
     *
     * <p>字段注入而不是再加构造器参数：现有 JVM 用例直接 {@code new IdentityService(...)}，
     * 加参数会把「安全默认值」变成每个测试都要重复的噪音；Spring 运行期仍可通过配置覆盖。
     */
    @Value("${shoppilot.identity.failure-window:15m}")
    private Duration failureWindow = Duration.ofMinutes(15);

    @Value("${shoppilot.identity.max-failures:5}")
    private int maxFailures = 5;

    @Value("${shoppilot.identity.lock-duration:15m}")
    private Duration lockDuration = Duration.ofMinutes(15);

    @Value("${shoppilot.identity.refresh-ttl:14d}")
    private Duration refreshTtl = Duration.ofDays(14);

    private final UserAccountRepository accounts;
    private final CustomerRepository customers;
    private final RefreshTokenRepository refreshTokens;
    private final IdentityStateStore state;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);
    private final SecureRandom random = new SecureRandom();

    public IdentityService(UserAccountRepository accounts, CustomerRepository customers,
                           RefreshTokenRepository refreshTokens, IdentityStateStore state,
                           AuditService audit, MeterRegistry meters) {
        this.accounts = accounts;
        this.customers = customers;
        this.refreshTokens = refreshTokens;
        this.state = state;
        this.audit = audit;
        this.meters = meters;
    }

    /**
     * 注册一个买家账号。
     *
     * <p>{@code subjectRef} 留空时挂到本租户的第一条 {@code customers} 行上——买家端登录之后
     * 「我的订单」要有主体可查，而 seed 出来的 C001 一定存在。
     */
    @Transactional
    public AccountView register(String username, String password, String displayName) {
        String tenantId = TenantContextHolder.tenantId();
        validateUsername(username);
        validatePassword(password);
        if (accounts.findByUsername(username).isPresent()) {
            throw new IllegalStateException("用户名已被占用");
        }
        String subjectRef = resolveBuyerSubject();
        UserAccount account = new UserAccount(newAccountId(), tenantId, username,
                encoder.encode(password), UserRole.BUYER, subjectRef,
                displayName == null || displayName.isBlank() ? username : displayName.trim(),
                Instant.now());
        return view(accounts.save(account));
    }

    /**
     * 校验口令并回账号视图。
     *
     * <p>失败一律抛 {@link BadCredentials}，**不区分原因**（见类注释第一条）。
     * 停用账号单独一类：它是「认得你、但不给你进」，运维要能从日志上把它与「密码错了」分开看，
     * 而对外仍然是同一句话。
     */
    @Transactional
    public AccountView authenticate(String username, String password) {
        TenantContextHolder.tenantId();
        if (username == null || username.isBlank() || password == null) {
            loginCounter("failure").increment();
            throw new BadCredentials();
        }
        Optional<UserAccount> found = accounts.findByUsername(username.trim());
        // 用户不存在时也要走一次哈希比较，否则「响应时间随用户名是否存在变化」就把账号枚举漏出去了。
        String hash = found.map(UserAccount::getPasswordHash)
                .orElseGet(() -> encoder.encode("no-such-account"));
        Instant now = Instant.now();
        // 锁定判定在口令比较之前：锁定期间即使口令正确也不放行，且不因比较耗时泄露差异。
        if (found.isPresent() && found.get().lockedAt(now)) {
            loginCounter("locked").increment();
            throw new AccountLocked(found.get().getLockedUntil());
        }
        if (!encoder.matches(password, hash) || found.isEmpty()) {
            found.ifPresent(account -> recordFailure(account.getId(), now));
            loginCounter("failure").increment();
            throw new BadCredentials();
        }
        UserAccount account = found.get();
        if (!account.active()) {
            loginCounter("failure").increment();
            throw new AccountDisabled();
        }
        account.clearFailures();
        loginCounter("success").increment();
        audit.publish("identity.login.succeeded", "USER", account.getId(),
                Actor.authenticated(account.getUsername()), "tenant=" + account.getTenantId());
        return view(account);
    }

    /** 建演示账号用：按角色直接落一条，不经过「注册」那条买家路径。 */
    @Transactional
    public Optional<AccountView> createRoleAccount(String username, String password, UserRole role,
                                                    String displayName, String subjectRef) {
        String tenantId = TenantContextHolder.tenantId();
        if (accounts.findByUsername(username).isPresent()) {
            return Optional.empty();
        }
        UserAccount account = new UserAccount(newAccountId(), tenantId, username,
                encoder.encode(password), role, subjectRef, displayName, Instant.now());
        return Optional.of(view(accounts.save(account)));
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> findByUsername(String username) {
        return accounts.findByUsername(username).map(IdentityService::view);
    }

    /** 按账号 id 回读；{@code /auth/me} 用它拿服务端最新角色与状态（ADR 0086 第 5 条）。 */
    @Transactional(readOnly = true)
    public Optional<AccountView> findById(String accountId) {
        return accounts.findById(accountId).map(IdentityService::view);
    }

    /**
     * 登录成功后开一条 refresh 令牌族（ADR 0086 第 1、2 条）。
     *
     * <p>明文只在这个返回值里出现一次；落库的是 SHA-256。family 的 id 与首张令牌不同，
     * 这样「复用检测撤销整族」不会把首张令牌的 id 也当成可轮换目标。
     */
    @Transactional
    public RefreshSession openSession(AccountView account) {
        UserAccount entity = requireAccount(account.accountId());
        String familyId = newId("F");
        IssuedRefresh token = issueRefresh(entity, familyId);
        return new RefreshSession(token.plain(), view(entity));
    }

    /**
     * 刷新：单次使用 + 轮换 + 复用检测。
     *
     * <p>三种失败对外分开：过期/不存在 → {@link InvalidRefresh}；复用 → {@link RefreshReuse}
     * （调用方会撤族）；账号停用或口令版本变化 → {@link InvalidRefresh} 但内部撤族。
     * 分开是为了让网关能决定「要不要清 Cookie」，而不是把泄露信号也翻成一句「请重新登录」。
     */
    @Transactional(noRollbackFor = {RefreshReuse.class, InvalidRefresh.class})
    public RefreshSession refresh(String plainToken) {
        Instant now = Instant.now();
        // 行级排他锁：同一个 refresh 的并发请求必须串行，否则两个都读到未使用。
        RefreshToken stored = refreshTokens.findByTokenHashForUpdate(hash(plainToken))
                .orElseThrow(InvalidRefresh::new);
        if (stored.getRevokedAt() != null) {
            throw new InvalidRefresh();
        }
        if (stored.getUsedAt() != null) {
            // 外层事务持有行锁，撤族必须加入本事务；noRollbackFor 保证抛出后仍提交。
            state.revokeFamilyInCurrentTransaction(stored.getFamilyId(), now);
            audit.publish("identity.refresh.reused", "REFRESH_TOKEN", stored.getId(),
                    Actor.SYSTEM, "family=" + stored.getFamilyId());
            throw new RefreshReuse();
        }
        if (!stored.getExpiresAt().isAfter(now)) {
            throw new InvalidRefresh();
        }
        UserAccount account = requireAccount(stored.getAccountId());
        if (!account.active()) {
            state.revokeFamilyInCurrentTransaction(stored.getFamilyId(), now);
            throw new InvalidRefresh();
        }
        // ADR 0086 §2：改密/换角色后，旧 refresh 必须立即失效，而不是等 TTL。
        if (stored.getPasswordVersion() != account.getPasswordVersion()
                || !account.getRole().name().equals(stored.getRole())) {
            state.revokeFamilyInCurrentTransaction(stored.getFamilyId(), now);
            throw new InvalidRefresh();
        }
        IssuedRefresh next = issueRefresh(account, stored.getFamilyId());
        stored.markUsed(now, next.id());
        refreshTokens.save(stored);
        return new RefreshSession(next.plain(), view(account));
    }

    /** 登出：撤销该 refresh 所在族；令牌不存在时静默成功（登出必须幂等）。 */
    @Transactional
    public void revokeSession(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(hash(plainToken))
                .ifPresent(token -> revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /** 改密（ADR 0086 第 3 条）：校验当前口令、推进版本、撤销全部 refresh。 */
    @Transactional
    public AccountView changePassword(String accountId, String currentPassword, String newPassword) {
        UserAccount account = requireAccount(accountId);
        if (currentPassword == null || !encoder.matches(currentPassword, account.getPasswordHash())) {
            throw new BadCredentials();
        }
        validatePassword(newPassword);
        account.changePassword(encoder.encode(newPassword));
        revokeAllForAccount(account.getId(), Instant.now());
        audit.publish("identity.password.changed", "USER", account.getId(),
                Actor.authenticated(account.getUsername()), null);
        return view(accounts.save(account));
    }

    /** 停用/启用（ADR 0086 第 5 条）：停用时立即撤销全部 refresh。 */
    @Transactional
    public AccountView setActive(String accountId, boolean active) {
        UserAccount account = requireAccount(accountId);
        if (active) {
            account.enable();
        } else {
            account.disable();
            revokeAllForAccount(account.getId(), Instant.now());
        }
        audit.publish(active ? "identity.account.enabled" : "identity.account.disabled", "USER",
                account.getId(), Actor.SYSTEM, "username=" + account.getUsername());
        return view(accounts.save(account));
    }

    /** 角色变更（ADR 0086 第 5 条）：立即撤销全部 refresh，access 按 TTL 失效。 */
    @Transactional
    public AccountView changeRole(String accountId, UserRole role) {
        if (role == null) {
            throw new IllegalArgumentException("角色不能为空");
        }
        UserAccount account = requireAccount(accountId);
        account.changeRole(role);
        revokeAllForAccount(account.getId(), Instant.now());
        audit.publish("identity.role.changed", "USER", account.getId(), Actor.SYSTEM,
                "username=" + account.getUsername() + " role=" + role.name());
        return view(accounts.save(account));
    }

    /** 对外一律是这一份形状：没有哈希，没有状态字面量。 */
    private static AccountView view(UserAccount account) {
        return new AccountView(account.getId(), account.getTenantId(), account.getUsername(), account.getRole(),
                account.getSubjectRef(), account.getDisplayName(), account.getPasswordVersion(),
                account.active());
    }

    private UserAccount requireAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new BadCredentials();
        }
        return accounts.findById(accountId).orElseThrow(BadCredentials::new);
    }

    private void recordFailure(String accountId, Instant now) {
        // 独立事务：口令错之后本方法会抛异常，同事务里的计数会被回滚（见 IdentityStateStore）。
        int attempts = state.recordFailure(accountId, now, failureWindow, maxFailures, lockDuration);
        if (attempts >= maxFailures) {
            loginCounter("locked").increment();
            audit.publish(AuditActions.ACCOUNT_LOCKED, "USER", accountId,
                    Actor.SYSTEM, "attempts=" + attempts);
        } else {
            audit.publish("identity.login.failed", "USER", accountId,
                    Actor.SYSTEM, "attempts=" + attempts);
        }
    }

    private IssuedRefresh issueRefresh(UserAccount account, String familyId) {
        String plain = RefreshTokenFormat.encode(account.getTenantId(), newPlainToken());
        Instant now = Instant.now();
        RefreshToken token = new RefreshToken(newId("R"), account.getTenantId(), familyId, account.getId(),
                hash(plain), now, now.plus(refreshTtl), account.getPasswordVersion(), account.getRole().name());
        refreshTokens.save(token);
        return new IssuedRefresh(token, plain);
    }

    private void revokeFamily(String familyId, Instant now) {
        // 撤销后调用方会抛 401，撤销必须留在库里 → 独立事务
        state.revokeFamily(familyId, now);
    }

    private void revokeAllForAccount(String accountId, Instant now) {
        state.revokeAllForAccount(accountId, now);
    }

    private Counter loginCounter(String result) {
        return Counter.builder("shoppilot_identity_login_total")
                .description("登录结果计数（ADR 0086）：success/failure/locked")
                .tag("result", result)
                .register(meters);
    }

    private String newPlainToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 只存哈希：库被读走也换不出可用凭证。 */
    static String hash(String plain) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    /** 签发结果：实体给后续轮换用，明文只给调用方一次。 */
    private record IssuedRefresh(RefreshToken entity, String plain) {
        String id() {
            return entity.getId();
        }
    }

    private String resolveBuyerSubject() {
        return customers.findTopByOrderByIdAsc().map(Customer::getId).orElse(null);
    }

    private static void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (username.trim().length() > 64) {
            throw new IllegalArgumentException("用户名过长");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("口令至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private static String newAccountId() {
        return "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 口令不对（含用户不存在）。两类对外同一句话，见类注释。 */
    public static class BadCredentials extends RuntimeException {
        public BadCredentials() {
            super("用户名或口令不对");
        }
    }

    /** 账号存在但已停用。对外文案与 {@link BadCredentials} 相同，只是内部可区分。 */
    public static class AccountDisabled extends RuntimeException {
        public AccountDisabled() {
            super("账号已停用");
        }
    }

    /** 锁定期间（即使口令正确）。网关把它翻成 429 + Retry-After，文案不确认账号是否存在。 */
    public static class AccountLocked extends RuntimeException {
        private final Instant until;

        public AccountLocked(Instant until) {
            super("尝试次数过多，请稍后再试");
            this.until = until;
        }

        public Instant until() {
            return until;
        }
    }

    /** refresh 过期、已撤销或账号状态不再允许。 */
    public static class InvalidRefresh extends RuntimeException {
        public InvalidRefresh() {
            super("登录已失效，请重新登录");
        }
    }

    /** 已使用的 refresh 再次出现：按泄露处理，整族已撤销。 */
    public static class RefreshReuse extends RuntimeException {
        public RefreshReuse() {
            super("登录凭证已失效，请重新登录");
        }
    }
}
