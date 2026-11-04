package com.shoppilot.ticket.integration;

import com.shoppilot.ticket.audit.AuditPublisher;
import com.shoppilot.ticket.audit.OutboundPublisher;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.audit.Actor;
import com.shoppilot.tool.audit.AuditTopics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.shoppilot.tool.audit.AuditTopics.CHANNEL_OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 两条 Redis Stream 的真实 XADD 语义（round48 票 204 / ADR 0083）。
 *
 * <p>现有用例全部 mock 掉 {@code StreamOperations}：它们断的是「该发的发了、发的字段长什么样」，
 * **但没有一条证明这些字段在真 Redis 上落得下去**。本类用与 compose 同镜像的
 * {@code redis:7-alpine} 补上这一段，只在 {@code -Pintegration} 下执行。
 *
 * <p>「发不出去只 warn」是类注释里写死的软依赖契约，本类第三条用例用真实不可达端口验它，
 * 而不是再 mock 一次异常——mock 只证明 catch 块存在，不证明连接层抛的是它接得住的类型。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "shoppilot.ticket.internal-token=test-internal",
        "spring.data.redis.timeout=2s"
})
class RedisStreamsIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    AuditPublisher audit;

    @Autowired
    OutboundPublisher outbound;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry registry;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("审计事件真的写到 shoppilot:audit，字段集与 AuditEvent 契约逐项一致")
    void auditEventLandsOnTheRealStream() {
        TenantContextHolder.set("T-REDIS", "C-REDIS");
        audit.publish("TICKET_CLAIM", "ticket", "T-REDIS-1", Actor.authenticated("alice"), "领取工单");

        List<MapRecord<String, Object, Object>> records = recordsOn(AuditTopics.AUDIT);
        assertThat(records).as("一条审计事件必须对应流里的一条记录").isNotEmpty();

        Map<String, String> fields = fieldsOf(records.get(records.size() - 1));
        assertThat(fields).containsKeys("eventId", "schemaVersion", "action", "objectType", "objectId",
                "tenantId", "actor", "actorAuthenticated", "detail", "occurredAt");
        assertThat(fields)
                .containsEntry("action", "TICKET_CLAIM")
                .containsEntry("objectType", "ticket")
                .containsEntry("objectId", "T-REDIS-1")
                .containsEntry("tenantId", "T-REDIS")
                .containsEntry("actor", "alice")
                .containsEntry("actorAuthenticated", "true")
                .containsEntry("detail", "领取工单");
    }

    @Test
    @DisplayName("渠道出站真的写到 shoppilot:channel-outbound；web/无目标不发但计数看得见")
    void outboundHonoursDeliveryRulesOnTheRealStream() {
        TenantContextHolder.set("T-REDIS", "C-REDIS");
        double noTargetBefore = registry.counter("shoppilot_outbound_no_target_total").count();
        int before = recordsOn(CHANNEL_OUTBOUND).size();

        assertThat(outbound.publish("web", "buyer@example.com", "T-OUT-1", "已处理")).isNull();
        assertThat(outbound.publish(null, "buyer@example.com", "T-OUT-1", "已处理")).isNull();
        assertThat(outbound.publish("email", "  ", "T-OUT-1", "已处理")).isNull();
        assertThat(recordsOn(CHANNEL_OUTBOUND)).as("按规则不发时流里不能长出新记录").hasSize(before);
        assertThat(registry.counter("shoppilot_outbound_no_target_total").count() - noTargetBefore)
                .as("不发也要计数，否则「发成功 0 条」与「都发成功」在面板上一样")
                .isEqualTo(3.0);

        String eventId = outbound.publish("email", "buyer@example.com", "T-OUT-1", "已处理");
        assertThat(eventId).as("有渠道有目标必须发出并返回 eventId").isNotBlank();

        List<MapRecord<String, Object, Object>> records = recordsOn(CHANNEL_OUTBOUND);
        assertThat(records).hasSize(before + 1);
        Map<String, String> fields = fieldsOf(records.get(records.size() - 1));
        assertThat(fields)
                .containsEntry("eventId", eventId)
                .containsEntry("tenantId", "T-REDIS")
                .containsEntry("channel", "email")
                .containsEntry("target", "buyer@example.com")
                .containsEntry("ticketId", "T-OUT-1")
                .containsEntry("body", "已处理");
    }

    @Test
    @DisplayName("Redis 真的不可达时两个生产端都只 warn、不向业务抛")
    void unreachableRedisOnlyWarns() {
        LettuceConnectionFactory offlineFactory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        offlineFactory.afterPropertiesSet();
        try {
            StringRedisTemplate offline = new StringRedisTemplate(offlineFactory);
            offline.afterPropertiesSet();
            TenantContextHolder.set("T-REDIS", "C-REDIS");

            assertThatCode(() -> new AuditPublisher(offline)
                    .publish("TICKET_CLAIM", "ticket", "T-OFF-1", Actor.authenticated("alice"), "领取"))
                    .as("审计是软依赖：发不出去不能把坐席动作卡住").doesNotThrowAnyException();

            String eventId = new OutboundPublisher(offline, new SimpleMeterRegistry())
                    .publish("email", "buyer@example.com", "T-OFF-1", "已处理");
            assertThat(eventId).as("发布失败时返回 null，调用方据此照登缺口").isNull();
        } finally {
            offlineFactory.destroy();
        }
    }

    private List<MapRecord<String, Object, Object>> recordsOn(String stream) {
        return redis.opsForStream().range(stream, Range.unbounded());
    }

    /** 读回来的键值在 StringRedisTemplate 下都是 String；转成断言好读的形状。 */
    private static Map<String, String> fieldsOf(MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new HashMap<>();
        record.getValue().forEach((key, value) -> fields.put(String.valueOf(key), String.valueOf(value)));
        return fields;
    }
}
