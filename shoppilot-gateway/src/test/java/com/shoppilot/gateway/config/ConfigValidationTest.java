package com.shoppilot.gateway.config;

import com.shoppilot.gateway.GatewayApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置校验只接格式与范围，不接必填（票 28，ADR 0029）。
 *
 * <p>判错必须发生在绑定/启动阶段，并且报错里能看到出错的配置字段。密钥与运维令牌的当前值
 * 不许成为校验器自己的泄密出口。
 */
class ConfigValidationTest {

    private static final Set<String> CONFIG_PLACEHOLDERS = Set.of(
            "SHOPPILOT_BIZMOCK_URL",
            "SHOPPILOT_CENTROID_CACHE",
            "SHOPPILOT_EMBED_MODEL",
            "SHOPPILOT_ES_URL",
            "SHOPPILOT_INTERNAL_TOKEN",
            "SHOPPILOT_JWT_SECRET",
            "SHOPPILOT_KNOWLEDGE_DIR",
            "SHOPPILOT_LLM_API_KEY",
            "SHOPPILOT_LLM_BASE_URL",
            "SHOPPILOT_LLM_DAILY_TOKEN_BUDGET",
            "SHOPPILOT_LLM_MAX_OUTPUT_TOKENS",
            "SHOPPILOT_LLM_MODEL",
            "SHOPPILOT_LLM_READ_TIMEOUT",
            "SHOPPILOT_LOCAL_LLM_MODEL",
            "SHOPPILOT_OLLAMA_URL",
            "SHOPPILOT_OPS_ENABLED",
            "SHOPPILOT_OPS_TOKEN",
            "SHOPPILOT_QDRANT_URL",
            "SHOPPILOT_REDIS_HOST",
            "SHOPPILOT_REDIS_PORT");

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        ValidationAutoConfiguration.class))
                .withInitializer(new StartupEnvironmentInitializer())
                .withUserConfiguration(BindingConfiguration.class);
    }

    @Test
    @DisplayName("回环绑定下 application.yml、环境后置处理器与两道启动链仍能通过校验并绑定")
    void loopbackDefaultConfigurationStillBinds() {
        runner().withPropertyValues("server.address=127.0.0.1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GatewayProperties.class);
            assertThat(context).hasSingleBean(ValidatedServerProperties.class);
            assertThat(context).hasSingleBean(DevDefaultsPolicy.class);
            assertThat(context.getEnvironment().getProperty("shoppilot.jwt-secret"))
                    .isEqualTo(DevDefaultsPolicy.JWT_SECRET);
            assertThat(context.getEnvironment().getProperty("shoppilot.bizmock.internal-token"))
                    .isEqualTo(DevDefaultsPolicy.INTERNAL_TOKEN);
            assertThat(context.getEnvironment().getProperty("shoppilot.ops.token"))
                    .isEqualTo(DevDefaultsPolicy.OPS_TOKEN);
        });
    }

    @ParameterizedTest(name = "server.port={0}")
    @ValueSource(strings = {"0", "65536"})
    void portMustBeInOneTo65535(String port) {
        assertValidationFailure("server.port=" + port, "server.port");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDurations")
    void everyDurationMustBePositive(String field) {
        assertValidationFailure(field + "=0s", field);
    }

    @ParameterizedTest(name = "semantic-threshold={0}")
    @ValueSource(strings = {"-0.01", "1.01"})
    void semanticThresholdMustBeBetweenZeroAndOne(String threshold) {
        assertValidationFailure("shoppilot.cache.semantic-threshold=" + threshold,
                "shoppilot.cache.semantic-threshold");
    }

    @ParameterizedTest(name = "temperature={0}")
    @ValueSource(strings = {"-0.01", "2.01"})
    void temperatureMustBeBetweenZeroAndTwo(String temperature) {
        assertValidationFailure("shoppilot.llm.temperature=" + temperature, "shoppilot.llm.temperature");
    }

    @Test
    @DisplayName("工具轮次至少 1 轮")
    void maxToolRoundsMustBeAtLeastOne() {
        assertValidationFailure("shoppilot.agent.max-tool-rounds=0", "shoppilot.agent.max-tool-rounds");
    }

    @Test
    @DisplayName("输出上限不得为负（0 合法 = 不限制，ADR 0044 票 50）")
    void maxOutputTokensMustNotBeNegative() {
        assertValidationFailure("shoppilot.llm.max-output-tokens=-1", "shoppilot.llm.max-output-tokens");
    }

    @Test
    @DisplayName("校验失败的报错不回显 JWT、内部令牌、运维令牌与模型密钥")
    void validationFailureDoesNotEchoSecrets() {
        String jwt = "leak-jwt-secret-0123456789";
        String apiKey = "leak-llm-api-key";
        String internal = "leak-internal-token";
        String ops = "leak-ops-token";

        runner().withPropertyValues(
                "shoppilot.llm.temperature=9",
                "shoppilot.jwt-secret=" + jwt,
                "shoppilot.llm.api-key=" + apiKey,
                "shoppilot.bizmock.internal-token=" + internal,
                "shoppilot.ops.token=" + ops).run(context -> {
            String failure = failureText(context.getStartupFailure());
            assertThat(failure).contains("shoppilot.llm.temperature");
            assertThat(failure)
                    .doesNotContain(jwt)
                    .doesNotContain(apiKey)
                    .doesNotContain(internal)
                    .doesNotContain(ops);
        });
    }

    @Test
    @DisplayName("application.yml 的 SHOPPILOT 占位符集合与清单逐字一致")
    void applicationPlaceholderSetIsPinned() {
        assertThat(placeholdersInApplicationYml()).containsExactlyInAnyOrderElementsOf(CONFIG_PLACEHOLDERS);
    }

    private void assertValidationFailure(String propertyValue, String expectedField) {
        runner().withPropertyValues(propertyValue).run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureText(context.getStartupFailure())).contains(expectedField);
        });
    }

    private static Stream<String> invalidDurations() {
        return Stream.of(
                "shoppilot.llm.connect-timeout",
                "shoppilot.llm.read-timeout",
                "shoppilot.llm.perf-first-token-latency",
                "shoppilot.llm.perf-total-latency",
                "shoppilot.embedding.timeout",
                "shoppilot.embedding.warmup-timeout",
                "shoppilot.cache.l1-ttl",
                "shoppilot.cache.negative-ttl",
                "shoppilot.cache.singleflight-wait-timeout",
                "shoppilot.bizmock.connect-timeout",
                "shoppilot.bizmock.read-timeout",
                "shoppilot.agent.session-ttl");
    }

    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current).append('\n');
        }
        return text.toString();
    }

    private static Set<String> placeholdersInApplicationYml() {
        try (InputStream in = ConfigValidationTest.class.getResourceAsStream("/application.yml")) {
            String yaml = new String(Objects.requireNonNull(in, "application.yml 应在 classpath")
                    .readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\\$\\{(SHOPPILOT_[A-Z0-9_]+)(?::[^}]*)?}")
                    .matcher(yaml);
            Set<String> names = new LinkedHashSet<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            return names;
        } catch (Exception failure) {
            throw new IllegalStateException("读不到 application.yml", failure);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GatewayProperties.class, ValidatedServerProperties.class})
    @Import(DevDefaultsConfiguration.class)
    static class BindingConfiguration {
    }

    /** 复刻 SpringApplication 的启动前环境链路：先读配置，再跑 dev 默认值后置处理器。 */
    static class StartupEnvironmentInitializer
            implements ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {

        private final ConfigDataApplicationContextInitializer configData =
                new ConfigDataApplicationContextInitializer();
        private final DevDefaultsEnvironmentPostProcessor devDefaults =
                new DevDefaultsEnvironmentPostProcessor();

        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            configData.initialize(context);
            devDefaults.postProcessEnvironment(context.getEnvironment(),
                    new SpringApplication(GatewayApplication.class));
        }
    }
}
