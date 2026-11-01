package com.shoppilot.bizmock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识修订生命周期（round43 票 185 / ADR 0078）：复核前置、两道人工门、单向流转、
 * 跨租户 404、ingest 拉取口只吐 PUBLISHED。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class KnowledgeRevisionTest {

    private static final String TOKEN = "test-internal";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Test
    @DisplayName("复核完成 → 附稿 DRAFT → 发布 PUBLISHED；ingest 拉取口带 tenantId 与 body")
    void reviewedFeedbackAcceptsRevisionAndPublishes() throws Exception {
        String revision = submit("C101", "kr-conv-1", "生鲜破损理赔补充条款",
                "## 破损理赔\n\n签收后 24 小时内拍照可申请全额理赔。", "POLICY_FRESH", "ECOMMERCE", "SHOP");
        assertThat(statusOf(revision)).isEqualTo("DRAFT");
        assertThat(tenantOf(revision)).isEqualTo("T001");

        String published = publish(idOf(revision), "T001").body();
        assertThat(statusOf(published)).isEqualTo("PUBLISHED");

        JsonNode all = publishedForIngest();
        List<String> ids = new ArrayList<>();
        all.forEach(row -> ids.add(row.path("id").asText()));
        assertThat(ids).contains(idOf(revision));
        JsonNode row = all.get(ids.indexOf(idOf(revision)));
        assertThat(row.path("tenantId").asText()).isEqualTo("T001");
        assertThat(row.path("intent").asText()).isEqualTo("POLICY_FRESH");
        assertThat(row.path("scope").asText()).isEqualTo("SHOP");
        assertThat(row.path("body").asText()).contains("24 小时");
    }

    @Test
    @DisplayName("复核前置：PENDING 的反馈附稿被拒（409）；不存在的反馈 404")
    void pendingFeedbackCannotCarryRevision() throws Exception {
        String pending = idOf(create("C102", "kr-conv-2", "T001", "C102"));
        assertThat(submitRaw(pending, "kr-conv-2", "标题", "正文",
                "POLICY_RETURN", "ECOMMERCE", "SHOP", "T001", "C102").statusCode()).isEqualTo(409);
        assertThat(submitRaw("FB-not-exist", "kr-conv-3", "标题", "正文",
                "POLICY_RETURN", "ECOMMERCE", "SHOP", "T001", "C102").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("形状校验：动作意图、非法 scope、空标题都是 400")
    void malformedDraftsAreRejected() throws Exception {
        String reviewed = createAndReview("C103", "kr-conv-5", "T001", "C103");
        assertThat(submitRaw(reviewed, "kr-conv-5", "标题", "正文",
                "ACTION_ORDER", "ECOMMERCE", "SHOP", "T001", "C103").statusCode()).isEqualTo(400);
        assertThat(submitRaw(reviewed, "kr-conv-5", "标题", "正文",
                "POLICY_RETURN", "ECOMMERCE", "UNIVERSE", "T001", "C103").statusCode()).isEqualTo(400);
        assertThat(submitRaw(reviewed, "kr-conv-5", " ", "正文",
                "POLICY_RETURN", "ECOMMERCE", "SHOP", "T001", "C103").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("跨租户：B 店发布不了 A 店的修订（404，不确认存在）")
    void crossTenantPublishIsNotFound() throws Exception {
        String revision = submit("C104", "kr-conv-6", "标题", "正文", "POLICY_RETURN", "ECOMMERCE", "SHOP");
        assertThat(publish(idOf(revision), "T002").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("驳回是终态：DRAFT → REJECTED，再发布 409")
    void rejectionIsTerminal() throws Exception {
        String revision = submit("C105", "kr-conv-7", "标题", "正文", "POLICY_PROMO", "ECOMMERCE", "PLATFORM");
        String rejected = transition(idOf(revision), "reject", "T001").body();
        assertThat(statusOf(rejected)).isEqualTo("REJECTED");
        assertThat(publish(idOf(revision), "T001").statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("ingest 拉取口不含未发布稿；不同租户的已发布修订都在")
    void ingestListOnlyCarriesPublishedAcrossTenants() throws Exception {
        String draft = submit("C106", "kr-conv-8", "未发布稿", "正文", "POLICY_SHIPPING", "ECOMMERCE", "SHOP");
        String other = submitUnder("C207", "kr-conv-9", "T002 店条款", "T002 正文",
                "POLICY_FRESH", "ECOMMERCE", "SHOP", "T002", "C207");
        publish(other, "T002");

        JsonNode all = publishedForIngest();
        List<String> ids = new ArrayList<>();
        all.forEach(row -> ids.add(row.path("id").asText()));
        assertThat(ids).doesNotContain(idOf(draft));
        assertThat(ids).contains(other);
    }

    // ---- helpers -------------------------------------------------------------

    private String createAndReview(String customer, String conversation, String tenant, String tenantCustomer)
            throws Exception {
        String feedback = create(customer, conversation, tenant, tenantCustomer);
        String id = idOf(feedback);
        patchReview(id, tenant);
        return id;
    }

    private String submit(String customer, String conversation, String title, String body,
                          String intent, String domain, String scope) throws Exception {
        String feedbackId = createAndReview(customer, conversation, "T001", customer);
        return submitRaw(feedbackId, conversation, title, body, intent, domain, scope, "T001", customer).body();
    }

    private HttpResponse<String> submitRaw(String feedbackId, String conversation, String title, String body,
                                           String intent, String domain, String scope,
                                           String tenant, String customer) throws Exception {
        return send("POST", "/api/feedback/" + feedbackId + "/revision", tenant, customer,
                JSON.writeValueAsString(new LinkedHashMap<String, Object>() {{
                    put("title", title);
                    put("body", body);
                    put("intent", intent);
                    put("domain", domain);
                    put("scope", scope);
                }}));
    }

    private String submitUnder(String customer, String conversation, String title, String body,
                               String intent, String domain, String scope, String tenant, String tenantCustomer)
            throws Exception {
        String feedbackId = createAndReview(customer, conversation, tenant, tenantCustomer);
        return idOf(submitRaw(feedbackId, conversation, title, body, intent, domain, scope, tenant, tenantCustomer).body());
    }

    private HttpResponse<String> publish(String id, String tenant) throws Exception {
        return transition(id, "publish", tenant);
    }

    private HttpResponse<String> transition(String id, String action, String tenant) throws Exception {
        return send("POST", "/api/knowledge/revisions/" + id + "/" + action, tenant, null, null);
    }

    private String create(String customer, String conversation, String tenant, String tenantCustomer)
            throws Exception {
        HttpResponse<String> response = send("POST", "/api/feedback", tenant, tenantCustomer,
                JSON.writeValueAsString(new LinkedHashMap<String, Object>() {{
                    put("customerId", customer);
                    put("conversationId", conversation);
                    put("verdict", "DOWN");
                    put("ruleIds", List.of("POLICY-RETURN-01"));
                }}));
        if (!response.body().startsWith("{")) {
            throw new AssertionError("create 响应异常 status=" + response.statusCode()
                    + " headers=" + response.headers().map() + " body=[" + response.body() + "]");
        }
        return response.body();
    }

    private void patchReview(String feedbackId, String tenant) throws Exception {
        send("PATCH", "/api/feedback/" + feedbackId + "/review", tenant, null,
                "{\"status\":\"REVIEWED\"}");
    }

    private JsonNode publishedForIngest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/admin/knowledge/revisions"))
                .header("X-Internal-Token", TOKEN)
                .GET()
                .build();
        return JSON.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private HttpResponse<String> send(String method, String path, String tenant, String customer, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Internal-Token", TOKEN);
        if (tenant != null) {
            builder.header("X-Tenant-Id", tenant);
        }
        if (customer != null) {
            builder.header("X-Customer-Id", customer);
        }
        builder.header("X-Actor", "agent-1").header("X-Actor-Authenticated", "true");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) {
            throw new AssertionError("下游 5xx：" + method + " " + path + " → " + response.statusCode()
                    + "，原文：" + response.body());
        }
        return response;
    }

    private String idOf(String row) throws Exception {
        JsonNode node;
        try {
            node = JSON.readTree(row);
        } catch (JsonProcessingException broken) {
            throw new AssertionError("非 JSON 响应，原文=[" + row + "]", broken);
        }
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new AssertionError("响应没有 id 字段，原文：" + row);
        }
        return id;
    }

    private String statusOf(String row) throws Exception {
        return JSON.readTree(row).path("status").asText();
    }

    private String tenantOf(String row) throws Exception {
        return JSON.readTree(row).path("tenantId").asText();
    }
}
