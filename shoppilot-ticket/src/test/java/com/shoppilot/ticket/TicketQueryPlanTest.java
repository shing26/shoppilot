package com.shoppilot.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单查询的执行计划（round23 票 72）。
 *
 * <p><b>round18 票 43 的那笔否决跟着工单表搬到了这里</b>：{@code tickets(tenant_id, created_at)}
 * 让计划从扫表变成走索引，但三次连跑 p50 一致比扫表差 10~45%，所以当年没有落进 V2。
 * 证据留在 {@code docs/slow-query-optimization-2026-09-21.md}，可复跑的判断搬到这里继续守着。
 *
 * <p>而搬家顺带改变了一件事：**坐席台的主查询是有上界的**（按队列取件），
 * 所以这条索引现在是 {@code (queue, priority, created_at)} —— 它有一个真实的查询要用它。
 * 两条断言合起来说的是同一件事：**有上界才有资格要索引**。
 *
 * <p>SQL 一律参数化：即便这里全是常量，拼接出来的语句也是坏习惯——
 * 它会教读的人「测试里拼一下没关系」，而下一个人就会把它抄到生产路径上。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "shoppilot.ticket.internal-token=test-internal"
})
class TicketQueryPlanTest {

    private static final int ROWS = 2_000;
    private static final String IDX_QUEUE = "IDX_TICKET_QUEUE_ORDER";
    private static final String IDX_TENANT_CREATED = "IDX_TICKET_CREATED";

    /** 无上界的全量列表（当年就是这条查询让索引被否决）。 */
    private static final String EXPLAIN_UNBOUNDED_LIST = """
            explain analyze
            select id, tenant_id, customer_id, reason, status, priority, queue, created_at
              from tickets where tenant_id = ?
             order by created_at desc
            """;

    /** 按队列取件：坐席台主查询，有上界。 */
    private static final String EXPLAIN_QUEUE_LIST = """
            explain analyze
            select id, tenant_id, customer_id, reason, status, priority, queue, created_at
              from tickets where queue = ? and status <> 'RESOLVED'
             order by priority asc, created_at asc
            """;

    private static final String INSERT_TICKETS = """
            insert into tickets (id, tenant_id, customer_id, reason, user_query, transcript, status,
                                 created_at, source, priority, queue)
            select 'T' || x, 't1', 'C001', 'USER_REQUESTED', 'q' || x, 'transcript', 'OPEN',
                   timestamp with time zone '2026-01-01 00:00:00+00' + (x * interval '1' second),
                   'DEGRADE',
                   case when mod(x, 3) = 0 then 'high' else 'normal' end,
                   case when mod(x, 4) = 0 then 'REFUND' else 'ESCALATION' end
              from system_range(1, ?)
            """;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("按队列取件坐席台主查询：走 idx_ticket_queue_order（有上界，所以这条索引站得住）")
    void queueScopedQueryUsesItsIndex() throws Exception {
        seedTickets();

        assertThat(explain(EXPLAIN_QUEUE_LIST, "ESCALATION"))
                .as("有上界的查询正是这条索引存在的理由")
                .contains(IDX_QUEUE);
    }

    @Test
    @DisplayName("无上界的全量列表：被否决的 (tenant_id, created_at) 索引没有落进基线")
    void unboundedListStillHasNoTenantTimeIndex() throws Exception {
        seedTickets();

        assertThat(explain(EXPLAIN_UNBOUNDED_LIST, "t1"))
                .as("无上界取全量就是扫表")
                .contains("tableScan");

        // 标识符不能参数化，所以这里写字面量而不是拼常量——拼出来的 DDL 是坏习惯的起点
        jdbc.execute("create index idx_ticket_created on tickets (tenant_id, created_at)");
        try {
            // 计划确实会变好 —— 但 round18 实测耗时一致更差（要逐行回表且无覆盖能力），
            // 所以这条断言钉的是「否决仍然成立」而不是「索引没用」：后者会让一次误判看起来像结论。
            assertThat(explain(EXPLAIN_UNBOUNDED_LIST, "t1"))
                    .as("加索引计划会变好；当年否决的是耗时，不是计划")
                    .contains(IDX_TENANT_CREATED);
        } finally {
            jdbc.execute("drop index idx_ticket_created");
        }
    }

    private String explain(String preparedSql, String... params) throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(preparedSql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                StringBuilder plan = new StringBuilder();
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
                return plan.toString();
            }
        }
    }

    private void seedTickets() {
        jdbc.execute("delete from tickets");
        try (Connection connection = jdbc.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT_TICKETS)) {
            statement.setInt(1, ROWS);
            statement.executeUpdate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}