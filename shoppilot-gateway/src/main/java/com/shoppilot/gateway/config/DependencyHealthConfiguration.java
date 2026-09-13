package com.shoppilot.gateway.config;

import com.shoppilot.gateway.knowledge.EsRestClient;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可降级依赖的真实状态，挂在 group {@code deps} 下而不是 readiness 里（ADR 0026）。
 *
 * <p>为什么不进就绪门：{@code scripts/run-acceptance.ps1} 拿 {@code /actuator/health/readiness} 当每个
 * Need 步的放行谓词，{@code up.ps1} 等的是同一个端点，而 {@code nocache}、{@code no-ollama} 这些实验
 * profile 存在的意义就是把某个依赖人为弄残、去证明降级路径成立。把这三个指示器塞进 readiness，
 * 等于用运维口把已经逐点写明的 fail-open 取向改回 fail-closed。真要上 K8s 希望 ES 挂时摘流量，
 * 改一行配置把 deps 并进 readiness 即可，不用动代码。
 *
 * <p>三格各自回答一个问题，不许混：{@code qdrant} 与 {@code elasticsearch} 问的是**引擎可达**，
 * {@code knowledgeBase} 问的是**语料在位**。引擎活着而库里 0 块时答案会退化成「未检索到相关条款」，
 * 那一格必须自己红，不能躲在两个 ping 后面报绿。
 */
@Configuration
public class DependencyHealthConfiguration {

    @Bean
    HealthIndicator qdrantHealthIndicator(QdrantRestClient qdrant, GatewayProperties properties) {
        GatewayProperties.Retrieval retrieval = properties.retrieval();
        return () -> report(qdrant.ping(),
                Map.of("endpoint", retrieval.qdrantUrl(), "collection", retrieval.ruleCollection()));
    }

    @Bean
    HealthIndicator elasticsearchHealthIndicator(EsRestClient es, GatewayProperties properties) {
        return () -> report(es.ping(), Map.of("endpoint", properties.retrieval().esUrl()));
    }

    @Bean
    HealthIndicator knowledgeBaseHealthIndicator(QdrantRestClient qdrant, EsRestClient es, KbEpoch kbEpoch,
                                                 GatewayProperties properties) {
        return () -> {
            GatewayProperties.Retrieval retrieval = properties.retrieval();
            Map<String, Object> details = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            details.put("kbEpoch", kbEpoch.current());
            try {
                long ruleChunks = qdrant.count(retrieval.ruleCollection());
                details.put("vectorRuleChunks", ruleChunks);
                if (ruleChunks == 0) {
                    problems.add("向量侧规则块 0 块");
                }
            } catch (RuntimeException vectorQueryFailed) {
                details.put("vectorError", String.valueOf(vectorQueryFailed.getMessage()));
                problems.add("向量侧计数取不到");
            }
            long lexicalDocs = es.count(retrieval.esIndex());
            details.put("lexicalDocs", lexicalDocs);
            if (lexicalDocs == 0) {
                problems.add("词法侧文档 0 篇（含索引不在）");
            }
            if (!problems.isEmpty()) {
                details.put("reason", String.join("；", problems));
            }
            return report(problems.isEmpty(), details);
        };
    }

    private static Health report(
            boolean usable, Map<String, ?> details) {
        return Health.status(usable ? Status.UP : Status.DOWN)
                .withDetail("usable", usable)
                .withDetails(details)
                .build();
    }
}
