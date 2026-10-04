package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.tool.audit.AuditTopics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 渠道出站的消费端（round26 票 88 / ADR 0059）。
 *
 * <p>落在**网关进程内**而不是第五个服务：ADR 0053 已经把「渠道出站适配」划给网关，
 * 而一个只做发 HTTP 的消费者不值一台 JVM（本机全栈已约 6.6 GB）。
 *
 * <p>三条承重的口径：
 * <ol>
 *   <li><b>「送达」的定义是目标返回 2xx</b>，不是「发出去了」。连接失败、超时、非 2xx 都算失败——
 *       把「发出去了」当「送到了」，这条链就永远绿。</li>
 *   <li><b>失败不丢</b>：webhook 重试 3 次仍不成功，或渠道没有投递方式，都落一张
 *       {@code CHANNEL_RECEIPT} 工单。理由：结论丢了就再也回不去，而落单是买家与坐席都看得到的地方。</li>
 *   <li><b>至少一次 → 必须幂等</b>：按 {@code eventId} 去重，重投不产生第二次 POST。</li>
 * </ol>
 *
 * <p><b>email 不真发</b>（ADR 0059 第 7 条）：本仓没有 SMTP 依赖，收件地址是 seed 出来的假数据。
 * email 的投递方式就是落回执工单——这本来就是 ADR 0035 给它定的交付形态。
 */
@Component
public class OutboundDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryService.class);
    private static final int BATCH = 64;
    private static final String CONSUMER = "gateway-outbound";

    /** 去重集合的容量上限。它是缓存不是账本——超出就丢最旧的。 */
    private static final int DEDUPE_CAPACITY = 1000;

    /** 投递几次（ADR 0059 第 6 条：重试 3 次后落回执工单）。**刻意不做成配置**：见 ADR 0059 的取舍。 */
    private static final int ATTEMPTS = 3;

    private final StringRedisTemplate redis;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final EmailReceiptWriter receiptWriter;
    private final io.micrometer.core.instrument.MeterRegistry registry;

    private final Map<String, Boolean> delivered = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUPE_CAPACITY;
        }
    };

    private final ScheduledExecutorService loop;

    public OutboundDeliveryService(StringRedisTemplate redis, HttpClient http, ObjectMapper mapper,
                                   EmailReceiptWriter receiptWriter,
                                   io.micrometer.core.instrument.MeterRegistry registry,
                                   @Value("${shoppilot.outbound.poll-interval:5s}") Duration pollInterval) {
        this.redis = redis;
        this.http = http;
        this.mapper = mapper;
        this.receiptWriter = receiptWriter;
        this.registry = registry;
        // 单线程 + 固定延迟。审计那条流选了惰性消费（要有人读审计才收），但出站不行——
        // 「要有人点一下才投递」是 ADR 0059 明确否掉的形态：一个从后台自己走的定时任务就够。
        this.loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbound-delivery");
            thread.setDaemon(true);
            return thread;
        });
        this.loop.scheduleWithFixedDelay(this::drainQuietly, pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 本轮成功处理的条数（失败也算处理完——它已经落成回执工单了，不重投才有那一格）。 */
    public int drain() {
        ensureGroup();
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                    Consumer.from(AuditTopics.CHANNEL_OUTBOUND, CONSUMER),
                    StreamReadOptions.empty().count(BATCH),
                    StreamOffset.create(AuditTopics.CHANNEL_OUTBOUND, ReadOffset.lastConsumed()));
        } catch (RuntimeException unreachable) {
            log.warn("出站流读取失败，本轮跳过: {}", unreachable.getMessage());
            return 0;
        }
        List<RecordId> acked = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Event event = Event.from(valuesOf(record));
            if (event == null) {
                // 坏消息认领掉但不当成功：留在 pending 里等人看，不让它无限重投（同审计那条纪律）
                acked.add(record.getId());
                continue;
            }
            handle(event);
            acked.add(record.getId());
        }
        if (!acked.isEmpty()) {
            try {
                redis.opsForStream().acknowledge(AuditTopics.CHANNEL_OUTBOUND, CONSUMER,
                        acked.toArray(new RecordId[0]));
            } catch (RuntimeException unreachable) {
                log.warn("出站流 ack 失败，下一轮会重投（幂等保证不会重复投递）: {}", unreachable.getMessage());
            }
        }
        return acked.size();
    }

    private void handle(Event event) {
        if (event.eventId() != null && delivered.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
            log.info("出站事件 {} 投递过了，跳过（至少一次投递的幂等）", event.eventId());
            return;
        }
        boolean sent = switch (event.channel() == null ? "" : event.channel()) {
            case "webhook" -> postToWebhook(event);
            // email 落回执工单**就是它的交付形态**（ADR 0035），所以这一条算送达；
            // 「没有投递方式」也落单，但那条不算送达——落单只是没让结论丢掉。
            case "email" -> landReceipt(event, "email 渠道按 ADR 0035 以回执工单交付，本轮不真发邮件", true);
            default -> landReceipt(event, "渠道 " + event.channel() + " 没有投递方式", false);
        };
        if (sent) {
            registry.counter("shoppilot_outbound_delivered_total", "channel", channelLabel(event)).increment();
        } else {
            registry.counter("shoppilot_outbound_failed_total", "channel", channelLabel(event)).increment();
        }
    }

    /** @return 目标是否回了 2xx。**只有 2xx 算送达**。 */
    private boolean postToWebhook(Event event) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(event.target()))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Shoppilot-Ticket", event.ticketId() == null ? "" : event.ticketId())
                        .POST(HttpRequest.BodyPublishers.ofString(payload(event)))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return true;
                }
                log.warn("出站投递被拒 ticket={} status={} 第 {}/{} 次", event.ticketId(), response.statusCode(),
                        attempt, ATTEMPTS);
            } catch (Exception unreachable) {
                log.warn("出站投递异常 ticket={} 第 {}/{} 次: {}", event.ticketId(), attempt, ATTEMPTS,
                        unreachable.getMessage());
            }
        }
        log.warn("重试 {} 次仍送不到，改落回执工单 ticket={}", ATTEMPTS, event.ticketId());
        landReceipt(event, "重试 " + ATTEMPTS + " 次仍送不到", false);
        return false;
    }

    /**
     * 落一张回执工单——**结论没丢**的那一格。
     *
     * <p>走既有的 {@link EmailReceiptWriter}：它本来就是「把答案变成一张买家与坐席都看得到的单」，
     * 这里复用的是同一个机制，不是新造第二个出口。
     */
    private boolean landReceipt(Event event, String why, boolean countsAsDelivered) {
        try {
            receiptWriter.writeReceipt("工单 " + (event.ticketId() == null ? "(未知)" : event.ticketId()),
                    event.body() == null ? "" : event.body(), event.target());
            log.info("出站改落回执工单 ticket={} 算送达={} 原因：{}", event.ticketId(), countsAsDelivered, why);
            return countsAsDelivered;
        } catch (RuntimeException failure) {
            log.error("出站未送达且回执工单也落不下去 ticket={} 原因：{} 异常：{}", event.ticketId(), why,
                    failure.getMessage());
            return false;
        }
    }

    private String payload(Event event) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "ticketId", event.ticketId() == null ? "" : event.ticketId(),
                    "tenantId", event.tenantId() == null ? "" : event.tenantId(),
                    "body", event.body() == null ? "" : event.body()));
        } catch (Exception unserializable) {
            return "{\"ticketId\":\"\",\"body\":\"\"}";
        }
    }

    /**
     * 流记录的字段表归一成 {@code Map<String,String>}。
     *
     * <p>Redis 的 stream 字段本身是 {@code Map<String,String>}，但 {@code read()} 的泛型签名把它
     * 擦成了 {@code Object}。这里显式转换一次，而不是在每个取值点写断言。
     */
    private static Map<String, String> valuesOf(MapRecord<String, Object, Object> record) {
        Map<String, String> values = new java.util.HashMap<>();
        if (record.getValue() instanceof Map<?, ?> fields) {
            fields.forEach((key, value) ->
                    values.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
        }
        return values;
    }

    private static String channelLabel(Event event) {
        return event.channel() == null || event.channel().isBlank() ? "unknown" : event.channel();
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (RuntimeException unexpected) {
            // 后台循环里任何未预料的异常都不能让线程死掉——那样回流会静默停摆
            log.warn("出站消费循环本轮异常（线程仍在）: {}", unexpected.getMessage());
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(AuditTopics.CHANNEL_OUTBOUND, ReadOffset.from("0-0"), CONSUMER);
        } catch (RuntimeException alreadyThere) {
            // BUSYGROUP：组已存在，是正常路径不是错误
            log.debug("出站消费组已存在");
        }
    }

    @PreDestroy
    void stop() {
        loop.shutdownNow();
    }

    /** 一条出站事件的流字段；坏消息在这里被判空，不让它进业务分支。 */
    record Event(String eventId, String tenantId, String channel, String target, String ticketId, String body) {

        /**
         * 从流字段还原；**判空在这里做**，不让坏消息进业务分支。
         *
         * <p>判据只有两条：有 {@code eventId}（没有它就没法幂等，重投会重复投递），
         * 有 {@code channel}（没有它就没有投递方式）。{@code target} 缺不缺不在这里判——
         * webhook 缺地址会在投递那里失败并落回执工单，那比在这里静默吞掉更接近事实。
         */
        static Event from(Map<String, String> fields) {
            if (fields == null) {
                return null;
            }
            String eventId = string(fields, "eventId");
            String channel = string(fields, "channel");
            if (isBlank(eventId) || isBlank(channel)) {
                return null;
            }
            return new Event(eventId, string(fields, "tenantId"), channel, string(fields, "target"),
                    string(fields, "ticketId"), string(fields, "body"));
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String string(Map<String, String> fields, String key) {
            Object value = fields.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }
}