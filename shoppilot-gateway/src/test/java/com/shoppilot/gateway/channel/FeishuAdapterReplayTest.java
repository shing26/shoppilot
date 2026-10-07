package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * round31 票 97 / ADR 0060 决策 4：飞书入站事件的录放门（0 token、干净 runner 可复现）。
 *
 * <p>同 ADR 0049 检索录放门的纪律：夹具（{@code eval/im-events/}）录的是**平台原样事件**，
 * 是输入不是期望；期望（归一结果、派生会话 id、派生 clientToken 三样）钉在本测试里，
 * 改夹具必红。夹具**append-only**：清单与内容 sha256 钉在 {@link #fixturesAreAppendOnly()}，
 * 新增或修改夹具必须显式更新那一行的哈希——diff 里可见，顺手改夹具让门变绿这条路被堵死。
 *
 * <p>门禁同时断 spec §3 的两条「不成立也算数」：跨渠道会话不串、幂等不靠参数猜。
 * 本门**不守**长连接本身（那是票 113/115 的活体）——它守归一契约的确定性。
 */
class FeishuAdapterReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FeishuAdapter FEISHU = new FeishuAdapter();
    private static final WebhookAdapter WEBHOOK = new WebhookAdapter();
    private static Path repoRoot;

    @AfterEach
    void clearChannelContext() {
        // 线程复用必须显式清理（与 TenantContext 同纪律）
        ChannelContext.clear();
    }

    @BeforeAll
    static void locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !(Files.isDirectory(current.resolve("eval"))
                && Files.isDirectory(current.resolve("knowledge")))) {
            current = current.getParent();
        }
        org.junit.jupiter.api.Assertions.assertNotNull(current,
                "从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根（含 eval/ 与 knowledge/）");
        repoRoot = current;
    }

    private static Map<String, JsonNode> loadFixtures() throws IOException {
        Map<String, JsonNode> fixtures = new TreeMap<>();
        try (Stream<Path> files = Files.list(repoRoot.resolve("eval").resolve("im-events"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            fixtures.put(p.getFileName().toString(),
                                    MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8)));
                        } catch (IOException e) {
                            throw new IllegalStateException("读夹具失败：" + p, e);
                        }
                    });
        }
        return fixtures;
    }

    @Test
    @DisplayName("夹具 append-only：清单与内容 sha256 钉死，改夹具必红（同 ADR 0049 的哈希钉）")
    void fixturesAreAppendOnly() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.list(repoRoot.resolve("eval").resolve("im-events"))) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            digest.update(p.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                            digest.update(Files.readAllBytes(p));
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        assertThat(hex.toString())
                .as("eval/im-events/ 夹具清单变化了：这是 append-only 目录，新增夹具要显式更新本哈希并在票面登记")
                .isEqualTo("5f2cda7673dd5cf186021c40c302dec7d6a01b3b2775413c67544f5a055b7b08");
    }

    @Test
    @DisplayName("归一三样：飞书 p2p 文本事件 → query 逐字、会话 id 与 clientToken 派生钉死")
    void feishuTextEventsNormalizeToPinnedExpectations() throws Exception {
        Map<String, JsonNode> fixtures = loadFixtures();

        ChannelAdapter.NormalizedChat first = FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-text-01.json"), Map.class));
        assertThat(first.query()).isEqualTo("七天无理由退货怎么操作");
        assertThat(first.conversationId()).isEqualTo("feishu:chat:oc_chat0001");
        assertThat(first.clientToken()).isEqualTo("feishu:msg:om_case000001");
        assertThat(first.contact()).isNull();
        assertThat(first.idempotencyToken()).isNull();

        ChannelAdapter.NormalizedChat second = FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-text-02.json"), Map.class));
        assertThat(second.query()).isEqualTo("订单90002发什么快递");
        // 同一段聊天（oc_chat0001）→ 同一段会话；不同 message id → 不同幂等 token
        assertThat(second.conversationId()).isEqualTo("feishu:chat:oc_chat0001");
        assertThat(second.clientToken()).isEqualTo("feishu:msg:om_case000002");
    }

    @Test
    @DisplayName("spec §3 之一 跨渠道会话不串：同人同参数，飞书与 webhook 的会话键不同")
    void sessionsDoNotLeakAcrossPlatforms() throws Exception {
        Map<String, JsonNode> fixtures = loadFixtures();
        ChannelAdapter.NormalizedChat feishu = FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-text-01.json"), Map.class));
        // webhook 适配器的渠道标签来自 ChannelContext（入站端点设置）——补上真实链路形态；
        // 飞书适配器不读它（长连接线程没有这个上下文），正是两家的结构差异
        ChannelContext.set(Channel.WEBHOOK);
        ChannelAdapter.NormalizedChat webhook = WEBHOOK.normalize(MAPPER.convertValue(
                fixtures.get("webhook-text-01.json"), Map.class));

        // 同一句诉求（query 相同），但两个平台各自有各自的会话——session 键绝不共用
        assertThat(feishu.query()).isEqualTo(webhook.query());
        assertThat(feishu.conversationId()).startsWith("feishu:");
        assertThat(webhook.conversationId()).startsWith("webhook:");
        assertThat(feishu.conversationId()).isNotEqualTo(webhook.conversationId());

        // 飞书侧另一个人（oc_chat0002）与第一人也不同段
        ChannelAdapter.NormalizedChat third = FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-text-03.json"), Map.class));
        assertThat(third.conversationId()).isEqualTo("feishu:chat:oc_chat0002");
        assertThat(third.conversationId()).isNotEqualTo(feishu.conversationId());
    }

    @Test
    @DisplayName("spec §3 之二 幂等不靠参数猜：同参数不同 message id → 不同 clientToken，两次都执行")
    void idempotencyDoesNotGuessFromArguments() throws Exception {
        Map<String, JsonNode> fixtures = loadFixtures();
        ChannelAdapter.NormalizedChat feishu = FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-text-01.json"), Map.class));
        ChannelContext.set(Channel.WEBHOOK);
        ChannelAdapter.NormalizedChat webhook = WEBHOOK.normalize(MAPPER.convertValue(
                fixtures.get("webhook-text-01.json"), Map.class));

        // 同一句诉求、不同的平台消息 id → 幂等键不同 → 两次都执行（而不是第二次判重放）。
        // 幂等层「clientToken 优先于参数派生」的语义由 IdempotencyServiceRequestReplayTest 钉住。
        assertThat(feishu.query()).isEqualTo(webhook.query());
        assertThat(feishu.clientToken()).isEqualTo("feishu:msg:om_case000001");
        assertThat(webhook.clientToken()).isEqualTo("webhook:msg:msg-webhook-01");
        assertThat(feishu.clientToken()).isNotEqualTo(webhook.clientToken());
    }

    @Test
    @DisplayName("范围外形状当场拒：非 text、坏 content——不静默吞（round31 spec §5）")
    void outOfScopeShapesAreRejected() throws Exception {
        Map<String, JsonNode> fixtures = loadFixtures();

        assertThatThrownBy(() -> FEISHU.normalize(MAPPER.convertValue(
                fixtures.get("feishu-p2p-image-04.json"), Map.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 text");

        Map<String, Object> brokenContent = Map.of("event", Map.of("message", Map.of(
                "message_id", "om_case000009", "chat_id", "oc_chat0001",
                "chat_type", "p2p", "message_type", "text", "content", "not-json-at-all")));
        assertThatThrownBy(() -> FEISHU.normalize(brokenContent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text 字段");

        Map<String, Object> notFeishu = Map.of("query", "这是 webhook 形状不是飞书事件");
        assertThatThrownBy(() -> FEISHU.normalize(notFeishu))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("飞书");
    }

    @Test
    @DisplayName("零暴露红线：飞书不从 HTTP 路径解析出来，/webhook/feishu 一律 400 形状")
    void feishuHasNoInboundHttpPath() {
        assertThat(Channel.fromPath("feishu")).isNull();
        assertThat(Channel.fromPath("app")).isEqualTo(Channel.APP);
        assertThat(Channel.FEISHU.label()).isEqualTo("feishu");
    }
}
