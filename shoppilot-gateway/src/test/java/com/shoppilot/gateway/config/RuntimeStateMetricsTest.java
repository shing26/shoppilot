package com.shoppilot.gateway.config;

import com.shoppilot.gateway.cache.WriteBackPool;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.DefaultHealthContributorRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.StatusAggregator;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时状态进指标面（票 29）。全部不起容器：真熔断器、真健康端点、真写回池，
 * 只把结果落到 {@link SimpleMeterRegistry} 上断言。
 *
 * <p>四组读数的共同判据是「值随状态变，不随请求变」：状态迁移、依赖变红、默认值切换、
 * 队列积压各自能改变读数，而重复读取或普通请求不会伪造一次状态迁移。
 */
class RuntimeStateMetricsTest {

    @Test
    @DisplayName("熔断状态迁移后 state gauge 与 transition_total 各自对得上")
    void circuitStateGaugeAndTransitionCounterTrackState() {
        try (Fixture fixture = fixture(oneDevDefault())) {
            Gauge state = fixture.gauge("shoppilot_circuit_state");
            assertThat(state.value()).isZero();

            fixture.circuit.transitionToOpenState();
            assertThat(state.value()).isEqualTo(1d);
            assertThat(fixture.transitions("CLOSED", "OPEN")).isEqualTo(1d);

            fixture.circuit.transitionToHalfOpenState();
            assertThat(state.value()).isEqualTo(2d);
            assertThat(fixture.transitions("OPEN", "HALF_OPEN")).isEqualTo(1d);

            fixture.circuit.transitionToClosedState();
            assertThat(state.value()).isZero();
            assertThat(fixture.transitions("HALF_OPEN", "CLOSED")).isEqualTo(1d);

            double beforeRequest = state.value();
            fixture.health.health();
            assertThat(state.value()).isEqualTo(beforeRequest);
        }
    }

    @Test
    @DisplayName("两次抓取之间的闪断只留在 transition_total，state 前后都正常")
    void shortCircuitTransitionSurvivesScrapeWindow() {
        try (Fixture fixture = fixture(oneDevDefault())) {
            Gauge state = fixture.gauge("shoppilot_circuit_state");
            assertThat(state.value()).isZero();

            // 两次读取之间 OPEN 一下又 CLOSED 回来：只看 state 会完全漏掉发生过什么。
            fixture.circuit.transitionToOpenState();
            fixture.circuit.transitionToClosedState();

            assertThat(state.value()).isZero();
            assertThat(fixture.transitions("CLOSED", "OPEN")).isEqualTo(1d);
            assertThat(fixture.transitions("OPEN", "CLOSED")).isEqualTo(1d);
        }
    }

    @Test
    @DisplayName("依赖灯只从 deps 健康组取数，状态变化后 gauge 跟着变")
    void dependencyGaugeReadsTheDepsHealthGroup() {
        try (Fixture fixture = fixture(oneDevDefault())) {
            Gauge qdrant = fixture.gauge("shoppilot_dependency_up", "dependency", "qdrant");
            Gauge elasticsearch = fixture.gauge("shoppilot_dependency_up", "dependency", "elasticsearch");
            Gauge knowledgeBase = fixture.gauge("shoppilot_dependency_up", "dependency", "knowledgeBase");

            assertThat(qdrant.value()).isEqualTo(1d);
            assertThat(elasticsearch.value()).isEqualTo(1d);
            assertThat(knowledgeBase.value()).isEqualTo(1d);

            fixture.qdrantHealth.set(Health.down().build());
            assertThat(qdrant.value()).isZero();
            assertThat(elasticsearch.value()).isEqualTo(1d);
            assertThat(knowledgeBase.value()).isEqualTo(1d);

            fixture.health.health();
            assertThat(qdrant.value()).isZero();
        }
    }

    @Test
    @DisplayName("dev 默认值 gauge 与观测面同一份列表：一处在用为 1，全换掉为 0")
    void devDefaultsGaugeFollowsTheExistingObservationField() {
        try (Fixture withDefault = fixture(oneDevDefault())) {
            Gauge gauge = withDefault.gauge("shoppilot_dev_defaults_in_use");
            assertThat(gauge.value()).isEqualTo(1d);
            withDefault.health.health();
            assertThat(gauge.value()).isEqualTo(1d);
        }

        try (Fixture withoutDefault = fixture(noDevDefaults())) {
            assertThat(withoutDefault.gauge("shoppilot_dev_defaults_in_use").value()).isZero();
        }
    }

    @Test
    @DisplayName("写回队列深度随积压起落，dropped 与 caller_runs 计数都在指标面")
    void writeBackGaugeTracksQueueDepthAndCountersAreRegistered() throws Exception {
        try (Fixture fixture = fixture(oneDevDefault())) {
            Gauge queueDepth = fixture.gauge("shoppilot_writeback_queue_depth");
            assertThat(queueDepth.value()).isZero();

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            fixture.writeBackPool.submit(() -> {
                started.countDown();
                await(hold);
            });
            await(started);
            fixture.writeBackPool.submit(() -> {
            });
            fixture.writeBackPool.submit(() -> {
            });

            assertThat(queueDepth.value()).isEqualTo(2d);
            double beforeRequest = queueDepth.value();
            fixture.health.health();
            assertThat(queueDepth.value()).isEqualTo(beforeRequest);
            assertThat(fixture.registry.find("shoppilot_writeback_dropped_total").counter()).isNotNull();
            assertThat(fixture.registry.find("shoppilot_writeback_caller_runs_total").counter()).isNotNull();

            hold.countDown();
            fixture.writeBackPool.close(Duration.ofSeconds(5));
            assertThat(queueDepth.value()).isZero();
        }
    }

    private static DevDefaultsPolicy oneDevDefault() {
        return new DevDefaultsPolicy("127.0.0.1", DevDefaultsPolicy.JWT_SECRET,
                "real-internal-token", "real-ops-token", true);
    }

    private static DevDefaultsPolicy noDevDefaults() {
        return new DevDefaultsPolicy("127.0.0.1", "real-jwt-secret-0123456789",
                "real-internal-token", "real-ops-token", true);
    }

    private static Fixture fixture(DevDefaultsPolicy devDefaults) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test-bizmock");
        AtomicReference<Health> qdrantHealth = new AtomicReference<>(Health.up().build());
        DefaultHealthContributorRegistry contributors = new DefaultHealthContributorRegistry(Map.of(
                "qdrant", (HealthIndicator) () -> qdrantHealth.get(),
                "elasticsearch", (HealthIndicator) () -> Health.up().build(),
                "knowledgeBase", (HealthIndicator) () -> Health.up().build()));

        Set<String> dependencyNames = Set.of("qdrant", "elasticsearch", "knowledgeBase");
        HealthEndpointGroup depsGroup = mock(HealthEndpointGroup.class);
        when(depsGroup.isMember(anyString())).thenAnswer(invocation ->
                dependencyNames.contains(invocation.getArgument(0)));
        when(depsGroup.getStatusAggregator()).thenReturn(StatusAggregator.getDefault());
        HealthEndpointGroups groups = HealthEndpointGroups.of(depsGroup, Map.of("deps", depsGroup));
        HealthEndpoint health = new HealthEndpoint(contributors, groups, Duration.ofSeconds(1));
        WriteBackPool writeBackPool = new WriteBackPool(1, 1, 0L, 2, registry);
        new RuntimeStateMetrics(circuitBreaker, health, groups, contributors, devDefaults, writeBackPool, registry);
        return new Fixture(registry, circuitBreaker, writeBackPool, health, qdrantHealth);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record Fixture(
            SimpleMeterRegistry registry,
            CircuitBreaker circuit,
            WriteBackPool writeBackPool,
            HealthEndpoint health,
            AtomicReference<Health> qdrantHealth) implements AutoCloseable {

        Gauge gauge(String name, String... tags) {
            Gauge gauge = registry.find(name).tags(tags).gauge();
            assertThat(gauge).as("指标 %s%s 未注册", name, java.util.Arrays.toString(tags)).isNotNull();
            return gauge;
        }

        double transitions(String from, String to) {
            Counter counter = registry.find("shoppilot_circuit_transition_total")
                    .tags("from", from, "to", to)
                    .counter();
            return counter == null ? -1d : counter.count();
        }

        @Override
        public void close() {
            writeBackPool.close(Duration.ofSeconds(5));
        }
    }
}
