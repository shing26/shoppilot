package com.shoppilot.bizmock.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.audit.AuditEvent;
import com.shoppilot.tool.audit.AuditTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 票 236：审计流名/组名必须可配置，且默认构造仍用生产值。
 *
 * <p>这条测试钉的是**线格式的键名**：publish/read/createGroup/ack 只要有一处还写死
 * {@link AuditTopics#AUDIT}，测试事件就会在活体 biz-mock 的惰性 drain 里落进生产台账。
 */
class AuditStreamIsolationTest {

    private static final String TEST_STREAM = "shoppilot:audit-test";
    private static final String TEST_GROUP = "shoppilot-audit-test";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void wireStreamOperations() {
        when(redis.opsForStream()).thenReturn(streams);
    }

    @Test
    @DisplayName("默认构造仍用生产流/组：生产默认不被测试配置偷换")
    void defaultConstructorUsesProductionTopics() {
        RedisAuditChannel channel = new RedisAuditChannel(redis, mapper);
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        channel.publish(event());
        channel.consume("consumer", ignored -> true);

        verify(streams).add(eq(AuditTopics.AUDIT), anyMap());
        verify(streams).createGroup(eq(AuditTopics.AUDIT), any(ReadOffset.class), eq(AuditTopics.AUDIT_GROUP));
        ArgumentCaptor<Consumer> consumer = ArgumentCaptor.forClass(Consumer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamOffset<String>> offset = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streams).read(consumer.capture(), any(StreamReadOptions.class), offset.capture());
        assertThat(consumer.getValue().getGroup()).isEqualTo(AuditTopics.AUDIT_GROUP);
        assertThat(offset.getValue().getKey()).isEqualTo(AuditTopics.AUDIT);
    }

    @Test
    @DisplayName("自定义流/组用于 publish、createGroup 与 read")
    void customTopicsAreUsedForPublishGroupAndRead() {
        RedisAuditChannel channel = new RedisAuditChannel(redis, mapper, TEST_STREAM, TEST_GROUP);
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        channel.publish(event());
        channel.consume("consumer", ignored -> true);

        verify(streams).add(eq(TEST_STREAM), anyMap());
        verify(streams).createGroup(eq(TEST_STREAM), any(ReadOffset.class), eq(TEST_GROUP));
        ArgumentCaptor<Consumer> consumer = ArgumentCaptor.forClass(Consumer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamOffset<String>> offset = ArgumentCaptor.forClass(StreamOffset.class);
        verify(streams).read(consumer.capture(), any(StreamReadOptions.class), offset.capture());
        assertThat(consumer.getValue().getGroup()).isEqualTo(TEST_GROUP);
        assertThat(offset.getValue().getKey()).isEqualTo(TEST_STREAM);
    }

    @Test
    @DisplayName("自定义流/组用于 acknowledge：消费成功后不能 ack 到生产流")
    void customTopicsAreUsedForAcknowledge() {
        RedisAuditChannel channel = new RedisAuditChannel(redis, mapper, TEST_STREAM, TEST_GROUP);
        Map<Object, Object> fields = new HashMap<>(channel.toMap(event()));
        MapRecord<String, Object, Object> record = MapRecord.create(TEST_STREAM, fields);
        when(streams.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        int consumed = channel.consume("consumer", ignored -> true);

        assertThat(consumed).isEqualTo(1);
        verify(streams).acknowledge(eq(TEST_STREAM), eq(TEST_GROUP), any(RecordId[].class));
    }

    private static AuditEvent event() {
        return new AuditEvent("evt-236", AuditEvent.CURRENT_SCHEMA, "REFUND_APPROVED", "REFUND", "236",
                "T001", "agent-001", "测试事件", Instant.parse("2026-11-28T08:00:00Z"), true);
    }
}
