package com.shoppilot.gateway.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round22 票 64 / ADR 0049：检索融合的录放回归门（层一，JVM 是唯一 owner）。
 *
 * <p>守什么：**排序流水线的确定性与四个常数、语料指纹、夹具完整性**。
 * <p>不守什么：**活体 hit@5 准确率**——所以它**不闭合** round19 登记第 5 项（那句触发原文要的是
 * 「可离线复跑的录制/回放路径」，而它要连模型一起回放，代价高一个量级）。
 *
 * <p>为什么是「重算」而不是「读期望」：夹具里 `dense` / `lexical` 是**输入**，`fused` 是输出。
 * 本用例由输入重算输出再比对 —— 只改夹具里的 `fused` 必红（ADR 0049 的第一道防假绿）。
 * 重算调用的是**生产那一份** `HybridRetriever.rrf`（为此把它与 `Scored` 放开到包级可见）：
 * 在测试里另写一份融合等于造出第二份判据。
 */
class RetrievalFusionReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture;
    private static Path fixturePath;
    private static GatewayProperties.Retrieval retrieval;
    private static HybridRetriever retriever;

    @BeforeAll
    static void loadFixtureAndProductionConfig() throws IOException {
        fixturePath = newestFixture();
        fixture = MAPPER.readTree(Files.readString(fixturePath, StandardCharsets.UTF_8));
        retrieval = retrievalFromProductionYaml();
        retriever = new HybridRetriever(null, null, null, null, properties(retrieval), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("夹具完整性：必须有 top-5 分歧，否则这份夹具对融合是 no-op（正是本票要修的病）")
    void fixtureMustContainDivergence() {
        long diverging = 0;
        for (JsonNode testCase : fixture.path("cases")) {
            List<String> dense = ids(testCase.path("dense"));
            List<String> fused = ids(testCase.path("fused"));
            if (!dense.subList(0, topK()).equals(fused.subList(0, topK()))) {
                diverging++;
            }
        }
        long total = fixture.path("cases").size();
        assertTrue(diverging > 0,
                "夹具 " + fixturePath.getFileName() + " 里没有任何一条 case 的 dense-topK 与 fused-topK 不同 —— "
                        + "对融合是 no-op，拿它守融合等于给自己发绿灯（原 16 条查询就是这个毛病）");
        System.out.printf("夹具 %s：%d/%d 条 case 在 top-%d 上 dense≠fused%n",
                fixturePath.getFileName(), diverging, total, topK());
    }

    @Test
    @DisplayName("重算融合：由夹具的两路序重算，top-K 前缀必须逐字等于夹具的 fused")
    void fusionRecomputationMatchesEveryRecordedCase() {
        int checked = 0;
        for (JsonNode testCase : fixture.path("cases")) {
            List<String> expected = ids(testCase.path("fused")).subList(0, topK());
            List<String> recomputed = fuse(ids(testCase.path("dense")), ids(testCase.path("lexical")));
            assertEquals(expected, recomputed,
                    "case " + testCase.path("id").asText() + " 的融合序与录制值不一致："
                            + "要么融合算法变了，要么夹具需要按新算法重录（重录是一次证据口径变更）");
            checked++;
        }
        assertEquals(fixture.path("cases").size(), checked, "应当逐条比对，不许中途跳过");
    }

    @Test
    @DisplayName("反证：把两路输入打乱，重算结果必须与录制值不同（断言真的对输入敏感）")
    void shuffledInputsDoNotReproduceTheRecordedFusion() {
        JsonNode target = firstDivergingCase();
        List<String> dense = new ArrayList<>(ids(target.path("dense")));
        List<String> lexical = new ArrayList<>(ids(target.path("lexical")));
        java.util.Collections.reverse(dense);
        java.util.Collections.reverse(lexical);
        List<String> recomputed = fuse(dense, lexical);
        List<String> recorded = ids(target.path("fused")).subList(0, topK());
        assertNotEquals(recorded, recomputed,
                "把两路序整个反转后仍然得到同一份 top-K —— 这条断言对输入不敏感，等于恒真");
    }

    @Test
    @DisplayName("四个常数从生产 application.yml 绑定，且与夹具录制时一致（改 yml 即红）")
    void constantsAreBoundFromProductionYamlAndMatchTheFixture() {
        JsonNode pinned = fixture.path("constants");
        assertEquals(pinned.path("rrfK").asInt(), retrieval.rrfK(), "shoppilot.retrieval.rrf-k 与夹具不一致");
        assertEquals(pinned.path("denseTopK").asInt(), retrieval.denseTopK(), "dense-top-k 与夹具不一致");
        assertEquals(pinned.path("lexicalTopK").asInt(), retrieval.lexicalTopK(), "lexical-top-k 与夹具不一致");
        assertEquals(pinned.path("fusedTopK").asInt(), retrieval.fusedTopK(), "fused-top-k 与夹具不一致");
    }

    @Test
    @DisplayName("语料指纹现算相等（改任一 knowledge/*.md 即红）")
    void corpusFingerprintMatchesTheFixture() throws Exception {
        assertEquals(fixture.path("corpus_sha256").asText(), corpusSha256(),
                "knowledge/ 下的语料与录制夹具时不一致：夹具是按当时那份语料录的，语料一改就要重录");
    }

    // ---- 辅助 -----------------------------------------------------------------

    private static int topK() {
        return fixture.path("constants").path("fusedTopK").asInt();
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /** 用生产实现重算融合，并按生产口径截到 fusedTopK。 */
    private static List<String> fuse(List<String> dense, List<String> lexical) {
        List<HybridRetriever.Retrieved> fused = retriever.rrf(scored(dense), scored(lexical));
        return fused.stream().map(HybridRetriever.Retrieved::ruleId).limit(topK()).toList();
    }

    /** rank 就是列表下标 —— 探针给出的两路序本身就是名次序。 */
    private static List<HybridRetriever.Scored> scored(List<String> ruleIds) {
        List<HybridRetriever.Scored> scored = new ArrayList<>();
        for (int i = 0; i < ruleIds.size(); i++) {
            scored.add(new HybridRetriever.Scored(ruleIds.get(i), i, "PLATFORM", "", ""));
        }
        return scored;
    }

    private static JsonNode firstDivergingCase() {
        for (JsonNode testCase : fixture.path("cases")) {
            List<String> dense = ids(testCase.path("dense"));
            List<String> fused = ids(testCase.path("fused"));
            if (!dense.subList(0, topK()).equals(fused.subList(0, topK()))) {
                return testCase;
            }
        }
        throw new AssertionError("夹具里没有分歧 case，反证无从下手（夹具完整性那条用例应当先红）");
    }

    private static GatewayProperties properties(GatewayProperties.Retrieval value) {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(value);
        return properties;
    }

    /** 用 Spring 自己的 Binder 读生产 yml —— 不是正则抄一份，yml 写坏了这里也会红。 */
    private static GatewayProperties.Retrieval retrievalFromProductionYaml() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
        GatewayProperties bound = Binder.get(environment).bind("shoppilot", GatewayProperties.class).get();
        assertNotNull(bound, "application.yml 里的 shoppilot 段绑定失败");
        return bound.retrieval();
    }

    /** 与 scripts/provenance.py 同一定义：knowledge/*.md 按文件名排序，逐个拼进摘要。 */
    private static String corpusSha256() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.list(repoRoot().resolve("knowledge"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** 夹具家族是 append-only：取**最新**那一份，与 scripts/retrieval_gate.py 同一口径。 */
    private static Path newestFixture() throws IOException {
        Path dir = repoRoot().resolve("eval");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> fixtures = files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("retrieval-fixture-") && name.endsWith(".json");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
            assertFalse(fixtures.isEmpty(),
                    "eval/ 下没有 retrieval-fixture-*.json：先跑 python scripts/record_retrieval_fixture.py");
            return fixtures.get(0);
        }
    }

    /** surefire 的工作目录是模块目录，往上找到含 eval/ 与 knowledge/ 的那一层。 */
    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("eval")) && Files.isDirectory(current.resolve("knowledge"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根（含 eval/ 与 knowledge/）");
    }
}
