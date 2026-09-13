package com.shoppilot.gateway.identity;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * 一次请求的四个坐标：链路、租户、买家、会话。装填点唯一在 {@link AuthFilter}（ADR 0014 同族规矩）。
 *
 * <p>日志按 {@code traceId} 捞得到人，前提是这条链上每一行都带着它。异步边界（流式工作线程、
 * 缓存写回线程）不会自己继承 ThreadLocal，提交任务处一律经 {@link #wrap(Runnable)} 过一遍。
 *
 * <p>{@code wrap} 复原而非清除：写回池用 CallerRunsPolicy，任务有可能就在请求线程上跑，
 * 收尾若 {@code clear()} 会把请求自己的坐标抹掉，后半程日志全瞎。
 *
 * <p>刻意不引入 {@code io.micrometer:context-propagation}：见 ADR 0027。
 */
public final class RequestTrace {

    public static final String TRACE_ID = "traceId";
    public static final String TENANT_ID = "tenantId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String CONVERSATION_ID = "conversationId";

    private RequestTrace() {
    }

    /**
     * 新起一条链路并把 traceId 装进 MDC，返回它。必须在任何一行日志之前调用。
     *
     * <p>先清一次：容器线程复用，上一笔请求的坐标可能还留在 MDC 里，不清就会串进这一笔。
     */
    public static String start() {
        clear();
        String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID, traceId);
        return traceId;
    }

    /** 当前链路的 traceId；不在链路里返回 null，不抛——读日志坐标不该是条业务规则。 */
    public static String traceId() {
        return MDC.get(TRACE_ID);
    }

    /** 把已验签的三个身份坐标装进 MDC。只认 {@link TenantContext}，与业务查询同一来源。 */
    public static void bind(TenantContext.Identity identity) {
        put(TENANT_ID, identity.tenantId());
        put(CUSTOMER_ID, identity.customerId());
        put(CONVERSATION_ID, identity.conversationId());
    }

    /**
     * 把当前 MDC 快照带进任务，返回包好的任务。提交异步任务处一律用它。
     *
     * <p>捕获发生在提交时（调用线程），运行发生在池线程；运行完把该线程**运行前**的快照装回，
     * 池线程复用时不残留上一笔的坐标，CallerRuns 回调用线程时也不误删调用者的上下文。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = snapshot();
        return () -> {
            Map<String, String> previous = snapshot();
            apply(captured);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /** 清掉本类装填的四个键。其余键留给别的组件，互不越界。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(TENANT_ID);
        MDC.remove(CUSTOMER_ID);
        MDC.remove(CONVERSATION_ID);
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static Map<String, String> snapshot() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return context == null ? Map.of() : context;
    }

    private static void apply(Map<String, String> context) {
        if (context.isEmpty()) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(context);
    }
}
