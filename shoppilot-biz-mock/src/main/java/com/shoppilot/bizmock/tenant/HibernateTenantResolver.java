package com.shoppilot.bizmock.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * 配合实体上的 {@code @TenantId} 实现行级逻辑隔离（ADR 0005 防线二）：
 * Hibernate 会在所有查询上自动拼接归属条件，裸 JPQL 也绕不过去。
 *
 * <p>不加 {@code @Component}：Hibernate 经 {@code hibernate.tenant_identifier_resolver}
 * 以类名自行实例化，这里只依赖静态 ThreadLocal，无需 Spring 注入。
 */
public class HibernateTenantResolver implements CurrentTenantIdentifierResolver<String> {

    /** seed 与运维查询在无请求上下文时以平台身份运行。 */
    private static final String PLATFORM = "PLATFORM";

    @Override
    public String resolveCurrentTenantIdentifier() {
        try {
            return TenantContextHolder.tenantId();
        } catch (IllegalStateException noRequestContext) {
            return PLATFORM;
        }
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
