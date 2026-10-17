package com.shoppilot.gateway.config;

import com.shoppilot.gateway.knowledge.Domain;
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

    public TenantDomainRegistry(GatewayProperties properties) {
        GatewayProperties.TenantDomains tenantDomains = properties.tenantDomains();
        this.configured = tenantDomains == null || tenantDomains.domains() == null
                ? Map.of()
                : Map.copyOf(tenantDomains.domains());
    }

    public Domain resolve(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Domain.UNKNOWN;
        }
        return Domain.from(configured.get(tenantId));
    }
}
