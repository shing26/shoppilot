package com.shoppilot.gateway.web;

/**
 * 网关自产错误的唯一形状（ADR 0028）：{@code code} / {@code message} / {@code traceId}。
 *
 * <p>{@code code} 是给机器分的类，{@code message} 是给人看的一句话，{@code traceId} 回指 ADR 0027
 * 那条链路——拿着响应体里这个 id 去 grep 日志，能捞到同一单的四坐标。
 *
 * <p>刻意不含 {@code timestamp} 与 {@code path}：那两样 Spring 默认错误体里有，但它们既没有分类能力
 * （{@code code} 才是），也没人读；而 {@code path} 会把内部路由回显给客户端，演示项目换不到什么。
 */
public record ApiError(String code, String message, String traceId) {

    /** 鉴权拒绝：令牌缺失、伪造、过期共用一个 code，具体原因在 {@code message} 里。 */
    public static final String UNAUTHORIZED = "unauthorized";
    /** 运维端点自己看得懂的入参错误（未知意图、缺 query、非法模式）。 */
    public static final String INVALID_REQUEST = "invalid_request";
    /** Bean Validation 挡下来的入参（问题为空、超长）。 */
    public static final String VALIDATION_FAILED = "validation_failed";
    /** 网关到业务中台那一跳连不上：这是网关自产的判断，不是下游回来的体。 */
    public static final String DOWNSTREAM_UNREACHABLE = "downstream_unreachable";
    /** 没人接的运行时异常：状态码仍是 500，但不再是一具连类都没有的裸错误体。 */
    public static final String INTERNAL_ERROR = "internal_error";
}
