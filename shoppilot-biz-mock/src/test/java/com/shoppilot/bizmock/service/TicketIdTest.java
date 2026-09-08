package com.shoppilot.bizmock.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单号唯一性（2026-09-08 mix80 压测暴露的缺陷）。
 *
 * <p>降级链路的终点就是这张 tickets 表：一旦同一毫秒内的两句话算出同一个号，
 * 主键冲突让 POST /api/tickets 返回 500，"转人工可查"这条否决项当场失守。
 * 所以唯一性不能靠时钟精度，必须靠进程内单调序列。
 */
class TicketIdTest {

    @Test
    @DisplayName("同一毫秒内并发生成的工单号两两不同")
    void uniqueWithinTheSameMillisecond() throws Exception {
        int threads = 16;
        int perThread = 200;
        // 故意用同一个 Instant：把"时钟没走"这一最坏情况固定住，
        // 否则测试会通过毫秒差掩盖真正的碰撞
        Instant sameInstant = Instant.now();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        for (int n = 0; n < perThread; n++) {
                            ids.add(BizMockService.nextTicketId(sameInstant));
                        }
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(ids).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("工单号长度落在列宽 40 以内且前缀可读")
    void staysWithinColumnWidth() {
        String id = BizMockService.nextTicketId(Instant.ofEpochMilli(1_788_871_770_123L));

        assertThat(id).startsWith("T1788871770123-");
        assertThat(id).hasSizeLessThanOrEqualTo(40);
    }
}
