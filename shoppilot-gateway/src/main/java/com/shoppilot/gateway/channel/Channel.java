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
    EMAIL;

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
            if (channel.label().equalsIgnoreCase(value)) {
                return channel;
            }
        }
        return null;
    }
}
