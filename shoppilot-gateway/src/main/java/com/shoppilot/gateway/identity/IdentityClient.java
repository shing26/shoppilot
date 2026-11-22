package com.shoppilot.gateway.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.identity.AccountView;
import com.shoppilot.tool.identity.AuthenticateRequest;
import com.shoppilot.tool.identity.ChangePasswordRequest;
import com.shoppilot.tool.identity.RegisterAccountRequest;
import com.shoppilot.tool.identity.RefreshSession;
import com.shoppilot.tool.identity.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 网关 → 身份域（biz-mock）的调用（round25 票 81 / ADR 0058 第 1 条）。
 *
 * <p><b>刻意不套 {@code BizMockClient} 的熔断与工具语义</b>：那条路的失败会被翻译成给模型看的
 * {@code ToolStatus}，而登录失败要回的是给人看的一句话。两者共用同一段 HTTP 是可以的，
 * 共用同一套失败翻译不行。
 *
 * <p>租户从<b>方法参数</b>进来而不是从 {@link TenantContext}——登录那一刻还没有已验签的身份，
 * 这是本服务里唯一一处「先有租户、后有身份」的顺序。
 */
@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    /**
     * 一次调用的结局。
     *
     * @param status 下游原样带回的 HTTP 状态（401 / 409 / 200…），由控制器决定怎么翻译
     * @param message 下游给的一句话；{@link #account()} 非空时无意义
     */
    public record Attempt(int status, AccountView account, String message, String code, Long retryAfterSeconds) {

        public Attempt(int status, AccountView account, String message) {
            this(status, account, message, null, null);
        }

        public Attempt(int status, AccountView account, String message, String code) {
            this(status, account, message, code, null);
        }

        public boolean rejected() {
            return account == null;
        }
    }

    /** 刷新的一次结局；{@code code} 区分「过期」与「复用」，网关据此决定是否清 Cookie。 */
    public record RefreshAttempt(int status, RefreshSession session, String message, String code) {

        public boolean rejected() {
            return session == null;
        }
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final GatewayProperties.BizMock config;

    public IdentityClient(HttpClient http, ObjectMapper mapper, GatewayProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.config = properties.bizmock();
    }

    public Attempt register(String tenantId, String username, String password, String displayName) {
        return call("/api/identity/register", new RegisterAccountRequest(username, password, displayName), tenantId);
    }

    public Attempt authenticate(String tenantId, String username, String password) {
        return call("/api/identity/authenticate", new AuthenticateRequest(username, password), tenantId);
    }

    public Attempt account(String tenantId, String accountId) {
        return call("/api/identity/accounts/" + accountId, null, tenantId, "GET");
    }

    public RefreshAttempt openSession(String tenantId, AccountView account) {
        return refreshCall("/api/identity/sessions", account, tenantId);
    }

    public RefreshAttempt refresh(String tenantId, String refreshToken) {
        return refreshCall("/api/identity/refresh", Map.of("refreshToken", refreshToken), tenantId);
    }

    public void logout(String tenantId, String refreshToken) {
        refreshCall("/api/identity/logout", Map.of("refreshToken", refreshToken), tenantId);
    }

    public Attempt changePassword(String tenantId, String accountId, String currentPassword, String newPassword) {
        return call("/api/identity/accounts/" + accountId + "/password",
                new ChangePasswordRequest(currentPassword, newPassword), tenantId);
    }

    public Attempt setAccountStatus(String tenantId, String accountId, boolean active) {
        return call("/api/identity/accounts/" + accountId + "/status",
                new com.shoppilot.tool.identity.AccountStatusRequest(active), tenantId);
    }

    public Attempt setAccountRole(String tenantId, String accountId, UserRole role) {
        return call("/api/identity/accounts/" + accountId + "/role",
                new com.shoppilot.tool.identity.AccountRoleRequest(role), tenantId);
    }

    private Attempt call(String path, Object body, String tenantId) {
        return call(path, body, tenantId, "POST");
    }

    private Attempt call(String path, Object body, String tenantId, String method) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", config.internalToken())
                    // 这一行是「用户选了哪家店」，不是身份断言：租户由身份域按它去查账号，
                    // 而令牌里的 tid 取自**库里那一行**的 tenant_id，不是这个请求里的值。
                    // ADR 0005 防线一管的是「已验签身份只能从上下文来」，这里还没有身份可验。
                    .header("X-Tenant-Id", tenantId == null ? "" : tenantId);
            HttpRequest request = "GET".equals(method)
                    ? builder.GET().build()
                    : builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return new Attempt(response.statusCode(), mapper.readValue(response.body(), AccountView.class), null);
            }
            return new Attempt(response.statusCode(), null, messageOf(response.body()), codeOf(response.body()),
                    retryAfterOf(response));
        } catch (Exception unavailable) {
            log.warn("身份域不可达 path={} error={}", path, unavailable.getMessage());
            return new Attempt(0, null, "身份服务不可达");
        }
    }

    private RefreshAttempt refreshCall(String path, Object body, String tenantId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                    .timeout(config.readTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", config.internalToken())
                    .header("X-Tenant-Id", tenantId == null ? "" : tenantId)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return new RefreshAttempt(response.statusCode(),
                        mapper.readValue(response.body(), RefreshSession.class), null, null);
            }
            return new RefreshAttempt(response.statusCode(), null, messageOf(response.body()),
                    codeOf(response.body()));
        } catch (Exception unavailable) {
            log.warn("身份域不可达 path={} error={}", path, unavailable.getMessage());
            return new RefreshAttempt(0, null, "身份服务不可达", null);
        }
    }

    /** 下游那句话透传给调用方；拿不到就给一句兜底，不返回 null 让上层自己拼。 */
    private String messageOf(String body) {
        try {
            String message = mapper.readTree(body).path("message").asText("");
            return message.isBlank() ? "请求被拒绝" : message;
        } catch (Exception unparsable) {
            return "请求被拒绝";
        }
    }

    /** 下游的机器码（{@code refresh_reuse} / {@code refresh_invalid}）；没有就返回 null。 */
    private String codeOf(String body) {
        try {
            String code = mapper.readTree(body).path("code").asText("");
            return code.isBlank() ? null : code;
        } catch (Exception unparsable) {
            return null;
        }
    }

    /** 锁定响应的 Retry-After 必须透传，否则浏览器与前端只能看到一句没有等待时间的 429。 */
    private static Long retryAfterOf(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException notSeconds) {
                        return null;
                    }
                })
                .orElse(null);
    }

    /** 令牌里带的角色；给控制器判响应用。 */
    public static UserRole roleOf(TenantContext.Identity identity) {
        return identity == null ? UserRole.BUYER : identity.role();
    }
}
