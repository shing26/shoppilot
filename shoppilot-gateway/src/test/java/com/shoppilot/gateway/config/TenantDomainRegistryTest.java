package com.shoppilot.gateway.config;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.scenario.ScenarioPackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * round33 票 125：租户行业必须显式登记；未登记租户 fail-closed 为 UNKNOWN，
 * 不能因为“没配置”就自动拿到电商平台政策。
 */
class TenantDomainRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("显式登记生效，未登记租户回到 UNKNOWN")
    void resolvesExplicitDomainsAndFailsClosedForUnknownTenants() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.tenantDomains()).thenReturn(new GatewayProperties.TenantDomains(
                Map.of("T001", "ECOMMERCE", "T004", "HOTEL")));
        TenantDomainRegistry registry = new TenantDomainRegistry(properties);

        assertThat(registry.resolve("T001")).isEqualTo(Domain.ECOMMERCE);
        assertThat(registry.resolve("T004")).isEqualTo(Domain.HOTEL);
        assertThat(registry.resolve("T999")).isEqualTo(Domain.UNKNOWN);
        assertThat(registry.resolve(null)).isEqualTo(Domain.UNKNOWN);
    }

    @Test
    @DisplayName("场景包覆盖 application.yml 的租户域")
    void scenarioPackOverridesConfiguredDomain() throws Exception {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.tenantDomains()).thenReturn(new GatewayProperties.TenantDomains(
                Map.of("T001", "ECOMMERCE")));
        Files.writeString(tempDir.resolve("hotel.json"), """
                { "id": "hotel", "domain": "HOTEL",
                  "stores": { "T900": { "name": "测试酒店", "business": "客房" } } }
                """);
        TenantDomainRegistry registry = new TenantDomainRegistry(
                properties, new ScenarioPackService(tempDir.toString()));

        assertThat(registry.resolve("T900")).isEqualTo(Domain.HOTEL);
        assertThat(registry.resolve("T001")).isEqualTo(Domain.ECOMMERCE);
    }
}
