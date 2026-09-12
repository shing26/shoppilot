package com.shoppilot.gateway.identity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mock 身份签发端点：调试台用它一键切换店铺与买家（ticket 15）。
 * 生产应替换为真实 IdP，这里保留验签的真实性即可（ADR 0014）。
 *
 * <p>「验签是真的」不等于「身份拿不到」：这个端点不要任何凭证，谁 POST 一下就能领到
 * 任意店铺 + 任意买家的合法身份。所以它只在回环绑定上注册（ADR 0029），
 * 而 README 与问答库不许再拿「身份不可伪造」覆盖这种领取——两个词的分工见 {@code CONTEXT.md}。
 */
@RestController
@Conditional(MockIdentityCondition.class)
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
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

    public record MockTokenRequest(@NotBlank String tenantId, @NotBlank String customerId) {
    }
}
