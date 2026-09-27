package com.shoppilot.gateway.agent;

import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 请求级幂等回放索引（ADR 0046 票 58）。
 *
 * <p>这里钉的是"客户端重试同一次请求时，回放与否还取决于不取决于模型"。现状把这条承诺挂在
 * "模型这一轮肯不肯再发一次工具调用"上，模型不发就没有回放、也没有降级话术（round20 spec
 * 登记节第 5 项，3/3 复现）。索引把它改成请求级事实。
 *
 * <p>两个键都在同一个 Map 上走真实读写（不是逐次 stub 返回值），因为本类要验的正是
 * "写进去的指针能不能被读出来"这种往返性质。
 */
class IdempotencyServiceRequestReplayTest {

    private static final String FINGERPRINT = "fp-aaa";

    private final Map<String, String> store = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SimpleMeterRegistry registry;
    private IdempotencyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> store.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        // ValueOperations.set(...) 是 void，不能走 when(...).thenAnswer(...)，只能 doAnswer
        doAnswer(call -> {
            store.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> store.remove(call.getArgument(0)) != null);

        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        registry = new SimpleMeterRegistry();
        service = new IdempotencyService(redis, redisson, registry);
    }

    private static Map<String, Object> refund(String orderNo) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("orderNo", orderNo);
        args.put("reason", "生鲜破损");
        return args;
    }

    /** 走一次完整的"首次执行成功"：begin → complete。 */
    private void firstExecutionSucceeds(String tenant, String customer, String clientToken,
                                        String fingerprint, String resultJson) {
        IdempotencyService.Guard guard = service.begin(tenant, customer, ToolName.APPLY_REFUND,
                refund("90001"), clientToken, fingerprint);
        service.complete(tenant, customer, ToolName.APPLY_REFUND, guard, resultJson);
    }

    @Test
    @DisplayName("显式 token + 同指纹：回放首次结果，不再问模型")
    void replayHitsForTheSameClientTokenAndFingerprint() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\",\"payload\":{}}");

        Optional<IdempotencyService.Replay> replay =
                service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT);

        assertThat(replay).isPresent();
        assertThat(replay.get().tool()).isEqualTo(ToolName.APPLY_REFUND);
        assertThat(replay.get().resultJson()).isEqualTo("{\"status\":\"OK\",\"payload\":{}}");
        assertThat(registry.get("shoppilot_duplicate_submit_total").counter().count())
                .as("回放同样是“拦下一次重复执行”，要与 post-model 那道幂等同口径计数")
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("同一个 token 换了要求：指纹不等就放行给正常链路，不误回放")
    void replayMissesWhenTheFingerprintDiffers() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\"}");

        Optional<IdempotencyService.Replay> replay =
                service.lookupByClientToken("T001", "C001", "tok-1", "fp-另一个要求");

        assertThat(replay).isEmpty();
    }

    @Test
    @DisplayName("派生 token 不写索引：它算在模型之后，客户端也无从复用")
    void derivedTokensAreNeverIndexed() {
        // clientToken 传 null = 走派生 token
        firstExecutionSucceeds("T001", "C001", null, null, "{\"status\":\"OK\"}");

        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT)).isEmpty();
        assertThat(store.keySet())
                .as("派生 token 那条路不该在请求级索引里留下任何键")
                .noneMatch(key -> key.startsWith("shoppilot:idem:req:"));
    }

    @Test
    @DisplayName("索引不串买家、不串租户：同 token 也不互通")
    void replayDoesNotCrossBuyersOrTenants() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\"}");

        assertThat(service.lookupByClientToken("T001", "C002", "tok-1", FINGERPRINT)).isEmpty();
        assertThat(service.lookupByClientToken("T002", "C001", "tok-1", FINGERPRINT)).isEmpty();
        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT)).isPresent();
    }

    @Test
    @DisplayName("被业务拒绝的那一次不留索引：abandon 把指针与结果一起清掉")
    void abandonRemovesTheRequestIndexToo() {
        IdempotencyService.Guard guard = service.begin("T001", "C001", ToolName.APPLY_REFUND,
                refund("90001"), "tok-1", FINGERPRINT);
        service.complete("T001", "C001", ToolName.APPLY_REFUND, guard, "{\"status\":\"OK\"}");
        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT)).isPresent();

        service.abandon("T001", "C001", ToolName.APPLY_REFUND, guard);

        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT))
                .as("留下一个指向已删结果的指针，下次重试会拿到空回放")
                .isEmpty();
    }

    @Test
    @DisplayName("结果被 TTL 收走时返回空：没有可信的“上一次”就走正常链路")
    void replayMissesWhenTheResultIsGone() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\"}");
        // 模拟首次结果先于指针过期
        store.keySet().removeIf(key -> key.startsWith("shoppilot:idem:") && !key.startsWith("shoppilot:idem:req:"));

        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT)).isEmpty();
    }

    @Test
    @DisplayName("首次还挂在 PENDING 上不算结果：不能把“正在跑”回放成“已完成”")
    void pendingIsNotAReplayableResult() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\"}");
        store.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("shoppilot:idem:")
                        && !entry.getKey().startsWith("shoppilot:idem:req:"))
                .forEach(entry -> entry.setValue("__PENDING__"));

        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT))
                .as("指针指向一个还没落定的占位，此时回放出去的会是一个空结果")
                .isEmpty();
    }

    @Test
    @DisplayName("没有 token 或没有指纹时不查索引：与 ADR 0008 时期逐字一致")
    void noLookupWithoutTokenOrFingerprint() {
        firstExecutionSucceeds("T001", "C001", "tok-1", FINGERPRINT, "{\"status\":\"OK\"}");

        assertThat(service.lookupByClientToken("T001", "C001", null, FINGERPRINT)).isEmpty();
        assertThat(service.lookupByClientToken("T001", "C001", "  ", FINGERPRINT)).isEmpty();
        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", null)).isEmpty();
        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", "  ")).isEmpty();
    }

    @Test
    @DisplayName("Redis 停机时返回空：可用性优先，照常走模型（与 begin 同一口径）")
    void lookupBypassesWhenRedisIsUnavailable() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("Unable to connect to Redis"));

        assertThat(service.lookupByClientToken("T001", "C001", "tok-1", FINGERPRINT)).isEmpty();
    }
}
