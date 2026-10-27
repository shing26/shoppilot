package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书 L2 身份映射（票 171）：把飞书 chat_id / sender open_id 映射到店铺租户与买家。
 *
 * <p>没有映射文件时保持 L1 行为：全部落到配置的 tenant/customer。
 * 配了映射文件后进入严格模式：先查 chat，再查 user，最后 default；三者都没有则拒绝，
 * 绝不把未知飞书身份静默塞进默认租户——那会跨店看到别人的订单。
 */
final class FeishuIdentityResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Identity(String tenant, String customer) {
    }

    private final GatewayProperties properties;
    private String identityFile;
    private Identity fallback;
    private final Map<String, Identity> chats = new HashMap<>();
    private final Map<String, Identity> users = new HashMap<>();
    private volatile Identity defaultIdentity;
    private volatile boolean strict;
    private volatile boolean loaded;

    FeishuIdentityResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    /** 测试入口：直接给文件路径与默认身份。 */
    FeishuIdentityResolver(String identityFile, String tenant, String customer) {
        this.properties = null;
        this.identityFile = identityFile == null ? "" : identityFile.trim();
        this.fallback = new Identity(
                tenant == null || tenant.isBlank() ? "T001" : tenant.trim(),
                customer == null || customer.isBlank() ? "C001" : customer.trim());
    }

    Optional<Identity> resolve(String chatId, String openId) {
        ensureLoaded();
        if (!strict) {
            return Optional.of(fallback);
        }
        Identity byChat = chats.get(chatId);
        if (byChat != null) {
            return Optional.of(byChat);
        }
        Identity byUser = users.get(openId);
        if (byUser != null) {
            return Optional.of(byUser);
        }
        return Optional.ofNullable(defaultIdentity);
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        if (properties != null) {
            GatewayProperties.Feishu feishu = properties.feishu();
            this.identityFile = feishu == null ? "" : feishu.identityFile();
            this.fallback = new Identity(
                    feishu == null ? "T001" : feishu.tenant(),
                    feishu == null ? "C001" : feishu.customer());
        }
        if (identityFile == null || identityFile.isBlank()) {
            strict = false;
            loaded = true;
            return;
        }
        strict = true;
        Path path = Path.of(identityFile);
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(path));
        } catch (Exception failure) {
            throw new IllegalStateException("飞书身份映射文件读取失败：" + path.toAbsolutePath(), failure);
        }
        defaultIdentity = parse(root.path("default"), "default");
        readMap(root.path("chats"), chats, "chats");
        readMap(root.path("users"), users, "users");
        if (defaultIdentity == null && chats.isEmpty() && users.isEmpty()) {
            throw new IllegalStateException("飞书身份映射文件没有 default/chats/users：" + path.toAbsolutePath());
        }
        loaded = true;
    }

    private static void readMap(JsonNode node, Map<String, Identity> target, String field) {
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            Identity identity = parse(entry.getValue(), field + "." + entry.getKey());
            if (identity != null) {
                target.put(entry.getKey(), identity);
            }
        }
    }

    private static Identity parse(JsonNode node, String what) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String tenant = node.path("tenant").asText("").trim();
        String customer = node.path("customer").asText("").trim();
        if (tenant.isBlank() || customer.isBlank()) {
            throw new IllegalStateException("飞书身份映射 " + what + " 缺 tenant/customer");
        }
        return new Identity(tenant, customer);
    }
}
