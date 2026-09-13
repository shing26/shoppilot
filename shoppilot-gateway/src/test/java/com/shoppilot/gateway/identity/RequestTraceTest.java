package com.shoppilot.gateway.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四个请求坐标的载体本体（ADR 0027）。
 *
 * <p>这里只量机制：跨线程带得过去、带过去之后不污染复用线程、以及 CallerRuns 那条最容易写错的分支。
 * 两处真实异步提交点（流式工作线程、缓存写回线程）的接线在
 * {@code TraceCorrelationAcrossAsyncTest} 里量，那才是摘掉 wrap 会当场判红的格子。
 */
class RequestTraceTest {

    @AfterEach
    void wipeContext() {
        RequestTrace.clear();
    }

    @Test
    @DisplayName("start 之后 traceId 就在 MDC 里，且两次调用拿到两个值")
    void startMintsTraceIdIntoMdc() {
        String traceId = RequestTrace.start();

        assertThat(traceId).isNotBlank();
        assertThat(RequestTrace.traceId()).isEqualTo(traceId);

        RequestTrace.clear();
        assertThat(RequestTrace.traceId()).isNull();
        assertThat(RequestTrace.start()).isNotEqualTo(traceId);
    }

    @Test
    @DisplayName("bind 把三个身份坐标按 TenantContext 的原样装进去")
    void bindCarriesAllThreeIdentityKeys() {
        RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));

        assertThat(MDC.get(RequestTrace.TENANT_ID)).isEqualTo("T001");
        assertThat(MDC.get(RequestTrace.CUSTOMER_ID)).isEqualTo("C155");
        assertThat(MDC.get(RequestTrace.CONVERSATION_ID)).isEqualTo("conv-1");
    }

    @Test
    @DisplayName("wrap 过的任务在别的线程上照样认得回这一单")
    void wrapCarriesContextAcrossThreads() throws Exception {
        String traceId = RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(RequestTrace.wrap(() -> seen.set(MDC.getCopyOfContextMap()))).get(5, TimeUnit.SECONDS);
        }
        assertThat(seen.get()).containsEntry(RequestTrace.TRACE_ID, traceId)
                .containsEntry(RequestTrace.TENANT_ID, "T001")
                .containsEntry(RequestTrace.CUSTOMER_ID, "C155")
                .containsEntry(RequestTrace.CONVERSATION_ID, "conv-1");
    }

    @Test
    @DisplayName("复用线程不残留上一单：同一根线程第二次接到没包好的任务，MDC 是空的")
    void reusedThreadKeepsNoResidue() throws Exception {
        RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
        AtomicReference<Map<String, String>> residue = new AtomicReference<>();
        try (ExecutorService single = Executors.newSingleThreadExecutor()) {
            single.submit(RequestTrace.wrap(() -> {
            })).get(5, TimeUnit.SECONDS);
            // 同一根线程再接一单没有坐标的任务：上一单的四个键不许还挂着
            single.submit(() -> residue.set(MDC.getCopyOfContextMap())).get(5, TimeUnit.SECONDS);
        }
        // logback 在 MDC 空的时候给的是 null 而不是空图：这两种都算"没残留"
        assertThat(residue.get()).isNullOrEmpty();
    }

    @Test
    @DisplayName("任务里改 MDC 不许回头污染提交者的快照，也不许影响同一快照的第二次派发")
    void taskMutationsDoNotLeakIntoTheCapturedSnapshot() throws Exception {
        String traceId = RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
        Runnable dirty = RequestTrace.wrap(() -> {
            MDC.put(RequestTrace.CONVERSATION_ID, "conv-hijacked");
            MDC.remove(RequestTrace.TENANT_ID);
        });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.submit(dirty).get(5, TimeUnit.SECONDS);
            // 同一份快照再派一次：如果 wrap 交出去的是共享引用，这一趟带回来的就是被改脏的那份
            AtomicReference<String> conversation = new AtomicReference<>();
            AtomicReference<String> tenant = new AtomicReference<>();
            pool.submit(RequestTrace.wrap(() -> {
                conversation.set(MDC.get(RequestTrace.CONVERSATION_ID));
                tenant.set(MDC.get(RequestTrace.TENANT_ID));
            })).get(5, TimeUnit.SECONDS);

            assertThat(conversation.get()).isEqualTo("conv-1");
            assertThat(tenant.get()).isEqualTo("T001");
        }
        assertThat(MDC.get(RequestTrace.CONVERSATION_ID)).isEqualTo("conv-1");
        assertThat(MDC.get(RequestTrace.TENANT_ID)).isEqualTo("T001");
        assertThat(RequestTrace.traceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("CallerRuns 把任务退回请求线程自己身上时，不许顺手把请求的坐标清掉")
    void callerRunsPolicyKeepsTheCallersOwnContext() throws Exception {
        String traceId = RequestTrace.start();
        RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
        // 与 AsyncConfig 的写回池同形：小池 + 有界队列 + CallerRuns，队列满时任务在提交线程上跑完
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch blockWorker = new CountDownLatch(1);
        try {
            saturated.execute(() -> awaitQuietly(blockWorker));
            saturated.execute(() -> awaitQuietly(blockWorker));
            // 队列已满、线程已满：这一条必然走 CallerRuns，在测试线程自己身上执行
            saturated.execute(RequestTrace.wrap(() -> assertThat(RequestTrace.traceId()).isEqualTo(traceId)));
            blockWorker.countDown();
            saturated.shutdown();
            assertThat(saturated.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            blockWorker.countDown();
        }
        // 收尾之后请求自己仍然看得见这一单：wrap 是复原而不是清除
        assertThat(RequestTrace.traceId()).isEqualTo(traceId);
        assertThat(MDC.get(RequestTrace.TENANT_ID)).isEqualTo("T001");
    }

    @Test
    @DisplayName("日志事件里的四个坐标：同一请求内所有事件同一个 traceId")
    void logEventsInsideOneRequestShareOneTraceId() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestTraceTest.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String traceId = RequestTrace.start();
            RequestTrace.bind(new TenantContext.Identity("T001", "C155", "conv-1"));
            logger.info("第一行");
            logger.info("最后一行");

            assertThat(appender.list).hasSize(2);
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getMDCPropertyMap()).containsEntry(RequestTrace.TRACE_ID, traceId)
                        .containsEntry(RequestTrace.TENANT_ID, "T001")
                        .containsEntry(RequestTrace.CUSTOMER_ID, "C155")
                        .containsEntry(RequestTrace.CONVERSATION_ID, "conv-1");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
