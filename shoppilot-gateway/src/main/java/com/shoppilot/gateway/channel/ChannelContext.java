package com.shoppilot.gateway.channel;

/**
 * 请求级渠道上下文。与 TenantContext 同族（ThreadLocal），由入站端点设置、请求线程内读取：
 * 限流计数打 channel 标签、SSE meta 回显、渠道请求计数都从这里取。未设置时默认 WEB——
 * 主入口 /chat 与 /chat/stream 在没有显式渠道时就是 web 渠道。
 *
 * <p>{@code contact} 是 round26 票 86 加的一格：买家从哪个渠道来、**那个渠道的回我地址是什么**。
 * 它随工单一路带到工单服务，结果回流才知道要送到哪儿（ADR 0059）。
 *
 * <p><b>它不是身份</b>：ADR 0005 的三条防线一条都不看它，它也不进缓存键、不参与会话归属
 * （ADR 0025「渠道不参与身份」）。web 渠道没有 contact——买家就在浏览器里等，不存在「送回去」。
 */
public final class ChannelContext {

    private static final ThreadLocal<Channel> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CONTACT = new ThreadLocal<>();

    private ChannelContext() {
    }

    public static void set(Channel channel) {
        set(channel, null);
    }

    public static void set(Channel channel, String contact) {
        CURRENT.set(channel);
        CONTACT.set(contact == null || contact.isBlank() ? null : contact.trim());
    }

    public static Channel current() {
        Channel channel = CURRENT.get();
        return channel == null ? Channel.WEB : channel;
    }

    /** 该渠道的回我地址；没有就是 null（web 渠道恒为 null）。 */
    public static String contact() {
        return CONTACT.get();
    }

    /** 虚拟线程复用必须显式清理（与 TenantContext 同纪律）。 */
    public static void clear() {
        CURRENT.remove();
        CONTACT.remove();
    }
}
