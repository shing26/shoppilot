package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.Customer;
import com.shoppilot.bizmock.domain.UserAccount;
import com.shoppilot.bizmock.repo.CustomerRepository;
import com.shoppilot.bizmock.repo.UserAccountRepository;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.UserRole;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 身份域的账号读写（round25 票 80 / ADR 0056、0058）。
 *
 * <p>两处**有意的简化**，写在这里是为了让读代码的人知道它是简化、不是漏了：
 * <ol>
 *   <li>「用户不存在」与「口令不对」对调用方是**同一句话**。真实的量产系统要分（才能做撞库防护与
 *       账号枚举防护），本仓是自用/演示口径，ADR 0024 的红线仍然成立，所以不为它加机器。</li>
 *   <li>没有失败锁定、没有速率限制、没有口令强度策略以外的任何东西。ADR 0056 已把这三项列为非目标。</li>
 * </ol>
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

    private final UserAccountRepository accounts;
    private final CustomerRepository customers;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);

    public IdentityService(UserAccountRepository accounts, CustomerRepository customers) {
        this.accounts = accounts;
        this.customers = customers;
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
    @Transactional(readOnly = true)
    public AccountView authenticate(String username, String password) {
        TenantContextHolder.tenantId();
        if (username == null || username.isBlank() || password == null) {
            throw new BadCredentials();
        }
        Optional<UserAccount> found = accounts.findByUsername(username.trim());
        // 用户不存在时也要走一次哈希比较，否则「响应时间随用户名是否存在变化」就把账号枚举漏出去了。
        String hash = found.map(UserAccount::getPasswordHash)
                .orElseGet(() -> encoder.encode("no-such-account"));
        if (!encoder.matches(password, hash) || found.isEmpty()) {
            throw new BadCredentials();
        }
        UserAccount account = found.get();
        if (!account.active()) {
            throw new AccountDisabled();
        }
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

    /** 对外一律是这一份形状：没有哈希，没有状态字面量。 */
    private static AccountView view(UserAccount account) {
        return new AccountView(account.getId(), account.getTenantId(), account.getUsername(), account.getRole(),
                account.getSubjectRef(), account.getDisplayName());
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
}