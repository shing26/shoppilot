package com.shoppilot.gateway.channel;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用 HTTP JSON 入口（模拟 App / 小程序 / 三方回调用）：整段 JSON 回包、无流式。
 * 渠道标签由调用路径给（app / miniapp / webhook），归一规则本身渠道无关。
 *
 * <p><b>{@code callbackUrl} 是 round26 票 88 加的可选字段</b>（ADR 0059 第 4 条）：
 * 「结果回流」要有个地方可送，而在此之前这个契约里**根本没有「回我哪儿」这一格**——
 * `contact` 恒为 null，于是 webhook 来源的工单永远没有投递目标，那条路径等于不存在。
 *
 * <p><b>它不是身份、也不进任何缓存键</b>：只是一个投递地址（同 ADR 0005 的分工）。
 */
@Component
public class WebhookAdapter implements ChannelAdapter {

    /** 只认这两种 scheme。别的（file:、gopher: 之类）在这里就被拒，不进到「往那儿发」的分支。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 上限 255，与工单表 contact 列同宽；超长的目标进不了库，早点拒比落库时截断好。 */
    private static final int MAX_TARGET_LENGTH = 255;

    @Override
    public Channel channel() {
        return Channel.WEBHOOK;
    }

    @Override
    public boolean streaming() {
        return false;
    }

    @Override
    public boolean canFollowUp() {
        return false;
    }

    @Override
    public NormalizedChat normalize(Map<String, Object> payload) {
        String query = WebSseAdapter.stringOrNull(payload.get("query"));
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        // 可选：没有就不发事件（照登，不假装有目标）。**不因为缺它而拒整个请求**——
        // 绝大多数调用方只是来问一句，并不需要把结果送回去。
        String callbackUrl = WebSseAdapter.stringOrNull(payload.get("callbackUrl"));
        String contact = null;
        if (callbackUrl != null && !callbackUrl.isBlank()) {
            contact = requireDeliverable(callbackUrl.trim());
        }
        return new NormalizedChat(query, WebSseAdapter.stringOrNull(payload.get("idempotencyToken")), contact);
    }

    /**
     * 校验回调地址的**形状**，不校验它指向哪里（那是网络策略的事，本仓没有那一层）。
     *
     * <p>诚实登记：这个字段让网关能对调用方给的地址发一次 POST，所以它天然是一条出站请求的
     * 出口。本仓的防线是「网关只绑回环 + 目标必须绝对 + scheme 限 http/https」，
     * **足以挡住顺手填的 `file:///etc/passwd` 这类，挡不住有意的 SSRF**——
     * 那需要一层出站地址白名单，本轮不做（ADR 0059 的登记项）。
     */
    private static String requireDeliverable(String callbackUrl) {
        if (callbackUrl.length() > MAX_TARGET_LENGTH) {
            throw new IllegalArgumentException("callbackUrl 过长（上限 " + MAX_TARGET_LENGTH + "）");
        }
        URI uri;
        try {
            uri = URI.create(callbackUrl);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("callbackUrl 不是一个合法地址");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("callbackUrl 只接受带主机名的 http/https 绝对地址");
        }
        return callbackUrl;
    }
}