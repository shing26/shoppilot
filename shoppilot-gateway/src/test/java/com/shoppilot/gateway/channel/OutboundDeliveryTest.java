package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.shoppilot.gateway.identity.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 出站的消费端（round26 票 88 / ADR 0059）。
 *
 * <p><b>用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个真的回声端点</b>，
 * 不 mock HTTP：这一格要证明的是「下游真的收到了那一次 POST」，
 * 而 mock 掉 HTTP 之后，「调用过 send」与「送到了」就分不开了——那正是本票要防的那类假绿。
 * 因为它不需要起全栈，所以这一格不必等清场日。
 */
class OutboundDeliveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer echo;
    private String echoUrl;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final EmailReceiptWriter receiptWriter = mock(EmailReceiptWriter.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    /** 同一个实例跨两次收：去重表是实例状态，两次 new 就等于两次「第一次」，
     *  而幂等恰恰要在**同一个进程的两轮之间**成立（见 Handoff 里那条登记）。 */
    private OutboundDeliveryService service() {
        return new OutboundDeliveryService(redis, HttpClient.newHttpClient(), JSON, receiptWriter, registry,
                Duration.ofHours(1));
    }

    @BeforeEach
    void startEcho() throws IOException {
        received.clear();
        status.set(200);
        echo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        echo.createContext("/hook", this::record);
        echo.start();
        echoUrl = "http://127.0.0.1:" + echo.getAddress().getPort() + "/hook";
        TenantContext.set(new TenantContext.Identity("T001", "C001", "conv-outbound"));
        doReturn(Optional.of("T-RECEIPT")).when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
    }

    @AfterEach
    void stopEcho() {
        echo.stop(0);
        TenantContext.clear();
    }

    @Test
    @DisplayName("webhook：下游真的收到一次 POST，且带上工单号与结论")
    void webhookDeliveryActuallyReachesTheTarget() throws Exception {
        drainWith(outboundEvent("ev-1", "webhook", echoUrl, "T-88", "已为您补发配件"));

        assertThat(received).as("承重格：不是「调用过 send」，是「对端真的收到了」").hasSize(1);
        JsonNode body = JSON.readTree(received.get(0));
        assertThat(body.path("ticketId").asText()).isEqualTo("T-88");
        assertThat(body.path("body").asText()).isEqualTo("已为您补发配件");
        assertThat(registry.get("shoppilot_outbound_delivered_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
        verify(receiptWriter, org.mockito.Mockito.never())
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("同一个 eventId 重投两次：只 POST 一次（至少一次投递的幂等）")
    void redeliveryIsIdempotent() {
        OutboundDeliveryService service = service();
        drainWith(service, outboundEvent("ev-dup", "webhook", echoUrl, "T-89", "已处理"));
        StreamOperations<String, Object, Object> second =
                drainWith(service, outboundEvent("ev-dup", "webhook", echoUrl, "T-89", "已处理"));

        assertThat(received).as("重投不产生第二次投递").hasSize(1);
        verify(second).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    @DisplayName("目标回非 2xx：重试 3 次之后落回执工单，结论没丢")
    void nonSuccessStatusLandsAReceiptTicket() {
        status.set(500);
        drainWith(outboundEvent("ev-2", "webhook", echoUrl, "T-90", "已为您退款"));

        assertThat(received).as("试满 3 次").hasSize(3);
        verify(receiptWriter).writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_failed_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("目标连不上：重试 3 次之后落回执工单，而不是静默丢掉")
    void unreachableTargetLandsAReceiptTicket() {
        // 指向一个没有进程在听的端口
        drainWith(outboundEvent("ev-3", "webhook", "http://127.0.0.1:1/hook", "T-91", "已处理"));

        verify(receiptWriter).writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_failed_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("email 渠道：落回执工单，不发 HTTP（本轮没有 SMTP 依赖，这是 ADR 0035 定的交付形态）")
    void emailLandsAReceiptInsteadOfSendingMail() {
        drainWith(outboundEvent("ev-4", "email", "buyer@example.com", "T-92", "已处理完成"));

        assertThat(received).as("没有 SMTP，就不该有任何 HTTP 出站").isEmpty();
        verify(receiptWriter).writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_delivered_total").tag("channel", "email").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("坏消息（有 eventId 没 channel）认领掉但不当成功，不进业务分支")
    void malformedRecordIsAckedButNotDelivered() {
        drainWith(Map.of("eventId", "ev-bad", "body", "无渠道"));

        assertThat(received).isEmpty();
        verify(receiptWriter, org.mockito.Mockito.never())
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        verify(redis.opsForStream()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    @DisplayName("重试耗尽落回执时带上事件里的 tenant/customer，不依赖消费线程的 ThreadLocal")
    void receiptUsesIdentityCarriedByTheEvent() {
        status.set(500);
        Map<String, String> event = new java.util.HashMap<>(outboundEvent(
                "ev-receipt", "webhook", echoUrl, "T-93", "已处理"));
        event.put("customerId", "C001");

        drainWith(event);

        verify(receiptWriter).writeReceipt(org.mockito.ArgumentMatchers.eq("T001"),
                org.mockito.ArgumentMatchers.eq("C001"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("默认 SSRF 策略拒绝元数据地址：不发请求，直接落回执")
    void metadataTargetIsDeniedAndLandsAReceipt() {
        drainWith(outboundEvent("ev-ssrf", "webhook", "http://169.254.169.254/latest/meta-data",
                "T-94", "已处理"));

        assertThat(received).isEmpty();
        verify(receiptWriter).writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("回执工单返回 empty（真实失败路径）：不 ack，事件留在 pending")
    void receiptEmptyKeepsEventPending() {
        status.set(500);
        doReturn(Optional.empty()).when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());

        StreamOperations<String, Object, Object> streams =
                drainWith(outboundEvent("ev-receipt-fail", "webhook", echoUrl, "T-95", "已处理"));

        org.mockito.Mockito.verify(streams, org.mockito.Mockito.never())
                .acknowledge(anyString(), anyString(), any(RecordId[].class));
        assertThat(registry.get("shoppilot_outbound_failed_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("回执工单抛异常：同样不 ack，事件留在 pending")
    void receiptExceptionKeepsEventPending() {
        status.set(500);
        org.mockito.Mockito.doThrow(new RuntimeException("ticket service down"))
                .when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());

        StreamOperations<String, Object, Object> streams =
                drainWith(outboundEvent("ev-receipt-throw", "webhook", echoUrl, "T-96", "已处理"));

        org.mockito.Mockito.verify(streams, org.mockito.Mockito.never())
                .acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    @DisplayName("每轮先读本消费者 PEL（0），再读新消息（>）：未 ack 的事件会重投")
    void pendingEntriesAreReadBeforeNewOnes() {
        StreamOperations<String, Object, Object> streams =
                drainWith(outboundEvent("ev-pending", "webhook", echoUrl, "T-97", "已处理"));

        org.mockito.ArgumentCaptor<StreamOffset[]> offsets =
                org.mockito.ArgumentCaptor.forClass(StreamOffset[].class);
        verify(streams, times(2)).read(any(Consumer.class), any(StreamReadOptions.class),
                offsets.capture());

        assertThat(offsets.getAllValues().get(0)[0].getOffset().getOffset())
                .as("第一读必须从 PEL 起点 0 开始，否则未 ack 事件永久卡住").isEqualTo("0");
        assertThat(offsets.getAllValues().get(1)[0].getOffset().getOffset())
                .as("第二读才是新消息 >").isEqualTo(">");
        assertThat(received).as("同一条事件在一轮里只投递一次").hasSize(1);
    }

    @Test
    @DisplayName("回执未落定：跨轮退避窗口内不再投递，到期后才重试")
    void crossRoundBackoffSkipsUntilDue() {
        status.set(500);
        doReturn(Optional.empty()).when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        AtomicLong now = new AtomicLong(1_000_000L);
        OutboundDeliveryService service = service(
                RoundBackoffPolicy.of(Duration.ofSeconds(5), 2, Duration.ofMinutes(5), 0), now::get);
        MapRecord<String, Object, Object> rec = record("1-0",
                outboundEvent("ev-backoff", "webhook", echoUrl, "T-227", "已处理"));

        StreamOperations<String, Object, Object> first = drainWithRecords(service, List.of(rec), List.of());
        assertThat(received).as("第一轮对下游试满 3 次").hasSize(3);
        assertThat(service.roundStateCount()).isEqualTo(1);
        verify(receiptWriter, times(1))
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        verify(first, org.mockito.Mockito.never())
                .acknowledge(anyString(), anyString(), any(RecordId[].class));

        now.addAndGet(4_000);
        StreamOperations<String, Object, Object> skipped = drainWithRecords(service, List.of(rec), List.of());
        assertThat(received).as("退避窗口内不再重打下游").hasSize(3);
        verify(receiptWriter, times(1))
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        verify(skipped, org.mockito.Mockito.never())
                .acknowledge(anyString(), anyString(), any(RecordId[].class));

        now.addAndGet(2_000);
        StreamOperations<String, Object, Object> retried = drainWithRecords(service, List.of(rec), List.of());
        assertThat(received).as("退避到期后重新尝试").hasSize(6);
        verify(receiptWriter, times(2))
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        verify(retried, org.mockito.Mockito.never())
                .acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    @Test
    @DisplayName("跨轮退避到期后回执落库：ack 并清除退避状态")
    void successfulRetryAcksAndClearsBackoff() {
        status.set(500);
        doReturn(Optional.empty(), Optional.of("T-227"))
                .when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        AtomicLong now = new AtomicLong(1_000_000L);
        OutboundDeliveryService service = service(
                RoundBackoffPolicy.of(Duration.ofSeconds(5), 2, Duration.ofMinutes(5), 0), now::get);
        MapRecord<String, Object, Object> rec = record("2-0",
                outboundEvent("ev-recover", "webhook", echoUrl, "T-230", "已处理"));

        drainWithRecords(service, List.of(rec), List.of());
        assertThat(service.roundStateCount()).isEqualTo(1);

        now.addAndGet(6_000);
        StreamOperations<String, Object, Object> streams = drainWithRecords(service, List.of(rec), List.of());

        assertThat(service.roundStateCount()).as("回执落库后清除跨轮状态").isZero();
        verify(streams).acknowledge(anyString(), anyString(), any(RecordId[].class));
        assertThat(received).as("两轮各试满 3 次").hasSize(6);
    }

    @Test
    @DisplayName("PEL 已满 BATCH：新消息仍在本轮被读取并处理")
    void newMessageIsNotStarvedWhenPelIsFull() {
        List<MapRecord<String, Object, Object>> pending = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            pending.add(record((i + 1) + "-0", Map.of("eventId", "pending-" + i)));
        }
        MapRecord<String, Object, Object> fresh = record("65-0",
                outboundEvent("ev-fresh", "webhook", echoUrl, "T-228", "已处理"));

        StreamOperations<String, Object, Object> streams =
                drainWithRecords(service(), pending, List.of(fresh));

        assertThat(received).as("新消息没有被 64 条 PEL 饿死").hasSize(1);
        ArgumentCaptor<RecordId[]> acked = ArgumentCaptor.forClass(RecordId[].class);
        verify(streams).acknowledge(anyString(), anyString(), acked.capture());
        assertThat(acked.getValue()).as("64 条坏消息 + 1 条新消息").hasSize(65);
    }

    @Test
    @DisplayName("同一 RecordId 同时出现在 PEL 与新消息：只处理一次")
    void duplicateRecordIsProcessedOnce() {
        MapRecord<String, Object, Object> rec = record("1-0",
                outboundEvent("ev-same-record", "webhook", echoUrl, "T-229", "已处理"));

        StreamOperations<String, Object, Object> streams =
                drainWithRecords(service(), List.of(rec), List.of(rec));

        assertThat(received).as("同一条记录不产生第二次 POST").hasSize(1);
        ArgumentCaptor<RecordId[]> acked = ArgumentCaptor.forClass(RecordId[].class);
        verify(streams).acknowledge(anyString(), anyString(), acked.capture());
        assertThat(acked.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("跨轮退避表有界：超出上限淘汰最旧条目，新条目继续退避")
    void roundStateTableIsBoundedAndEvictsOldest() {
        status.set(500);
        doReturn(Optional.empty()).when(receiptWriter)
                .writeReceipt(anyString(), nullable(String.class), anyString(), anyString(), anyString());
        AtomicLong now = new AtomicLong(1_000_000L);
        OutboundDeliveryService service = service(
                RoundBackoffPolicy.of(Duration.ofHours(1), 2, Duration.ofHours(1), 0), now::get, 2);
        MapRecord<String, Object, Object> oldest = record("1-0",
                outboundEvent("ev-old", "webhook", echoUrl, "T-231", "已处理"));
        MapRecord<String, Object, Object> middle = record("2-0",
                outboundEvent("ev-mid", "webhook", echoUrl, "T-232", "已处理"));
        MapRecord<String, Object, Object> newest = record("3-0",
                outboundEvent("ev-new", "webhook", echoUrl, "T-233", "已处理"));

        drainWithRecords(service, List.of(oldest, middle, newest), List.of());
        assertThat(received).as("三条各试满 3 次").hasSize(9);
        assertThat(service.roundStateCount()).as("上限 2").isEqualTo(2);

        received.clear();
        drainWithRecords(service, List.of(oldest), List.of());
        assertThat(received).as("最旧的 ev-old 已被淘汰，立即重试").hasSize(3);

        received.clear();
        drainWithRecords(service, List.of(newest), List.of());
        assertThat(received).as("较新的 ev-new 仍在 1 小时退避窗口内").isEmpty();
    }

    // --- harness ---------------------------------------------------------------------------

    private void record(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new String(body, StandardCharsets.UTF_8));
        int code = status.get();
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    /**
     * 推一条事件出去，然后收一轮。
     *
     * <p>流记录本身用 mock 而不是真的造一个 {@code MapRecord}：本类要断的是「投递」，
     * 而 {@code MapRecord} 的工厂 API 在不同 Spring Data 版本里签名变过好几轮——
     * 为一个只当数据载体的对象跟那套 API 较劲，是把力气花在错的地方。
     */
    private StreamOperations<String, Object, Object> drainWith(Map<String, String> fields) {
        return drainWith(service(), fields);
    }

    private StreamOperations<String, Object, Object> drainWith(OutboundDeliveryService service,
                                                               Map<String, String> fields) {
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        doReturn(streams).when(redis).opsForStream();
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        doReturn(fields).when(record).getValue();
        doReturn(RecordId.of("1-0")).when(record).getId();
        doReturn(List.of(record), List.of()).when(streams).read(
                any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class));
        service.drain();
        return streams;
    }

    private OutboundDeliveryService service(RoundBackoffPolicy backoff, LongSupplier clock) {
        return new OutboundDeliveryService(redis, HttpClient.newHttpClient(), JSON, receiptWriter, registry,
                Duration.ofHours(1),
                new EgressPolicy("127.0.0.1,localhost", HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()),
                DeliveredEventStore.inMemory(Duration.ZERO), RetryPolicy.fast(), backoff, clock);
    }

    private OutboundDeliveryService service(RoundBackoffPolicy backoff, LongSupplier clock, int maxRoundStates) {
        return new OutboundDeliveryService(redis, HttpClient.newHttpClient(), JSON, receiptWriter, registry,
                Duration.ofHours(1),
                new EgressPolicy("127.0.0.1,localhost", HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()),
                DeliveredEventStore.inMemory(Duration.ZERO), RetryPolicy.fast(), backoff, clock, maxRoundStates);
    }

    private StreamOperations<String, Object, Object> drainWithRecords(
            OutboundDeliveryService service,
            List<MapRecord<String, Object, Object>> pending,
            List<MapRecord<String, Object, Object>> fresh) {
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        doReturn(streams).when(redis).opsForStream();
        doReturn(pending, fresh).when(streams).read(
                any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset[].class));
        service.drain();
        return streams;
    }

    private static MapRecord<String, Object, Object> record(String id, Map<String, String> fields) {
        MapRecord<String, Object, Object> rec = mock(MapRecord.class);
        doReturn(fields).when(rec).getValue();
        doReturn(RecordId.of(id)).when(rec).getId();
        return rec;
    }

    private static Map<String, String> outboundEvent(String eventId, String channel, String target, String ticketId,
                                                      String body) {
        return Map.of("eventId", eventId, "schemaVersion", "1", "tenantId", "T001", "channel", channel,
                "target", target, "ticketId", ticketId, "body", body);
    }
}
