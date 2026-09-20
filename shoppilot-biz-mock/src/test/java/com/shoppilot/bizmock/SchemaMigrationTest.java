package com.shoppilot.bizmock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模式的唯一产生源是 db/migration 的 Flyway 迁移，不再是 Hibernate 的 ddl-auto（round18 票 42 / ADR 0041）。
 *
 * <p>本类钉三件事，缺任何一件「版本化迁移体系」就只是目录名：
 * <ol>
 *   <li>Flyway 真的跑过——{@code flyway_schema_history} 有 V1 的成功记录；</li>
 *   <li>V1 基线真的建出了 9 张表（不是 Hibernate 顺手建的）；</li>
 *   <li>ddl-auto 仍是 validate——有人改回 create 就等于把产生源又交还给 Hibernate，
 *       而那时前两条断言仍会绿（表照样在），所以这条必须单独钉住。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.internal-token=test-internal"
})
class SchemaMigrationTest {

    /** V1__baseline.sql 建出的全部表。加表时这里和迁移文件必须一起改。 */
    private static final List<String> BASELINE_TABLES = List.of(
            "coupons", "customers", "feedback", "logistics",
            "order_addresses", "orders", "refunds", "tenants", "tickets");

    /** V1 里的三个既有索引；它们的依据记在对应实体的 @Table(indexes=...) 上。 */
    private static final List<String> BASELINE_INDEXES = List.of(
            "idx_coupon_customer", "idx_logistics_order", "idx_addr_order");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment env;

    @Test
    @DisplayName("Flyway 的 V1 基线已成功应用")
    void baselineMigrationIsApplied() {
        // Flyway 10 在 H2 上把历史表连表带列都建成带引号的小写名（"flyway_schema_history" /
        // "version" / "success"），不是 H2 默认的大写。裸写表名或列名都会报 not found。
        Integer applied = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"version\" = '1' and \"success\" = true",
                Integer.class);
        assertThat(applied).as("V1 基线必须在 flyway_schema_history 里留下成功记录").isEqualTo(1);

        Integer failed = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = false", Integer.class);
        assertThat(failed).as("历史表里不允许存在失败的迁移").isZero();
    }

    @Test
    @DisplayName("V1 基线建出了 9 张表与 3 个既有索引")
    void baselineCreatesAllTablesAndIndexes() {
        List<String> tables = jdbc.queryForList(
                "select lower(table_name) from information_schema.tables where table_schema = 'PUBLIC'", String.class);
        assertThat(tables).as("V1 基线建出的表").containsAll(BASELINE_TABLES);

        List<String> indexes = jdbc.queryForList(
                "select lower(index_name) from information_schema.indexes where table_schema = 'PUBLIC'", String.class);
        assertThat(indexes).as("V1 基线建出的索引").containsAll(BASELINE_INDEXES);
    }

    @Test
    @DisplayName("ddl-auto 仍是 validate：模式的产生源不许退回 Hibernate")
    void ddlAutoStaysValidate() {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("改回 create/create-drop/update 会让「迁移可回滚」重新变成结构性不适用")
                .isEqualTo("validate");
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
    }
}
