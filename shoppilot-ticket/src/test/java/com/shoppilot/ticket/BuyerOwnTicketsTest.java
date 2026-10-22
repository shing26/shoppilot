package com.shoppilot.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 买家自己的工单（round27 票 92）。
 *
 * <p><b>这一类是靠跨买家隔离承重的</b>：同租户另一个买家的工单号，一条都不许出现在
 * 第一个买家的结果里。请求里**带别人的买家号参数也不改变结果**——
 * 买家号只从已验签身份取（ADR 0005 防线一），从参数读就等于任何人能读别人的单。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class BuyerOwnTicketsTest {

    private static final String TOKEN = "test-internal";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("买家只看到自己的工单：同租户另一个买家的单一条都不出现")
    void seesOnlyItsOwnTickets() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");
        String otherBuyer = createTicket("C155", "这条是别家买家的");

        List<String> ids = idsOf(getMine("C001"));

        assertThat(ids).contains(mine);
        assertThat(ids).as("同租户另一买家的工单号不许出现").doesNotContain(otherBuyer);
    }

    @Test
    @DisplayName("请求里带别人的买家号参数不改变结果：买家号只从已验签身份取")
    void ignoresBuyerIdFromTheRequest() throws Exception {
        String otherBuyer = createTicket("C155", "这条是别家买家的");

        // ?customerId=C155 想读别人的单——但下游看到的是令牌/请求头里的 C001
        List<String> ids = idsOf(getMine("C001", "?customerId=C155", "T001"));

        assertThat(ids).as("参数传了也不生效").doesNotContain(otherBuyer);
    }

    @Test
    @DisplayName("跨租户读不到：换一个租户上下文就是另一套数据")
    void tenantScoped() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");

        assertThat(idsOf(getMine("C001", null, "T002"))).as("T002 看不到 T001 的单").doesNotContain(mine);
    }

    @Test
    @DisplayName("坐席拿得到会话原文、买家拿不到：两臂同测，避免真空断言")
    void opsSeesTranscriptButBuyerDoesNot() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");

        // 先证「坐席这一臂真的带 transcript」——只断买家拿不到的话，两边都没有该字段时它也会绿
        // （round36 / 票 151 修掉的正是这条真空断言）。
        JsonNode ops = JSON.readTree(getTicket(mine, "T001").getBody());
        assertThat(ops.path("transcript").asText())
                .as("坐席必须拿得到会话原文")
                .isEqualTo("坐席与买家之间的原文");

        JsonNode buyer = firstOf(getMine("C001"));
        assertThat(buyer.path("id").asText()).isEqualTo(mine);
        assertThat(buyer.hasNonNull("transcript")).as("买家不该拿到会话原文").isFalse();
        assertThat(buyer.path("userQuery").asText()).as("诉求原文要给，那是买家自己说的").isEqualTo("我这条要找客服");
        assertThat(buyer.path("status").asText()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("买家按号读自己的工单：带状态与时间，仍不带会话原文")
    void readsOwnTicketDetail() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");

        ResponseEntity<String> detail = getMineOne(mine, "C001", "T001");

        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(detail.getBody());
        assertThat(body.path("id").asText()).isEqualTo(mine);
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(body.hasNonNull("transcript")).as("买家详情也不该拿到会话原文").isFalse();
    }

    @Test
    @DisplayName("坐席结单意见落库，买家详情能读到处理结论")
    void resolutionNoteReachesBuyerDetail() throws Exception {
        String mine = createTicket("C001", "我要退款");
        claimAndResolve(mine, "已核实，退款原路退回");

        ResponseEntity<String> detail = getMineOne(mine, "C001", "T001");

        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(detail.getBody());
        assertThat(body.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(body.path("resolutionNote").asText()).isEqualTo("已核实，退款原路退回");
    }

    @Test
    @DisplayName("同租户另一个买家读详情 → 404，不泄露存在性")
    void otherBuyerCannotReadDetail() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");

        assertThat(getMineOne(mine, "C155", "T001").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("跨租户读详情 → 404")
    void otherTenantCannotReadDetail() throws Exception {
        String mine = createTicket("C001", "我这条要找客服");

        assertThat(getMineOne(mine, "C001", "T002").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("按创建时间倒序：买家找的是「我最近问过什么」")
    void newestFirst() throws Exception {
        String first = createTicket("C001", "更早的一条");
        String second = createTicket("C001", "更新的一条");

        List<String> ids = idsOf(getMine("C001"));

        assertThat(ids.indexOf(second)).as("最新的一张在最前").isLessThan(ids.indexOf(first));
    }

    private String createTicket(String customerId, String query) throws Exception {
        ResponseEntity<String> created = rest.exchange("/api/tickets", HttpMethod.POST,
                new HttpEntity<>("{\"customerId\":\"" + customerId + "\",\"reason\":\"TOOL_UNAVAILABLE\","
                        + "\"userQuery\":\"" + query + "\",\"transcript\":\"坐席与买家之间的原文\"}",
                        headers("T001", customerId)), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return JSON.readTree(created.getBody()).path("id").asText();
    }

    private ResponseEntity<String> getMine(String customerId) {
        return getMine(customerId, null, "T001");
    }

    /** 坐席视角的按号读取（票 151 起用它证明 transcript 真的被带出来）。 */
    private ResponseEntity<String> getTicket(String ticketId, String tenantId) {
        return rest.exchange("/api/tickets/" + ticketId, HttpMethod.GET,
                new HttpEntity<>(headers(tenantId, "C001")), String.class);
    }

    private ResponseEntity<String> getMine(String customerId, String query, String tenantId) {
        return rest.exchange("/api/tickets/mine" + (query == null ? "" : query), HttpMethod.GET,
                new HttpEntity<>(headers(tenantId, customerId)), String.class);
    }

    private ResponseEntity<String> getMineOne(String ticketId, String customerId, String tenantId) {
        return rest.exchange("/api/tickets/mine/" + ticketId, HttpMethod.GET,
                new HttpEntity<>(headers(tenantId, customerId)), String.class);
    }

    private void claimAndResolve(String ticketId, String note) {
        ResponseEntity<String> claimed = rest.exchange("/api/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(actorHeaders()), String.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(204);
        ResponseEntity<String> resolved = rest.exchange("/api/tickets/" + ticketId + "/resolve", HttpMethod.POST,
                new HttpEntity<>("{\"note\":\"" + note + "\"}", actorHeaders()), String.class);
        assertThat(resolved.getStatusCode().value()).isEqualTo(204);
    }

    private HttpHeaders actorHeaders() {
        HttpHeaders headers = headers("T001", "C001");
        headers.set("X-Actor", "agent");
        headers.set("X-Actor-Authenticated", "true");
        return headers;
    }

    private List<String> idsOf(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).as("请求应成功，实际 %s", response.getStatusCode()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        for (JsonNode ticket : JSON.readTree(response.getBody())) {
            ids.add(ticket.path("id").asText());
        }
        return ids;
    }

    private JsonNode firstOf(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody()).get(0);
    }

    private HttpHeaders headers(String tenantId, String customerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Token", TOKEN);
        headers.set("X-Tenant-Id", tenantId);
        headers.set("X-Customer-Id", customerId);
        return headers;
    }
}