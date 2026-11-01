package com.shoppilot.gateway.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * ingest 的第二个语料源（round43 票 186 / ADR 0078）：从 biz-mock 拉取全部已发布的知识修订。
 *
 * <p>失败语义是本类唯一容易被想错的点：**拉取失败必须中止入库**，不允许静默降级成
 * "只有文件语料"——那会发布一个缺了人工核准条款的知识库，且悄无声息。调用方
 * （{@link IngestRunner}）把这里的异常当 {@code DependencyMissing} 同类处理。
 */
@Component
public class KnowledgeRevisionSource {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRevisionSource.class);

    /** 入库切分需要的全部字段，与 biz-mock 的 RevisionView 对齐。 */
    public record Revision(String id, String tenantId, String feedbackId, String title, String body,
                           String intent, String domain, String scope) {
    }

    private final HttpClient http;
    private final GatewayProperties properties;
    private final ObjectMapper mapper;

    public KnowledgeRevisionSource(HttpClient http, GatewayProperties properties, ObjectMapper mapper) {
        this.http = http;
        this.properties = properties;
        this.mapper = mapper;
    }

    public List<Revision> fetchPublished() {
        HttpRequest request;
        HttpResponse<String> response;
        try {
            request = HttpRequest.newBuilder(
                            URI.create(properties.bizmock().baseUrl() + "/api/admin/knowledge/revisions"))
                    .timeout(properties.bizmock().readTimeout())
                    .header("X-Internal-Token", properties.bizmock().internalToken())
                    .GET()
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception unreachable) {
            throw new IllegalStateException("知识修订拉取失败（" + properties.bizmock().baseUrl()
                    + " 不可达）：" + unreachable.getMessage()
                    + "。修订是人工核准过的条款，缺了它们的知识库不允许入库（ADR 0078）。");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("知识修订拉取失败：biz-mock 返回 " + response.statusCode()
                    + "。修订是人工核准过的条款，缺了它们的知识库不允许入库（ADR 0078）。");
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            if (!root.isArray()) {
                throw new IllegalStateException("知识修订拉取失败：响应不是数组形状。"
                        + "修订是人工核准过的条款，坏形状与好形状不许混着入库（ADR 0078）。");
            }
            List<Revision> revisions = new ArrayList<>();
            for (JsonNode row : root) {
                revisions.add(new Revision(
                        row.path("id").asText(), row.path("tenantId").asText(), row.path("feedbackId").asText(),
                        row.path("title").asText(), row.path("body").asText(), row.path("intent").asText(),
                        row.path("domain").asText(), row.path("scope").asText()));
            }
            log.info("拉取已发布知识修订 {} 条", revisions.size());
            return revisions;
        } catch (Exception malformed) {
            throw new IllegalStateException("知识修订拉取失败：响应不是预期形状。" + malformed.getMessage());
        }
    }
}
