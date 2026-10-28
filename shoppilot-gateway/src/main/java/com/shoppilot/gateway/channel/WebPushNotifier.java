package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web Push（票 181）：把新买家消息推到浏览器 Service Worker，浏览器关掉也能收。
 *
 * <p>VAPID 密钥可用环境变量提供；没提供时启动生成临时密钥（重启后旧订阅失效），
 * 演示环境够用，生产应固定 SHOPPILOT_PUSH_VAPID_*。
 */
@Component
public class WebPushNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebPushNotifier.class);

    private final ChannelPushSubscriptionStore store;
    private final ObjectMapper mapper;
    private final PushService pushService;
    private final String publicKey;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "web-push");
        thread.setDaemon(true);
        return thread;
    });

    public WebPushNotifier(ChannelPushSubscriptionStore store, ObjectMapper mapper,
                           @Value("${shoppilot.push.vapid-public-key:}") String configuredPublicKey,
                           @Value("${shoppilot.push.vapid-private-key:}") String configuredPrivateKey,
                           @Value("${shoppilot.push.subject:mailto:ops@shoppilot.local}") String subject) {
        this.store = store;
        this.mapper = mapper;
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            if (!configuredPublicKey.isBlank() && !configuredPrivateKey.isBlank()) {
                this.publicKey = configuredPublicKey;
                this.pushService = new PushService(configuredPublicKey, configuredPrivateKey, subject);
            } else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair keyPair = generator.generateKeyPair();
                this.publicKey = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(Utils.encode((ECPublicKey) keyPair.getPublic()));
                this.pushService = new PushService(keyPair, subject);
                log.warn("未配置 VAPID 密钥，使用临时密钥；网关重启后已有 Web Push 订阅会失效");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Web Push 初始化失败", failure);
        }
    }

    public String publicKey() {
        return publicKey;
    }

    /** 只在会话保存的发布端调用一次；远端 listener 不调用，避免多实例重复推送。 */
    public void notify(ChannelConversationStore.Conversation conversation) {
        if (conversation == null || conversation.unread() <= 0) {
            return;
        }
        List<ChannelConversationStore.Message> messages = conversation.messages();
        if (messages == null || messages.isEmpty()
                || !"user".equals(messages.get(messages.size() - 1).role())) {
            return;
        }
        List<ChannelPushSubscriptionStore.Subscription> subscriptions = store.list(conversation.tenantId());
        if (subscriptions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "title", "新的渠道消息 · " + conversation.channel(),
                    "body", conversation.lastMessage() == null ? "买家发来一条新消息" : conversation.lastMessage(),
                    "conversationId", conversation.conversationId()));
        } catch (Exception failure) {
            log.warn("Web Push payload 序列化失败: {}", failure.getMessage());
            return;
        }
        for (ChannelPushSubscriptionStore.Subscription subscription : subscriptions) {
            executor.submit(() -> send(conversation.tenantId(), subscription, payload));
        }
    }

    private void send(String tenantId, ChannelPushSubscriptionStore.Subscription subscription, String payload) {
        try {
            Subscription webPushSubscription = new Subscription(
                    subscription.endpoint(),
                    new Subscription.Keys(subscription.p256dh(), subscription.auth()));
            int status = pushService.send(new Notification(webPushSubscription, payload))
                    .getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                store.remove(tenantId, subscription.endpoint());
                log.info("Web Push 订阅已失效，删除 endpoint={}", subscription.endpoint());
            } else if (status >= 400) {
                log.warn("Web Push 发送失败 status={} endpoint={}", status, subscription.endpoint());
            }
        } catch (Exception failure) {
            log.warn("Web Push 发送异常: {}", failure.getMessage());
        }
    }
}
