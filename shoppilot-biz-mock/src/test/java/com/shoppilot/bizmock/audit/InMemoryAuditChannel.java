package com.shoppilot.bizmock.audit;

import com.shoppilot.tool.audit.AuditEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 内存版审计通道——**只存在于测试源集**（所有者裁定 D）。
 *
 * <p>它存在的唯一理由是让「发布 → 消费 → ACK → 幂等」这条链路在 CI 上可被钉死，
 * 因为 CI runner 上没有 Redis。
 *
 * <p>它模拟了三条真实语义，缺一条这套测试就等于没测：
 * <ol>
 *   <li>**至少一次投递**：同一条消息可以被消费多次（重新入队），所以消费端必须幂等；</li>
 *   <li><b>pending</b>：handler 拒绝的事件留在队列里，下次还在；</li>
 *   <li><b>不去重</b>：真实 Redis Streams 就会把未 ack 的消息再投一次，
 *       所以这里也照投——去重是<b>消费端</b>的活（唯一索引），通道替它做了就等于没测。</li>
 *   <li><b>不可用开关</b>：{@link #unavailable} 让测试能走「通道挂了 → 直写兜底」那条路。</li>
 * </ol>
 */
public class InMemoryAuditChannel implements AuditChannel {

    private final Deque<AuditEvent> pending = new ArrayDeque<>();
    private volatile boolean unavailable;

    /** 模拟 Redis 挂掉：publish 不可用，调用方应走直写兜底。 */
    public void unavailable() {
        this.unavailable = true;
    }

    /** 恢复可用。测试之间共用同一个通道实例，不复原会把后面的用例连坐掉。 */
    public void markAvailable() {
        this.unavailable = false;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** 模拟「至少一次」的重投：把已消费过的消息再放回队列一次。 */
    public void redeliver(AuditEvent event) {
        pending.addLast(event);
    }

    @Override
    public void publish(AuditEvent event) {
        if (unavailable) {
            throw new IllegalStateException("通道不可用");
        }
        pending.addLast(event);
    }

    @Override
    public int consume(String consumerName, AuditChannel.AuditEventHandler handler) {
        int processed = 0;
        // 复制一份再遍历：handler 里可能又往队列里塞东西（重投），直接遍历会 ConcurrentModification
        for (AuditEvent event : new ArrayDeque<>(pending)) {
            if (handler.handle(event)) {
                pending.remove(event);
                processed++;
            }
        }
        return processed;
    }

    @Override
    public boolean available() {
        return !unavailable;
    }
}