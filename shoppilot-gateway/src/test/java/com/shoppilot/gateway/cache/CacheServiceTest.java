package com.shoppilot.gateway.cache;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.knowledge.ShopRuleIndex;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.startsWith;

/**
 * 缓存层在"向量服务不可用"时的退化边界。
 *
 * <p>要守住的性质是：embedding 挂掉只该让 L2 失效，不该让 L1 停止积累。L1 的 key 是
 * {@code MD5(租户+意图+纪元+归一化问法)}，与向量无关；以前 prepareWrite 一并要求
 * queryVector，等于把"本地向量服务抖一下"放大成"大促期间缓存整体失效"。
 *
 * <p>2026-10-17 起多一条：平台桶的读写要过 {@link ShopRuleIndex}——本行业该意图存在店铺级
 * 条款时，跨店共享格整体停用（实机复现的"跨店同问同答"缺陷，见下面两条新用例）。
 */
class CacheServiceTest {

    private static final long EPOCH = 7L;

    private L1Cache l1;
    private L2SemanticCache l2;
    private EmbeddingClient embedding;
    private ShopRuleIndex shopRules;
    private SimpleMeterRegistry registry;
    private CacheService service;

    @BeforeEach
    void setUp() {
        l1 = mock(L1Cache.class);
        l2 = mock(L2SemanticCache.class);
        embedding = mock(EmbeddingClient.class);
        shopRules = mock(ShopRuleIndex.class);
        registry = new SimpleMeterRegistry();
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.cache()).thenReturn(new GatewayProperties.Cache(true, Duration.ofMinutes(10),
                0.95d, Duration.ofSeconds(60), Duration.ofSeconds(30), true));
        service = new CacheService(l1, l2, embedding, shopRules, properties, registry);
        when(l1.key(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> "key:" + call.getArgument(0) + ":" + call.getArgument(1));
        // 默认口径 = 该行业该意图没有店铺级条款（跨店共享安全），旧用例按原语义继续考。
        when(shopRules.hasShopScopedRules(any(), any())).thenReturn(false);
    }

    private CacheService.Lookup missWithOutage() {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("Ollama 不可达"));
        return service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);
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

        Optional<CacheEntry> prepared = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, EPOCH,
                lookup, "签收次日起七天内可退", List.of("shop"), List.of("R-01"), List.of("七天无理由"),
                List.of("return-01-7day-basic"), "qwen2.5:3b");
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
        when(l2.search(any(), anyString(), anyString(), anyString(), any(), anyLong())).thenReturn(Optional.empty());
        CacheService.Lookup lookup =
                service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        CacheEntry entry = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, EPOCH, lookup,
                "签收次日起七天内可退", List.of("shop"), List.of("R-01"), List.of("七天无理由"),
                List.of("return-01-7day-basic"), "qwen2.5:3b").orElseThrow();
        service.writeBack(entry, lookup);

        verify(l1).put(anyString(), any(CacheEntry.class));
        verify(l2).store(any(float[].class), anyString(), any(CacheEntry.class));
        assertThat(registry.get("shoppilot_cache_writeback_l1_only_total").counter().count()).isZero();
    }

    @Test
    @DisplayName("L1 命中不碰 embedding：命中路径零远程向量化调用是链路性质，不是运气")
    void l1HitNeedsNoEmbedding() {
        CacheEntry platformEntry = CacheEntry.of("平台口径答案", Intent.POLICY_RETURN,
                CacheService.platformBucketId(Domain.ECOMMERCE), CacheService.SCOPE_PLATFORM, Domain.ECOMMERCE, EPOCH,
                List.of("R-01"), List.of("七天无理由"), List.of("return-01-7day-basic"), "qwen2.5:3b",
                "七天无理由怎么退");
        when(l1.get(anyString())).thenReturn(Optional.of(platformEntry));

        CacheService.Lookup lookup =
                service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        assertThat(lookup.hit()).isTrue();
        assertThat(lookup.layer()).isEqualTo(CacheService.Layer.L1);
        verifyNoInteractions(embedding);
    }

    @Test
    @DisplayName("引用全为平台条款、且本行业该意图无店铺级条款，才进跨店桶；掺本店规则就写本店桶")
    void writeBucketFollowsCitedScopes() {
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        CacheService.Lookup lookup =
                service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, "七天无理由怎么退", EPOCH, null);

        CacheEntry platform = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, EPOCH, lookup,
                "答案", List.of("PLATFORM", "platform"), List.of("R-01", "R-02"), List.of("", ""),
                List.of("", ""), "m").orElseThrow();
        assertThat(platform.tenantId()).isEqualTo("PLATFORM:ECOMMERCE");
        assertThat(platform.scope()).isEqualTo(CacheService.SCOPE_PLATFORM);

        CacheEntry mixed = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_RETURN, EPOCH, lookup,
                "答案", List.of("PLATFORM", "SHOP"), List.of("R-01", "R-09"), List.of("", ""),
                List.of("", ""), "m").orElseThrow();
        assertThat(mixed.tenantId()).isEqualTo("T001");
        assertThat(mixed.scope()).isEqualTo(CacheService.SCOPE_SHOP);
    }

    @Test
    @DisplayName("回归（跨店同问同答）：本行业该意图存在店铺级条款时，平台桶既不可读也不可写")
    void platformBucketIsDisabledWhenDomainHasShopScopedRules() {
        // 复现实机：T002 对 POLICY_FRESH 有 fresh-08 门店死蟹细则，T001 只引到平台条款；
        // 旧规则会把 T001 的答案写进 PLATFORM:ECOMMERCE，T002 再问同句直接命中、细则永不生效。
        when(shopRules.hasShopScopedRules(eq(Domain.ECOMMERCE), eq(Intent.POLICY_FRESH))).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        when(l2.search(any(), anyString(), anyString(), anyString(), any(), anyLong())).thenReturn(Optional.empty());
        // 平台桶里有一条修复前写下的旧答案（跨店污染的自愈性）：读侧不该再碰它
        CacheEntry stale = CacheEntry.of("平台口径的旧答案", Intent.POLICY_FRESH,
                CacheService.platformBucketId(Domain.ECOMMERCE), CacheService.SCOPE_PLATFORM, Domain.ECOMMERCE,
                EPOCH, List.of("R-01"), List.of("活鲜死亡"), List.of("fresh-06-seafood-dead"), "m", "死蟹怎么赔付");
        when(l1.get(anyString())).thenReturn(Optional.of(stale));

        CacheService.Lookup lookup =
                service.lookup("T002", Domain.ECOMMERCE, Intent.POLICY_FRESH, "死蟹怎么赔付", EPOCH, null);

        // 读：只查本店桶，平台桶的旧条目不再参与命中
        assertThat(lookup.hit()).isFalse();
        verify(l1, never()).get(startsWith("key:PLATFORM:"));
        verify(l1, atLeastOnce()).get("key:T002:POLICY_FRESH");

        // 写：引用全是平台条款也不行——本行业有店铺级条款，答案只对本店成立
        CacheEntry entry = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_FRESH, EPOCH, lookup,
                "按只数占比退款", List.of("PLATFORM", "PLATFORM"), List.of("R-01", "R-02"),
                List.of("活鲜死亡", "理赔比例"), List.of("fresh-06-seafood-dead", "fresh-02-claim-scope"),
                "m").orElseThrow();
        assertThat(entry.tenantId()).isEqualTo("T001");
        assertThat(entry.scope()).isEqualTo(CacheService.SCOPE_SHOP);
    }

    @Test
    @DisplayName("回归（跨店同问同答）：无店铺级条款的意图（如运费政策）平台桶照常跨店共享")
    void platformBucketStillSharedWhenDomainHasNoShopScopedRulesForIntent() {
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        CacheService.Lookup lookup =
                service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_SHIPPING, "退货运费谁承担", EPOCH, null);

        CacheEntry entry = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_SHIPPING, EPOCH, lookup,
                "无理由退货运费由买家承担", List.of("PLATFORM"), List.of("R-03"), List.of("退货运费承担"),
                List.of("return-03-shipping-cost"), "m").orElseThrow();

        assertThat(entry.tenantId()).isEqualTo("PLATFORM:ECOMMERCE");
        assertThat(entry.scope()).isEqualTo(CacheService.SCOPE_PLATFORM);
        verify(l1, atLeastOnce()).get(startsWith("key:PLATFORM:ECOMMERCE:"));
    }

    @Test
    @DisplayName("round33：平台桶带 domain，电商与酒店的同问法不会互相命中")
    void platformBucketIsScopedByDomain() {
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        CacheService.Lookup ecommerceLookup =
                service.lookup("T001", Domain.ECOMMERCE, Intent.POLICY_HOTEL, "押金什么时候退", EPOCH, null);

        CacheEntry hotel = service.prepareWrite("T001", Domain.ECOMMERCE, Intent.POLICY_HOTEL, EPOCH,
                ecommerceLookup, "电商口径", List.of("PLATFORM"), List.of("fresh-01"),
                List.of("生鲜理赔"), List.of("fresh-01-claim-window"), "m").orElseThrow();

        assertThat(hotel.domain()).isEqualTo(Domain.ECOMMERCE);
        // 平台桶身份把行业编进去（PLATFORM:<DOMAIN>），这正是两个行业不共用同一格的原因
        assertThat(hotel.tenantId()).isEqualTo(CacheService.platformBucketId(Domain.ECOMMERCE));

        service.lookup("T004", Domain.HOTEL, Intent.POLICY_HOTEL, "押金什么时候退", EPOCH, null);

        // 两个 domain 的 platform 桶 key 必须不同；这里用捕获到的 key 前缀证明不会复用同一条。
        verify(l1, atLeastOnce()).get(startsWith("key:PLATFORM:HOTEL:"));
        verify(l1, atLeastOnce()).get(startsWith("key:PLATFORM:ECOMMERCE:"));
    }

    @Test
    @DisplayName("scope 常量与 RuleChunk 同源：缓存桶身份不再各自写一份字面量")
    void scopeConstantsStayInSyncWithRuleChunk() {
        assertThat(CacheService.SCOPE_PLATFORM).isEqualTo(RuleChunk.SCOPE_PLATFORM);
        assertThat(CacheService.SCOPE_SHOP).isEqualTo(RuleChunk.SCOPE_SHOP);
    }
}
