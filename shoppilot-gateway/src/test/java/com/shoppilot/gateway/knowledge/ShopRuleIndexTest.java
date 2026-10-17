package com.shoppilot.gateway.knowledge;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-17 跨店同问同答缺陷修复的探针门：它决定平台桶能不能跨店共享。
 *
 * <p>要守住三条：① 查询确实按 scope=SHOP + 行业 + 意图 + 当前纪元过滤（漏掉任一项都可能
 * 把"别家有门店细则"误判成"没有"）；② 结果按纪元记忆，语料换代后必须重问；
 * ③ 探针失败 fail-closed 当"存在店铺级条款"——隔离比命中率值钱。
 */
class ShopRuleIndexTest {

    @Test
    @DisplayName("探针按 scope=SHOP + domain + intent + 当前纪元过滤，命中即返回 true")
    void probeFiltersByScopeDomainIntentAndEpoch() {
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(es.searchRaw(anyString(), any())).thenReturn(List.of(
                new EsRestClient.Hit("fresh-08", 1.0d, "正文", "标题", "POLICY", "ALL", "SHOP",
                        "鲜果驿站死蟹理赔门店细则", "fresh-08-shop-t002-dead-crab")));

        ShopRuleIndex index = new ShopRuleIndex(es, epoch, properties());
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, Intent.POLICY_FRESH)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(es).searchRaw(eq("shoppilot-policy"), body.capture());
        String filter = body.getValue().toString();
        assertThat(filter).contains("SHOP", "ECOMMERCE", "POLICY_FRESH", "kbEpoch");
    }

    @Test
    @DisplayName("无店铺级条款返回 false；同一纪元内结果被记忆，不反复问 ES")
    void probeMemoizesPerEpoch() {
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(es.searchRaw(anyString(), any())).thenReturn(List.of());

        ShopRuleIndex index = new ShopRuleIndex(es, epoch, properties());
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, Intent.POLICY_SHIPPING)).isFalse();
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, Intent.POLICY_SHIPPING)).isFalse();
        verify(es, times(1)).searchRaw(anyString(), any());

        // 纪元推进 = 语料换代：旧答案作废的同一条边界，探针结果也必须重问
        when(epoch.current()).thenReturn(8L);
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, Intent.POLICY_SHIPPING)).isFalse();
        verify(es, times(2)).searchRaw(anyString(), any());
    }

    @Test
    @DisplayName("探针失败 fail-closed：当存在店铺级条款处理，不把 ES 抖动放大成跨店共享")
    void probeFailureFailsClosed() {
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(es.searchRaw(anyString(), any())).thenThrow(new IllegalStateException("ES 不可达"));

        ShopRuleIndex index = new ShopRuleIndex(es, epoch, properties());
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, Intent.POLICY_FRESH)).isTrue();
    }

    @Test
    @DisplayName("意图未定案（null）不共享：没有意图就没有可见范围可谈")
    void nullIntentIsNeverShareable() {
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);

        ShopRuleIndex index = new ShopRuleIndex(es, epoch, properties());
        assertThat(index.hasShopScopedRules(Domain.ECOMMERCE, null)).isTrue();
        verify(es, never()).searchRaw(anyString(), any());
    }

    @Test
    @DisplayName("domain 为 null 按 UNKNOWN 查：未登记行业 fail-closed 只看本租户，共享同样按 UNKNOWN 分区")
    void nullDomainQueriesUnknownPartition() {
        EsRestClient es = mock(EsRestClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(es.searchRaw(anyString(), any())).thenReturn(List.of());

        ShopRuleIndex index = new ShopRuleIndex(es, epoch, properties());
        assertThat(index.hasShopScopedRules(null, Intent.POLICY_RETURN)).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(es).searchRaw(eq("shoppilot-policy"), body.capture());
        assertThat(body.getValue().toString()).contains("UNKNOWN");
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(
                "http://localhost:1", "http://localhost:2", "rules", "cache", "shoppilot-policy", 20, 20, 5, 60));
        return properties;
    }
}
