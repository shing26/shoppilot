package com.shoppilot.gateway.knowledge;

/**
 * 规则块：政策文档切分出的最小检索与引用单元（CONTEXT.md）。
 *
 * @param scope PLATFORM 表示全租户共享同一份，SHOP 表示仅本租户可见（ADR 0004）
 */
public record RuleChunk(
        String ruleId,
        String sourceDoc,
        String headingPath,
        String title,
        String text,
        String ruleType,
        String applicableCategory,
        String scope,
        String tenantId,
        String intent,
        String effectiveFrom,
        long kbEpoch) {

    public static final String PLATFORM_TENANT = "PLATFORM";

    public boolean platformScoped() {
        return "PLATFORM".equalsIgnoreCase(scope);
    }
}
