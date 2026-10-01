package com.shoppilot.bizmock.audit;

import com.shoppilot.tool.audit.AuditEvent;

/**
 * 审计通道（round23 票 71 / ADR 0054）：事件从生产端到消费端的唯一通路。
 *
 * <p>这个端口只有两个实现：**Redis Streams**（生产）与**内存实现**（只在测试源集里）。
 * 它存在的唯一理由是让「发布 → 消费 → ACK → 幂等 → pending 归零」这条链路能在 CI 上被钉死，
 * 因为 CI runner 上没有 Redis（所有者裁定 D）。内存实现**不进生产包**——
 * 生产路径上如果能拿到它，审计就在本机内存里，跨进程那条路当场失效而且看不出来。
 *
 * <p>窄接口是刻意的：这里没有「订阅任意 topic」的通用 API。本轮只有审计一条流（裁定 C），
 * 为不存在的多 topic 场景设计通用抽象，就是本仓禁止的 speculative generality。
 */
public interface AuditChannel {

    /** 发布一条审计事件。实现要能扛住「下游暂时不可用」——由调用方决定兜底策略。 */
    void publish(AuditEvent event);

    /**
     * 消费一批事件并交给 handler，返回**成功处理**的条数。
     *
     * @param consumerName 消费方名字，进 pending 列表时要能看出是谁卡住了
     */
    int consume(String consumerName, AuditEventHandler handler);

    /** 通道是否可用。不可用时 {@link #publish} 不应被调用——调用方据此走直写兜底。 */
    boolean available();

    /** 处理一条事件，返回 false 表示「不认领」：该条留在 pending 里等人来看。 */
    @FunctionalInterface
    interface AuditEventHandler {
        boolean handle(AuditEvent event);
    }
}