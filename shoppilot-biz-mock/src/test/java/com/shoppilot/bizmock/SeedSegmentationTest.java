package com.shoppilot.bizmock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed 拆两段（round28 票 101 / ADR 0061）：初始化段（租户）与演示段（买家/压测订单/固定单）
 * 各自独立幂等、独立开关。本用例钉住持久档的默认形态：**租户在、演示数据一条不在**。
 *
 * <p>反方向（demo-data 默认 true 时两段全播）由既有测试组全覆盖——它们每个都要靠
 * 演示订单与买家才能断言，拆段若破坏了默认行为，surefire 里红的不止一个。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        // 独立 H2：本用例断言的是「空演示数据」，共用默认库会撞上别的用例播进去的订单
        "spring.datasource.url=jdbc:h2:mem:seed-segmentation;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "shoppilot.bizmock.seed.customers=5",
        "shoppilot.bizmock.seed.orders=10",
        "shoppilot.bizmock.seed.demo-data=false",
        "shoppilot.bizmock.internal-token=test-internal"
})
class SeedSegmentationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("demo-data=false：初始化段照常播租户，演示段一条不播")
    void initSegmentRunsAndDemoSegmentSkipped() {
        // round32 起是 4 家：三家电商租户 + 一家非电商租户（T004）
        assertThat(jdbc.queryForObject("select count(*) from tenants", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from customers", Integer.class)).isEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("round32：非电商租户只建租户行 —— 演示段关着它也在，且它名下一条订单都没有")
    void nonShopTenantIsATenantRowAndNothingElse() {
        assertThat(jdbc.queryForObject("select name from tenants where id = 'T004'", String.class))
                .isEqualTo("云栖酒店");
        assertThat(jdbc.queryForObject("select count(*) from orders where tenant_id = 'T004'", Integer.class))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("round32：店铺名以 knowledge/ 的店铺级语料为准，不再与语料各叫一个名字")
    void shopNamesFollowTheKnowledgeCorpus() {
        assertThat(jdbc.queryForObject("select name from tenants where id = 'T001'", String.class))
                .isEqualTo("星选生活旗舰店");
        assertThat(jdbc.queryForObject("select name from tenants where id = 'T002'", String.class))
                .isEqualTo("鲜果驿站");
    }
}
