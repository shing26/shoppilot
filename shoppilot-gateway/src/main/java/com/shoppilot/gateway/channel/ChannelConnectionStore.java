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

    /** 管理面唯一状态（ADR 0087）：布尔 restartRequired 是它的派生。 */
    public enum State {
        APPLIED, RESTART_REQUIRED, ROLLED_BACK, DISABLED
    }

    private final Path file;
    private final GatewayProperties.Feishu env;
    private volatile GatewayProperties.Feishu current;
    private volatile boolean fromFile;
    private volatile boolean restartRequired;
    private volatile State state;
    private volatile String message = "";

    public ChannelConnectionStore(GatewayProperties properties,
                                  @Value("${shoppilot.channels.file:config/channels.json}") String path) {
        this.file = Path.of(path == null || path.isBlank() ? "config/channels.json" : path);
        this.env = properties == null || properties.feishu() == null
                ? new GatewayProperties.Feishu(false, "", "", "T001", "C001", "")
                : properties.feishu();
        this.current = load();
        this.state = this.current.enabled() ? State.APPLIED : State.DISABLED;
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

    public State state() {
        return state;
    }

    public String message() {
        return message;
    }

    public String maskedAppId() {
        String appId = current.appId();
        if (appId == null || appId.isBlank()) {
            return "(未配置)";
        }
        return appId.length() <= 6 ? "****" : appId.substring(0, 6) + "****";
    }

    public synchronized FeishuConnection save(FeishuConnection update) {
        GatewayProperties.Feishu next = resolve(update);
        persist(next);
        markRestartRequired("配置已保存，需重启网关后生效。");
        return new FeishuConnection(next.enabled(), next.appId(), "", next.tenant(),
                next.customer(), next.identityFile());
    }

    /** 合并「留空沿用当前值」并校验；不写盘、不切换。 */
    public synchronized GatewayProperties.Feishu resolve(FeishuConnection update) {
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
        return next;
    }

    /** 写盘并切到「候选配置」；真正的生效状态由生命周期管理器标记。 */
    public synchronized void persist(GatewayProperties.Feishu next) {
        write(next);
        this.current = next;
        this.fromFile = true;
        this.restartRequired = false;
        log.info("飞书连接配置已写盘：enabled={} tenant={} customer={}",
                next.enabled(), next.tenant(), next.customer());
    }

    /** 切换失败时恢复到 last-known-good：原来没有文件就删掉候选文件，回到 env。 */
    public synchronized void restore(GatewayProperties.Feishu previous, boolean previousFromFile) {
        this.current = previous;
        this.fromFile = previousFromFile;
        this.restartRequired = false;
        if (previousFromFile) {
            write(previous);
        } else {
            try {
                Files.deleteIfExists(file.toAbsolutePath());
            } catch (Exception failure) {
                log.warn("回滚渠道配置文件失败：{}", failure.getMessage());
            }
        }
    }

    public synchronized void markApplied() {
        this.restartRequired = false;
        this.state = current.enabled() ? State.APPLIED : State.DISABLED;
        this.message = current.enabled() ? "配置已生效，长连接已切换。" : "长连接已停用。";
    }

    public synchronized void markRestartRequired(String why) {
        this.restartRequired = true;
        this.state = State.RESTART_REQUIRED;
        this.message = why;
        log.warn("飞书连接配置需重启生效：{}", why);
    }

    public synchronized void markRolledBack(String why) {
        this.restartRequired = false;
        this.state = State.ROLLED_BACK;
        this.message = why;
        log.warn("飞书连接配置回滚：{}", why);
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
