package com.shoppilot.gateway.identity;

import com.shoppilot.tool.config.PostureGuard;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * mock 身份签发端点：调试台用它一键切换店铺与买家（ticket 15）。
 * 生产应替换为真实 IdP，这里保留验签的真实性即可（ADR 0014）。
 *
 * <p>「验签是真的」不等于「身份拿不到」：这个端点不要任何凭证，谁 POST 一下就能领到
 * 任意店铺 + 任意买家的合法身份。所以它只在回环绑定上注册（ADR 0029），
 * 而 README 与问答库不许再拿「身份不可伪造」覆盖这种领取——两个词的分工见 {@code CONTEXT.md}。
 *
 * <p>round37 增加 {@code /auth/demo-login}：产品页的「一键体验坐席/管理员」。它同样只回环注册，
 * 且**不把口令交给浏览器**——服务端用 {@code SHOPPILOT_IDENTITY_DEMO_PASSWORD} 去换真账号令牌。
 * 口令为空时端点 503（不创建、不猜测），角色只接受 AGENT/ADMIN。
 */
@RestController
@Conditional(MockIdentityCondition.class)
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtService jwtService;
    private final IdentityClient identityClient;
    private final String demoPassword;

    public AuthController(JwtService jwtService, IdentityClient identityClient,
                          @Value("${shoppilot.identity.demo-password:}") String demoPassword) {
        this.jwtService = jwtService;
        this.identityClient = identityClient;
        this.demoPassword = demoPassword;
    }

    @PostMapping("/mock-token")
    public Map<String, Object> mockToken(@RequestBody MockTokenRequest request) {
        String token = jwtService.issue(request.tenantId(), request.customerId());
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("tid", request.tenantId());
        claims.put("cid", request.customerId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("claims", claims);
        body.put("expiresInSeconds", jwtService.ttl().toSeconds());
        return body;
    }

    /**
     * 一键体验登录：服务端持有演示口令，浏览器只收到令牌。
     *
     * <p>它只解决「本机演示不想手打口令」，不是身份模型的放宽。**请求期再做一次回环校验**：
     * {@link MockIdentityCondition} 只在 Bean 注册期看绑定地址，反向代理 / SSH 隧道 / DNS rebinding
     * 都能让请求以 127.0.0.1 的身份打进来。这里同时看 remoteAddr 与 Host：前者挡外部直连，
     * 后者挡「浏览器被指到攻击者域名、但连接落到本机」的那一类。
     */
    @PostMapping("/demo-login")
    public ResponseEntity<?> demoLogin(@RequestBody @Valid DemoLoginRequest request, HttpServletRequest http) {
        // 非本机请求与「端点未注册」同形，不新增可探测的 401。
        if (!isLocalRequest(http) || demoPassword == null || demoPassword.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String requestedRole = request.role() == null ? "" : request.role().trim();
        String username = switch (requestedRole) {
            case "agent" -> "agent";
            case "admin" -> "admin";
            default -> null;
        };
        if (username == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "演示登录只支持 agent / admin"));
        }
        IdentityClient.Attempt attempt = identityClient.authenticate(request.tenantId(), username, demoPassword);
        if (attempt.account() == null) {
            log.warn("演示登录失败 tenant={} username={} downstreamStatus={}", request.tenantId(), username,
                    attempt.status());
            // 下游细节不外泄：不可达/5xx 统一 502，其余统一 401。
            return attempt.status() == 0 || attempt.status() >= 500
                    ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "演示登录暂时不可用"))
                    : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "演示账号不可用"));
        }
        AccountView account = attempt.account();
        // 角色白名单不能只信请求参数：签发的角色取自下游账号，这里必须再次校验。
        if (account.role() != UserRole.AGENT && account.role() != UserRole.ADMIN) {
            log.warn("演示登录拒绝：下游账号不是员工 tenant={} username={} role={}",
                    account.tenantId(), account.username(), account.role());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "演示登录只接受员工账号"));
        }
        log.info("演示登录 tenant={} username={} role={}", account.tenantId(), account.username(), account.role());
        String token = jwtService.issue(account.tenantId(), account.subjectRef(), account.role(),
                account.accountId(), account.username());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("accountId", account.accountId());
        body.put("username", account.username());
        body.put("tenantId", account.tenantId());
        body.put("customerId", account.subjectRef());
        body.put("role", account.role().name());
        body.put("displayName", account.displayName());
        body.put("expiresInSeconds", jwtService.ttl().toSeconds());
        return ResponseEntity.ok(body);
    }

    /** remoteAddr 必须是回环，且浏览器实际请求的 Host 也必须是本机名/IP。 */
    private static boolean isLocalRequest(HttpServletRequest request) {
        if (!PostureGuard.isLoopback(request.getRemoteAddr())) {
            return false;
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        String name;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            name = end > 0 ? host.substring(1, end) : host;
        } else {
            int colon = host.indexOf(':');
            name = colon >= 0 ? host.substring(0, colon) : host;
        }
        name = name.toLowerCase(Locale.ROOT);
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("::1");
    }

    public record MockTokenRequest(@NotBlank String tenantId, @NotBlank String customerId) {
    }

    public record DemoLoginRequest(@NotBlank String tenantId, @NotBlank String role) {
    }
}
