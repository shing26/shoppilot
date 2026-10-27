package com.shoppilot.gateway.web;

import com.shoppilot.gateway.channel.ChannelConnectionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 渠道连接管理 v1（票 173）：状态、凭据测试、保存飞书连接。
 *
 * <p>挂在 /api/v1/support/ops/** 下，复用 OpsAuthorizationInterceptor：
 * 没有 X-Ops-Token 或 ops 关闭时一律拒绝。API 不回显 secret，只回遮罩 appId。
 */
@RestController
@RequestMapping("/api/v1/support/ops/channels")
public class ChannelAdminController {

    private final ChannelConnectionStore store;

    public ChannelAdminController(ChannelConnectionStore store) {
        this.store = store;
    }

    public record ProbeRequest(String appId, String appSecret) {
    }

    public record FeishuUpdate(Boolean enabled, String appId, String appSecret,
                               String tenant, String customer, String identityFile) {
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("channels", List.of(status()));
    }

    @PostMapping("/feishu/test")
    public Map<String, Object> test(@RequestBody(required = false) ProbeRequest request) {
        String appId = request == null ? null : request.appId();
        String secret = request == null ? null : request.appSecret();
        ChannelConnectionStore.Probe probe = store.probe(appId, secret);
        return Map.of("ok", probe.ok(), "message", probe.message());
    }

    @PutMapping("/feishu")
    public ResponseEntity<Map<String, Object>> save(@RequestBody FeishuUpdate update) {
        if (update == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求体不能为空"));
        }
        try {
            boolean enabled = update.enabled() == null ? store.current().enabled() : update.enabled();
            ChannelConnectionStore.FeishuConnection saved = store.save(
                    new ChannelConnectionStore.FeishuConnection(
                            enabled, update.appId(), update.appSecret(), update.tenant(),
                            update.customer(), update.identityFile()));
            return ResponseEntity.ok(Map.of(
                    "saved", true,
                    "enabled", saved.enabled(),
                    "appId", store.maskedAppId(),
                    "tenant", saved.tenant(),
                    "customer", saved.customer(),
                    "source", store.fromFile() ? "file" : "env",
                    "restartRequired", store.restartRequired()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }

    private Map<String, Object> status() {
        return Map.of(
                "type", "feishu",
                "enabled", store.current().enabled(),
                "appId", store.maskedAppId(),
                "tenant", store.current().tenant(),
                "customer", store.current().customer(),
                "source", store.fromFile() ? "file" : "env",
                "restartRequired", store.restartRequired());
    }
}
