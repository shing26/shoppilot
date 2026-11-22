package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.IdentityService;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.AccountRoleRequest;
import com.shoppilot.tool.identity.AccountStatusRequest;
import com.shoppilot.tool.identity.AuthenticateRequest;
import com.shoppilot.tool.identity.ChangePasswordRequest;
import com.shoppilot.tool.identity.RegisterAccountRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 身份域的内部端点（round25 票 80 / ADR 0058 第 1 条）。
 *
 * <p>浏览器永远不直连本服务：登录面在网关（{@code /auth/login}，票 81），本服务只回答
 * 「这个账号是谁、什么角色」。因此这里不返回令牌——**签名密钥不出网关**，
 * 本仓的密钥设施（ADR 0029）也就没有被搬进第二个服务。
 *
 * <p>租户来自 {@code X-Tenant-Id}（{@link InternalAuthFilter} 已校验内部凭证后写入），**不从请求体取**。
 * 所以这两个端点是租户级路径，不是平台级放行——用户名按 (租户, 用户名) 唯一，跨店同名是正常业务。
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final IdentityService identity;

    public IdentityController(IdentityService identity) {
        this.identity = identity;
    }

    /** 注册一个买家账号并直接回账号视图（由网关签发令牌）。 */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterAccountRequest request) {
        try {
            return ResponseEntity.ok(identity.register(request.username(), request.password(), request.displayName()));
        } catch (IllegalArgumentException rejected) {
            return badRequest(rejected.getMessage());
        } catch (IllegalStateException taken) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", taken.getMessage()));
        }
    }

    /**
     * 校验口令并回账号视图。
     *
     * <p>失败一律 401 + 同一句话：用户不存在与口令不对对外没有区别（{@code IdentityService} 类注释）。
     */
    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticate(@RequestBody AuthenticateRequest request) {
        try {
            return ResponseEntity.ok(identity.authenticate(request.username(), request.password()));
        } catch (IdentityService.AccountLocked locked) {
            // 429 + Retry-After：让调用方知道「等一会儿」而不是「口令错」，但不确认账号是否存在。
            long retryAfter = Math.max(1, java.time.Duration.between(java.time.Instant.now(), locked.until()).toSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .body(Map.of("message", "尝试次数过多，请稍后再试"));
        } catch (IdentityService.BadCredentials | IdentityService.AccountDisabled wrong) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "用户名或口令不对"));
        }
    }

    /** 登录成功后开 refresh 族；明文只在这次内部响应里出现，网关立刻放进 HttpOnly Cookie。 */
    @PostMapping("/sessions")
    public ResponseEntity<?> openSession(@RequestBody AccountView account) {
        try {
            return ResponseEntity.ok(identity.openSession(account));
        } catch (IdentityService.BadCredentials missing) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "账号不存在"));
        }
    }

    /** 刷新：单次使用 + 轮换；复用返回 401 + {@code refresh_reuse}，网关据此清 Cookie。 */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        try {
            return ResponseEntity.ok(identity.refresh(request.refreshToken()));
        } catch (IdentityService.RefreshReuse reuse) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", reuse.getMessage(), "code", "refresh_reuse"));
        } catch (IdentityService.InvalidRefresh invalid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", invalid.getMessage(), "code", "refresh_invalid"));
        }
    }

    /** 登出：幂等撤销该 refresh 族。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshRequest request) {
        identity.revokeSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** 改密：账号 id 由网关从已验签令牌取，不在请求体里。 */
    @PostMapping("/accounts/{accountId}/password")
    public ResponseEntity<?> changePassword(@PathVariable String accountId,
                                            @RequestBody ChangePasswordRequest request) {
        try {
            return ResponseEntity.ok(identity.changePassword(accountId, request.currentPassword(),
                    request.newPassword()));
        } catch (IdentityService.BadCredentials wrong) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "当前口令不对"));
        } catch (IllegalArgumentException invalid) {
            return badRequest(invalid.getMessage());
        }
    }

    /** 账号最新状态与角色：{@code /auth/me} 用它回读服务端事实。 */
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<?> account(@PathVariable String accountId) {
        return identity.findById(accountId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "账号不存在")));
    }

    @PostMapping("/accounts/{accountId}/status")
    public ResponseEntity<?> status(@PathVariable String accountId, @RequestBody AccountStatusRequest request) {
        try {
            return ResponseEntity.ok(identity.setActive(accountId, request.active()));
        } catch (IdentityService.BadCredentials missing) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "账号不存在"));
        }
    }

    @PostMapping("/accounts/{accountId}/role")
    public ResponseEntity<?> role(@PathVariable String accountId, @RequestBody AccountRoleRequest request) {
        try {
            return ResponseEntity.ok(identity.changeRole(accountId, request.role()));
        } catch (IdentityService.BadCredentials missing) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "账号不存在"));
        } catch (IllegalArgumentException invalid) {
            return badRequest(invalid.getMessage());
        }
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    public record RefreshRequest(String refreshToken) {
    }
}
