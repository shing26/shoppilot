package com.shoppilot.gateway.channel;

/**
 * 请求级渠道上下文。与 TenantContext 同族（ThreadLocal），由入站端点设置、请求线程内读取：
 * 限流计数打 channel 标签、SSE meta 回显、渠道请求计数都从这里取。未设置时默认 WEB——
 * 主入口 /chat 与 /chat/stream 在没有显式渠道时就是 web 渠道。
 */
public final class ChannelContext {

    private static final ThreadLocal<Channel> CURRENT = new ThreadLocal<>();

    private ChannelContext() {
    }

    public static void set(Channel channel) {
        CURRENT.set(channel);
    }

    public static Channel current() {
        Channel channel = CURRENT.get();
        return channel == null ? Channel.WEB : channel;
    }

    /** 虚拟线程复用必须显式清理（与 TenantContext 同纪律）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
