package com.shoppilot.gateway.channel;

import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 渠道配置的「探测 → 写盘 → 原子切换」状态机（round56 票 225 / ADR 0087）。
 *
 * <p>管理面只调这里，不直接调 store/client——否则「保存成功」与「已生效」又会被混成
 * 同一件事。
 */
@Component
public class ChannelLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ChannelLifecycleService.class);

    public record Result(boolean saved, boolean applied, String state, boolean restartRequired,
                         String message) {
    }

    private final ChannelConnectionStore store;
    private final FeishuLongConnectionClient client;

    public ChannelLifecycleService(ChannelConnectionStore store, FeishuLongConnectionClient client) {
        this.store = store;
        this.client = client;
    }

    public Result apply(ChannelConnectionStore.FeishuConnection update) {
        GatewayProperties.Feishu candidate = store.resolve(update);
        if (candidate.enabled()) {
            ChannelConnectionStore.Probe probe = store.probe(candidate.appId(), candidate.appSecret());
            if (!probe.ok()) {
                store.markRolledBack("凭据探测失败，未保存：" + probe.message());
                return new Result(false, false, store.state().name(), false, store.message());
            }
        }

        GatewayProperties.Feishu previous = store.current();
        boolean previousFromFile = store.fromFile();
        store.persist(candidate);

        FeishuLongConnectionClient.SwitchResult switched = client.restart(candidate);
        return switch (switched) {
            case APPLIED -> {
                store.markApplied();
                yield new Result(true, true, store.state().name(), false, store.message());
            }
            case NOT_SUPPORTED -> {
                store.markRestartRequired("配置已保存；当前运行环境无法安全关闭旧长连接，需重启网关生效。");
                yield new Result(true, false, store.state().name(), true, store.message());
            }
            case UNSAFE -> {
                // 旧、新连接都关不掉：不谎称已回滚。配置保留新值，重启后只会起新连接。
                store.markRestartRequired(
                        "配置已保存，但旧连接无法关闭且新连接无法回滚；可能仍有两条长连接在收事件，请立即重启网关。");
                log.error("渠道配置切换进入不安全状态：{}", store.message());
                yield new Result(true, false, store.state().name(), true, store.message());
            }
            case FAILED -> {
                store.restore(previous, previousFromFile);
                // 启动失败时旧连接从未被动过；不再 restart(previous)，否则会多起一条连接。
                store.markRolledBack("新连接启动失败，已恢复上一份可用配置。");
                log.error("渠道配置切换失败，已回滚：{}", store.message());
                yield new Result(false, false, store.state().name(), false, store.message());
            }
        };
    }

    /** 实际仍在运行的飞书 appId；没有活动连接时返回 null。 */
    public String activeAppId() {
        GatewayProperties.Feishu active = client.activeFeishu();
        return active == null ? null : active.appId();
    }
}
