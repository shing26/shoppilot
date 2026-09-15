package com.shoppilot.gateway.cache;

import com.shoppilot.gateway.identity.RequestTrace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 缓存写回的停机 seam（票 27，ADR 0030）。写回线程池从状态机手里搬进来，
 * 停机与饱和两条路都在这里收口，外面只认 {@link #submit} 与 {@link #close(Duration)}。
 *
 * <p>停机：{@code close} 先婉拒新单（shutdown），给在飞与排队的写回一段宽限
 * （awaitTermination），宽限耗尽才强制停（shutdownNow），被强制停扔下的每一笔
 * 记进 {@code shoppilot_writeback_dropped_total}——静默丢是原缺陷，计数是它的解药。
 *
 * <p>饱和与关闭后的提交：一律调用线程代跑并计 {@code shoppilot_writeback_caller_runs_total}。
 * 不能用现成的 {@code CallerRunsPolicy}：它在池 shutdown 之后是直接扔掉任务（它的源码写着
 * {@code if (!e.isShutdown()) r.run();}），恰好复刻本类要修的那个洞，所以策略自己实现。
 *
 * <p>{@link #submit} 内部自带 {@link RequestTrace#wrap}：提交点不必各自记得包坐标，
 * 新加提交点也就静默丢链路号那条欠账（round13 Further Notes 登过）少一处能犯。
 */
public class WriteBackPool {

    /** 公共停机预算里分给写回池 drain 的那 5 秒（HTTP 收尾用 Spring 默认 30s，总预算 35s 写在 README 运维段）。 */
    public static final Duration DEFAULT_DRAIN_GRACE = Duration.ofSeconds(5);

    private final ThreadPoolExecutor executor;
    private final Counter dropped;
    private final Counter callerRuns;

    public WriteBackPool(int coreSize, int maxSize, long keepAliveSeconds, int queueCapacity,
                         MeterRegistry registry) {
        this.dropped = Counter.builder("shoppilot_writeback_dropped_total")
                .description("停机宽限耗尽后被强制丢弃的写回笔数")
                .register(registry);
        this.callerRuns = Counter.builder("shoppilot_writeback_caller_runs_total")
                .description("队列饱和或池已关闭时由提交线程代跑的写回次数（命中路径 TP99 的饱和税）")
                .register(registry);
        this.executor = new ThreadPoolExecutor(coreSize, maxSize, keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "cache-write-back");
                    thread.setDaemon(true);
                    return thread;
                },
                this::onRejected);
        if (keepAliveSeconds > 0) {
            // 与旧 AsyncConfig 同款：闲下来的写回线程要能退场；测试夹具用 0 存活时间，跳过
            this.executor.allowCoreThreadTimeOut(true);
        }
    }

    /** 提交一笔写回。日志坐标由本方法负责包装，任务体只写业务。 */
    public void submit(Runnable task) {
        executor.execute(RequestTrace.wrap(task));
    }

    /** 当前压在队列里等执行的写回笔数（不含在飞）。供饱和观测读数，票 29 第四组挂 gauge 用它。 */
    public int queueDepth() {
        return executor.getQueue().size();
    }

    /** Spring 容器销毁回调用：无参关闭走默认 5 秒宽限。 */
    public void close() {
        close(DEFAULT_DRAIN_GRACE);
    }

    /**
     * 停机：婉拒新单 → 宽限内等落完 → 超时才强制停并把扔下的笔数记进 dropped。
     * 宽限内正常跑完的路径一格计数都不涨——dropped 非零本身就是一次事故信号。
     *
     * <p>dropped 的边界是实话：数的是「队列里没来得及被线程拿走」的那些。
     * 已被拿走却在强制停时被打断的在飞笔不在此数内——它走的是任务体自己的失败日志那条路，
     * 两边各算各的，不重复计，也不假装一条都不漏。
     */
    public void close(Duration grace) {
        executor.shutdown();
        List<Runnable> stranded;
        try {
            if (executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                return;
            }
            stranded = executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            stranded = executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dropped.increment(stranded.size());
    }

    private void onRejected(Runnable task, ThreadPoolExecutor pool) {
        // 队列满、或池已关闭后仍到达：都在提交线程上当场跑完。
        // 代价是这一次请求慢（远程写回换了主人），收益是一笔都不丢。
        callerRuns.increment();
        task.run();
    }
}
