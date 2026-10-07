package com.shoppilot.gateway.channel;

/**
 * 入站渠道标签（ADR 0035）。渠道只是入站标签，不是隔离边界：
 * 会话归属仍由「店铺 + 买家」二元组判定（ADR 0025），渠道不参与身份推导、不进缓存键。
 */
public enum Channel {
    WEB,
    APP,
    MINIAPP,
    WEBHOOK,
    EMAIL,
    FEISHU;

    /** 小写标签：指标、SSE meta、评测用例里的 channel 字段共用这一套写法。 */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 路径参数解析：未知渠道返回 null，由调用方判 400。 */
    public static Channel fromPath(String value) {
        if (value == null) {
            return null;
        }
        for (Channel channel : values()) {
            // 飞书（round31 票 97 / ADR 0065）只走长连接收事件，没有 HTTP 入站路径——
            // 不从路径解析出来，/webhook/feishu 一律 400，暴露面红线（零暴露）从这里就成立
            if (channel == FEISHU) {
                continue;
            }
            if (channel.label().equalsIgnoreCase(value)) {
                return channel;
            }
        }
        return null;
    }
}
