package com.shoppilot.gateway.config;

import com.shoppilot.gateway.cache.L2SemanticCache;
import com.shoppilot.gateway.knowledge.EsRestClient;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把两个向量集合与 ES 索引准备好，省掉"第一次请求才发现基础设施没建表"这类假故障。
 *
 * <p>这里刻意不让启动失败：中间件没起来时网关仍应能以降级模式对外，故障演练（ticket 14）
 * 依赖这个行为。真正的错误会在每次检索时暴露。
 */
@Component
@Order(10)
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final QdrantRestClient qdrant;
    private final EsRestClient es;
    private final L2SemanticCache l2;
    private final KbEpoch kbEpoch;
    private final GatewayProperties properties;

    public StartupInitializer(QdrantRestClient qdrant, EsRestClient es, L2SemanticCache l2, KbEpoch kbEpoch,
                              GatewayProperties properties) {
        this.qdrant = qdrant;
        this.es = es;
        this.l2 = l2;
        this.kbEpoch = kbEpoch;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        GatewayProperties.Retrieval retrieval = properties.retrieval();
        try {
            qdrant.ensureCollection(retrieval.ruleCollection(), properties.embedding().dimension(),
                    List.of("tenant_id", "scope", "intent", "rule_type", "applicable_category", "source_doc"),
                    List.of("kb_epoch"));
            l2.ensureCollection();
            es.ensureIndex(retrieval.esIndex());
            long purged = l2.purgeBeforeEpoch(kbEpoch.current());
            log.info("检索基础设施就绪: qdrant={}/{}, es={}, 规则块={}, 词法文档={}, 清理旧纪元缓存点={}",
                    retrieval.qdrantUrl(), retrieval.ruleCollection(), retrieval.esUrl(),
                    qdrant.count(retrieval.ruleCollection()), es.count(retrieval.esIndex()), purged);
        } catch (RuntimeException infrastructureUnavailable) {
            log.warn("检索基础设施未就绪，网关以降级模式启动: {}", infrastructureUnavailable.getMessage());
        }
    }
}
