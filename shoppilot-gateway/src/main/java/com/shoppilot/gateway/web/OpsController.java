package com.shoppilot.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.llm.LlmFaultInjector;
import com.shoppilot.gateway.llm.TokenBudget;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.tool.Intent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维代理端点（ticket 14、15）。
 *
 * <p>存在的唯一理由是把 biz-mock 挡在网关后面：调试台要注入故障、拉工单队列，
 * 但 {@code X-Internal-Token} 一旦进浏览器就等于没有这道门，而 biz-mock 只监听本机、
 * 跨 origin 直连必被 CORS 拦。所以浏览器只跟同源网关说话，凭证由网关在服务端补。
 *
 * <p>工单是租户级数据，身份取自已验签的 {@link TenantContext}，不接受路径或查询参数里的租户号。
 * 故障注入与演示复位是平台级动作，额外要求运维凭证。
 */
@RestController
@RequestMapping("/api/v1/support/ops")
public class OpsController {

    private final HttpClient http;
    private final GatewayProperties properties;
    private final BizMockClient bizMockClient;
    private final ObjectMapper mapper;
    private final LlmFaultInjector llmFaultInjector;
    private final CacheService cacheService;
    private final KbEpoch kbEpoch;
    private final TokenBudget tokenBudget;

    public OpsController(HttpClient http, GatewayProperties properties, BizMockClient bizMockClient,
                         ObjectMapper mapper, LlmFaultInjector llmFaultInjector, CacheService cacheService,
                         KbEpoch kbEpoch, TokenBudget tokenBudget) {
        this.http = http;
        this.properties = properties;
        this.bizMockClient = bizMockClient;
        this.mapper = mapper;
        this.llmFaultInjector = llmFaultInjector;
        this.cacheService = cacheService;
        this.kbEpoch = kbEpoch;
        this.tokenBudget = tokenBudget;
    }

    /** 本店工单队列，按当前身份的租户隔离。 */
    @GetMapping("/tickets")
    public ResponseEntity<String> listTickets() {
        return forward("GET", "/api/tickets", null, true);
    }

    /**
     * 店铺清单：调试台的租户下拉从这里取，不在页面里写死一份名单。
     * 写死的那份会和 biz-mock 的 tenants 表悄悄分家，届时"选不到店"会被当成前端 bug 查。
     */
    @GetMapping("/tenants")
    public ResponseEntity<String> tenants() {
        return forward("GET", "/api/admin/tenants", null, false);
    }

    @PatchMapping("/tickets/{ticketId}/status")
    public ResponseEntity<String> updateTicketStatus(@PathVariable String ticketId,
                                                     @RequestBody Map<String, Object> body) {
        return forward("PATCH", "/api/tickets/" + ticketId + "/status", body, true);
    }

    @GetMapping("/fault")
    public ResponseEntity<String> getFault(@RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        return guarded("GET", "/api/admin/fault", null, opsToken);
    }

    @PutMapping("/fault")
    public ResponseEntity<String> setFault(@RequestHeader(value = "X-Ops-Token", required = false) String opsToken,
                                           @RequestBody Map<String, Object> body) {
        return guarded("PUT", "/api/admin/fault", body, opsToken);
    }

    @GetMapping("/stats")
    public ResponseEntity<String> stats(@RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        return guarded("GET", "/api/admin/stats", null, opsToken);
    }

    /** 把四张演示固定单恢复初始状态，供彩排与验收脚本重复执行。 */
    @PostMapping("/demo/reset")
    public ResponseEntity<String> resetDemo(@RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        return guarded("POST", "/api/admin/demo/reset", null, opsToken);
    }

    /** 熔断器状态来自网关进程自己，不需要代理。 */
    @GetMapping("/circuit")
    public Map<String, Object> circuit() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("circuitState", bizMockClient.circuitState());
        view.put("lastToolLatencyMs", bizMockClient.lastLatencyMs());
        view.put("llmMode", properties.llm().mode());
        // 评测脚本要记录"这组数字是哪个模型跑出来的"，以及跑之前预算还剩多少（ADR 0012）
        view.put("llmModel", activeModelName());
        view.put("tokensUsedToday", tokenBudget.usedToday());
        view.put("dailyTokenBudget", properties.llm().dailyTokenBudget());
        return view;
    }

    private String activeModelName() {
        GatewayProperties.Llm llm = properties.llm();
        if (llm.dev()) {
            return llm.model();
        }
        if (llm.local()) {
            return llm.localModel();
        }
        return "MockLLM";
    }

    /**
     * 模型侧故障注入（none|timeout|unavailable|budget）。
     *
     * <p>这是网关自己的状态，不代理给 biz-mock——被注入的是模型调用链，不是业务链。
     */
    @GetMapping("/llm-fault")
    public Map<String, Object> getLlmFault() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mode", llmFaultInjector.mode());
        view.put("modes", LlmFaultInjector.MODES);
        return view;
    }

    @PutMapping("/llm-fault")
    public ResponseEntity<String> setLlmFault(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken,
            @RequestBody Map<String, Object> body) {
        if (!requireOps(opsToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"ops endpoint disabled\"}");
        }
        try {
            llmFaultInjector.configure(String.valueOf(body.getOrDefault("mode", "none")));
            return ResponseEntity.ok(mapper.writeValueAsString(getLlmFault()));
        } catch (IllegalArgumentException | JsonProcessingException rejected) {
            return ResponseEntity.badRequest().body("{\"error\":\"" + safe(rejected.getMessage()) + "\"}");
        }
    }

    /** 给某个问法打负缓存标记，用于稳定复现 {@code INTENT_UNRESOLVED}。 */
    @PostMapping("/negative-cache")
    public ResponseEntity<String> markNegative(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken,
            @RequestBody Map<String, Object> body) {
        if (!requireOps(opsToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"ops endpoint disabled\"}");
        }
        String query = String.valueOf(body.getOrDefault("query", ""));
        Intent intent;
        try {
            intent = Intent.valueOf(String.valueOf(body.getOrDefault("intent", Intent.POLICY_RETURN.name())));
        } catch (IllegalArgumentException unknownIntent) {
            return ResponseEntity.badRequest().body("{\"error\":\"unknown intent\"}");
        }
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"query required\"}");
        }
        TenantContext.Identity identity = TenantContext.current();
        cacheService.markNegative(identity.tenantId(), intent, kbEpoch.current(), query);
        return ResponseEntity.ok("{\"marked\":true}");
    }

    private boolean requireOps(String opsToken) {
        GatewayProperties.Ops ops = properties.ops();
        return ops.enabled() && ops.token() != null && ops.token().equals(opsToken);
    }

    /**
     * 推进知识库纪元：演示"政策一改、旧答案整体作废"这条链路（ADR 0003）。
     * 注意副作用：纪元同时是检索过滤器，推完必须重跑入库，否则政策条款会被整体摘出检索范围。
     */
    @PostMapping("/epoch/bump")
    public ResponseEntity<String> bumpEpoch(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        if (!requireOps(opsToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"invalid or disabled ops token\"}");
        }
        return ResponseEntity.ok("{\"kbEpoch\":" + kbEpoch.bump() + "}");
    }

    /**
     * 清空答案缓存（L1 正文 + 负标记 + L2 向量表），不动知识库纪元。
     *
     * <p>验收脚本靠它把上一轮留下的命中清干净，否则"未命中路径"根本走不到模型。
     * 这里不能用纪元推进代替：纪元同时是检索过滤器，推一次等于把政策条款整体摘出检索范围。
     */
    @PostMapping("/cache/flush")
    public ResponseEntity<String> flushCache(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        if (!requireOps(opsToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"invalid or disabled ops token\"}");
        }
        try {
            return ResponseEntity.ok(mapper.writeValueAsString(cacheService.flush()));
        } catch (JsonProcessingException failure) {
            return ResponseEntity.ok("{\"l1KeysDeleted\":-1}");
        }
    }

    private ResponseEntity<String> guarded(String method, String path, Map<String, Object> body, String opsToken) {
        if (!requireOps(opsToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"invalid or disabled ops token\"}");
        }
        return forward(method, path, body, false);
    }

    private ResponseEntity<String> forward(String method, String path, Map<String, Object> body,
                                           boolean tenantScoped) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.bizmock().baseUrl() + path))
                    .timeout(properties.bizmock().readTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", properties.bizmock().internalToken());
            if (tenantScoped) {
                TenantContext.Identity identity = TenantContext.current();
                builder.header("X-Tenant-Id", identity.tenantId());
                builder.header("X-Customer-Id", identity.customerId() == null ? "" : identity.customerId());
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(writeJson(body));
            builder.method(method, publisher);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (Exception downstreamUnavailable) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"biz-mock unreachable: " + safe(downstreamUnavailable.getMessage()) + "\"}");
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException unserializable) {
            return "{}";
        }
    }

    private static String safe(String message) {
        return message == null ? "" : message.replace('"', '\'');
    }
}
