package com.shoppilot.tool.identity;

/**
 * 账号视图（round25 票 80）。
 *
 * <p><b>刻意没有密码哈希，也没有状态字面量</b>：这一份是网关拿去签发令牌的，签发只需要
 * 「你是谁、你在哪家店、以什么角色」这三样。哈希出网关就等于把它摊到第二份账里。
 * {@code active} 是派生布尔而不是 {@code ACTIVE/DISABLED} 字面量：网关的 {@code /auth/me}
 * 需要它拒绝已停用账号，但不需要把内部状态机暴露成对外契约。
 *
 * @param accountId   账号主键，进审计事件的 actor
 * @param subjectRef  账号动作的对象：买家指向 {@code customers.id}，坐席与管理员为空
 * @param passwordVersion 口令版本（round56 票 224 / ADR 0086）：改密后 +1，进 access token 的 {@code pv}。
 *                    旧登录入口与既有测试不必感知它，默认 0 与迁移默认值一致。
 */
public record AccountView(String accountId, String tenantId, String username, UserRole role,
                          String subjectRef, String displayName, int passwordVersion, boolean active) {

    /** 兼容既有调用点：没有版本信息的账号按 0 处理（与迁移默认值同口径）。 */
    public AccountView(String accountId, String tenantId, String username, UserRole role,
                       String subjectRef, String displayName) {
        this(accountId, tenantId, username, role, subjectRef, displayName, 0, true);
    }

    /** 兼容 round56 之前的版本化调用点：active 默认 true。 */
    public AccountView(String accountId, String tenantId, String username, UserRole role,
                       String subjectRef, String displayName, int passwordVersion) {
        this(accountId, tenantId, username, role, subjectRef, displayName, passwordVersion, true);
    }
}
