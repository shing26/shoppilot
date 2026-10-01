package com.shoppilot.bizmock.audit;

import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.AuditActions;
import com.shoppilot.tool.audit.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件对账门禁（round23 票 74 / ADR 0054）。
 *
 * <p>它量的是**发布与消费对不对得上**：发布数、落账数、pending 三者必须自洽。
 * ADR 0054 把事件列为第三类证据（指标、离线产物之外），这一格就是那个承诺的机器落点——
 * 收口审计的 G8 钉住本类，所以删掉它会让审计当场判红。
 *
 * <p><b>口径**：本门禁只在 JVM 层成立（所有者裁定 D——CI 无 Redis）。
 * 真实 Redis 上的 pending 积压与 MAXLEN 裁剪仍**未验证**，照登不摘红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:event-reconcile;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.internal-token=test-internal"
})
class EventReconciliationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    InMemoryAuditChannel channel;
    /** 生产里的幂等写入器（票 71）。门禁量的是真实那条链，所以去重也得走它。 */
    @Autowired
    AuditEventWriter writer;

    /**
     * 每个用例前把表与通道都排空。
     *
     * <p>这不是洁癖：通道是同一个 bean，被拒的事件会留在 pending 里，
     * 下一个用例一消费就会替上一个用例把那行插进去——然后撞唯一约束，
     * 报出来的红却在**这一个**用例身上（同 RoutingDispatchTest 里「一个用例的选择偷走另一个用例的断言」）。
     */
    @BeforeEach
    void reset() {
        // 事件属于 T001，而 @TenantId 守卫在「无请求上下文」时会以 PLATFORM 身份跑——
        // 所以必须真的把租户上下文设上，否则写库当场被拦（这条守卫拦得对，不是要绕开它）。
        TenantContextHolder.set("T001", "C001");
        jdbc.update("delete from audit_event");
        while (channel.consume("reset", e -> true) > 0) {
            // 排空 pending，循环到空为止
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @TestConfiguration
    static class InMemoryChannelWiring {
        @Bean
        @Primary
        AuditChannel auditChannel() {
            return new InMemoryAuditChannel();
        }
    }

    @Test
    @DisplayName("发布数 = 落账数，消费后 pending 归零")
    void publishedEventsAllLandAndPendingReturnsToZero() {
        List<AuditEvent> published = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AuditEvent event = event("REFUND_APPROVED", "refund-" + i);
            published.add(event);
            channel.publish(event);
        }

        int consumed = consumeAll();

        assertThat(consumed).as("消费条数 = 发布条数").isEqualTo(published.size());
        assertThat(channel.pendingCount()).as("全部 ack 后 pending 归零").isZero();
        assertThat(rows()).as("落账条数 = 发布条数").isEqualTo(published.size());
    }

    @Test
    @DisplayName("重投的事件不重复入账（幂等在唯一约束上，通道照样重投）")
    void redeliveredEventsAreNotCountedTwice() {
        AuditEvent event = event("FEEDBACK_REVIEWED", "fb-1");
        channel.publish(event);
        consumeAll();

        channel.redeliver(event);
        assertThat(consumeAll())
                .as("真实 Streams 会重投未 ack 的消息；通道若替消费端去重，唯一索引就永远没被考验到")
                .isEqualTo(1);
        assertThat(rows()).as("去重落在 event_id 唯一约束上，重投不产生第二行").isEqualTo(1);
    }

    @Test
    @DisplayName("handler 拒绝的事件留在 pending 里，对账时它既不算消费也不算落账")
    void rejectedEventsStayPendingAndAreNotCounted() {
        channel.publish(event("ROUTING_RULE_CREATED", "rule-1"));

        int rejected = channel.consume("gate", e -> false);

        assertThat(rejected).isZero();
        assertThat(channel.pendingCount()).isEqualTo(1);
        assertThat(rows()).isZero();

        assertThat(consumeAll()).as("改主意之后它仍能被收下").isEqualTo(1);
        assertThat(channel.pendingCount()).isZero();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("三件事同时成立：发布 = 消费 = 落账，且 pending 为零")
    void publishedEqualsConsumedEqualsLanded() {
        for (int i = 0; i < 3; i++) {
            channel.publish(event(AuditActions.REFUND_REJECTED, "rj-" + i));
        }
        int consumed = consumeAll();

        assertThat(consumed).isEqualTo(rows());
        assertThat(channel.pendingCount()).isZero();
    }

    /**
     * 消费并落账，形状与生产消费者一致（{@code AuditService.store}）：
     * 写入器让重复事件的约束冲突**逃出**事务边界，消费者按「已处理」catch 掉。
     *
     * <p>去重因此只发生在两处：唯一约束（跨进程重投）与这里（消费者视角）。
     * 通道一个都不做——它只负责重投。
     */
    private int consumeAll() {
        return channel.consume("gate", e -> {
            try {
                writer.write(e);
            } catch (org.springframework.dao.DataIntegrityViolationException alreadyLanded) {
                // 至少一次投递下重投是常态：撞了唯一约束就当已处理
            }
            return true;
        });
    }

    private long rows() {
        Long count = jdbc.queryForObject("select count(*) from audit_event", Long.class);
        return count == null ? 0L : count;
    }

    private static AuditEvent event(String action, String suffix) {
        return new AuditEvent(UUID.randomUUID().toString() + "-" + suffix, AuditEvent.CURRENT_SCHEMA, action,
                "REFUND", "id-" + suffix, "T001", AuditActions.SYSTEM_ACTOR, "gate", Instant.now());
    }
}