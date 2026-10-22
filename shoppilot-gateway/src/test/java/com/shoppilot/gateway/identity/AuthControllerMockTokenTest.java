package com.shoppilot.gateway.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthControllerMockTokenTest {

    private final AuthController controller = new AuthController(
            new JwtService("0123456789abcdef0123456789abcdef"), mock(IdentityClient.class), "", false);

    @Test
    @DisplayName("回环 + localhost Host → 签发")
    void loopbackIssues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8082");

        ResponseEntity<?> response = controller.mockToken(
                new AuthController.MockTokenRequest("T001", "C001"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
    }

    @Test
    @DisplayName("非回环 remoteAddr → 404，不签发")
    void externalAddressRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("Host", "localhost:8082");

        assertThat(controller.mockToken(new AuthController.MockTokenRequest("T001", "C001"), request)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("回环但 Host 是外部域名 → 404（DNS rebinding 形状）")
    void foreignHostRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "evil.example.com");

        assertThat(controller.mockToken(new AuthController.MockTokenRequest("T001", "C001"), request)
                .getStatusCode().value()).isEqualTo(404);
    }
}
