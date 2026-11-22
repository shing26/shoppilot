package com.shoppilot.gateway.identity;

import com.shoppilot.gateway.web.ApiError;
import com.shoppilot.gateway.web.ApiErrorWriter;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.ChangePasswordRequest;
import com.shoppilot.tool.identity.RefreshTokenFormat;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 账号登录面（round25 票 81 / ADR 0058）。
 *
 * <p>与 {@link AuthController} 的分工必须能被读出来：{@code /auth/mock-token} 是**假身份**的领取口
 * （回环才注册、任何人都能领到任意店铺 + 任意买家，ADR 0014/0029），而这里是**真身份**的入口——
 * 拿着账号去换一张带角色的令牌。这两条并存是有意的：前者是调试台与那批验收脚本的正门，
 * 把它删了，那些脚本与演示全要重写。
 *
 * <p>{@code /auth/me} 自己要求令牌：{@code AuthFilter} 对 {@code /auth/} 前缀整体放行（登录页拿不到令牌），
 * 但放行是过滤器不拦，不是端点不设防。
 */
@RestController
@RequestMapping("/auth")
public class AccountController {

    private final IdentityClient identityClient;
    private final JwtService jwtService;
    private final ApiErrorWriter errors;
    private final RefreshCookie refreshCookie;

    @Autowired
    public AccountController(IdentityClient identityClient, JwtService jwtService, ApiErrorWriter errors,
                             @Value("${shoppilot.identity.refresh-ttl:14d}") java.time.Duration refreshTtl,
                             @Value("${shoppilot.identity.secure-cookie:false}") boolean secureCookie) {
        this.identityClient = identityClient;
        this.jwtService = jwtService;
        this.errors = errors;
        this.refreshCookie = new RefreshCookie(refreshTtl, secureCookie);
    }

    /** 测试缝：默认 refresh TTL 与非 Secure Cookie，与生产默认配置同值。 */
    public AccountController(IdentityClient identityClient, JwtService jwtService, ApiErrorWriter errors) {
        this(identityClient, jwtService, errors, java.time.Duration.ofDays(14), false);
    }

    /**
     * 用户名 + 口令 → access token（响应体 {@code token}）+ refresh token（HttpOnly Cookie）。
     *
     * <p>失败一律 401，文案由身份域给（用户不存在与口令错同句）；锁定是 429 + Retry-After，
     * 不确认账号是否存在。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        IdentityClient.Attempt attempt =
                identityClient.authenticate(request.tenantId(), request.username(), request.password());
        if (attempt.account() == null) {
            return rejected(attempt);
        }
        return sessionResponse(attempt.account(), "login");
    }

    /** 注册买家账号并直接给令牌——演示口径下不做邮箱验证，也不要求先有账号。 */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody LoginRequest request) {
        IdentityClient.Attempt attempt = identityClient.register(request.tenantId(), request.username(),
                request.password(), request.displayName());
        if (attempt.account() == null) {
            return rejected(attempt);
        }
        return sessionResponse(attempt.account(), "register");
    }

    /**
     * 刷新：从 HttpOnly Cookie 取 refresh，轮换后同时换 access。
     *
     * <p>浏览器不会自己带 {@code Authorization} 来刷新（access 可能已经过期），
     * 所以本端点只认 Cookie；CSRF 面由 `SameSite=Strict` + `Path=/auth` 收敛。
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        Optional<String> presented = RefreshCookie.read(request);
        if (presented.isEmpty()) {
            // 没有 Cookie 也要清一次：浏览器若残留一张格式已坏的票，留着只会反复带上来。
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                    .body(Map.of("code", ApiError.UNAUTHORIZED, "message", "登录已失效，请重新登录"));
        }
        // 租户取自已验签 access 不可行（它可能刚过期）。refresh 自带租户前缀，
        // 它只是「去哪张表找」的线索；真正作数的是整串哈希与库里那一行匹配。
        String tenantHint = RefreshTokenFormat.tenantOf(presented.get());
        if (tenantHint == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                    .body(Map.of("code", ApiError.UNAUTHORIZED, "message", "登录已失效，请重新登录"));
        }
        IdentityClient.RefreshAttempt attempt = identityClient.refresh(
                tenantHint, presented.get());
        if (attempt.session() == null) {
            // 复用与过期都要清 Cookie：留着它只会让浏览器反复带一张废票。
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(
                    attempt.status() == 0 ? HttpStatus.BAD_GATEWAY : HttpStatus.UNAUTHORIZED);
            return builder.header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                    .body(Map.of("code", attempt.code() == null ? ApiError.UNAUTHORIZED : attempt.code(),
                            "message", attempt.message() == null ? "登录已失效，请重新登录" : attempt.message()));
        }
        return sessionResponse(attempt.session().account(), "refresh", attempt.session().refreshToken());
    }

    /** 登出：撤销 refresh 族并清 Cookie；没有 Cookie 也返回 204（登出必须幂等）。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        RefreshCookie.read(request).ifPresent(token -> {
            String tenantHint = RefreshTokenFormat.tenantOf(token);
            if (tenantHint != null) {
                identityClient.logout(tenantHint, token);
            }
        });
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    /**
     * 改密（ADR 0086 第 3 条）：必须带有效 access token，账号 id 取自令牌而不是请求体。
     *
     * <p>成功后该账号全部 refresh 已撤销，浏览器手里的 Cookie 也随之清掉；
     * 当前 access token 允许用到短 TTL。
     */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(
            @RequestHeader(value = "Authorization", required = false) String header,
            @RequestBody ChangePasswordRequest request) {
        if (header == null || !header.startsWith("Bearer ")) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED, "missing bearer token");
        }
        var identity = jwtService.verify(header.substring(7).trim(), "password");
        if (identity.isEmpty() || identity.get().accountId() == null) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED,
                    "invalid or expired token");
        }
        var current = identity.get();
        IdentityClient.Attempt attempt = identityClient.changePassword(current.tenantId(), current.accountId(),
                request.currentPassword(), request.newPassword());
        if (attempt.account() == null) {
            if (attempt.status() == 0) {
                return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                        attempt.message());
            }
            HttpStatus status = attempt.status() == 401 ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.valueOf(attempt.status());
            return ResponseEntity.status(status)
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                    .body(Map.of("code", ApiError.UNAUTHORIZED,
                            "message", attempt.message() == null ? "当前口令不对" : attempt.message()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .body(Map.of("changed", true, "message", "口令已更新，请重新登录"));
    }

    /**
     * 停用/启用账号（ADR 0086 第 5 条）：只允许 ADMIN，账号 id 走路径参数。
     *
     * <p>不复用 ops token：那是平台运维凭证，不是「哪个管理员做的」。动作本身会进审计，
     * actor 取自已验签令牌。
     */
    @PostMapping("/accounts/{accountId}/status")
    public ResponseEntity<?> accountStatus(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable String accountId,
            @RequestBody AccountStatusBody request) {
        ResponseEntity<?> denied = requireAdmin(header);
        if (denied != null) return denied;
        var identity = jwtService.verify(header.substring(7).trim(), "status").orElseThrow();
        IdentityClient.Attempt attempt = identityClient.setAccountStatus(identity.tenantId(), accountId,
                request.active());
        return adminResponse(attempt);
    }

    /** 角色变更（ADR 0086 第 5 条）：只允许 ADMIN；成功后该账号全部 refresh 立即失效。 */
    @PostMapping("/accounts/{accountId}/role")
    public ResponseEntity<?> accountRole(
            @RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable String accountId,
            @RequestBody AccountRoleBody request) {
        ResponseEntity<?> denied = requireAdmin(header);
        if (denied != null) return denied;
        var identity = jwtService.verify(header.substring(7).trim(), "role").orElseThrow();
        IdentityClient.Attempt attempt = identityClient.setAccountRole(identity.tenantId(), accountId,
                request.role());
        return adminResponse(attempt);
    }

    private ResponseEntity<?> requireAdmin(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED, "missing bearer token");
        }
        var identity = jwtService.verify(header.substring(7).trim(), "admin");
        if (identity.isEmpty()) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED,
                    "invalid or expired token");
        }
        if (identity.get().role() != com.shoppilot.tool.identity.UserRole.ADMIN) {
            return errors.entity(HttpStatus.FORBIDDEN.value(), "role.denied", "该操作需要管理员账号");
        }
        return null;
    }

    private ResponseEntity<?> adminResponse(IdentityClient.Attempt attempt) {
        if (attempt.account() != null) {
            AccountView account = attempt.account();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accountId", account.accountId());
            body.put("username", account.username());
            body.put("role", account.role().name());
            body.put("displayName", account.displayName());
            return ResponseEntity.ok(body);
        }
        if (attempt.status() == 0) {
            return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                    attempt.message());
        }
        HttpStatus status = HttpStatus.valueOf(attempt.status());
        return ResponseEntity.status(status)
                .body(new ApiError(status == HttpStatus.NOT_FOUND ? "account_not_found" : ApiError.INVALID_REQUEST,
                        attempt.message() == null ? "请求被拒绝" : attempt.message(), RequestTrace.traceId()));
    }

    /**
     * 回读当前身份与能力。
     *
     * <p>round56 起它回答的是「服务端现在认为我是谁」：有账号 id 时回读 biz-mock 的最新角色与
     * 状态，停用账号直接 401；没有账号 id 的 mock 令牌仍按令牌里的角色解释（兼容旧入口）。
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED, "missing bearer token");
        }
        return jwtService.verify(header.substring(7).trim(), "me")
                .<ResponseEntity<?>>map(tokenIdentity -> {
                    var identity = tokenIdentity;
                    if (identity.accountId() != null) {
                        IdentityClient.Attempt current = identityClient.account(identity.tenantId(),
                                identity.accountId());
                        if (current == null || current.account() == null) {
                            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED,
                                    "账号已停用或不存在");
                        }
                        AccountView account = current.account();
                        identity = new TenantContext.Identity(account.tenantId(), account.subjectRef(),
                                identity.conversationId(), account.role(), account.accountId(), account.username());
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("tenantId", identity.tenantId());
                    body.put("customerId", identity.customerId());
                    body.put("accountId", identity.accountId());
                    body.put("role", identity.role().name());
                    body.put("capabilities", Capabilities.of(identity.role()));
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED,
                        "invalid or expired token"));
    }

    private ResponseEntity<?> rejected(IdentityClient.Attempt attempt) {
        if (attempt.account() == null) {
            // 下游不可达（status 0）与被拒（401/409/400）是两件事：前者 502，后者原样带回去。
            if (attempt.status() == 0) {
                return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                        attempt.message());
            }
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(attempt.status());
            if (attempt.retryAfterSeconds() != null) {
                builder.header("Retry-After", String.valueOf(attempt.retryAfterSeconds()));
            }
            // 429 是锁定，不是入参错误：保留下游的 code 语义，别退化成 invalid_request。
            String code = attempt.status() == 429
                    ? (attempt.code() == null ? "login_locked" : attempt.code())
                    : statusCodeOf(attempt.status());
            // traceId 交给 ApiErrorWriter 的同款来源，不在这里另起一份空值。
            return builder.body(new ApiError(code,
                    attempt.message() == null ? "请求被拒绝" : attempt.message(),
                    RequestTrace.traceId()));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private ResponseEntity<?> sessionResponse(AccountView account, String action) {
        return sessionResponse(account, action, null);
    }

    /**
     * 账号 → access token + refresh Cookie。refresh 由身份域开族，明文只在这一次内部往返里出现。
     *
     * <p>{@code action=refresh} 时复用身份域刚轮换出的新 refresh；登录/注册则新开一族。
     */
    private ResponseEntity<?> sessionResponse(AccountView account, String action, String rotatedRefresh) {
        String refresh = rotatedRefresh;
        if (refresh == null) {
            IdentityClient.RefreshAttempt opened = identityClient.openSession(account.tenantId(), account);
            if (opened == null || opened.session() == null) {
                return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                        opened == null || opened.message() == null ? "身份服务不可达" : opened.message());
            }
            refresh = opened.session().refreshToken();
        }
        // 租户取自**库里那一行**而不是请求里的 tenantId：请求里的值只是「去哪张表里找这个用户名」的线索。
        String token = jwtService.issueAccess(account.tenantId(), account.subjectRef(), account.role(),
                account.accountId(), account.username(), account.passwordVersion());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("accountId", account.accountId());
        // **username 不能少**（round25 写漏了，清场日被工作台门禁抓到）：两个前端的 Session
        // 都声明了它，而登录响应里没有这一格，于是页面上 `{{ username }}` 直接渲染成 undefined。
        // 买家端因为写的是 `displayName ?? username` 才没露出来——**那是一个巧合，不是设计**。
        body.put("username", account.username());
        body.put("tenantId", account.tenantId());
        body.put("customerId", account.subjectRef());
        body.put("role", account.role().name());
        body.put("capabilities", Capabilities.of(account.role()));
        body.put("displayName", account.displayName());
        body.put("expiresInSeconds", jwtService.ttl().toSeconds());
        body.put("refreshExpiresInSeconds", refreshCookie.ttlSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.issue(refresh).toString())
                .body(body);
    }

    /** 下游状态码到本仓错误码的一个对照表。逐个写出来而不是直接透传数字，是为了让响应体形状只有一个来源（ADR 0028）。 */
    private static String statusCodeOf(int downstreamStatus) {
        // switch 的 case 标签要是编译期常量，HttpStatus.UNAUTHORIZED.value() 不是，所以写数字并在这里点名。
        return switch (downstreamStatus) {
            case 401 -> ApiError.UNAUTHORIZED;   // HttpStatus.UNAUTHORIZED
            case 409 -> "username_taken";        // HttpStatus.CONFLICT
            default -> ApiError.INVALID_REQUEST;
        };
    }

    public record LoginRequest(@NotBlank String tenantId, @NotBlank String username,
                                @NotBlank String password, String displayName) {
    }

    public record AccountStatusBody(boolean active) {
    }

    public record AccountRoleBody(com.shoppilot.tool.identity.UserRole role) {
    }
}
