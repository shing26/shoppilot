package com.shoppilot.gateway.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「缓存表不存在」与「Qdrant 坏了」必须是两回事。
 *
 * <p>触发现场：调试台的清缓存按钮会调 {@code /ops/cache/flush}，旧实现是 DELETE 再 PUT 两次往返，
 * 中间那段表真的不在。把 flush 与 6 路并发问答混打 12 轮，每轮稳定漏出 4-7 行
 * 「L2 检索失败，按未命中处理」，12 轮 48 行——而这些请求的正确答案就是"缓存是空的"，
 * 只有日志级别把它们说得像存储故障。修法分两层：flush 换成一次原子 recreate 把空窗关掉，
 * 读路径再把剩余缺失按 miss 处理并自愈补建。
 */
class L2SemanticCacheTest {

    private static final String MISSING_TABLE =
            "Qdrant POST /collections/answer_cache/points/search -> 404 "
                    + "{\"status\":{\"error\":\"Not found: Collection `answer_cache` doesn't exist!\"}}";

    private QdrantRestClient qdrant;
    private L2SemanticCache l2;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        qdrant = mock(QdrantRestClient.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        GatewayProperties properties = mock(GatewayProperties.class);
        when(embedding.dimension()).thenReturn(4);
        when(properties.cache()).thenReturn(new GatewayProperties.Cache(true, Duration.ofMinutes(10),
                0.95d, Duration.ofSeconds(60), Duration.ofSeconds(30), true));
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval("http://127.0.0.1:1",
                "http://127.0.0.1:2", "policy_rules", "answer_cache", "rules", 10, 10, 5, 60));
        registry = new SimpleMeterRegistry();
        l2 = new L2SemanticCache(qdrant, embedding, mock(StringRedisTemplate.class), new ObjectMapper(),
                properties, registry);
    }

    private void failSearchWith(String message) {
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException(message));
    }

    private double count(String name, String result) {
        return registry.find(name).tag("result", result).counter() == null ? 0
                : registry.find(name).tag("result", result).counter().count();
    }

    @Test
    @DisplayName("表不存在按 miss 处理，并触发一次补建")
    void missingTableIsMissNotOutage() {
        failSearchWith(MISSING_TABLE);

        assertThat(l2.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, "T001", "platform", "POLICY_RETURN", 3L))
                .isEmpty();

        assertThat(count("shoppilot_cache_l2_total", "miss")).isEqualTo(1);
        assertThat(registry.find("shoppilot_cache_l2_missing_total").counter().count()).isEqualTo(1);
        verify(qdrant, times(1)).ensureCollection(eq("answer_cache"), eq(4), anyList(), anyList());
    }

    @Test
    @DisplayName("真正的存储故障不去抢着建表，仍然只是 miss")
    void realFailureDoesNotTryToRecreate() {
        failSearchWith("Qdrant POST /collections/answer_cache/points/search -> 503 upstream unavailable");

        assertThat(l2.search(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, "T001", "platform", "POLICY_RETURN", 3L))
                .isEmpty();

        verify(qdrant, never()).ensureCollection(anyString(), anyInt(), anyList(), anyList());
        assertThat(registry.find("shoppilot_cache_l2_missing_total").counter().count()).isZero();
        assertThat(count("shoppilot_cache_l2_total", "miss")).isEqualTo(1);
    }

    @Test
    @DisplayName("补建有节流：同一时间窗内的第二次缺失不会再起一次建表")
    void recreateIsThrottled() {
        failSearchWith(MISSING_TABLE);
        float[] vector = {0.1f, 0.2f, 0.3f, 0.4f};

        l2.search(vector, "T001", "platform", "POLICY_RETURN", 3L);
        l2.search(vector, "T001", "platform", "POLICY_RETURN", 3L);

        assertThat(registry.find("shoppilot_cache_l2_missing_total").counter().count()).isEqualTo(2);
        verify(qdrant, times(1)).ensureCollection(eq("answer_cache"), eq(4), anyList(), anyList());
    }

    @Test
    @DisplayName("flush 先删表再补建；顺序反过来等于把刚清掉的答案又装回去")
    void flushDeletesThenRecreates() {
        l2.flush();

        InOrder order = inOrder(qdrant);
        order.verify(qdrant).deleteCollection("answer_cache");
        order.verify(qdrant).ensureCollection(eq("answer_cache"), eq(4),
                eq(List.of("tenant_id", "scope", "intent", "answer_key")), eq(List.of("kb_epoch")));
    }

    @Test
    @DisplayName("只有 Qdrant 那句 Not found 会被认成缺表，别的 404 不算")
    void classificationIsNarrow() {
        assertThat(QdrantRestClient.isMissingCollection(new IllegalStateException(MISSING_TABLE))).isTrue();
        assertThat(QdrantRestClient.isMissingCollection(
                new IllegalStateException("Qdrant GET /collections/x -> 404 page not found"))).isFalse();
        assertThat(QdrantRestClient.isMissingCollection(new IllegalStateException())).isFalse();
    }
}
