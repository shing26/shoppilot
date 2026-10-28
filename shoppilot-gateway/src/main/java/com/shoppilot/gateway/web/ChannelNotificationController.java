package com.shoppilot.gateway.web;

import com.shoppilot.gateway.channel.ChannelPushSubscriptionStore;
import com.shoppilot.gateway.channel.WebPushNotifier;
import com.shoppilot.gateway.identity.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Web Push 订阅入口（票 181）：VAPID 公钥、注册/注销浏览器推送订阅。 */
@RestController
@RequestMapping("/api/v1/support/ops/channel-notifications")
public class ChannelNotificationController {

    public record SubscribeRequest(String endpoint, String p256dh, String auth) {
    }

    private final WebPushNotifier notifier;
    private final ChannelPushSubscriptionStore store;

    public ChannelNotificationController(WebPushNotifier notifier, ChannelPushSubscriptionStore store) {
        this.notifier = notifier;
        this.store = store;
    }

    @GetMapping("/public-key")
    public ResponseEntity<?> publicKey() {
        return ResponseEntity.ok(Map.of("publicKey", notifier.publicKey()));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@RequestBody SubscribeRequest request) {
        if (request.endpoint() == null || request.endpoint().isBlank()
                || request.p256dh() == null || request.p256dh().isBlank()
                || request.auth() == null || request.auth().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "endpoint/p256dh/auth 不能为空"));
        }
        if (!isHttpsEndpoint(request.endpoint())) {
            return ResponseEntity.badRequest().body(Map.of("error", "endpoint 必须是 HTTPS 推送地址"));
        }
        boolean saved = store.save(tenant(), new ChannelPushSubscriptionStore.Subscription(
                request.endpoint(), request.p256dh(), request.auth()));
        if (!saved) {
            return ResponseEntity.status(503).body(Map.of("error", "订阅存储暂不可用"));
        }
        return ResponseEntity.ok(Map.of("subscribed", true));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<?> unsubscribe(@RequestBody Map<String, String> request) {
        String endpoint = request.get("endpoint");
        if (endpoint != null && !endpoint.isBlank()) {
            store.remove(tenant(), endpoint);
        }
        return ResponseEntity.ok(Map.of("subscribed", false));
    }

    private boolean isHttpsEndpoint(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private String tenant() {
        return TenantContext.current().tenantId();
    }
}
