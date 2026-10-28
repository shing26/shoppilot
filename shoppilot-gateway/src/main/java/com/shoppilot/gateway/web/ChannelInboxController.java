package com.shoppilot.gateway.web;

import com.shoppilot.gateway.channel.ChannelConversationStore;
import com.shoppilot.gateway.channel.FeishuReplySender;
import com.shoppilot.gateway.identity.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 渠道会话收件箱（票 175）：坐席查看飞书会话、接管、回复、释放。
 *
 * <p>挂在 /api/v1/support/ops/** 下，复用 ops 鉴权；租户只从 TenantContext 取，
 * 不接受查询参数。会话 id 不能跨租户读取，因为 Redis 键里带 tenant。
 */
@RestController
@RequestMapping("/api/v1/support/ops/channel-conversations")
public class ChannelInboxController {

    private static final String FEISHU_CHAT_PREFIX = "feishu:chat:";

    private final ChannelConversationStore store;
    private final FeishuReplySender replySender;

    public ChannelInboxController(ChannelConversationStore store, FeishuReplySender replySender) {
        this.store = store;
        this.replySender = replySender;
    }

    public record ReplyRequest(String text) {
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ChannelConversationStore.Conversation> conversations = store.list(tenant());
        return Map.of("conversations", conversations);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        ChannelConversationStore.Conversation conversation = store.find(tenant(), id);
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(conversation);
    }

    @PostMapping("/{id}/takeover")
    public ResponseEntity<?> takeover(@PathVariable String id) {
        String actor = TenantContext.current().actor();
        ChannelConversationStore.Conversation conversation = store.takeover(tenant(), id, actor);
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }
        boolean noticeSent = sendNotice(id, "人工坐席 " + actor + " 已接入，请稍候。");
        return ResponseEntity.ok(Map.of("conversation", conversation, "noticeSent", noticeSent));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<?> release(@PathVariable String id) {
        ChannelConversationStore.Conversation conversation = store.release(tenant(), id);
        if (conversation == null) {
            return ResponseEntity.notFound().build();
        }
        boolean noticeSent = sendNotice(id, "已切回智能助手，继续为您服务。");
        return ResponseEntity.ok(Map.of("conversation", conversation, "noticeSent", noticeSent));
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id, @RequestBody(required = false) ReplyRequest request) {
        String text = request == null || request.text() == null ? "" : request.text().trim();
        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "回复内容不能为空"));
        }
        ChannelConversationStore.Conversation current = store.find(tenant(), id);
        if (current == null) {
            return ResponseEntity.notFound().build();
        }
        String actor = TenantContext.current().actor();
        if (!"AGENT".equals(current.status())) {
            return ResponseEntity.status(409).body(Map.of("error", "请先接管会话再回复"));
        }
        if (current.assignee() != null && !current.assignee().equals(actor)) {
            return ResponseEntity.status(409).body(Map.of("error", "这张会话已由 " + current.assignee() + " 接管"));
        }
        try {
            replySender.send(chatId(id), text);
        } catch (Exception failure) {
            return ResponseEntity.status(502).body(Map.of("error", "飞书发送失败，未写入会话记录"));
        }
        ChannelConversationStore.Conversation conversation =
                store.recordAgentReply(tenant(), id, actor, text);
        return ResponseEntity.ok(Map.of("conversation", conversation));
    }

    private boolean sendNotice(String conversationId, String text) {
        try {
            replySender.send(chatId(conversationId), text);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String chatId(String conversationId) {
        if (conversationId == null || !conversationId.startsWith(FEISHU_CHAT_PREFIX)) {
            throw new IllegalArgumentException("不是飞书会话：" + conversationId);
        }
        return conversationId.substring(FEISHU_CHAT_PREFIX.length());
    }

    private static String tenant() {
        return TenantContext.current().tenantId();
    }
}
