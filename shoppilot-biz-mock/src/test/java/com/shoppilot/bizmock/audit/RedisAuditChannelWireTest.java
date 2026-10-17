package com.shoppilot.bizmock.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.audit.AuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Redis 流的线格式往返（2026-10-18）。
 *
 * <p>这条边界此前零覆盖，代价是真金白银的：{@code toMap} 漏写 {@code actorAuthenticated}，
 * 消费端 {@code fromMap} 回落九参旧构造 → **所有走流的审计事件都落成"未认证"**，
 * 包括已验签 agent 账号放行/驳回的退款（B3 / ADR 0063 承诺"资金动作的责任人必须是
 * 已验签的账号"，查询面却分不出来）。JVM 用例用的是内存通道（对象直传、不过序列化），
 * 一个都没红；矩阵里的活体断言又指向一个不存在的端点，从没真正执行过。
 *
 * <p>把两个转换函数放到包级可见（同 {@code HybridRetriever.rrf()} 的先例）就是为了让
 * 这条线格式能被直接钉住：**字段漏一格 = 语义静默降级**，这种病只能靠往返测试防。
 */
class RedisAuditChannelWireTest {

    private final RedisAuditChannel channel =
            new RedisAuditChannel(mock(StringRedisTemplate.class), new ObjectMapper());

    private static AuditEvent event(boolean authenticated) {
        return new AuditEvent("evt-1", AuditEvent.CURRENT_SCHEMA, "REFUND_APPROVED", "REFUND", "9",
                "T001", "agent", "退款放行，金额 19900 分", Instant.parse("2026-10-18T02:41:01Z"),
                authenticated);
    }

    @Test
    @DisplayName("往返保真：已认证的 actorAuthenticated 必须活着过流（B3 资金动作审计丢过这一格）")
    void roundTripKeepsActorAuthenticated() {
        AuditEvent original = event(true);

        AuditEvent back = channel.fromMap(new HashMap<>(channel.toMap(original)));

        assertThat(back).isNotNull();
        assertThat(back.actorAuthenticated()).isTrue();
        assertThat(back.actor()).isEqualTo("agent");
        assertThat(back.action()).isEqualTo("REFUND_APPROVED");
        assertThat(back.objectType()).isEqualTo("REFUND");
        assertThat(back.objectId()).isEqualTo("9");
        assertThat(back.tenantId()).isEqualTo("T001");
        assertThat(back.detail()).isEqualTo("退款放行，金额 19900 分");
        assertThat(back.occurredAt()).isEqualTo(original.occurredAt());
        assertThat(back.eventId()).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("未认证的射出去还是未认证：反方向也不能被静默升级成认证过的")
    void roundTripKeepsUnAuthenticated() {
        AuditEvent back = channel.fromMap(new HashMap<>(channel.toMap(event(false))));

        assertThat(back).isNotNull();
        assertThat(back.actorAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("换代期旧消息（缺字段）按未认证处理：老格式不能把自报名字读成认证过的")
    void legacyMessageWithoutTheFlagFailsClosed() {
        Map<String, String> legacy = channel.toMap(event(true));
        legacy.remove("actorAuthenticated");

        AuditEvent back = channel.fromMap(new HashMap<>(legacy));

        assertThat(back).isNotNull();
        assertThat(back.actorAuthenticated()).isFalse();
    }

    @Test
    @DisplayName("坏消息返回 null 而不是抛给消费循环（留在 pending 里等人看）")
    void malformedMessageIsReturnedAsNull() {
        Map<Object, Object> malformed = new HashMap<>();
        malformed.put("eventId", "evt-bad");
        malformed.put("schemaVersion", "一");  // 解析必炸

        assertThat(channel.fromMap(malformed)).isNull();
    }
}
