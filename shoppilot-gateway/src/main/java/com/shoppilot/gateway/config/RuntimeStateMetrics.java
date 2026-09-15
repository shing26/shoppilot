package com.shoppilot.gateway.config;

import com.shoppilot.gateway.cache.WriteBackPool;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 运行时状态的自注册指标（票 29，ADR 0030）。
 *
 * <p>四组读数都不另造判定：熔断取进程里真用的 {@link CircuitBreaker}，依赖取
 * {@code deps} 健康组实际成员的 {@link HealthEndpoint} 读数，dev 默认值取观测面同一个
 * {@link DevDefaultsPolicy}，写回队列取 {@link WriteBackPool#queueDepth()}。这是一条
 * pull-based 的观测路径，值只在抓取时计算，不随请求流量自己漂移。
 *
 * <p>不引 {@code resilience4j-micrometer}：本仓只需要 circuitbreaker 本体，
 * 状态 gauge 与迁移 counter 在这里成对登记。
 */
@Component
public class RuntimeStateMetrics {

    static final String CIRCUIT_STATE = "shoppilot_circuit_state";
    static final String CIRCUIT_TRANSITION_TOTAL = "shoppilot_circuit_transition_total";
    static final String DEPENDENCY_UP = "shoppilot_dependency_up";
    static final String DEV_DEFAULTS_IN_USE = "shoppilot_dev_defaults_in_use";
    static final String WRITEBACK_QUEUE_DEPTH = "shoppilot_writeback_queue_depth";

    public RuntimeStateMetrics(CircuitBreaker bizMockCircuitBreaker,
                               HealthEndpoint healthEndpoint,
                               HealthEndpointGroups healthGroups,
                               HealthContributorRegistry healthContributors,
                               DevDefaultsPolicy devDefaults,
                               WriteBackPool writeBackPool,
                               MeterRegistry registry) {
        Gauge.builder(CIRCUIT_STATE, bizMockCircuitBreaker, RuntimeStateMetrics::circuitStateCode)
                .description("biz-mock 熔断状态：0=CLOSED，1=OPEN/FORCED_OPEN，2=HALF_OPEN")
                .register(registry);
        bizMockCircuitBreaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            Counter.builder(CIRCUIT_TRANSITION_TOTAL)
                    .description("两次抓取之间的熔断状态迁移次数")
                    .tags("from", transition.getFromState().name(), "to", transition.getToState().name())
                    .register(registry)
                    .increment();
        });

        HealthEndpointGroup dependencyGroup = healthGroups.get("deps");
        if (dependencyGroup == null) {
            throw new IllegalStateException("缺少 deps 健康组，无法登记 shoppilot_dependency_up");
        }
        healthContributors.stream()
                .map(NamedContributor::getName)
                .filter(dependencyGroup::isMember)
                .sorted()
                .forEach(dependency -> Gauge.builder(DEPENDENCY_UP, healthEndpoint,
                                endpoint -> dependencyUp(endpoint, dependency))
                        .description("可降级依赖是否可用，与 deps 健康组同源：UP=1，其余=0")
                        .tag("dependency", dependency)
                        .register(registry));

        Gauge.builder(DEV_DEFAULTS_IN_USE, devDefaults,
                        policy -> policy.devDefaultsInUse().isEmpty() ? 0d : 1d)
                .description("是否正在使用仓库默认凭证，与观测面 devDefaultsInUse 同源")
                .register(registry);

        Gauge.builder(WRITEBACK_QUEUE_DEPTH, writeBackPool, WriteBackPool::queueDepth)
                .description("缓存写回队列当前积压笔数；票 27 的 dropped/caller_runs 计数与本 gauge 同组")
                .register(registry);
    }

    private static double circuitStateCode(CircuitBreaker circuitBreaker) {
        return switch (circuitBreaker.getState()) {
            case CLOSED, DISABLED, METRICS_ONLY -> 0d;
            case OPEN, FORCED_OPEN -> 1d;
            case HALF_OPEN -> 2d;
        };
    }

    private static double dependencyUp(HealthEndpoint healthEndpoint, String dependency) {
        HealthComponent component = healthEndpoint.healthForPath("deps", dependency);
        return component != null && Status.UP.equals(component.getStatus()) ? 1d : 0d;
    }
}
