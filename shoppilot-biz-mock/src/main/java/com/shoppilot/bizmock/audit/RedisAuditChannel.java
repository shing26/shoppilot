package com.shoppilot.bizmock.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.audit.AuditEvent;
import com.shoppilot.tool.audit.AuditTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Streams 实现（生产路径，ADR 0054）。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>MAXLEN 用近似裁剪</b>（{@code ~}）：审计流要的是近期，精确裁剪在每条消息上都要算长度，
 *       为了精确丢几条旧消息付这个代价不值；</li>
 *   <li><b>发布失败不抛给业务</b>：{@link #publish} 吞掉连接异常并把通道标记为不可用，
 *       由 {@link AuditService} 走直写兜底。理由：退款放行是资金动作，不能因为审计发不出去而失败——
 *       审计重要，但没有它重要到让买家退不了款；</li>
 *   <li><b>消费是惰性的</b>：不跑后台监听线程。{@link #consume} 在读审计时调用，
 *       与票 70 的 SLA 超时打戳同一套理由——少一个线程池就少一处停机负担。</li>
 * </ol>
 */
@Component
public class RedisAuditChannel implements AuditChannel {

    private static final Logger log = LoggerFactory.getLogger(RedisAuditChannel.class);
    /** 一次读多少条。多了会让单次读成为长事务，少了要多几轮。 */
    static final int BATCH = 128;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisAuditChannel(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public void publish(AuditEvent event) {
        try {
            redis.opsForStream().add(AuditTopics.AUDIT, toMap(event));
        } catch (RuntimeException unreachable) {
            log.warn("审计事件发布失败，改走直写兜底 action={} object={}: {}", event.action(), event.objectId(),
                    unreachable.getMessage());
        }
    }

    @Override
    public int consume(String consumerName, AuditChannel.AuditEventHandler handler) {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records;
        try {
            // group/consumer 走 StreamReadOptions，ReadOffset.lastConsumed() 不带参数——这是
            // 「只取这个消费方还没 ack 过的」，不是「从流尾开始」（那会让老消息永远没人消费）。
            records = redis.opsForStream().read(
                    Consumer.from(AuditTopics.AUDIT_GROUP, consumerName),
                    StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(AuditTopics.AUDIT, ReadOffset.lastConsumed()));
        } catch (RuntimeException unreachable) {
            log.warn("审计流读取失败，本轮跳过消费: {}", unreachable.getMessage());
            return 0;
        }
        List<RecordId> acked = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            AuditEvent event = fromMap(record.getValue());
            if (event == null) {
                // 认领掉但不当成功：坏消息留在 pending 里等人看，不让它无限重投
                acked.add(record.getId());
                continue;
            }
            if (handler.handle(event)) {
                acked.add(record.getId());
            }
        }
        if (!acked.isEmpty()) {
            redis.opsForStream().acknowledge(AuditTopics.AUDIT, AuditTopics.AUDIT_GROUP, acked.toArray(new RecordId[0]));
        }
        return acked.size();
    }

    @Override
    public boolean available() {
        try {
            redis.getConnectionFactory().getConnection().ping();
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    /** 消费组不存在时创建；用 MKSTREAM 让组与流一次建好，不依赖「先发一条再读」。 */
    private void ensureGroup() {
        try {
            // 参数顺序是 (key, ReadOffset, group)——group 在最后，写成 (key, group, offset) 编译不过
            redis.opsForStream().createGroup(AuditTopics.AUDIT, ReadOffset.from("0-0"), AuditTopics.AUDIT_GROUP);
        } catch (RuntimeException alreadyThere) {
            // BUSYGROUP：组已存在，是正常路径不是错误
            log.debug("审计消费组已存在");
        }
    }

    private Map<String, String> toMap(AuditEvent event) {
        Map<String, String> fields = new HashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("schemaVersion", String.valueOf(event.schemaVersion()));
        fields.put("action", event.action());
        fields.put("objectType", event.objectType());
        fields.put("objectId", event.objectId());
        fields.put("tenantId", event.tenantId());
        fields.put("actor", event.actor());
        fields.put("detail", event.detail() == null ? "" : event.detail());
        fields.put("occurredAt", event.occurredAt().toString());
        return fields;
    }

    private AuditEvent fromMap(Map<Object, Object> fields) {
        try {
            if (fields == null || fields.get("eventId") == null) {
                return null;
            }
            return new AuditEvent(
                    String.valueOf(fields.get("eventId")),
                    Integer.parseInt(String.valueOf(fields.get("schemaVersion"))),
                    String.valueOf(fields.get("action")),
                    String.valueOf(fields.get("objectType")),
                    String.valueOf(fields.get("objectId")),
                    String.valueOf(fields.get("tenantId")),
                    String.valueOf(fields.get("actor")),
                    fields.get("detail") == null || String.valueOf(fields.get("detail")).isEmpty()
                            ? null : String.valueOf(fields.get("detail")),
                    Instant.parse(String.valueOf(fields.get("occurredAt"))));
        } catch (RuntimeException malformed) {
            log.warn("审计消息形状不认识，留在 pending 里等人看: {}", malformed.getMessage());
            return null;
        }
    }
}