package com.shoppilot.gateway.channel;

import java.time.Duration;

/**
 * 出站幂等账本（round56 票 225 / ADR 0087）。
 *
 * <p>接口只留两件事：查过没有、记一笔。生产实现落 Redis（跨重启），测试与
 * Redis 不可用时退到进程内 LRU。**跨重启承诺只由 Redis 实现承担**。
 */
public interface DeliveredEventStore {

    boolean isDelivered(String eventId);

    void markDelivered(String eventId);

    /** 进程内兜底：容量有上限，Redis 恢复后仍以 Redis 为准。 */
    static DeliveredEventStore inMemory(Duration unusedTtl) {
        return new InMemoryDeliveredEventStore();
    }

    final class InMemoryDeliveredEventStore implements DeliveredEventStore {

        private static final int CAPACITY = 1000;

        private final java.util.Map<String, Boolean> delivered =
                new java.util.LinkedHashMap<>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                        return size() > CAPACITY;
                    }
                };

        @Override
        public synchronized boolean isDelivered(String eventId) {
            return eventId != null && delivered.containsKey(eventId);
        }

        @Override
        public synchronized void markDelivered(String eventId) {
            if (eventId != null) {
                delivered.put(eventId, Boolean.TRUE);
            }
        }
    }
}
