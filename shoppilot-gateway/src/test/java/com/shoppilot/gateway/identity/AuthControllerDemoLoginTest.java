package com.shoppilot.gateway.identity;

import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerDemoLoginTest {

    private static final String DEMO_PASSWORD = "demo-pass";

    private final IdentityClient identity = mock(IdentityClient.class);
    private final AuthController controller = new AuthController(
            new JwtService("0123456789abcdef0123456789abcdef"), identity, DEMO_PASSWORD, true);

    @Test
    @DisplayName("买家一键体验：回环 + buyer 角色 → 签发 BUYER 令牌")
    void buyerDemoLoginIssuesBuyerToken() {
        when(identity.authenticate("T001", "buyer", DEMO_PASSWORD)).thenReturn(new IdentityClient.Attempt(
                200, new AccountView("acc-buyer", "T001", "buyer", UserRole.BUYER, "C001", "体验买家"), null));

        ResponseEntity<?> response = controller.demoLogin(
                new AuthController.DemoLoginRequest("T001", "buyer"), localRequest("127.0.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("role")).isEqualTo("BUYER");
        assertThat(body.get("customerId")).isEqualTo("C001");
        assertThat(body.get("token")).isNotNull();
    }

    @Test
    @DisplayName("容器档：Docker 网桥 remoteAddr + 本机 Host 也放行（仅 demo-login-enabled）")
    void dockerBridgeDemoLoginAllowed() {
        when(identity.authenticate("T001", "buyer", DEMO_PASSWORD)).thenReturn(new IdentityClient.Attempt(
                200, new AccountView("acc-buyer", "T001", "buyer", UserRole.BUYER, "C001", "体验买家"), null));

        ResponseEntity<?> response = controller.demoLogin(
                new AuthController.DemoLoginRequest("T001", "buyer"), localRequest("172.18.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("外部地址 → 404，不签发也不暴露端点存在")
    void externalAddressRejected() {
        ResponseEntity<?> response = controller.demoLogin(
                new AuthController.DemoLoginRequest("T001", "buyer"), localRequest("8.8.8.8"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("未知角色 → 400，且不调用身份域")
    void unknownRoleRejected() {
        when(identity.authenticate(anyString(), anyString(), anyString()))
                .thenReturn(new IdentityClient.Attempt(401, null, "bad"));

        ResponseEntity<?> response = controller.demoLogin(
                new AuthController.DemoLoginRequest("T001", "root"), localRequest("127.0.0.1"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private static MockHttpServletRequest localRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("Host", "127.0.0.1:8082");
        return request;
    }
}
