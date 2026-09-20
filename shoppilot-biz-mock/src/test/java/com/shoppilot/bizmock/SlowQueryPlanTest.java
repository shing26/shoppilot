package com.shoppilot.bizmock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢查询优化前后对照（round18 票 43 / ADR 0041）。
 *
 * <p>本类同时是**守卫**与**可复跑的测量入口**：
 * <ul>
 *   <li>守卫：V2 迁移建的索引必须在库里，且复核队列查询在有索引时执行计划必须用上它。
 *       删掉 {@code V2__index_feedback_review.sql}，{@code v2IndexesExist} 当场变红。</li>
 *   <li>测量：在同一个进程里先量「有索引」再 {@code drop index} 量「无索引」——两次读数共享
 *       同一份数据与同一台机器状态，而不是另跑一次删掉迁移。读数落
 *       {@code target/slow-query-plan-readings.txt}，docs 里那份产物就是它的抄录。</li>
 * </ul>
 *
 * <p><b>本类记录了一个被否决的优化</b>：{@code tickets(tenant_id, created_at)} 能让计划从扫表
 * 变成走索引，但实测一致让耗时变差，所以没有落进 V2。{@link #rejectedTicketListIndex()} 把那笔
 * 测量留在仓里，免得下一个人再试一遍同一个想法。理由与归因见
 * {@code docs/slow-query-optimization-2026-09-21.md}。
 *
 * <p><b>为什么复核队列要扫一遍选择性</b>：索引的收益取决于谓词选择性。本仓第一版只取 1% 待办，
 * 结论含混；把 0.01% / 0.1% / 1% / 10% 四档都量出来，交叉点自己显形，而不是挑一个好看的数字交差。
 *
 * <p>用独立的内存库（{@code mem:slowquery}）而不是默认的 {@code mem:shoppilot}：
 * 本类要往表里灌两万行并临时删索引，跑在共享库上会污染同 JVM 的其它测试类。
 *
 * <p>口径边界：H2 内存库的绝对耗时不代表生产，见产物文档。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:slowquery;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class SlowQueryPlanTest {

    /** 灌进两张表的行数。两万行足以让 H2 的代价模型在扫表与索引之间做出可观测的选择。 */
    private static final int ROWS = 20_000;

    /** 复核队列待办比例的档位：每 N 行留 1 行 PENDING。N 越大谓词越有选择性。 */
    private static final int[] PENDING_EVERY_LEVELS = {10, 100, 1_000, 10_000};

    private static final int FEEDBACK_ITERATIONS = 200;
    /** 工单列表单次是毫秒量级，采样少时 p95 只是某个离群点；100 次才让 p95 有意义。 */
    private static final int TICKET_ITERATIONS = 100;

    /**
     * 与 FeedbackService.findByReviewStatusOrderByCreatedAtDesc 同形：Hibernate 生成的是全列 select
     * 加上 @TenantId 拼出的 tenant_id 谓词。**必须用 select \* 而不是 select id** —— 只取 id 时
     * 索引可能变成覆盖索引，量出来的就不是真实查询的账。
     */
    private static final String REVIEW_QUEUE_SQL =
            "select * from feedback where tenant_id = 't1' and review_status = 'PENDING' order by created_at desc";

    /** 与 BizMockService.findAllByOrderByCreatedAtDesc 同形（同样带 @TenantId 谓词、同样全列）。 */
    private static final String TICKET_LIST_SQL =
            "select * from tickets where tenant_id = 't1' order by created_at desc";

    private static final String IDX_FEEDBACK = "IDX_FEEDBACK_REVIEW";
    private static final String IDX_TICKET = "IDX_TICKET_CREATED";

    /**
     * 读数落盘的位置。**不走 stdout**：surefire 对每个测试方法的输出捕获不可靠（实测同一个类里
     * 一个方法的 println 会整个丢失），而产物必须可引用。
     */
    private static final Path READINGS = Path.of("target", "slow-query-plan-readings.txt");

    private static final List<String> LINES = new ArrayList<>();

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void startReadings() throws IOException {
        LINES.clear();
        Files.deleteIfExists(READINGS);
    }

    @AfterAll
    static void writeReadings() throws IOException {
        Files.createDirectories(READINGS.getParent());
        Files.write(READINGS, LINES, StandardCharsets.UTF_8);
        System.out.println("=== SLOWQUERY readings written to " + READINGS.toAbsolutePath());
    }

    private static void record(String line) {
        LINES.add(line);
        System.out.println(line);
    }

    @Test
    @DisplayName("V2 迁移建的索引在库中")
    void v2IndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "select upper(index_name) from information_schema.indexes where table_schema = 'PUBLIC'", String.class);
        assertThat(indexes).contains(IDX_FEEDBACK);
    }

    @Test
    @DisplayName("复核队列：索引被采用，并扫出收益随选择性变化的四档读数")
    void reviewQueueBeforeAfter() {
        for (int pendingEvery : PENDING_EVERY_LEVELS) {
            seedFeedback(pendingEvery);
            int pending = countWhere("feedback", "review_status = 'PENDING'");

            PlanAndLatency after = measure(REVIEW_QUEUE_SQL, FEEDBACK_ITERATIONS);
            assertThat(after.plan()).as("有索引时计划必须用上 idx_feedback_review").contains(IDX_FEEDBACK);

            dropFeedbackIndex();
            PlanAndLatency before = measure(REVIEW_QUEUE_SQL, FEEDBACK_ITERATIONS);
            assertThat(before.plan()).as("去掉索引后计划里不该再出现 idx_feedback_review")
                    .doesNotContain(IDX_FEEDBACK);
            createFeedbackIndex();

            record("review-queue rows=" + ROWS + " pending=" + pending + " (1/" + pendingEvery + ")");
            record("review-queue   BEFORE plan: " + before.plan());
            record("review-queue   BEFORE p50=" + before.p50Micros() + "us p95=" + before.p95Micros() + "us");
            record("review-queue   AFTER  plan: " + after.plan());
            record("review-queue   AFTER  p50=" + after.p50Micros() + "us p95=" + after.p95Micros() + "us");
        }
    }

    /**
     * 被否决的那笔优化，留在这里当可复跑的记录：{@code tickets(tenant_id, created_at)} 让计划从
     * {@code TICKETS.tableScan} 变成 {@code IDX_TICKET_CREATED: TENANT_ID = 't1'}，但三次连跑
     * p50 一致比扫表差 10~45%，所以没有落进 V2。索引由本方法临时建、测完删掉。
     */
    @Test
    @DisplayName("被否决的工单索引：计划变好但耗时一致变差")
    void rejectedTicketListIndex() {
        seedTickets();

        PlanAndLatency before = measure(TICKET_LIST_SQL, TICKET_ITERATIONS);
        assertThat(before.plan()).as("无索引时是扫表").contains("tableScan");

        jdbc.execute("create index idx_ticket_created on tickets (tenant_id, created_at)");
        try {
            PlanAndLatency after = measure(TICKET_LIST_SQL, TICKET_ITERATIONS);
            assertThat(after.plan()).as("有索引时计划确实用上了它").contains(IDX_TICKET);

            record("ticket-list rows=" + countOf("tickets") + "  [索引已否决，未落进 V2]");
            record("ticket-list   BEFORE plan: " + before.plan());
            record("ticket-list   BEFORE p50=" + before.p50Micros() + "us p95=" + before.p95Micros() + "us");
            record("ticket-list   AFTER  plan: " + after.plan());
            record("ticket-list   AFTER  p50=" + after.p50Micros() + "us p95=" + after.p95Micros() + "us");
        } finally {
            jdbc.execute("drop index idx_ticket_created");
        }
    }

    private void seedFeedback(int pendingEvery) {
        jdbc.execute("delete from feedback");
        jdbc.execute("""
                insert into feedback (id, tenant_id, customer_id, conversation_id, verdict, review_status, created_at)
                select 'F' || x, 't1', 'C001', 'conv-' || x, 'DOWN',
                       case when mod(x, %d) = 0 then 'PENDING' else 'REVIEWED' end,
                       timestamp with time zone '2026-01-01 00:00:00+00' + (x * interval '1' second)
                from system_range(1, %d)
                """.formatted(pendingEvery, ROWS));
    }

    private void seedTickets() {
        jdbc.execute("delete from tickets");
        jdbc.execute("""
                insert into tickets (id, tenant_id, customer_id, reason, user_query, transcript, status, created_at)
                select 'T' || x, 't1', 'C001', 'USER_REQUESTED', 'q' || x, 'transcript', 'OPEN',
                       timestamp with time zone '2026-01-01 00:00:00+00' + (x * interval '1' second)
                from system_range(1, %d)
                """.formatted(ROWS));
    }

    private void dropFeedbackIndex() {
        jdbc.execute("drop index idx_feedback_review");
    }

    private void createFeedbackIndex() {
        jdbc.execute("create index if not exists idx_feedback_review on feedback (review_status, created_at)");
    }

    private int countOf(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countWhere(String table, String predicate) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + predicate, Integer.class);
    }

    /** 取一次执行计划 + 重复测量的耗时分位。计划里的换行压成 " | " 便于抄录成单行读数。 */
    private PlanAndLatency measure(String sql, int iterations) {
        String plan = jdbc.queryForList("explain " + sql, String.class).stream()
                .reduce((a, b) -> a + " | " + b).orElse("")
                .replaceAll("\\s*\\n\\s*", " | ");

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            drain(sql);
            samples[i] = (System.nanoTime() - started) / 1_000L;
        }
        java.util.Arrays.sort(samples);
        return new PlanAndLatency(plan, samples[iterations / 2], samples[(int) Math.round(iterations * 0.95) - 1]);
    }

    /**
     * 把结果集读干净但**不物化**：逐行读**每一列**，等价于 {@code queryForList(sql)} 在数据库侧
     * 的全部工作，但不把两万行 × 十一列（feedback 的 rule_ids 还是 CLOB）攒成一个 List&lt;Map&gt; ——
     * 那样每轮迭代都造一份几 MB 的垃圾，本机内存紧张时会把分叉 JVM 直接压崩（实测有过）。
     *
     * <p>**必须读全列**：只读第一列时 JDBC 驱动不必搬运宽行，工单列表的 p50 会从毫秒量级掉到
     * 微秒量级，量出来的就不是 {@code select *} 的账了。
     */
    private void drain(String sql) {
        jdbc.query(sql, rs -> {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int c = 1; c <= columns; c++) {
                    rs.getObject(c);
                }
            }
            return null;
        });
    }

    private record PlanAndLatency(String plan, long p50Micros, long p95Micros) {
    }
}
