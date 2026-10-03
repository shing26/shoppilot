package com.shoppilot.tool.audit;

/**
 * 一次动作的操作人（round25 票 82 / ADR 0058 第 4 条）。
 *
 * <p>以前审计事件里只有一个 {@code actor} 字符串，而那个字符串有三种来路：已验签的账号、
 * 调用方自报的头、{@code system} 占位。**一个字符串答不出「这条是谁做的」**——自报的那些
 * 恰恰是最该被怀疑的（退款放行是不可逆的资金动作，而它的责任人曾经是一个请求头）。
 *
 * <p>所以这里把「名字」与「这个名字可不可信」绑在一起传：{@code authenticated=false}
 * 的事件仍然记下来（照登未达成比假装有据强），但它能被查询面挑出来。
 *
 * @param authenticated 这个名字是否来自**已验签的令牌**，而不是调用方自报
 */
public record Actor(String name, boolean authenticated) {

    /** 系统动作。与「匿名」不同：它说明没人参与，不是缺了操作人。 */
    public static final Actor SYSTEM = new Actor(AuditActions.SYSTEM_ACTOR, true);

    /**
     * 兜底：名字为空时用 {@code system} 占位，并**标成已认证**。
     *
     * <p>这是刻意的方向性选择：空 actor 只在「确实没有真人参与」时出现（例如网关自动落单），
     * 把它标成不可信会让真正的自报事件淹没在噪声里。
     */
    public static Actor of(String name) {
        return name == null || name.isBlank() ? SYSTEM : new Actor(name.trim(), false);
    }

/** 已验签的账号。 */
    public static Actor authenticated(String name) {
        return new Actor(name, true);
    }

    /** 改写「这个名字可不可信」。名字为空时不变（{@link #SYSTEM} 本来就带 {@code true}）。 */
    public Actor authenticated(boolean value) {
        return new Actor(name, value);
    }
}