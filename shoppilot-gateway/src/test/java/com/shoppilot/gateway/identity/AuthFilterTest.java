package com.shoppilot.gateway.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.web.ApiError;
import com.shoppilot.gateway.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份不可伪造（否决项）：无 token、未签名、过期 token 一律 401，客户端自带的租户标识一律被忽略。
 */
class AuthFilterTest {

    private static final String SECRET = "unit-test-secret-value-at-least-32-bytes!!";
    private final JwtService jwtService = new JwtService(SECRET);
    private final ApiErrorWriter errors = new ApiErrorWriter(new ObjectMapper());
    private final AuthFilter filter = new AuthFilter(jwtService, errors);

    @AfterEach
    void wipeContext() {
        RequestTrace.clear();
        TenantContext.clear();
    }

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

    @Test
    @DisplayName("四个坐标在鉴权入口一次装填：进到业务链里 MDC 已经齐了")
    void fillsFourCoordinatesAtTheEntry() throws Exception {
        MockHttpServletRequest request = authorized(jwtService.issue("T001", "C155"));
        request.addHeader("X-Conversation-Id", "conv-1");
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<String> customer = new AtomicReference<>();
        AtomicReference<String> conversation = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            trace.set(MDC.get(RequestTrace.TRACE_ID));
            tenant.set(MDC.get(RequestTrace.TENANT_ID));
            customer.set(MDC.get(RequestTrace.CUSTOMER_ID));
            conversation.set(MDC.get(RequestTrace.CONVERSATION_ID));
        };

        assertThat(doFilter(request, chain)).isEqualTo(200);

        assertThat(trace.get()).isNotBlank();
        assertThat(tenant.get()).isEqualTo("T001");
        assertThat(customer.get()).isEqualTo("C155");
        assertThat(conversation.get()).isEqualTo("conv-1");
        // 出了过滤器必须清干净：容器线程复用，残留等于把上一单的身份发给下一单
        assertThat(MDC.get(RequestTrace.TRACE_ID)).isNull();
        assertThat(MDC.get(RequestTrace.CONVERSATION_ID)).isNull();
    }

    @Test
    @DisplayName("装填发生在任何一行日志之前：那条试探告警自己就带 traceId")
    void tenantProbeWarningItselfCarriesTheTraceId() throws Exception {
        Logger filterLogger = (Logger) LoggerFactory.getLogger(AuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        try {
            MockHttpServletRequest request = authorized(jwtService.issue("T001", "C155"));
            request.addHeader("X-Tenant-Id", "T002");

            assertThat(doFilter(request, (req, res) -> {
            })).isEqualTo(200);

            List<ILoggingEvent> probes = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("忽略客户端自带的租户标识"))
                    .toList();
            assertThat(probes).hasSize(1);
            assertThat(probes.get(0).getMDCPropertyMap().get(RequestTrace.TRACE_ID)).isNotBlank();
        } finally {
            filterLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("401 的三条返回路径同样清场，被拒的请求不往线程上留坐标")
    void rejectedRequestsLeaveNoContextBehind() throws Exception {
        MockHttpServletRequest noHeader = new MockHttpServletRequest("POST", "/api/v1/support/chat");
        assertThat(doFilter(noHeader, (req, res) -> {
        })).isEqualTo(401);
        assertThat(MDC.get(RequestTrace.TRACE_ID)).isNull();

        String expired = jwtService.issue("T001", "C155", Duration.ofSeconds(-30));
        assertThat(doFilter(authorized(expired), (req, res) -> {
        })).isEqualTo(401);
        assertThat(MDC.get(RequestTrace.TRACE_ID)).isNull();
        assertThat(TenantContext.present()).isFalse();
    }

    @Test
    @DisplayName("并发两单各走各的鉴权：线程复用时租户与买家不许串")
    void concurrentRequestsDoNotShareCoordinates() throws Exception {
        String firstToken = jwtService.issue("T001", "C155");
        String secondToken = jwtService.issue("T002", "C777");
        int rounds = 20;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<String> mismatch = new AtomicReference<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                String token = i % 2 == 0 ? firstToken : secondToken;
                String expectedTenant = i % 2 == 0 ? "T001" : "T002";
                String expectedCustomer = i % 2 == 0 ? "C155" : "C777";
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(go);
                    try {
                        doFilter(authorized(token), (req, res) -> {
                            String tenant = MDC.get(RequestTrace.TENANT_ID);
                            String customer = MDC.get(RequestTrace.CUSTOMER_ID);
                            if (!expectedTenant.equals(tenant) || !expectedCustomer.equals(customer)) {
                                mismatch.compareAndSet(null, "拿到 " + tenant + "/" + customer
                                        + "，应为 " + expectedTenant + "/" + expectedCustomer);
                            }
                        });
                    } catch (Exception failure) {
                        mismatch.compareAndSet(null, String.valueOf(failure));
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(mismatch.get()).isNull();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
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

    @Test
    @DisplayName("401 那一处与 advice 出同一形状：code / message / traceId 三键，且经序列化而非字符串拼接")
    void rejectionBodyIsTheSharedEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/support/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain(new AtomicReference<>(false)));

        assertThat(response.getStatus()).isEqualTo(401);
        var node = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(node.fieldNames()).toIterable().containsExactly("code", "message", "traceId");
        assertThat(node.get("code").asText()).isEqualTo(ApiError.UNAUTHORIZED);
        assertThat(node.get("message").asText()).isEqualTo("missing bearer token");
        // 拒绝发生在链路装填之后，所以错误体里那个 id 与日志里的对得上（票 24 装的源）
        assertThat(node.get("traceId").isNull()).isFalse();
    }
}
