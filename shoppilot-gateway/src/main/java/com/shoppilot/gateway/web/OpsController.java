package com.shoppilot.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.BizMockClient;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.config.DevDefaultsPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.OpsAccess;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.llm.LlmFaultInjector;
import com.shoppilot.gateway.llm.TokenBudget;
import com.shoppilot.gateway.knowledge.HybridRetriever;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 *
 * <p>错误体两分（ADR 0028）：网关自己拒的一律经 {@link ApiErrorWriter} 出 {@code code} / {@code message}
 * / {@code traceId}；代理透传回来的下游体逐字节原样出，包一层外壳就把「谁拒的」这个信息抹平了。
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
    private final HybridRetriever retriever;
    private final DevDefaultsPolicy devDefaults;
    private final ApiErrorWriter errors;

    public OpsController(HttpClient http, GatewayProperties properties, BizMockClient bizMockClient,
                         ObjectMapper mapper, LlmFaultInjector llmFaultInjector, CacheService cacheService,
                         KbEpoch kbEpoch, TokenBudget tokenBudget,
                         HybridRetriever retriever, DevDefaultsPolicy devDefaults, ApiErrorWriter errors) {
        this.http = http;
        this.properties = properties;
        this.bizMockClient = bizMockClient;
        this.mapper = mapper;
        this.llmFaultInjector = llmFaultInjector;
        this.cacheService = cacheService;
        this.kbEpoch = kbEpoch;
        this.tokenBudget = tokenBudget;
        this.retriever = retriever;
        this.devDefaults = devDefaults;
        this.errors = errors;
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
        // 只记模型名不够：dev 模式指向哪个端点决定了这组数字是云端真模型还是本地兼容端点
        view.put("llmBaseUrl", activeBaseUrl());
        view.put("tokensUsedToday", tokenBudget.usedToday());
        view.put("dailyTokenBudget", properties.llm().dailyTokenBudget());
        // ADR 0029：默认凭证只在回环上合法，所以「是不是回环」与「正在吃哪几处默认值」得能被机器读到。
        // 注意绑定回环只是必要条件、不是防线——反向代理打进来的也是 127.0.0.1。
        view.put("bindLoopback", devDefaults.loopback());
        view.put("devDefaultsInUse", devDefaults.devDefaultsInUse());
        return view;
    }

    /**
     * 实验开关回读（ticket 18 压测矩阵）。
     *
     * <p>起因：YAML 里一次缩进手误把 {@code no-embedding-cache} 的属性挂到了 {@code spring:} 下面，
     * profile 照样"生效"、健康检查照样 UP，但那组压测数据已经和对照组完全相同——日志层面的
     * profile 断言挡不住这种失败。压测脚本必须先回读这份实际生效值，再决定要不要跑。
     *
     * <p>{@code virtualThreadRequest} 读的是"处理这个请求的线程是不是虚拟线程"，比读配置更接近真相。
     */
    @GetMapping("/switches")
    public Map<String, Object> switches() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("llmMode", properties.llm().mode());
        view.put("llmBaseUrl", activeBaseUrl());
        view.put("virtualThreadRequest", Thread.currentThread().isVirtual());
        view.put("cacheEnabled", properties.cache().enabled());
        view.put("singleflightEnabled", properties.cache().singleflightEnabled());
        view.put("semanticThreshold", properties.cache().semanticThreshold());
        view.put("embeddingBaseUrl", properties.embedding().baseUrl());
        view.put("embeddingModel", properties.embedding().model());
        view.put("embeddingInProcessCache", properties.embedding().inProcessCache());
        view.put("ratelimitOverrideTenantQuota", properties.ratelimit().overrideTenantQuota());
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

    /** 当前模式真正在打的端点（不含 key）。perf 没有外部端点，直接写 mock。 */
    private String activeBaseUrl() {
        GatewayProperties.Llm llm = properties.llm();
        if (llm.dev()) {
            return llm.baseUrl();
        }
        if (llm.local()) {
            return llm.localBaseUrl();
        }
        return "mock://none";
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
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
        }
        try {
            llmFaultInjector.configure(String.valueOf(body.getOrDefault("mode", "none")));
            return ResponseEntity.ok(mapper.writeValueAsString(getLlmFault()));
        } catch (IllegalArgumentException | JsonProcessingException rejected) {
            return errors.entity(400, ApiError.INVALID_REQUEST, "故障模式设置失败：" + rejected.getMessage());
        }
    }

    /** 给某个问法打负缓存标记，用于稳定复现 {@code INTENT_UNRESOLVED}。 */
    @PostMapping("/negative-cache")
    public ResponseEntity<String> markNegative(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken,
            @RequestBody Map<String, Object> body) {
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
        }
        String query = String.valueOf(body.getOrDefault("query", ""));
        Intent intent;
        try {
            intent = Intent.valueOf(String.valueOf(body.getOrDefault("intent", Intent.POLICY_RETURN.name())));
        } catch (IllegalArgumentException unknownIntent) {
            return errors.entity(400, ApiError.INVALID_REQUEST,
                    "未知意图：" + body.getOrDefault("intent", ""));
        }
        if (query.isBlank()) {
            return errors.entity(400, ApiError.INVALID_REQUEST, "query 不能为空");
        }
        TenantContext.Identity identity = TenantContext.current();
        cacheService.markNegative(identity.tenantId(), intent, kbEpoch.current(), query);
        return ResponseEntity.ok("{\"marked\":true}");
    }

    /** 开关关闭与令牌不匹配是两件事，分开报；以前这里返回布尔，两件事就只能共用一句话。 */
    private OpsAccess opsAccess(String opsToken) {
        GatewayProperties.Ops ops = properties.ops();
        return OpsAccess.evaluate(ops.enabled(), ops.token(), opsToken);
    }

    private ResponseEntity<String> denied(OpsAccess access) {
        return errors.entity(HttpStatus.FORBIDDEN.value(), access.code(), access.message());
    }

    /**
     * 检索质量对比探针：同一次召回里分别取 dense / lexical / fused 三种序，
     * 供 {@code scripts/retrieval_compare.py} 生成 ticket 08 的 dense-only 对比表。
     */
    @GetMapping("/retrieval")
    public ResponseEntity<String> retrievalProbe(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken,
            @RequestParam("query") String query,
            @RequestParam(value = "intent", required = false) String intent,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
        }
        try {
            var diagnosis = retriever.diagnose(query, TenantContext.current().tenantId(),
                    intent == null || intent.isBlank() ? null : Intent.valueOf(intent),
                    Math.max(1, Math.min(20, limit)));
            return ResponseEntity.ok(mapper.writeValueAsString(diagnosis));
        } catch (Exception failure) {
            return ResponseEntity.ok("{\"error\":\"" + failure.getClass().getSimpleName() + "\"}");
        }
    }

    /**
     * 推进知识库纪元：演示"政策一改、旧答案整体作废"这条链路（ADR 0003）。
     * 注意副作用：纪元同时是检索过滤器，推完必须重跑入库，否则政策条款会被整体摘出检索范围。
     */
    @PostMapping("/epoch/bump")
    public ResponseEntity<String> bumpEpoch(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
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
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
        }
        try {
            return ResponseEntity.ok(mapper.writeValueAsString(cacheService.flush()));
        } catch (JsonProcessingException failure) {
            return ResponseEntity.ok("{\"l1KeysDeleted\":-1}");
        }
    }

    private ResponseEntity<String> guarded(String method, String path, Map<String, Object> body, String opsToken) {
        OpsAccess access = opsAccess(opsToken);
        if (!access.allowed()) {
            return denied(access);
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
            return errors.entity(HttpStatus.BAD_GATEWAY.value(), ApiError.DOWNSTREAM_UNREACHABLE,
                    "业务中台不可达：" + downstreamUnavailable.getMessage());
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException unserializable) {
            return "{}";
        }
    }
}
