package com.shoppilot.gateway.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.EsRestClient;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ingest 第二语料源的线上行为（round43 票 186 / ADR 0078，假下游不打真 biz-mock）。
 *
 * <p>钉的是失败语义而不是解析细节：解析错了顶多缺一条修订，**失败被吞才是事故**——
 * 拉取失败若静默降级成空列表，入库照常推进纪元，发布出的知识库就永远缺了那批人工核准条款，
 * 且没有任何声音。所以「任何失败都必须抛」在这里逐条钉死；变异对照（吞异常返回空列表 → 用例变红）
 * 归票 187 收口。
 */
class KnowledgeRevisionSourceTest {

    private static final String PUBLISHED_BODY = """
            [
              {"id":"KR1","tenantId":"T001","feedbackId":"FB1","title":"退换货口径",
               "body":"## 生效范围\\n自 2026-11 起所有生鲜类订单支持 48 小时无理由退货，正文刻意写长避免并块。",
               "intent":"POLICY_RETURN","domain":"ECOMMERCE","scope":"SHOP"},
              {"id":"KR2","tenantId":"T002","feedbackId":"FB2","title":"运费口径",
               "body":"## 运费规则\\n单笔满 99 元包邮，未达门槛收取 8 元运费，同段写长保持独立成块不被合并。",
               "intent":"POLICY_SHIPPING","domain":"GENERAL","scope":"PLATFORM"}
            ]
            """;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int status = 200;
    private volatile String payload = PUBLISHED_BODY;

    @BeforeEach
    void startFakeBizMock() throws IOException {
        requestCount.set(0);
        status = 200;
        payload = PUBLISHED_BODY;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/admin/knowledge/revisions", exchange -> {
            requestCount.incrementAndGet();
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KnowledgeRevisionSource source() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.bizmock()).thenReturn(new GatewayProperties.BizMock(
                baseUrl, "internal", Duration.ofSeconds(2), Duration.ofSeconds(2)));
        return new KnowledgeRevisionSource(HttpClient.newHttpClient(), properties, new ObjectMapper());
    }

    @Test
    @DisplayName("正常拉取：解析成修订列表，凭证与路径由网关补齐")
    void fetchesAndParsesPublishedRevisions() {
        List<KnowledgeRevisionSource.Revision> revisions = source().fetchPublished();

        assertThat(revisions).hasSize(2);
        KnowledgeRevisionSource.Revision first = revisions.get(0);
        assertThat(first.id()).isEqualTo("KR1");
        assertThat(first.tenantId()).isEqualTo("T001");
        assertThat(first.feedbackId()).isEqualTo("FB1");
        assertThat(first.title()).isEqualTo("退换货口径");
        assertThat(first.intent()).isEqualTo("POLICY_RETURN");
        assertThat(first.domain()).isEqualTo("ECOMMERCE");
        assertThat(first.scope()).isEqualTo("SHOP");
        assertThat(first.body()).startsWith("## 生效范围");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("非 2xx：抛异常中止入库，不许静默降级成空语料")
    void non200AbortsTheIngest() {
        status = 503;

        assertThatThrownBy(() -> source().fetchPublished())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("ADR 0078");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("下游不可达：同样抛异常，错误信息给出 biz-mock 地址")
    void unreachableDownstreamAbortsTheIngest() {
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> source().fetchPublished())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(port))
                .hasMessageContaining("ADR 0078");
    }

    @Test
    @DisplayName("响应不是数组形状：抛异常，坏形状与好形状不许混着入库")
    void malformedShapeAbortsTheIngest() {
        payload = "{\"revisions\":[]}";

        assertThatThrownBy(() -> source().fetchPublished())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("形状");
    }

    /**
     * 票 186 验收项「拉取失败 → 进程 exit 非 0」的用例缝：run() 会 System.exit（JVM 里不能真跑），
     * 所以钉在 {@link IngestRunner#ingest()}——修订拉取失败时异常向上抛，且**先于任何写入**：
     * embedding 不调用、Qdrant/ES 不写、纪元不推进。"先拉取后写入"的顺序就是这条用例钉的。
     */
    @Test
    @DisplayName("修订拉取失败：ingest 在任何写入之前中止，纪元不动")
    void ingestAbortsBeforeAnyWriteWhenRevisionFetchFails(@TempDir java.nio.file.Path dir) throws IOException {
        KnowledgeRevisionSource failingSource = mock(KnowledgeRevisionSource.class);
        when(failingSource.fetchPublished()).thenThrow(new IllegalStateException("拉取失败"));
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        when(qdrant.ping()).thenReturn(true);
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch kbEpoch = mock(KbEpoch.class);
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.ingest()).thenReturn(new GatewayProperties.Ingest(dir.toString()));
        // probe 阶段会读 retrieval() 拿 ES 索引名；es 是 mock，给个占位即可。
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(
                "http://127.0.0.1:1", "http://127.0.0.1:2", "rules", "answer_cache", "policy_rules", 10, 10, 5, 60));

        IngestRunner runner = new IngestRunner(embedding, qdrant, es, kbEpoch, properties,
                failingSource, mock(org.springframework.context.ConfigurableApplicationContext.class));

        assertThatThrownBy(runner::ingest).isInstanceOf(IllegalStateException.class);
        // probe 阶段有一次连通性 embedding，是允许的唯一一次；语料向量化与任何写入都不许发生。
        verify(embedding, times(1)).embedWarmup(any());
        verify(qdrant, never()).upsert(any(), any());
        verify(es, never()).index(any(), any(), any());
        verify(kbEpoch, never()).bump();
    }
}
