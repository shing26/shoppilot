package com.shoppilot.gateway.identity;

import com.shoppilot.gateway.web.ApiError;
import com.shoppilot.gateway.web.ApiErrorWriter;
import com.shoppilot.tool.identity.AccountView;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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

    public AccountController(IdentityClient identityClient, JwtService jwtService, ApiErrorWriter errors) {
        this.identityClient = identityClient;
        this.jwtService = jwtService;
        this.errors = errors;
    }

    /** 用户名 + 口令 → 一张带角色的令牌。失败一律 401，文案由身份域给（用户不存在与口令错同句）。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return respond(identityClient.authenticate(request.tenantId(), request.username(), request.password()));
    }

    /** 注册买家账号并直接给令牌——演示口径下不做邮箱验证，也不要求先有账号。 */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody LoginRequest request) {
        return respond(identityClient.register(request.tenantId(), request.username(), request.password(),
                request.displayName()));
    }

    /**
     * 回读当前令牌的主体与角色。
     *
     * <p>它回答的是「我手里这张令牌是谁」，不是「我是谁」——所以它只需要验签，不需要问身份域。
     * 要查账号状态（停用之类）得等票据 82 的角色守卫落地，那时才有第二个读者。
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED, "missing bearer token");
        }
        return jwtService.verify(header.substring(7).trim(), "me")
                .<ResponseEntity<?>>map(identity -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("tenantId", identity.tenantId());
                    body.put("customerId", identity.customerId());
                    body.put("accountId", identity.accountId());
                    body.put("role", identity.role().name());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> errors.entity(HttpStatus.UNAUTHORIZED.value(), ApiError.UNAUTHORIZED,
                        "invalid or expired token"));
    }

    private ResponseEntity<?> respond(IdentityClient.Attempt attempt) {
        if (attempt.account() == null) {
            // 下游不可达（status 0）与被拒（401/409/400）是两件事：前者 502，后者原样带回去。
            if (attempt.status() == 0) {
                return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                        attempt.message());
            }
            return errors.entity(attempt.status(), statusCodeOf(attempt.status()), attempt.message());
        }
        AccountView account = attempt.account();
        // 租户取自**库里那一行**而不是请求里的 tenantId：请求里的值只是「去哪张表里找这个用户名」的线索。
        String token = jwtService.issue(account.tenantId(), account.subjectRef(), account.role(), account.accountId(),
                account.username());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("accountId", account.accountId());
        body.put("tenantId", account.tenantId());
        body.put("customerId", account.subjectRef());
        body.put("role", account.role().name());
        body.put("displayName", account.displayName());
        body.put("expiresInSeconds", jwtService.ttl().toSeconds());
        return ResponseEntity.ok(body);
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
}