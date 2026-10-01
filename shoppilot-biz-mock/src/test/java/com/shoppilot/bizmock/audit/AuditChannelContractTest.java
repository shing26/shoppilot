package com.shoppilot.bizmock.audit;

import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.audit.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计通道的契约（round23 票 71 / 所有者裁定 D）。
 *
 * <p>这套用例**只依赖 {@link AuditChannel} 端口**，所以任何实现都要跑同一组语义。
 * 当前只有内存实现能在这里跑（CI 无 Redis），Redis 实现的那一份**本轮照登未达成**——
 * 见 {@link #productionImplementationIsTheRedisOne()} 与票 71 的 Handoff。
 */
class AuditChannelContractTest {

    private final InMemoryAuditChannel channel = new InMemoryAuditChannel();

    @Test
    @DisplayName("发布的事件能被消费到，顺序不变")
    void publishThenConsumeKeepsOrder() {
        channel.publish(event("REFUND_APPROVED", "1"));
        channel.publish(event("REFUND_REJECTED", "2"));

        List<String> seen = new ArrayList<>();
        int processed = channel.consume("c1", e -> {
            seen.add(e.action());
            return true;
        });

        assertThat(processed).isEqualTo(2);
        assertThat(seen).containsExactly("REFUND_APPROVED", "REFUND_REJECTED");
        assertThat(channel.pendingCount()).as("ack 之后不留 pending").isZero();
    }

    @Test
    @DisplayName("handler 拒绝的事件留在 pending 里，下次还在")
    void rejectedStaysPending() {
        channel.publish(event("REFUND_APPROVED", "1"));

        assertThat(channel.consume("c1", e -> false)).isZero();
        assertThat(channel.pendingCount()).as("不 ack 就要留在 pending 等人看").isEqualTo(1);

        assertThat(channel.consume("c1", e -> true)).isEqualTo(1);
        assertThat(channel.pendingCount()).isZero();
    }

    @Test
    @DisplayName("重投会被再次交付——通道不去重，去重是消费端的活")
    void redeliveryIsDeliveredAgain() {
        AuditEvent once = event("FEEDBACK_REVIEWED", "1");
        channel.publish(once);
        assertThat(channel.consume("c1", e -> true)).isEqualTo(1);

        channel.redeliver(once);
        assertThat(channel.consume("c1", e -> true))
                .as("真实 Streams 会重投未 ack 的消息；通道若替消费端去重，`event_id` 唯一索引就永远没被考验到")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("通道不可用时 publish 直接失败——调用方据此走直写兜底")
    void unavailableChannelRefusesPublish() {
        channel.unavailable();

        assertThat(channel.available()).isFalse();
        assertThatThrownBy(() -> channel.publish(event("ROUTING_RULE_CREATED", "1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("没有 eventId 或 action 的事件构造时就该被拒——它们是幂等与查询的主键")
    void eventRejectsBlankIdentity() {
        assertThatThrownBy(() -> new AuditEvent(null, 1, "A", "T", "1", "T001", "actor", null,
                java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent("e1", 1, " ", "T", "1", "T001", "actor", null,
                java.time.Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("主源码里只有 Redis 一个实现：内存实现留在测试源集，跨进程那条路才成立")
    void productionImplementationIsTheRedisOne() throws Exception {
        Path mainSources = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(mainSources)).as("从模块目录运行；找不到 src/main/java 说明跑错目录了").isTrue();

        List<String> implementations;
        try (Stream<Path> files = Files.walk(mainSources)) {
            implementations = files.filter(path -> path.toString().endsWith(".java"))
                    // lambda 里不能抛 IOException，读失败就当它不是实现类（真读失败时断言会因为「不是唯一」而红）
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("implements AuditChannel");
                        } catch (IOException unreadable) {
                            return false;
                        }
                    })
                    .map(path -> mainSources.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        assertThat(implementations)
                .as("主源码里的 AuditChannel 实现必须只有 Redis 那一个；内存实现挪进来这条就红")
                .containsExactly("com/shoppilot/bizmock/audit/RedisAuditChannel.java");
    }

    /** 内存实现自己必须在测试源集里——上面那条断言成立的前提是它没被挪进主源码。 */
    @Test
    @DisplayName("内存实现住在测试源集里")
    void inMemoryImplementationLivesInTestSources() throws Exception {
        Path testSources = Path.of("src", "test", "java");
        assertThat(Files.exists(testSources.resolve("com/shoppilot/bizmock/audit/InMemoryAuditChannel.java")))
                .as("内存实现必须在 src/test/java")
                .isTrue();
        assertThat(Files.exists(Path.of("src", "main", "java", "com/shoppilot/bizmock/audit",
                "InMemoryAuditChannel.java")))
                .as("主源码里不允许出现内存实现")
                .isFalse();
    }

    private static AuditEvent event(String action, String suffix) {
        return new AuditEvent(UUID.randomUUID().toString() + "-" + suffix, AuditEvent.CURRENT_SCHEMA, action,
                "REFUND", "id-" + suffix, "T001", AuditActions.SYSTEM_ACTOR, "d", java.time.Instant.now());
    }
}