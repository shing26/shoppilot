package com.shoppilot.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.knowledge.EmbeddingClient;
import com.shoppilot.gateway.knowledge.EsRestClient;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.knowledge.QdrantRestClient;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可降级依赖的状态挂在 {@code deps} 组里，就绪门一个字不改（ADR 0026、票 23）。
 *
 * <p>缺陷本体：网关此前没有任何一格能回答「向量引擎与词法引擎还在不在」。运维口读不到，调试台那颗
 * 灯就只能恒绿——它甚至从来没写过变红那一支。于是「还能应答但已经降级」与「别再给它流量」这两件
 * 本来各自有意义的事，在页面上塌成同一个恒绿。
 *
 * <p>本文件的用例一律**从 {@code application.yml} 读组配置**，不在测试里重抄一份成员名：读的是真配置，
 * 有人把 {@code deps} 并进 {@code readiness} 时红的是配置那一格，而不是「测试与实现各说各话」。
 * 三个指示器的注册也全部由 {@link DependencyHealthConfiguration} 的 {@code @Bean} 方法真实产出，
 * 摘掉任一注册即当场判错（第十一轮给合取项立的规矩）。
 */
class DependencyHealthTest {

    /** 无人监听的端口：连接立即被拒，等价于「这个依赖整个没了」。 */
    private static final String DEAD = "http://127.0.0.1:1";

    private HttpServer engines;
    private String liveUrl;

    @BeforeEach
    void startFakeEngines() throws Exception {
        // 一台「活着但空库」的引擎：ping 通、计数 0，用来把「引擎可达」与「语料在位」分开钉
        engines = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engines.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/points/count") ? "{\"result\":{\"count\":0},\"status\":\"ok\"}"
                    : "{\"count\":0,\"status\":\"ok\",\"version\":{\"number\":\"8.13.0\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        engines.start();
        liveUrl = "http://127.0.0.1:" + engines.getAddress().getPort();
    }

    @AfterEach
    void stopFakeEngines() {
        engines.stop(0);
    }

    // ---- 配置形状：ADR 0026 的红线 ----

    @Test
    @DisplayName("readiness 组成员一字未动：只有 readinessState")
    void readinessGateMembersUntouched() {
        assertThat(groupInclude("readiness")).containsExactly("readinessState");
        assertThat(groupInclude("liveness")).containsExactly("livenessState");
    }

    @Test
    @DisplayName("deps 组恰好三格，且这三格一格都不在就绪门里")
    void depsGroupHoldsTheThreeIndicators() {
        assertThat(groupInclude("deps")).containsExactlyInAnyOrder("qdrant", "elasticsearch", "knowledgeBase");
        assertThat(groupInclude("readiness")).doesNotContainAnyElementsOf(groupInclude("deps"));
    }

    @Test
    @DisplayName("摘掉任一指示器的注册，deps 组当场少一格")
    void eachRegistrationIsLoadBearing() {
        runner(DEAD, DEAD).run(context -> {
            assertThat(context).hasNotFailed();
            Set<String> registered = java.util.Arrays.stream(context.getBeanNamesForType(HealthIndicator.class))
                    .map(DependencyHealthTest::contributorId)
                    .collect(Collectors.toSet());
            // 三格各自有人注册，且 bean 名去后缀正好落在组里那三个成员名上。
            // 摘掉任一 @Bean 注册时先炸的是上一行的 hasNotFailed()：Boot 会校验组成员是否存在，
            // 少一格直接拒绝起上下文；改名则炸在这里——名字对不上，运维口上那格就没人了。
            assertThat(registered).contains("qdrant", "elasticsearch", "knowledgeBase");
            assertThat(members(context.getBean(HealthEndpoint.class), "deps"))
                    .containsExactlyInAnyOrder("qdrant", "elasticsearch", "knowledgeBase");
        });
    }

    // ---- DOWN 时的三件事，必须来自同一个世界 ----

    @Test
    @DisplayName("两个引擎同时不可达：deps 红、readiness 仍 UP、答案按既有降级路径给出")
    void dependencyDownTurnsDepsRedWithoutTouchingReadiness() {
        GatewayProperties properties = properties(DEAD, DEAD);
        runner(properties, DEAD, DEAD).run(context -> {
            HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
            assertThat(status(endpoint, "deps")).isEqualTo(Status.DOWN);
            // 这是 ADR 0026 的机器侧答辩：依赖没了，运维口不许顺手把流量摘干
            assertThat(status(endpoint, "readiness")).isEqualTo(Status.UP);
            // 上一行的 UP 之所以可信，全在这一格：就绪门里除了 readinessState 不许有别的东西
            assertThat(members(endpoint, "readiness")).containsExactly("readinessState");
            assertThat(members(endpoint, "deps")).containsExactlyInAnyOrder("qdrant", "elasticsearch", "knowledgeBase");
            assertThat(status(endpoint, "deps", "elasticsearch")).isEqualTo(Status.DOWN);
            assertThat(status(endpoint, "deps", "qdrant")).isEqualTo(Status.DOWN);
        });

        // 第三件事走业务自己的读路径：同一个死掉的 ES，检索不抛异常，而是标出「这一路挂了」
        HybridRetriever retriever = new HybridRetriever(liveQdrant(), new EsRestClient(HttpClient.newHttpClient(),
                new ObjectMapper(), properties), mock(EmbeddingClient.class), kbEpoch(), properties,
                new SimpleMeterRegistry());
        HybridRetriever.Result result = retriever.retrieve("七天无理由", "T001", null);
        assertThat(result.degraded()).isTrue();
        assertThat(result.lexicalHits()).isZero();
        assertThat(result.rules()).isNotEmpty();
    }

    @Test
    @DisplayName("引擎活着而库里 0 块：只有 knowledgeBase 红，两格 ping 仍 UP")
    void emptyCorpusTurnsKnowledgeBaseRedAlone() {
        runner(liveUrl, liveUrl).run(context -> {
            HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
            assertThat(status(endpoint, "deps", "qdrant")).isEqualTo(Status.UP);
            assertThat(status(endpoint, "deps", "elasticsearch")).isEqualTo(Status.UP);
            // 这一格不许躲在两个 ping 后面报绿：0 块的答案只会退化成「未检索到相关条款」
            assertThat(status(endpoint, "deps", "knowledgeBase")).isEqualTo(Status.DOWN);
            assertThat(status(endpoint, "deps")).isEqualTo(Status.DOWN);
            assertThat(status(endpoint, "readiness")).isEqualTo(Status.UP);
        });
    }

    // ---- 页面那颗灯 ----

    @Test
    @DisplayName("调试台健康灯改读 deps，且变红那一支真的写出来了")
    void healthLightReadsDepsAndHasRedBranch() throws Exception {
        String page = servedPage();
        assertThat(page).contains("/actuator/health/deps");
        assertThat(page).contains("classList.add('bad')");
        // 三态各自有名：拿不到读数时不许猜成绿，也不许猜成红
        assertThat(page).contains("classList.add('unknown')");
        assertThat(page).contains(".dot.unknown");
        // 三态各自有一个可断言的名字，验收脚本不必去猜 computed style 或颜色值
        assertThat(page).contains("dot.dataset.state = 'ok'");
        assertThat(page).contains("dot.dataset.state = 'bad'");
        assertThat(page).contains("dot.dataset.state = 'unknown'");
        // 灯得真的有人点：开页走一次，而不是等哪次操作顺手刷到
        assertThat(page).contains("await refreshHealth();");
        // 缺陷本体那一支：登录成功曾把灯抹成绿。签出 token 与向量引擎在不在是两件事。
        assertThat(slice(page, "async function login() {", "\n}"))
                .as("login 不许再碰健康灯：涂绿正是这盏灯说谎的来路").doesNotContain("health");
    }

    // ---- 研究 ----

    private ApplicationContextRunner runner(String qdrantUrl, String esUrl) {
        return runner(properties(qdrantUrl, esUrl), qdrantUrl, esUrl);
    }

    private ApplicationContextRunner runner(GatewayProperties properties, String qdrantUrl, String esUrl) {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        return new ApplicationContextRunner()
                // 这三点是 readiness 那一格在真进程里的来路：probes.enabled=true 才让
                // AvailabilityProbesAutoConfiguration 注册 readinessStateHealthIndicator（与 liveness 同支），
                // 而它以 ApplicationAvailability 为参数。少配一支，组校验会直接判「成员不存在」，
                // 红的是测试脚手架而不是实现。
                .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class,
                        AvailabilityHealthContributorAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class))
                // 组配置整份取自 application.yml：测试不重抄成员名，改配置的人红的就是这一格
                .withPropertyValues(healthEndpointProperties())
                .withBean(GatewayProperties.class, () -> properties)
                .withBean(ApplicationAvailability.class, DependencyHealthTest::applicationReady)
                .withBean(KbEpoch.class, DependencyHealthTest::kbEpoch)
                .withBean(QdrantRestClient.class,
                        () -> new QdrantRestClient(http, mapper, properties))
                .withBean(EsRestClient.class, () -> new EsRestClient(http, mapper, properties))
                .withUserConfiguration(DependencyHealthConfiguration.class);
    }

    /**
     * 真进程里 readiness 是由 {@code ApplicationReadyEvent} 推到 ACCEPTING_TRAFFIC 的，测试上下文没有那段
     * 生命周期，所以这里拿真的 {@link ApplicationAvailabilityBean} 手工推到位。
     *
     * <p>本票要钉的不是「readiness 该不该 UP」——那是 Spring Boot 自己的事；要钉的是 {@code deps} 红的时候
     * 它<b>不许跟着红</b>。所以这里必须是 UP，且成员只有它自己那一格。
     */
    private static ApplicationAvailability applicationReady() {
        ApplicationAvailabilityBean availability = new ApplicationAvailabilityBean();
        availability.onApplicationEvent(new AvailabilityChangeEvent<>(availability, ReadinessState.ACCEPTING_TRAFFIC));
        return availability;
    }

    private static QdrantRestClient liveQdrant() {
        QdrantRestClient qdrant = mock(QdrantRestClient.class);
        when(qdrant.search(anyString(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(new QdrantRestClient.Hit("point-1", 0.9d,
                        Map.of("rule_id", "R-7D", "title", "七天无理由", "text", "签收后七日内可退", "scope", "SHOP"))));
        return qdrant;
    }

    private static GatewayProperties properties(String qdrantUrl, String esUrl) {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.retrieval()).thenReturn(new GatewayProperties.Retrieval(qdrantUrl, esUrl,
                "policy_rules", "answer_cache", "shoppilot_rules", 10, 10, 5, 60));
        return properties;
    }

    private static KbEpoch kbEpoch() {
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        return epoch;
    }

    /** bean 名去掉 {@code HealthIndicator} 后缀就是运维口上的成员 id，注册名与组里的名字必须对得上。 */
    private static String contributorId(String beanName) {
        return beanName.endsWith("HealthIndicator")
                ? beanName.substring(0, beanName.length() - "HealthIndicator".length())
                : beanName;
    }

    private static Status status(HealthEndpoint endpoint, String... path) {
        return component(endpoint, path).getStatus();
    }

    private static List<String> members(HealthEndpoint endpoint, String group) {
        HealthComponent component = component(endpoint, group);
        assertThat(component).isInstanceOf(CompositeHealth.class);
        return List.copyOf(((CompositeHealth) component).getComponents().keySet());
    }

    private static HealthComponent component(HealthEndpoint endpoint, String... path) {
        HealthComponent component = endpoint.healthForPath(path);
        assertThat(component).as("运维口 /actuator/health/" + String.join("/", path) + " 读不到东西").isNotNull();
        return component;
    }

    private static List<String> groupInclude(String group) {
        Map<String, Object> spec = groupSpec(group);
        Object include = spec.get("include");
        assertThat(include).as("application.yml 里 deps/readiness 组必须显式写出 include").isNotNull();
        return List.of(String.valueOf(include).split("\\s*,\\s*"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> groupSpec(String group) {
        Map<String, Object> groups = mapAt(defaultDocument(), "management", "endpoint", "health", "group");
        Object spec = groups.get(group);
        assertThat(spec).as("application.yml 默认文档里没有 " + group + " 组").isInstanceOf(Map.class);
        return (Map<String, Object>) spec;
    }

    /**
     * 把 application.yml 默认文档里的 {@code management.endpoint.health.group.*} 原样摊平成属性。
     * 测试上下文因此与真进程读同一份组配置，而不是读测试里另写的一份。
     */
    @SuppressWarnings("unchecked")
    private static String[] healthEndpointProperties() {
        Map<String, Object> groups = mapAt(defaultDocument(), "management", "endpoint", "health", "group");
        List<String> properties = new ArrayList<>();
        properties.add("management.endpoint.health.probes.enabled=true");
        groups.forEach((group, spec) -> ((Map<String, Object>) spec).forEach((key, value) ->
                properties.add("management.endpoint.health.group." + group + "." + key + "=" + value)));
        return properties.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> root, String... path) {
        Map<String, Object> current = root;
        for (String key : path) {
            Object child = current.get(key);
            assertThat(child).as("application.yml 默认文档里缺 " + key + " 这一层").isInstanceOf(Map.class);
            current = (Map<String, Object>) child;
        }
        return current;
    }

    private static Map<String, Object> defaultDocument() {
        try (InputStream in = DependencyHealthTest.class.getResourceAsStream("/application.yml")) {
            Object first = new Yaml().loadAll(in).iterator().next();
            return new LinkedHashMap<>((Map<String, Object>) Objects.requireNonNull(first,
                    "application.yml 读不到默认文档"));
        } catch (Exception failure) {
            throw new IllegalStateException("读不到 application.yml 默认文档", failure);
        }
    }

    /** 取一段函数体：从签名到第一个「行首闭括号」。页面脚本没有嵌套函数时可足够。 */
    private static String slice(String haystack, String from, String to) {
        int start = haystack.indexOf(from);
        assertThat(start).as("静态页里找不到 " + from).isNotNegative();
        int end = haystack.indexOf(to, start);
        assertThat(end).as(from + " 的收尾括号不在行首").isGreaterThan(start);
        return haystack.substring(start, end);
    }

    private static String servedPage() throws Exception {
        try (InputStream in = DependencyHealthTest.class.getResourceAsStream("/static/index.html")) {
            return new String(Objects.requireNonNull(in, "静态页在 classpath：/static/index.html")
                    .readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
