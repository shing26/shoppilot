package com.shoppilot.gateway.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写回池的停机与饱和行为（票 27）。全部不起容器：一个真线程池、闩与计数器。
 *
 * <p>三格防的是「静默丢」：宽限被摘掉（直接强制停）、丢弃不计数、关闭后的提交被悄悄扔掉
 * ——每一条都是原缺陷的形状，摘掉对应防线本文件当场判红。
 */
class WriteBackPoolTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WriteBackPool pool = new WriteBackPool(1, 1, 0L, 2, registry);

    private double count(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? -1d : counter.count();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @DisplayName("宽限期内在飞与排队的写回全部跑完，dropped 一格不涨")
    void drainsInFlightAndQueuedWithinGrace() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();

        pool.submit(() -> {
            started.countDown();
            try {
                hold.await(2, TimeUnit.SECONDS);
                // 释放后再磨 150ms：close 到来时这一笔必须仍在飞，
                // 「摘掉宽限直接强制停」的变异才必然把排队那一笔抓现行
                Thread.sleep(150);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            executed.incrementAndGet();
        });
        await(started);
        pool.submit(executed::incrementAndGet); // 排队那一笔

        hold.countDown();
        pool.close(Duration.ofSeconds(5));

        assertThat(executed.get()).isEqualTo(2);
        assertThat(count("shoppilot_writeback_dropped_total")).isZero();
    }

    @Test
    @DisplayName("宽限耗尽才丢弃，且丢了几笔在计数器上看得见")
    void dropsOnlyAfterGraceExpiresAndCountsThem() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();

        pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000); // 在飞那一笔：关不掉它，只能等宽限耗尽
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        await(started);
        pool.submit(executed::incrementAndGet);
        pool.submit(executed::incrementAndGet);

        pool.close(Duration.ofMillis(50)); // 宽限远短于在飞那一笔，必然走到强制停

        assertThat(executed.get()).isZero();
        assertThat(count("shoppilot_writeback_dropped_total")).isEqualTo(2d);
    }

    @Test
    @DisplayName("池关后的提交走调用线程代跑：最坏是慢，不是丢，也不是抛")
    void submitAfterCloseRunsOnTheCallingThread() {
        pool.close(Duration.ofSeconds(1));

        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Thread> runner = new AtomicReference<>();
        pool.submit(() -> {
            ran.set(true);
            runner.set(Thread.currentThread());
        });

        assertThat(ran).isTrue();
        assertThat(runner.get()).isSameAs(Thread.currentThread());
        assertThat(count("shoppilot_writeback_caller_runs_total")).isEqualTo(1d);
        assertThat(count("shoppilot_writeback_dropped_total")).isZero();
    }

    @Test
    @DisplayName("队列满时请求线程代跑被计数，没有一笔写回被悄悄扔掉")
    void saturatedQueueRunsInlineAndCountsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean inlineRan = new AtomicBoolean();
        AtomicReference<Thread> inlineRunner = new AtomicReference<>();

        pool.submit(() -> {
            started.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        await(started);
        pool.submit(() -> {
        }); // 队列容量 2 的那格（池夹具 max=1，线程全忙）
        pool.submit(() -> {
        });
        pool.submit(() -> { // 第三个排队位被拒：代跑发生在提交这一行的线程上
            inlineRan.set(true);
            inlineRunner.set(Thread.currentThread());
        });

        assertThat(inlineRan).isTrue();
        assertThat(inlineRunner.get()).isSameAs(Thread.currentThread());
        assertThat(count("shoppilot_writeback_caller_runs_total")).isEqualTo(1d);

        hold.countDown();
        pool.close(Duration.ofSeconds(5));
        assertThat(count("shoppilot_writeback_dropped_total")).isZero();
    }

    @Test
    @DisplayName("队列深度随实际积压起落，饱和前的读数与饱和中的读数分得开")
    void queueDepthTracksRealBacklog() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);

        assertThat(pool.queueDepth()).isZero();
        pool.submit(() -> {
            started.countDown();
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        await(started);
        pool.submit(() -> {
        });
        pool.submit(() -> {
        });
        assertThat(pool.queueDepth()).isEqualTo(2); // 线程被占住，两笔都在队列里压着

        hold.countDown();
        pool.close(Duration.ofSeconds(5));
        assertThat(pool.queueDepth()).isZero();
    }
}
