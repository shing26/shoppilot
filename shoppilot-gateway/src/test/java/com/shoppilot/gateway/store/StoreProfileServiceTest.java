package com.shoppilot.gateway.store;

import com.shoppilot.gateway.scenario.ScenarioPackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 店铺人格的边界：登记租户追加三段，未登记租户一字不加。 */
class StoreProfileServiceTest {

    @TempDir
    Path tempDir;

    private final StoreProfileService service = new StoreProfileService(new StoreProfileProperties(Map.of(
            "T001", new StoreProfileProperties.Profile(
                    "星选生活旗舰店", "生鲜果蔬与家居日用", "自营商品无理由退货 15 天"),
            "T004", new StoreProfileProperties.Profile(
                    "云栖酒店", "客房预订与入住服务", "入住当日 18:00 前可免费取消"))));

    @Test
    @DisplayName("已登记租户：基座在前，只追加店名/主营；承诺摘要不进 Prompt")
    void appendsRegisteredProfileAfterBase() {
        String prompt = service.augment("基座正文", "T001");

        assertThat(prompt).startsWith("基座正文\n");
        assertThat(prompt).contains("店名：星选生活旗舰店");
        assertThat(prompt).contains("主营：生鲜果蔬与家居日用");
        // 承诺摘要删掉了条款限定条件，进 Prompt 会被模型当成政策复述。
        assertThat(prompt).doesNotContain("本店承诺");
        assertThat(prompt).doesNotContain("15 天");
    }

    @Test
    @DisplayName("未登记租户与空租户：不追加、不借别家店资料")
    void unknownTenantIsNotAugmented() {
        assertThat(service.augment("基座正文", "T999")).isEqualTo("基座正文");
        assertThat(service.augment("基座正文", null)).isEqualTo("基座正文");
        assertThat(service.augment("基座正文", " ")).isEqualTo("基座正文");
    }

    @Test
    @DisplayName("不同租户拿到不同店名——同一句「你们店卖什么」不会再同答")
    void profilesAreTenantSpecific() {
        assertThat(service.augment("", "T001")).contains("星选生活旗舰店").doesNotContain("云栖酒店");
        assertThat(service.augment("", "T004")).contains("云栖酒店").doesNotContain("星选生活旗舰店");
    }

    @Test
    @DisplayName("场景包覆盖店铺人格，并追加场景补充提示")
    void scenarioPackOverridesProfileAndAddsAddendum() throws Exception {
        Files.writeString(tempDir.resolve("hotel.json"), """
                { "id": "hotel", "domain": "HOTEL",
                  "stores": { "T001": { "name": "场景包店铺", "business": "场景包主营", "guarantees": "不要进 Prompt" } },
                  "promptAddendum": "当前场景为测试场景。" }
                """);
        StoreProfileService withPack = new StoreProfileService(
                new StoreProfileProperties(Map.of("T001", new StoreProfileProperties.Profile(
                        "旧店铺", "旧主营", "旧承诺"))),
                new ScenarioPackService(tempDir.toString()));

        String prompt = withPack.augment("基座正文", "T001");

        assertThat(prompt).contains("店名：场景包店铺");
        assertThat(prompt).contains("主营：场景包主营");
        assertThat(prompt).contains("【场景补充】当前场景为测试场景。");
        assertThat(prompt).doesNotContain("旧店铺");
        assertThat(prompt).doesNotContain("不要进 Prompt");
    }
}
