package com.shoppilot.gateway.identity;

import com.shoppilot.tool.identity.UserRole;

import java.util.List;

/**
 * 工作台能力投影（round53 票 221）。
 *
 * <p>能力回答的是「前端应该显示哪些入口」，不是新的授权等级，也不替代端点上的动作级守卫。
 * 服务端仍然逐个端点显式判定；前端隐藏菜单只减少误点，不能被当成安全边界。
 *
 * <p>角色到能力的映射只在这里写一份：登录响应、{@code /auth/me} 与拦截器共用同一个派生，
 * 避免三处各写一遍之后悄悄分家。能力不进 JWT——令牌只带已验签角色，能力每次按角色重算。
 */
public final class Capabilities {

    public static final String TICKETS = "tickets";
    public static final String CONVERSATIONS = "conversations";
    public static final String REFUNDS = "refunds";
    public static final String KNOWLEDGE = "knowledge";
    public static final String QUALITY = "quality";
    public static final String CHANNELS = "channels";
    public static final String TENANTS = "tenants";

    private static final List<String> BUYER_CAPABILITIES = List.of();

    /** 坐席的五项日常工作面；顺序固定，便于前端与验收脚本逐项比较。 */
    private static final List<String> AGENT_CAPABILITIES = List.of(
            TICKETS, CONVERSATIONS, REFUNDS, KNOWLEDGE, QUALITY);

    /** 管理员在坐席能力之上增加渠道连接与店铺清单。 */
    private static final List<String> ADMIN_CAPABILITIES = List.of(
            TICKETS, CONVERSATIONS, REFUNDS, KNOWLEDGE, QUALITY, CHANNELS, TENANTS);

    private Capabilities() {
    }

    public static List<String> of(UserRole role) {
        if (role == null) {
            return BUYER_CAPABILITIES;
        }
        return switch (role) {
            case BUYER -> BUYER_CAPABILITIES;
            case AGENT -> AGENT_CAPABILITIES;
            case ADMIN -> ADMIN_CAPABILITIES;
        };
    }

    public static boolean allows(UserRole role, String capability) {
        return of(role).contains(capability);
    }
}
