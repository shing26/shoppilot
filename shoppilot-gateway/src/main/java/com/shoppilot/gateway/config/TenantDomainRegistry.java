package com.shoppilot.gateway.config;

import com.shoppilot.gateway.knowledge.Domain;
import com.shoppilot.gateway.scenario.ScenarioPackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 租户行业登记（round33 / ADR 0067）。
 *
 * <p>行业不是从店铺名或买家问句猜出来的：它必须显式登记。未登记租户返回
 * {@link Domain#UNKNOWN}，检索层据此只看本租户规则，不继承平台电商政策。
 */
@Component
public class TenantDomainRegistry {

    private final Map<String, String> configured;
    private final ScenarioPackService scenarios;

    public TenantDomainRegistry(GatewayProperties properties) {
        this(properties, ScenarioPackService.empty());
    }

    @Autowired
    public TenantDomainRegistry(GatewayProperties properties, ScenarioPackService scenarios) {
        GatewayProperties.TenantDomains tenantDomains = properties.tenantDomains();
        this.configured = tenantDomains == null || tenantDomains.domains() == null
                ? Map.of()
                : Map.copyOf(tenantDomains.domains());
        this.scenarios = scenarios;
    }

    public Domain resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Domain.UNKNOWN;
        }
        Domain fromPack = scenarios.domainFor(tenantId);
        if (fromPack != null) {
            return fromPack;
        }
        return Domain.from(configured.get(tenantId));
    }
}
