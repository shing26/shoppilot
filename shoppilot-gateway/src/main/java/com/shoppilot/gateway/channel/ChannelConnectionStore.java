package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppilot.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 渠道连接管理 v1（票 173）：飞书单连接的可查看/可测试/可保存配置。
 *
 * <p>配置优先读 config/channels.json；没有文件时回退 .env 的 GatewayProperties.Feishu。
 * 保存只写文件并标记 restartRequired，不假装热重启 SDK——长连接的关闭语义 SDK 没公开，
 * 盲目重启会留下两个连接同时收事件。
 *
 * <p>密钥只在本机文件里，API 只回遮罩 appId，不回 secret。
 */
@Component
public class ChannelConnectionStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelConnectionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";

    public record FeishuConnection(boolean enabled, String appId, String appSecret,
                                   String tenant, String customer, String identityFile) {
    }

    public record Probe(boolean ok, String message) {
    }

    private final Path file;
    private final GatewayProperties.Feishu env;
    private volatile GatewayProperties.Feishu current;
    private volatile boolean fromFile;
    private volatile boolean restartRequired;

    public ChannelConnectionStore(GatewayProperties properties,
                                  @Value("${shoppilot.channels.file:config/channels.json}") String path) {
        this.file = Path.of(path == null || path.isBlank() ? "config/channels.json" : path);
        this.env = properties == null || properties.feishu() == null
                ? new GatewayProperties.Feishu(false, "", "", "T001", "C001", "")
                : properties.feishu();
        this.current = load();
    }

    public GatewayProperties.Feishu current() {
        return current;
    }

    public boolean fromFile() {
        return fromFile;
    }

    public boolean restartRequired() {
        return restartRequired;
    }

    public String maskedAppId() {
        String appId = current.appId();
        if (appId == null || appId.isBlank()) {
            return "(未配置)";
        }
        return appId.length() <= 6 ? "****" : appId.substring(0, 6) + "****";
    }

    public synchronized FeishuConnection save(FeishuConnection update) {
        if (update == null) {
            throw new IllegalArgumentException("连接配置不能为空");
        }
        String appId = update.appId() == null ? "" : update.appId().trim();
        if (appId.isBlank()) {
            appId = current.appId();
        }
        String secret = update.appSecret() == null ? "" : update.appSecret().trim();
        if (secret.isBlank()) {
            secret = current.appSecret();
        }
        if (update.enabled() && (appId.isBlank() || secret == null || secret.isBlank())) {
            throw new IllegalArgumentException("启用飞书必须同时提供 appId 和 appSecret");
        }
        GatewayProperties.Feishu next = new GatewayProperties.Feishu(
                update.enabled(), appId, secret,
                update.tenant(), update.customer(), update.identityFile());
        write(next);
        this.current = next;
        this.fromFile = true;
        this.restartRequired = true;
        log.info("飞书连接配置已保存，需重启网关生效：enabled={} tenant={} customer={}",
                next.enabled(), next.tenant(), next.customer());
        return new FeishuConnection(next.enabled(), next.appId(), "", next.tenant(),
                next.customer(), next.identityFile());
    }

    public Probe probe(String appId, String secret) {
        String resolvedAppId = appId == null || appId.isBlank() ? current.appId() : appId.trim();
        String resolvedSecret = secret == null || secret.isBlank() ? current.appSecret() : secret.trim();
        if (resolvedAppId == null || resolvedAppId.isBlank()
                || resolvedSecret == null || resolvedSecret.isBlank()) {
            return new Probe(false, "appId 或 appSecret 未配置");
        }
        try {
            String body = MAPPER.writeValueAsString(new java.util.LinkedHashMap<String, String>() {{
                put("app_id", resolvedAppId);
                put("app_secret", resolvedSecret);
            }});
            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(response.body());
            int code = root.path("code").asInt(-1);
            if (code == 0) {
                return new Probe(true, "飞书凭据有效，已获取 tenant_access_token");
            }
            return new Probe(false, "飞书返回 code=" + code + " msg=" + root.path("msg").asText(""));
        } catch (Exception failure) {
            return new Probe(false, "飞书凭据测试失败：" + failure.getClass().getSimpleName());
        }
    }

    private GatewayProperties.Feishu load() {
        if (!Files.exists(file)) {
            return env;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode feishu = root.path("feishu");
            if (!feishu.isObject()) {
                throw new IllegalStateException("渠道配置文件缺 feishu 节点：" + file.toAbsolutePath());
            }
            GatewayProperties.Feishu loaded = new GatewayProperties.Feishu(
                    feishu.path("enabled").asBoolean(false),
                    feishu.path("appId").asText(""),
                    feishu.path("appSecret").asText(""),
                    feishu.path("tenant").asText("T001"),
                    feishu.path("customer").asText("C001"),
                    feishu.path("identityFile").asText(""));
            validate(loaded);
            fromFile = true;
            log.info("渠道连接配置从文件加载：{} (enabled={}, tenant={})",
                    file.toAbsolutePath(), loaded.enabled(), loaded.tenant());
            return loaded;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("渠道配置文件读取失败：" + file.toAbsolutePath(), failure);
        }
    }

    private static void validate(GatewayProperties.Feishu feishu) {
        if (feishu.enabled()
                && (feishu.appId() == null || feishu.appId().isBlank()
                || feishu.appSecret() == null || feishu.appSecret().isBlank())) {
            throw new IllegalStateException("启用的飞书连接缺 appId/appSecret");
        }
    }

    private void write(GatewayProperties.Feishu feishu) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode node = root.putObject("feishu");
        node.put("enabled", feishu.enabled());
        node.put("appId", feishu.appId());
        node.put("appSecret", feishu.appSecret());
        node.put("tenant", feishu.tenant());
        node.put("customer", feishu.customer());
        node.put("identityFile", feishu.identityFile());
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(absolute, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception failure) {
            throw new IllegalStateException("渠道配置文件写入失败：" + file.toAbsolutePath(), failure);
        }
    }
}
