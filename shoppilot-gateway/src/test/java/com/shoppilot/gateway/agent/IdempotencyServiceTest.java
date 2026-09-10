package com.shoppilot.gateway.agent;

import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写操作幂等键的语义（ticket 12、ADR 0008）。
 *
 * <p>这里钉的是"什么算同一次提交"。门禁 eval 冒烟实测过一次反例：派生 token 只带订单号与退款原因，
 * 于是同一笔订单改两个**不同**地址被当成重复提交，第二次直接回放第一次的结果，用户听到"已修改"而地址没动。
 */
class IdempotencyServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedissonClient redisson;
    private SimpleMeterRegistry registry;
    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        registry = new SimpleMeterRegistry();
        service = new IdempotencyService(redis, redisson, registry);
    }

    private static Map<String, Object> address(String orderNo, String city, String detail) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("orderNo", orderNo);
        args.put("receiverName", "王强");
        args.put("receiverPhone", "13700003456");
        args.put("province", "广东省");
        args.put("city", city);
        args.put("district", "南山区");
        args.put("detailAddress", detail);
        return args;
    }

    @Test
    @DisplayName("同一订单的两次不同地址是两次提交，不是重复提交")
    void differentAddressPayloadsAreDifferentSubmissions() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService.Guard first = service.begin("T001", "C001",
                ToolName.MODIFY_DELIVERY_ADDRESS, address("90004", "杭州市", "文三路100号"), null);
        IdempotencyService.Guard second = service.begin("T001", "C001",
                ToolName.MODIFY_DELIVERY_ADDRESS, address("90004", "深圳市", "科技园南路8号"), null);

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isFalse();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values, org.mockito.Mockito.times(2))
                .setIfAbsent(keys.capture(), eq("__PENDING__"), any(Duration.class));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test
    @DisplayName("参数一字不差地说第二遍才是重试：回放首次结果，不重复执行")
    void identicalPayloadReplaysTheFirstResult() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        Map<String, Object> args = address("90004", "杭州市", "文三路100号");
        IdempotencyService.Guard first = service.begin("T001", "C001", ToolName.MODIFY_DELIVERY_ADDRESS, args, null);
        String key = capturedKey();

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(key)).thenReturn("{\"status\":\"SUCCESS\"}");

        IdempotencyService.Guard second = service.begin("T001", "C001", ToolName.MODIFY_DELIVERY_ADDRESS, args, null);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.cachedJson()).isEqualTo("{\"status\":\"SUCCESS\"}");
        assertThat(second.token()).isEqualTo(first.token());
        assertThat(registry.get("shoppilot_duplicate_submit_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("空串参数不参与派生：模型写空还是写同一个值，不该被当成两次不同提交")
    void blankParamsDoNotForkTheDerivedToken() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        IdempotencyService.Guard withBlank = service.begin("T001", "C001",
                ToolName.MODIFY_DELIVERY_ADDRESS, address("90004", "", "文三路100号"), null);
        IdempotencyService.Guard withoutKey = service.begin("T001", "C001",
                ToolName.MODIFY_DELIVERY_ADDRESS, address("90004", null, "文三路100号"), null);

        assertThat(withoutKey.token()).isEqualTo(withBlank.token());
    }

    @Test
    @DisplayName("幂等键含买家与动作：两个买家撞同一个 token、同一买家改地址与退款互不吞")
    void keyCarriesCustomerAndAction() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("orderNo", "90004");
        refund.put("reason", "生鲜破损");

        service.begin("T001", "C001", ToolName.APPLY_REFUND, refund, "tok-1");
        service.begin("T001", "C002", ToolName.APPLY_REFUND, refund, "tok-1");
        service.begin("T001", "C001", ToolName.MODIFY_DELIVERY_ADDRESS,
                address("90004", "杭州市", "文三路100号"), "tok-1");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values, org.mockito.Mockito.times(3))
                .setIfAbsent(keys.capture(), eq("__PENDING__"), any(Duration.class));
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Redis 停机时照常执行：重复交给业务侧唯一约束兜底")
    void bypassesWhenRedisIsUnavailable() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("Unable to connect to Redis"));

        IdempotencyService.Guard guard = service.begin("T001", "C001", ToolName.APPLY_REFUND,
                Map.of("orderNo", "90004", "reason", "生鲜破损"), "tok-2");

        assertThat(guard.duplicate()).isFalse();
        assertThat(guard.lockBusy()).isFalse();
        assertThat(guard.token()).isEqualTo("tok-2");
        assertThat(registry.get("shoppilot_idempotency_redis_bypass_total").counter().count()).isEqualTo(1.0d);
    }

    /** 取最近一次写进 Redis 的幂等键：测试里不复制 md5 实现，只从真实调用里反查。 */
    private String capturedKey() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(values, org.mockito.Mockito.atLeastOnce())
                .setIfAbsent(keys.capture(), anyString(), any(Duration.class));
        return keys.getValue();
    }
}
