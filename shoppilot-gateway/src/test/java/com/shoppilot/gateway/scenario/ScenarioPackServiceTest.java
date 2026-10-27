package com.shoppilot.gateway.scenario;

import com.shoppilot.gateway.knowledge.Domain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 票 172：场景包的加载、覆盖优先级与拒启条件。 */
class ScenarioPackServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("加载场景包：域、店铺人格、补充提示都按 tenant 可取")
    void loadsPackDomainProfileAndAddendum() throws Exception {
        Files.writeString(tempDir.resolve("hotel.json"), """
                {
                  "id": "hotel",
                  "domain": "HOTEL",
                  "stores": {
                    "T900": { "name": "测试酒店", "business": "客房", "guarantees": "14:00 后入住" }
                  },
                  "promptAddendum": "当前场景为酒店。"
                }
                """);

        ScenarioPackService service = new ScenarioPackService(tempDir.toString());

        assertThat(service.domainFor("T900")).isEqualTo(Domain.HOTEL);
        assertThat(service.profileFor("T900").name()).isEqualTo("测试酒店");
        assertThat(service.addendumFor("T900")).isEqualTo("当前场景为酒店。");
        assertThat(service.packs()).extracting(ScenarioPackService.Pack::id).containsExactly("hotel");
    }

    @Test
    @DisplayName("同一 tenant 出现在两个包：拒启，不静默取最后一个")
    void rejectsDuplicateTenantAcrossPacks() throws Exception {
        Files.writeString(tempDir.resolve("a.json"), """
                { "id": "a", "domain": "ECOMMERCE",
                  "stores": { "T901": { "name": "A", "business": "电商" } } }
                """);
        Files.writeString(tempDir.resolve("b.json"), """
                { "id": "b", "domain": "HOTEL",
                  "stores": { "T901": { "name": "B", "business": "酒店" } } }
                """);

        assertThatThrownBy(() -> new ScenarioPackService(tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多个场景包");
    }

    @Test
    @DisplayName("非法 domain：拒启，不退化成 UNKNOWN")
    void rejectsInvalidDomain() throws Exception {
        Files.writeString(tempDir.resolve("bad.json"), """
                { "id": "bad", "domain": "SPACE",
                  "stores": { "T902": { "name": "太空", "business": "飞船" } } }
                """);

        assertThatThrownBy(() -> new ScenarioPackService(tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("domain 非法");
    }

    @Test
    @DisplayName("仓库内实际场景包可加载：电商 + 酒店")
    void repositoryPacksLoad() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("config").resolve("scenarios"))) {
            current = current.getParent();
        }
        assertThat(current).as("从当前目录往上找不到 config/scenarios").isNotNull();
        ScenarioPackService service = new ScenarioPackService(
                current.resolve("config").resolve("scenarios").toString());

        assertThat(service.packs()).extracting(ScenarioPackService.Pack::id)
                .contains("ecommerce", "hotel");
        assertThat(service.domainFor("T004")).isEqualTo(Domain.HOTEL);
        assertThat(service.profileFor("T001").name()).isEqualTo("星选生活旗舰店");
    }

    @Test
    @DisplayName("没有目录：空包，不报错")
    void missingDirectoryIsEmpty() {
        ScenarioPackService service = new ScenarioPackService(tempDir.resolve("missing").toString());

        assertThat(service.packs()).isEmpty();
        assertThat(service.domainFor("T001")).isNull();
    }
}
