package com.shoppilot.gateway.cache;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.tool.Intent;
import com.shoppilot.gateway.knowledge.RuleChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缓存层在"向量服务不可用"时的退化边界。
 *
 * <p>要守住的性质是：embedding 挂掉只该让 L2 失效，不该让 L1 停止积累。L1 的 key 是
 * {@code MD5(租户+意图+纪元+归一化问法)}，与向量无关；以前 prepareWrite 一并要求
 * queryVector，等于把"本地向量服务抖一下"放大成"大促期间缓存整体失效"。
 */
class CacheServiceTest {

    private static final long EPOCH = 7L;

    private L1Cache l1;
    private L2SemanticCache l2;
    private EmbeddingClient embedding;
    private SimpleMeterRegistry registry;
    private CacheService service;

    @BeforeEach
    void setUp() {
        l1 = mock(L1Cache.class);
        l2 = mock(L2SemanticCache.class);
        embedding = mock(EmbeddingClient.class);
        registry = new SimpleMeterRegistry();
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.cache()).thenReturn(new GatewayProperties.Cache(true, Duration.ofMinutes(10),
                0.95d, Duration.ofSeconds(60), Duration.ofSeconds(30), true));
        service = new CacheService(l1, l2, embedding, properties, registry);
        when(l1.key(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> "key:" + call.getArgument(0) + ":" + call.getArgument(1));
    }

    private CacheService.Lookup missWithOutage() {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("Ollama 不可达"));
        return service.lookup("T001", Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);
    }

    @Test
    @DisplayName("向量化失败：按未命中处理，不抛给请求线程")
    void embeddingOutageDegradesToMiss() {
        CacheService.Lookup lookup = missWithOutage();

        assertThat(lookup.hit()).isFalse();
        assertThat(lookup.layer()).isEqualTo(CacheService.Layer.NONE);
        assertThat(lookup.queryVector()).isNull();
        assertThat(registry.get("shoppilot_cache_embed_unavailable_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("没有向量也要写 L1：只跳过 L2 定位，并打点标出退化写回")
    void writeBackStillStoresL1WithoutVector() {
        CacheService.Lookup lookup = missWithOutage();

        Optional<CacheEntry> prepared = service.prepareWrite("T001", Intent.POLICY_RETURN, EPOCH, lookup,
                "签收次日起七天内可退", List.of("shop"), List.of("R-01"), "qwen2.5:3b");
        assertThat(prepared).isPresent();
        service.writeBack(prepared.get(), lookup);

        verify(l1).put(eq("key:T001:POLICY_RETURN"), any(CacheEntry.class));
        verifyNoInteractions(l2);
        assertThat(registry.get("shoppilot_cache_writeback_l1_only_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("有向量时两写齐全，且不记退化写回")
    void writeBackStoresBothLayersWithVector() {
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        when(l2.search(any(), anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        CacheService.Lookup lookup = service.lookup("T001", Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        CacheEntry entry = service.prepareWrite("T001", Intent.POLICY_RETURN, EPOCH, lookup,
                "签收次日起七天内可退", List.of("shop"), List.of("R-01"), "qwen2.5:3b").orElseThrow();
        service.writeBack(entry, lookup);

        verify(l1).put(anyString(), any(CacheEntry.class));
        verify(l2).store(any(float[].class), anyString(), any(CacheEntry.class));
        assertThat(registry.get("shoppilot_cache_writeback_l1_only_total").counter().count()).isZero();
    }

    @Test
    @DisplayName("L1 命中不碰 embedding：命中路径零远程向量化调用是链路性质，不是运气")
    void l1HitNeedsNoEmbedding() {
        CacheEntry platformEntry = CacheEntry.of("平台口径答案", Intent.POLICY_RETURN,
                RuleChunk.PLATFORM_TENANT, CacheService.SCOPE_PLATFORM, EPOCH,
                List.of("R-01"), "qwen2.5:3b", "七天无理由怎么退");
        when(l1.get(anyString())).thenReturn(Optional.of(platformEntry));

        CacheService.Lookup lookup = service.lookup("T001", Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        assertThat(lookup.hit()).isTrue();
        assertThat(lookup.layer()).isEqualTo(CacheService.Layer.L1);
        verifyNoInteractions(embedding);
    }

    @Test
    @DisplayName("引用全为平台条款才进跨店桶，掺本店规则就写本店桶")
    void writeBucketFollowsCitedScopes() {
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        CacheService.Lookup lookup = service.lookup("T001", Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        CacheEntry platform = service.prepareWrite("T001", Intent.POLICY_RETURN, EPOCH, lookup,
                "答案", List.of("PLATFORM", "platform"), List.of("R-01", "R-02"), "m").orElseThrow();
        assertThat(platform.tenantId()).isEqualTo(RuleChunk.PLATFORM_TENANT);
        assertThat(platform.scope()).isEqualTo(CacheService.SCOPE_PLATFORM);

        CacheEntry mixed = service.prepareWrite("T001", Intent.POLICY_RETURN, EPOCH, lookup,
                "答案", List.of("PLATFORM", "SHOP"), List.of("R-01", "R-09"), "m").orElseThrow();
        assertThat(mixed.tenantId()).isEqualTo("T001");
        assertThat(mixed.scope()).isEqualTo(CacheService.SCOPE_SHOP);
    }
}
