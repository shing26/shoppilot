package com.shoppilot.tool.audit;

/**
 * 网关 → 下游的操作人头（round25 票 82 / ADR 0058）。
 *
 * <p>两个头而不是一个，是因为「名字」和「这个名字可不可信」必须分开传：一个下游日志里
 * 把两者揉在一起，看上去就都可信了。收窄的那条也在同一处：下游**只读这两个头**，
 * {@code X-Agent} / {@code X-Reviewer} 一律不读——留着它们就是留着一条绕过认证的路。
 *
 * <p>解析只有一种缺省取向：{@code authenticated} 缺失或不是 {@code true} 即按未认证。
 * 少传一个头的结果是「记下名字但标明不可信」，而不是「默认可信」。
 */
public final class ActorHeaders {

    /** 操作人名字。 */
    public static final String ACTOR = "X-Actor";

    /** 操作人是否来自已验签的令牌，取值 {@code true} / 其它一律按 {@code false}。 */
    public static final String ACTOR_AUTHENTICATED = "X-Actor-Authenticated";

    private ActorHeaders() {
    }

    /**
     * 由两个头还原出 {@link Actor}。
     *
     * <p>名字为空时落到 {@link Actor#SYSTEM} <b>并且不改它的认证位</b>：两个头都没带，说明这次
     * 根本没有操作人可言，把它标成「不可信的操作人」只会让真自报的那些更难被挑出来。
     * 「缺失即未认证」这条默认取向只作用于**有名字**的 actor —— 那才是需要判断可信度的场景。
     */
    public static Actor of(String actor, String authenticated) {
        Actor resolved = Actor.of(actor);
        if (resolved == Actor.SYSTEM) {
            return resolved;
        }
        return resolved.authenticated("true".equalsIgnoreCase(authenticated == null ? null : authenticated.trim()));
    }
}