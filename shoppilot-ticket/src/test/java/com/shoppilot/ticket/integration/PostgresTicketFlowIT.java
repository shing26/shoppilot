package com.shoppilot.ticket.integration;

import com.shoppilot.ticket.domain.Ticket;
import com.shoppilot.ticket.domain.TicketStatus;
import com.shoppilot.ticket.repo.TicketRepository;
import com.shoppilot.ticket.tenant.TenantContextHolder;
import com.shoppilot.tool.workitem.TicketSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户作用域的写入与读取在真 PG 上的行为（round48 票 204 / ADR 0083）。
 *
 * <p>H2 是逐列兼容的模式，{@code @TenantId} 的谓词拼接、领取那条条件更新在 CLOB/OID 与
 * 事务边界不同时是否还是同一个语义，只有真库能回答。本类不启动 HTTP、不经路由服务，
 * 直接压仓储层——**要验的是隔离本身，不是它的上层调用方**（后者已有 H2 侧用例）。
 *
 * <p>刻意不加 {@code @Transactional}：{@code HibernateTenantResolver} 的
 * {@code validateExistingCurrentSessions} 为 true，同一 session 里换租户会当场抛异常；
 * 让每次仓储调用各自开会话，才是线上 filter 的实际形状。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("postgres")
class PostgresTicketFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("ticket")
                    .withUsername("ticket_app")
                    .withPassword("ticket_app");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TicketRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("A 店的单 A 店能读、B 店读不到；tenant_id 列落的是真值而不是会话默认")
    void tenantScopedReadsStayOnRealPostgres() {
        TenantContextHolder.set("T-PG-A", "C-A");
        repository.save(newTicket("T-PG-A-1", "T-PG-A", "C-A"));
        TenantContextHolder.set("T-PG-B", "C-B");
        repository.save(newTicket("T-PG-B-1", "T-PG-B", "C-B"));

        TenantContextHolder.set("T-PG-A", "C-A");
        assertThat(repository.findByIdAndTenantId("T-PG-A-1", "T-PG-A")).isPresent();
        assertThat(repository.findByIdAndTenantId("T-PG-B-1", "T-PG-A"))
                .as("拿别人的 id 加自己的租户也读不到").isEmpty();
        assertThat(repository.findByTenantIdAndCustomerIdOrderByCreatedAtDesc("T-PG-A", "C-A"))
                .extracting(Ticket::getId).containsExactly("T-PG-A-1");

        List<String> stored = jdbc.queryForList(
                "select tenant_id from tickets where id = ?", String.class, "T-PG-A-1");
        assertThat(stored).containsExactly("T-PG-A");
    }

    @Test
    @DisplayName("领取的条件更新在真 PG 上仍按租户隔离：B 店领不到 A 店的单")
    void claimStaysTenantScoped() {
        TenantContextHolder.set("T-CLAIM-A", "C-CLAIM");
        repository.save(newTicket("T-CLAIM-1", "T-CLAIM-A", "C-CLAIM"));

        TenantContextHolder.set("T-CLAIM-B", "C-CLAIM");
        assertThat(repository.claim("T-CLAIM-1", "T-CLAIM-B", "bob"))
                .as("跨店领取必须 0 行").isZero();

        TenantContextHolder.set("T-CLAIM-A", "C-CLAIM");
        assertThat(repository.claim("T-CLAIM-1", "T-CLAIM-A", "alice")).isEqualTo(1);
        assertThat(repository.findByIdAndTenantId("T-CLAIM-1", "T-CLAIM-A"))
                .get()
                .satisfies(ticket -> {
                    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED.name());
                    assertThat(ticket.getAssignee()).isEqualTo("alice");
                });
    }

    private static Ticket newTicket(String id, String tenantId, String customerId) {
        Instant now = Instant.now();
        return new Ticket(id, tenantId, customerId, "TOOL_UNAVAILABLE", "查不到订单", "买家: 查不到订单",
                TicketStatus.OPEN.name(), now, "normal", TicketSource.DEGRADE, "ESCALATION", null,
                now.plusSeconds(3600), null);
    }
}
