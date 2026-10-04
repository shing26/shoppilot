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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
                .writeReceipt(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("同一个 eventId 重投两次：只 POST 一次（至少一次投递的幂等）")
    void redeliveryIsIdempotent() {
        OutboundDeliveryService service = service();
        drainWith(service, outboundEvent("ev-dup", "webhook", echoUrl, "T-89", "已处理"));
        drainWith(service, outboundEvent("ev-dup", "webhook", echoUrl, "T-89", "已处理"));

        assertThat(received).as("重投不产生第二次投递").hasSize(1);
    }

    @Test
    @DisplayName("目标回非 2xx：重试 3 次之后落回执工单，结论没丢")
    void nonSuccessStatusLandsAReceiptTicket() {
        status.set(500);
        drainWith(outboundEvent("ev-2", "webhook", echoUrl, "T-90", "已为您退款"));

        assertThat(received).as("试满 3 次").hasSize(3);
        verify(receiptWriter).writeReceipt(anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_failed_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("目标连不上：重试 3 次之后落回执工单，而不是静默丢掉")
    void unreachableTargetLandsAReceiptTicket() {
        // 指向一个没有进程在听的端口
        drainWith(outboundEvent("ev-3", "webhook", "http://127.0.0.1:1/hook", "T-91", "已处理"));

        verify(receiptWriter).writeReceipt(anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_failed_total").tag("channel", "webhook").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("email 渠道：落回执工单，不发 HTTP（本轮没有 SMTP 依赖，这是 ADR 0035 定的交付形态）")
    void emailLandsAReceiptInsteadOfSendingMail() {
        drainWith(outboundEvent("ev-4", "email", "buyer@example.com", "T-92", "已处理完成"));

        assertThat(received).as("没有 SMTP，就不该有任何 HTTP 出站").isEmpty();
        verify(receiptWriter).writeReceipt(anyString(), anyString(), anyString());
        assertThat(registry.get("shoppilot_outbound_delivered_total").tag("channel", "email").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("坏消息（有 eventId 没 channel）认领掉但不当成功，不进业务分支")
    void malformedRecordIsAckedButNotDelivered() {
        drainWith(Map.of("eventId", "ev-bad", "body", "无渠道"));

        assertThat(received).isEmpty();
        verify(receiptWriter, org.mockito.Mockito.never())
                .writeReceipt(anyString(), anyString(), anyString());
        verify(redis.opsForStream()).acknowledge(anyString(), anyString(), any(RecordId[].class));
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
    private void drainWith(Map<String, String> fields) {
        drainWith(service(), fields);
    }

    private void drainWith(OutboundDeliveryService service, Map<String, String> fields) {
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        doReturn(streams).when(redis).opsForStream();
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        doReturn(fields).when(record).getValue();
        doReturn(RecordId.of("1-0")).when(record).getId();
        doReturn(List.of(record)).when(streams).read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset[].class));
        service.drain();
    }

    private static Map<String, String> outboundEvent(String eventId, String channel, String target, String ticketId,
                                                      String body) {
        return Map.of("eventId", eventId, "schemaVersion", "1", "tenantId", "T001", "channel", channel,
                "target", target, "ticketId", ticketId, "body", body);
    }
}