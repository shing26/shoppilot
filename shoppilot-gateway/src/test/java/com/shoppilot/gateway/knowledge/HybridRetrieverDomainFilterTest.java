package com.shoppilot.gateway.knowledge;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.Intent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * round33 票 125：检索可见范围必须有行业维度，且空召回不得把 intent 过滤放宽。
 *
 * <p>这两条分别对应两个已复现缺陷：酒店租户看到电商平台政策；酒店问句空召回后重检到电商条款。
 */
class HybridRetrieverDomainFilterTest {

    @Test
    @DisplayName("酒店租户检索过滤带 domain=HOTEL，平台电商条款不可能进入可见范围")
    void hotelRetrievalFiltersPlatformRulesByDomain() {
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        EsRestClient es = mock(EsRestClient.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f});
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(es.searchRaw(anyString(), any())).thenReturn(List.of());

        HybridRetriever retriever = new HybridRetriever(qdrant, es, embedding, epoch,
                properties(), new SimpleMeterRegistry());
        retriever.retrieve("押金什么时候退", "T004", Domain.HOTEL, Intent.POLICY_HOTEL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> qdrantFilter = ArgumentCaptor.forClass(Map.class);
        verify(qdrant).search(anyString(), any(float[].class), qdrantFilter.capture(), anyInt(), any());
        assertThat(qdrantFilter.getValue().toString()).contains("domain", "HOTEL");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> esBody = ArgumentCaptor.forClass(Map.class);
        verify(es).searchRaw(anyString(), esBody.capture());
        assertThat(esBody.getValue().toString()).contains("domain", "HOTEL");
    }

    @Test
    @DisplayName("空召回不再把 intent=null 重检：embedding 只打一次，诊断里 intentFilterRelaxed=false")
    void emptyRecallDoesNotRelaxIntentFilter() {
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        EsRestClient es = mock(EsRestClient.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f});
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(es.searchRaw(anyString(), any())).thenReturn(List.of());

        HybridRetriever retriever = new HybridRetriever(qdrant, es, embedding, epoch,
                properties(), new SimpleMeterRegistry());
        HybridRetriever.Diagnosis diagnosis =
                retriever.diagnose("押金什么时候退", "T004", Domain.HOTEL, Intent.POLICY_HOTEL, 5);

        assertThat(diagnosis.intentFilterRelaxed()).isFalse();
        verify(embedding, times(1)).embed(anyString());
    }

    @Test
    @DisplayName("词法召回的 _source 必须带 sourceDoc（2026-10-17：稠密腿降级时引用出处不能整列变空）")
    void lexicalRecallRequestsSourceDocForCitationDetails() {
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        EsRestClient es = mock(EsRestClient.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        when(embedding.embed(anyString())).thenReturn(new float[]{1.0f});
        when(qdrant.search(anyString(), any(float[].class), any(), anyInt(), any()))
                .thenReturn(List.of());
        when(es.searchRaw(anyString(), any())).thenReturn(List.of());

        HybridRetriever retriever = new HybridRetriever(qdrant, es, embedding, epoch,
                properties(), new SimpleMeterRegistry());
        retriever.retrieve("死蟹怎么赔付", "T002", Domain.ECOMMERCE, Intent.POLICY_FRESH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> esBody = ArgumentCaptor.forClass(Map.class);
        verify(es).searchRaw(anyString(), esBody.capture());
        // 缺 sourceDoc 的漏法是静默的：只有稠密腿降级（词法独扛）时才能从数据上看见，
        // 黑盒 QA 实测同一 ruleId 的 citationDetails.doc 在不同记录里空/非空混现。
        assertThat(esBody.getValue().get("_source").toString()).contains("sourceDoc", "headingPath");
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(
                "http://localhost:1", "http://localhost:2", "rules", "cache", "index", 20, 20, 5, 60));
        return properties;
    }
}
