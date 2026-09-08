package com.shoppilot.gateway.ingest;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.EsRestClient;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import com.shoppilot.gateway.knowledge.RuleChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 离线入库：一条命令把政策 Markdown 切分、向量化并写入 Qdrant 与 ES（ticket 04）。
 *
 * <p>纪元处理是这里唯一容易被想错的点：新语料写进 {@code current + 1}，全部成功后才推进纪元。
 * 先写后推意味着读侧在整个入库过程中一直服务旧纪元的一致快照，推纪元等于蓝绿发布切流量；
 * 反过来"入库后推进"会让刚写进去的条目立刻变成陈旧数据。
 */
@Component
@Profile("ingest")
@Order(100)
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);
    private static final int BATCH = 25;

    private final EmbeddingClient embedding;
    private final QdrantRestClient qdrant;
    private final EsRestClient es;
    private final KbEpoch kbEpoch;
    private final GatewayProperties properties;

    public IngestRunner(EmbeddingClient embedding, QdrantRestClient qdrant, EsRestClient es, KbEpoch kbEpoch,
                        GatewayProperties properties) {
        this.embedding = embedding;
        this.qdrant = qdrant;
        this.es = es;
        this.kbEpoch = kbEpoch;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        long started = System.nanoTime();
        try {
            ingest();
            log.info("入库完成，耗时 {} 秒", (System.nanoTime() - started) / 1_000_000_000);
        } catch (DependencyMissing missing) {
            // 依赖没起来是操作问题，给一条能照着做的提示，不甩堆栈
            log.error("入库中止：{}", missing.getMessage());
            System.exit(1);
        } catch (Exception failure) {
            log.error("入库失败：{}", failure.getMessage());
            System.exit(1);
        }
    }

    private void ingest() throws Exception {
        Path dir = Path.of(properties.ingest().dir()).toAbsolutePath();
        if (!Files.isDirectory(dir)) {
            throw new DependencyMissing("政策语料目录不存在：" + dir + "。请在仓库根目录执行，"
                    + "或用 --shoppilot.ingest.dir=<路径> 指定。");
        }
        probeDependenciesOrThrow();

        long current = kbEpoch.current();
        long target = current + 1;
        List<RuleChunk> chunks = new ArrayList<>();
        List<Path> docs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(docs::add);
        }
        for (Path doc : docs) {
            chunks.addAll(MarkdownChunker.chunk(doc, target));
        }
        log.info("切分完成：文档 {} 篇，规则块 {} 块，目标纪元 {}", docs.size(), chunks.size(), target);

        Map<String, Integer> scopeCounts = new TreeMap<>();
        Map<String, Integer> intentCounts = new TreeMap<>();
        List<QdrantRestClient.Point> points = new ArrayList<>();
        int embedded = 0;
        for (RuleChunk chunk : chunks) {
            float[] vector = embedding.embedWarmup(chunk.text());
            points.add(new QdrantRestClient.Point(qdrantPointId(chunk.ruleId()), vector, payload(chunk)));
            es.index(properties.retrieval().esIndex(), chunk.ruleId(), esDocument(chunk));
            scopeCounts.merge(chunk.scope(), 1, Integer::sum);
            intentCounts.merge(chunk.intent(), 1, Integer::sum);
            if (++embedded % BATCH == 0) {
                qdrant.upsert(properties.retrieval().ruleCollection(), points);
                points.clear();
                log.info("已入库 {}/{} 块", embedded, chunks.size());
            }
        }
        if (!points.isEmpty()) {
            qdrant.upsert(properties.retrieval().ruleCollection(), points);
        }

        long bumped = kbEpoch.bump();
        if (bumped != target) {
            log.warn("纪元推进结果为 {}，与预期的 {} 不一致，请检查是否有并发入库任务", bumped, target);
        }
        log.info("统计 文档={} 规则块={} scope分布={} 意图分布={} 旧纪元={} 新纪元={} ES总数={} Qdrant总数={}",
                docs.size(), chunks.size(), scopeCounts, intentCounts, current, bumped,
                es.count(properties.retrieval().esIndex()),
                qdrant.count(properties.retrieval().ruleCollection()));
    }

    private void probeDependenciesOrThrow() {
        try {
            embedding.embedWarmup("连通性探测");
        } catch (RuntimeException failure) {
            throw new DependencyMissing("本地向量模型不可用（" + properties.embedding().baseUrl() + " / "
                    + properties.embedding().model() + "）。请先启动 Ollama 并执行 ollama pull "
                    + properties.embedding().model() + "。");
        }
        try {
            if (!qdrant.ping()) {
                throw new DependencyMissing("Qdrant 无响应（" + properties.retrieval().qdrantUrl()
                        + "）。请先执行 docker compose up -d。");
            }
        } catch (RuntimeException failure) {
            throw new DependencyMissing("Qdrant 不可用（" + properties.retrieval().qdrantUrl() + "）："
                    + failure.getMessage());
        }
        try {
            es.ensureIndex(properties.retrieval().esIndex());
        } catch (RuntimeException failure) {
            throw new DependencyMissing("Elasticsearch 不可用（" + properties.retrieval().esUrl()
                    + "）。请先执行 docker compose up -d。");
        }
    }

    /** Qdrant 的 point id 只接受无符号整数或 UUID，因此用 ruleId 派生的确定性 UUID：重跑仍是幂等 upsert。 */
    private static String qdrantPointId(String ruleId) {
        return UUID.nameUUIDFromBytes(ruleId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> payload(RuleChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rule_id", chunk.ruleId());
        payload.put("source_doc", chunk.sourceDoc());
        payload.put("heading_path", chunk.headingPath());
        payload.put("title", chunk.title());
        payload.put("text", chunk.text());
        payload.put("rule_type", chunk.ruleType());
        payload.put("applicable_category", chunk.applicableCategory());
        payload.put("scope", chunk.scope());
        payload.put("tenant_id", chunk.tenantId());
        payload.put("intent", chunk.intent());
        payload.put("effective_from", chunk.effectiveFrom());
        payload.put("kb_epoch", chunk.kbEpoch());
        return payload;
    }

    private static Map<String, Object> esDocument(RuleChunk chunk) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("ruleId", chunk.ruleId());
        document.put("sourceDoc", chunk.sourceDoc());
        document.put("headingPath", chunk.headingPath());
        document.put("title", chunk.title());
        document.put("text", chunk.text());
        document.put("ruleType", chunk.ruleType());
        document.put("applicableCategory", chunk.applicableCategory());
        document.put("scope", chunk.scope());
        document.put("tenantId", chunk.tenantId());
        document.put("intent", chunk.intent());
        document.put("effectiveFrom", chunk.effectiveFrom());
        document.put("kbEpoch", chunk.kbEpoch());
        return document;
    }

    /** 依赖缺失与代码缺陷要分开：前者给提示，后者才给堆栈。 */
    private static final class DependencyMissing extends RuntimeException {
        private DependencyMissing(String message) {
            super(message);
        }
    }
}
