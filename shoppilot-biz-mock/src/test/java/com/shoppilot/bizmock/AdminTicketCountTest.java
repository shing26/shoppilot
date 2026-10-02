package com.shoppilot.bizmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.bizmock.workitem.HttpWorkItemClient;
import com.shoppilot.bizmock.workitem.InMemoryWorkItemClient;
import com.shoppilot.tool.workitem.TicketSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维面板上的工单数（round23 票 75 清场日）。
 *
 * <p>票 72 把工单数据搬走时，`AdminController.stats()` 还在查本地那张已被 V6 删掉的表，
 * 于是 `/api/admin/stats` 与 `/api/admin/demo/reset` 一起 500（清场日活体验收抓到的第一个洞）。
 * 修法是改问工单服务，而**这一格恰好在 CI 上又抓了一次**：biz-mock 的 LINE 覆盖率
 * 因为「搬走了代码、又加了一条没测的调用路径」跌到 75.93%、跌破 76.0 的门槛——
 * 本地那 76.38% 是 jacoco 跨轮累积的假高。**不降线**，补这两处测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-ticket-count;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=50",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal",
        "shoppilot.bizmock.ticket-base-url=http://127.0.0.1:1",
        "shoppilot.bizmock.ticket-timeout=200ms"
})
class AdminTicketCountTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    InMemoryWorkItemClient workItems;

    @TestConfiguration
    static class StubWorkItems {
        @Bean
        @Primary
        InMemoryWorkItemClient workItemClient() {
            return new InMemoryWorkItemClient();
        }
    }

    @Test
    @DisplayName("/api/admin/stats 的 tickets 走工单服务：端点是 200，且那个数是真的取来的")
    void statsReadsTicketCountFromTheTicketService() throws Exception {
        workItems.create(TicketSource.REFUND_APPROVAL, "C001", "REFUND_APPROVAL", "订单 90002 的退款待人工审核",
                "", null, "{\"refundId\":\"1\"}");

        ResponseEntity<String> response = rest.exchange("/api/admin/stats", HttpMethod.GET,
                new HttpEntity<>(internalHeaders()), String.class);

        // 这条端点在清场日之前会因为「查一张已被 V6 删掉的表」而 500。
        // 所以承重的两件事是：状态码是 200，以及 tickets 那个数等于我们刚造的工单数。
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(stats).containsKeys("tenants", "customers", "orders", "refunds", "tickets");
        assertThat(((Number) stats.get("tickets")).intValue()).isEqualTo(workItems.count());
        assertThat(workItems.count()).as("替身确实造了单，否则这条断言会自己骗自己").isPositive();
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", "test-internal");
        headers.set("X-Tenant-Id", "T001");
        return headers;
    }

    @Test
    @DisplayName("工单服务可达时 count() 读出它的数字")
    void httpClientReadsTheCount() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tickets/count", exchange -> {
            byte[] body = "{\"count\":7}".getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            HttpWorkItemClient client = new HttpWorkItemClient(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "t", Duration.ofSeconds(2));

            assertThat(client.count()).isEqualTo(7);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("工单服务不可达时 count() 返 0 而不是抛出去（运维面板不该为一个数整体 500）")
    void httpClientDegradesToZeroWhenTicketServiceIsDown() {
        HttpWorkItemClient client = new HttpWorkItemClient(new ObjectMapper(),
                // 端口 1 一定连不上；超时压到 200ms，别让用例等满默认值
                "http://127.0.0.1:1", "t", Duration.ofMillis(200));

        assertThat(client.count()).isZero();
    }
}