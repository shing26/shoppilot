package com.shoppilot.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个服务的 Redis 配置必须指向同一组占位符（round26 清场日 2026-10-05 补）。
 *
 * <p><b>它钉的是一次真实事故</b>：{@code shoppilot-biz-mock} 的 {@code application.yml} 里
 * **根本没有 {@code spring.data.redis} 这一段**，于是它用 Spring Boot 的默认值
 * {@code localhost:6379} 去连——而本机的 Redis 刻意偏移在 {@code 16379}（compose 里避开
 * wslrelay 已占用的标准端口）。结果是<strong>全程静默</strong>：
 * {@code available()} 探不通 → 审计 publish 走直写兜底；consume 抛错被 catch；
 * {@code /api/audit} 永远返回 {@code []}。而 JVM 层测试用的是内存通道，
 * 所以「审计事件在真 Redis 上收发」这件事从 round23 起就没有被验过。
 *
 * <p><b>为什么它能潜伏这么久</b>：Redis 在 biz-mock 里是<strong>软依赖</strong>（票 71 的既定取舍——
 * 退款放行不能因为审计发不出去而失败）。软依赖的代价是「连错地方」不会让服务起不来，
 * 只会让它安静地退化成单机模式。<strong>这类缺陷正是机器判据该守的形状</strong>：
 * 不需要任何外部服务就能断，而且断的正是配置本身。
 *
 * <p>读法说明：本类在 ticket 模块里，却要读另两个模块的 yml，所以走<strong>文件路径</strong>而不是
 * classpath——surefire 的工作目录是模块目录，{@code ../} 正好落到兄弟模块。
 */
class RedisConfigConsistencyTest {

    /** 三个服务。少一个就等于放走「只有它连错」的那种事故。 */
    private static final List<String> SERVICES = List.of("shoppilot-gateway", "shoppilot-biz-mock", "shoppilot-ticket");

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    @DisplayName("每个服务都显式配了 spring.data.redis.host/port，且用的是同一组 SHOPPILOT_ 占位符")
    void everyServiceDeclaresRedisOnTheSamePlaceholders() throws IOException {
        Map<String, String> shapes = new LinkedHashMap<>();
        for (String service : SERVICES) {
            shapes.put(service, redisShapeOf(service, readConfig(service)));
        }

        String reference = shapes.get(SERVICES.get(0));
        assertThat(reference).as("基准形状不能是「缺配置」——那就是事故本身").doesNotContain("<缺");
        for (String service : SERVICES) {
            assertThat(shapes.get(service))
                    .as("%s 的 spring.data.redis 配置必须与其它服务逐字一致", service)
                    .isEqualTo(reference);
        }
    }

    /**
     * 端口占位符必须带 {@code :16379} 那个偏移默认值。
     *
     * <p>只比「有没有」不够：写成 {@code ${SHOPPILOT_REDIS_PORT:6379}} 也算「配了」，
     * 但那正是事故的另一种形态——<strong>配了，却指着一个被别的项目占着的端口</strong>。
     */
    @Test
    @DisplayName("Redis 端口的默认值是偏移后的 16379，不是被别的项目占着的 6379")
    void redisPortDefaultIsTheOffsetOne() throws IOException {
        for (String service : SERVICES) {
            assertThat(readConfig(service))
                    .as("%s 的 Redis 端口默认值必须是 16379（compose 里刻意避开 wslrelay 占用的 6379）", service)
                    .contains("SHOPPILOT_REDIS_PORT:16379");
        }
    }

    /** 从 yml 里把 host 与 port 两行抠出来，别的都不管。 */
    private static String redisShapeOf(String service, String yml) {
        Matcher host = Pattern.compile("(?m)^\\s{6}host:\\s*(\\$\\{SHOPPILOT_REDIS_HOST:[^}]*}|\\S+)\\s*$").matcher(yml);
        Matcher port = Pattern.compile("(?m)^\\s{6}port:\\s*(\\$\\{SHOPPILOT_REDIS_PORT:[^}]*}|\\S+)\\s*$").matcher(yml);
        if (!host.find() || !port.find()) {
            return service + ": <缺 spring.data.redis 的 host/port>";
        }
        return "host=" + host.group(1) + " port=" + port.group(1);
    }

    private static String readConfig(String service) throws IOException {
        Path yml = REPO.resolve(service).resolve("src/main/resources/application.yml");
        assertThat(yml).as("找不到 %s 的 application.yml（测试的工作目录应当是模块目录）", service).exists();
        return Files.readString(yml, StandardCharsets.UTF_8);
    }
}
