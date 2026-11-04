package com.shoppilot.ticket.integration;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 迁移集与实体映射的真实执行（round48 票 204 / ADR 0083）。
 *
 * <p>默认档跑 H2：{@code db/migration-postgresql} 的方言 DDL、{@code ddl-auto: validate}
 * 在 PG 上是否成立、{@code @Lob} 到底落成什么列型，**都没有被真正执行过**。
 * 本类用与 {@code docker-compose.yml} 同镜像的容器补上这一段，只在 {@code -Pintegration}
 * 下由 failsafe 执行（nightly 是保证 Docker 可用的执行点）。
 *
 * <p><b>没有 Docker 时整类 skip，不是 pass</b>（{@code disabledWithoutDocker}）：
 * 跳过只说明这段证据缺口仍在，nightly 的绿才是「实际执行过」。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("postgres")
class PostgresSchemaIT {

    /** 与 compose 的持久档同镜像：生产用的就是 pgvector 打包的 PG16。 */
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
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway 在真 PG 上跑完 V1-V3，且每条迁移都是 success")
    void appliesEveryPostgresMigration() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "select version, success from flyway_schema_history order by installed_rank");

        assertThat(history).as("PG 迁移集 V1-V3 都必须落账").hasSizeGreaterThanOrEqualTo(3);
        assertThat(history).extracting(row -> row.get("version"))
                .as("版本钟与 H2 侧同步（V2 是占位，V3 是 resolution_note）")
                .contains("1", "2", "3");
        assertThat(history).allSatisfy(row ->
                assertThat(row.get("success")).as("迁移失败不会被 Boot 放过，这里再钉一次").isEqualTo(true));
    }

    @Test
    @DisplayName("种子与索引在 PG 上真的存在：六条分流规则 + 取件索引")
    void seedsRoutingRulesAndIndex() {
        Integer enabledRules = jdbc.queryForObject(
                "select count(*) from routing_rules where enabled = true", Integer.class);
        assertThat(enabledRules).as("四种来源 + 通配 + 情绪升级 = 六条种子").isEqualTo(6);

        Integer queueIndex = jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and indexname = 'idx_ticket_queue_order'",
                Integer.class);
        assertThat(queueIndex).as("实体注解导不出这条手工索引，它只能由迁移产生").isEqualTo(1);
    }

    @Test
    @DisplayName("transcript / payload 在真 PG 上落 oid——ADR 0061 记录的三选一取舍，H2 证不了")
    void lobColumnsLandAsLargeObjects() {
        List<String> types = jdbc.queryForList("""
                select column_name || '=' || data_type
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'tickets'
                   and column_name in ('transcript', 'payload')
                 order by column_name
                """, String.class);

        assertThat(types).containsExactly("payload=oid", "transcript=oid");
    }
}
