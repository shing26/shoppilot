package com.shoppilot.gateway.web;

import com.shoppilot.gateway.channel.ChannelConnectionStore;
import com.shoppilot.gateway.channel.ChannelLifecycleService;
import com.shoppilot.gateway.channel.EgressPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * 渠道连接管理 v1（票 173）：状态、凭据测试、保存飞书连接。
 *
 * <p>挂在 /api/v1/support/ops/** 下，复用 OpsAuthorizationInterceptor：
 * 票 221 起这一面只允许管理员账号或 X-Ops-Token；坐席账号返回 403。
 * API 不回显 secret，只回遮罩 appId。
 */
@RestController
@RequestMapping("/api/v1/support/ops/channels")
public class ChannelAdminController {

    private final ChannelConnectionStore store;
    private final ChannelLifecycleService lifecycle;
    private final EgressPolicy egress;

    public ChannelAdminController(ChannelConnectionStore store, ChannelLifecycleService lifecycle,
                                  EgressPolicy egress) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.egress = egress;
    }

    public record ProbeRequest(String appId, String appSecret) {
    }

    public record FeishuUpdate(Boolean enabled, String appId, String appSecret,
                               String tenant, String customer, String identityFile) {
    }

    public record WebhookProbe(String url) {
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
            ChannelLifecycleService.Result result = lifecycle.apply(
                    new ChannelConnectionStore.FeishuConnection(
                            enabled, update.appId(), update.appSecret(), update.tenant(),
                            update.customer(), update.identityFile()));
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("saved", result.saved());
            body.put("applied", result.applied());
            body.put("state", result.state());
            body.put("restartRequired", result.restartRequired());
            body.put("message", result.message());
            body.put("enabled", store.current().enabled());
            body.put("appId", store.maskedAppId());
            body.put("tenant", store.current().tenant());
            body.put("customer", store.current().customer());
            body.put("source", store.fromFile() ? "file" : "env");
            body.put("activeAppId", activeAppId());
            return result.saved()
                    ? ResponseEntity.ok(body)
                    : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
    }

    /**
     * 管理面「测试 webhook」：只发一条固定探测负载，不写任何配置。
     *
     * <p>SSRF 判定与真实投递共用同一个 {@link EgressPolicy}；这里不回显解析出的
     * 内网地址，只回「被拒」与一句人话。
     */
    @PostMapping("/webhook/test")
    public Map<String, Object> testWebhook(@RequestBody(required = false) WebhookProbe request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            return Map.of("ok", false, "message", "webhook 地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(request.url().trim());
        } catch (IllegalArgumentException malformed) {
            return Map.of("ok", false, "message", "webhook 地址不合法");
        }
        EgressPolicy.Decision decision = egress.check(uri);
        if (!decision.allowed()) {
            return Map.of("ok", false, "message", decision.reason());
        }
        try {
            HttpResponse<String> response = egress.post(uri, "{\"probe\":true}",
                    Map.of("Content-Type", "application/json", "X-Shoppilot-Probe", "1"));
            boolean ok = response.statusCode() / 100 == 2;
            return Map.of("ok", ok, "status", response.statusCode(),
                    "message", ok ? "webhook 可达" : "webhook 返回非 2xx");
        } catch (EgressPolicy.DeniedException denied) {
            return Map.of("ok", false, "message", denied.getMessage());
        } catch (Exception unreachable) {
            return Map.of("ok", false, "message", "webhook 不可达：" + unreachable.getClass().getSimpleName());
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
                "restartRequired", store.restartRequired(),
                "state", store.state().name(),
                "message", store.message(),
                "activeAppId", activeAppId());
    }

    /** 实际在跑的连接 appId；与「磁盘上最新配置」分开，避免 RESTART_REQUIRED 时误导。 */
    private String activeAppId() {
        String appId = lifecycle.activeAppId();
        if (appId == null || appId.isBlank()) {
            return "(无活动连接)";
        }
        return appId.length() <= 6 ? "****" : appId.substring(0, 6) + "****";
    }
}
