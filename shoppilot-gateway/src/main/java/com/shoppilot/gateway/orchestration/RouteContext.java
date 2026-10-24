package com.shoppilot.gateway.orchestration;

import java.util.List;
import java.util.Map;

/**
 * 请求级路由上下文（ADR 0072）。与 ChannelContext 同族：由编排线程设置，降级落单时读取。
 *
 * <p>它只携带**本轮路由事实**，不是授权上下文；虚拟线程复用必须显式清理。
 * H2 起还携带 confirmed slots 与 blocked reason，blocked Handoff 的可查工单靠它带走证据。
 */
public final class RouteContext {

    private static final ThreadLocal<Snapshot> SNAPSHOT = new ThreadLocal<>();

    private RouteContext() {
    }

    @SuppressWarnings("java:S107")
    public record Snapshot(AgentRole role, List<HandoffStep> trail, Map<String, String> confirmedSlots,
                           String normalizedIntent, HandoffBlockReason blockedReason,
                           String promptVersion, String policyVersion) {

        public Snapshot {
            trail = trail == null ? List.of() : List.copyOf(trail);
            confirmedSlots = confirmedSlots == null ? Map.of() : Map.copyOf(confirmedSlots);
        }

        public static Snapshot unrouted() {
            return new Snapshot(null, List.of(), Map.of(), null, null, null, null);
        }
    }

    public static void set(Snapshot snapshot) {
        SNAPSHOT.set(snapshot == null ? Snapshot.unrouted() : snapshot);
    }

    /** 没跑到路由层就降级时返回空快照；落单 payload 不伪造路由事实。 */
    public static Snapshot snapshot() {
        Snapshot snapshot = SNAPSHOT.get();
        return snapshot == null ? Snapshot.unrouted() : snapshot;
    }

    public static void clear() {
        SNAPSHOT.remove();
    }
}
