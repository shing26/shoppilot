package com.shoppilot.gateway.orchestration;

import java.util.List;

/**
 * 请求级路由上下文（ADR 0072）。与 ChannelContext 同族：由编排线程设置，降级落单时读取。
 *
 * <p>它只携带**本轮路由事实**，不是授权上下文；虚拟线程复用必须显式清理。
 */
public final class RouteContext {

    private static final ThreadLocal<AgentRole> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<List<HandoffStep>> TRAIL = new ThreadLocal<>();

    private RouteContext() {
    }

    public record Snapshot(AgentRole role, List<HandoffStep> trail) {
    }

    public static void set(AgentRole role, List<HandoffStep> trail) {
        ROLE.set(role);
        TRAIL.set(trail == null ? List.of() : List.copyOf(trail));
    }

    /** 没跑到路由层就降级时返回空快照；落单 payload 不伪造路由事实。 */
    public static Snapshot snapshot() {
        AgentRole role = ROLE.get();
        List<HandoffStep> trail = TRAIL.get();
        if (role == null && (trail == null || trail.isEmpty())) {
            return new Snapshot(null, List.of());
        }
        return new Snapshot(role, trail == null ? List.of() : List.copyOf(trail));
    }

    public static void clear() {
        ROLE.remove();
        TRAIL.remove();
    }
}
