package com.shoppilot.gateway.identity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * mock 身份签发端点：调试台用它一键切换店铺与买家（ticket 15）。
 * 生产应替换为真实 IdP，这里保留验签的真实性即可（ADR 0014）。
 */
@RestController
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
