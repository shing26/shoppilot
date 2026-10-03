package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.service.IdentityService;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.AuthenticateRequest;
import com.shoppilot.tool.identity.RegisterAccountRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        } catch (IdentityService.BadCredentials | IdentityService.AccountDisabled wrong) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "用户名或口令不对"));
        }
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}