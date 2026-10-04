package com.shoppilot.gateway.identity;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 静态入口的放行面（round27 票 93）。
 *
 * <p>清场日抓到过这个洞：`/workspace/`（**目录**路径、无扩展名）不在放行名单里 → 401，
 * 而「打开页面先要 token」这件事要等到活体验收才发现。买家中心是**同一类洞的第二个实例**，
 * 所以这里用一条用例把三个入口一次钉住——下一个入口加进来时，它会先在这条上撞红。
 */
class StaticEntryExemptionTest {

    private final ApiErrorWriterStub errors = new ApiErrorWriterStub();
    private final AuthFilter filter = new AuthFilter(mock(JwtService.class), errors);

    @Test
    @DisplayName("三个静态入口的目录路径都放行")
    void allStaticEntriesAreExempt() throws Exception {
        assertThat(exempted("/")).isTrue();
        assertThat(exempted("/workspace/")).isTrue();
        assertThat(exempted("/workspace/index.html")).isTrue();
        assertThat(exempted("/buyer/")).isTrue();
        assertThat(exempted("/buyer/index.html")).isTrue();
        assertThat(exempted("/buyer/assets/index-abc.js")).isTrue();
    }

    @Test
    @DisplayName("登录面与健康检查放行")
    void authAndHealthAreExempt() throws Exception {
        assertThat(exempted("/auth/login")).isTrue();
        assertThat(exempted("/auth/mock-token")).isTrue();
        assertThat(exempted("/actuator/health/readiness")).isTrue();
    }

    @Test
    @DisplayName("业务与运维面**不放行**：买家令牌、ops 凭证各自把门")
    void businessAndOpsSurfacesAreNotExempt() throws Exception {
        assertThat(exempted("/api/v1/support/chat")).isFalse();
        assertThat(exempted("/api/v1/support/ops/tickets/mine")).isFalse();
        assertThat(exempted("/api/v1/support/ops/refunds/12/review")).isFalse();
    }

    @Test
    @DisplayName("/buyer 少了尾斜杠也放行（浏览器常把尾斜杠去掉）")
    void buyerWithoutTrailingSlashIsExempt() throws Exception {
        // 入口转发接住了无尾斜杠（StaticEntryController），但过滤器这一层也要放行，
        // 否则那个转发请求本身先被 401 拦下——两处都要，两处就都要有断言。
        assertThat(exempted("/buyer/assets")).isTrue();
    }

    private boolean exempted(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        // 放行的请求会走到 chain，不放行的会被 401 截断——这里断的是「有没有走到 chain」
        if (response.getStatus() == 401) {
            return false;
        }
        verify(chain).doFilter(any(), any());
        return true;
    }

    /** `ApiErrorWriter` 需要一个 ObjectMapper；这里给真的，省得为了断一句话去 mock 一串。 */
    private static final class ApiErrorWriterStub extends com.shoppilot.gateway.web.ApiErrorWriter {

        private ApiErrorWriterStub() {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }
}