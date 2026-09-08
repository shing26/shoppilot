package com.shoppilot.gateway.identity;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份不可伪造（否决项）：无 token、未签名、过期 token 一律 401，客户端自带的租户标识一律被忽略。
 */
class AuthFilterTest {

    private static final String SECRET = "unit-test-secret-value-at-least-32-bytes!!";
    private final JwtService jwtService = new JwtService(SECRET);
    private final AuthFilter filter = new AuthFilter(jwtService);

    @Test
    @DisplayName("缺少 Authorization 直接 401，不进入业务链")
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/support/chat");
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        int status = doFilter(request, chain(chained));

        assertThat(status).isEqualTo(401);
        assertThat(chained.get()).isFalse();
    }

    @Test
    @DisplayName("伪造签名（换密钥签发）的 token 被拒")
    void rejectsForgedSignature() throws Exception {
        String forgedByOtherKey = new JwtService("another-secret-value-that-is-also-32-chars!").issue("T002", "C999");
        MockHttpServletRequest request = authorized(forgedByOtherKey);
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        assertThat(doFilter(request, chain(chained))).isEqualTo(401);
        assertThat(chained.get()).isFalse();
    }

    @Test
    @DisplayName("过期 token 被拒")
    void rejectsExpiredToken() throws Exception {
        String expired = jwtService.issue("T001", "C155", Duration.ofSeconds(-30));
        MockHttpServletRequest request = authorized(expired);
        AtomicReference<Boolean> chained = new AtomicReference<>(false);

        assertThat(doFilter(request, chain(chained))).isEqualTo(401);
        assertThat(chained.get()).isFalse();
    }

    @Test
    @DisplayName("合法 token 注入身份，且请求里自带的 tenantId 不改变身份")
    void validTokenInjectsIdentityAndIgnoresClientSuppliedTenant() throws Exception {
        MockHttpServletRequest request = authorized(jwtService.issue("T001", "C155"));
        request.addParameter("tenantId", "T002");
        request.addHeader("X-Tenant-Id", "T002");
        request.addHeader("X-Conversation-Id", "conv-1");
        AtomicReference<TenantContext.Identity> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.current());

        assertThat(doFilter(request, chain)).isEqualTo(200);
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().tenantId()).isEqualTo("T001");
        assertThat(seen.get().customerId()).isEqualTo("C155");
        assertThat(seen.get().conversationId()).isEqualTo("conv-1");
        assertThat(TenantContext.present()).isFalse();
    }

    private static FilterChain chain(AtomicReference<Boolean> chained) {
        return (req, res) -> chained.set(true);
    }

    private MockHttpServletRequest authorized(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/support/chat");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private int doFilter(MockHttpServletRequest request, FilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }
}
