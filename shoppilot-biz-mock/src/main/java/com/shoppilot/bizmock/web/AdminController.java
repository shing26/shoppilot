package com.shoppilot.bizmock.web;

import com.shoppilot.bizmock.fault.FaultInjector;
import com.shoppilot.bizmock.seed.SeedRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 运维端点：故障注入与数据规模核对。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final FaultInjector faultInjector;
    private final SeedRunner seedRunner;
    private final JdbcTemplate jdbcTemplate;

    public AdminController(FaultInjector faultInjector, SeedRunner seedRunner, JdbcTemplate jdbcTemplate) {
        this.faultInjector = faultInjector;
        this.seedRunner = seedRunner;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 用原生 SQL 统计，刻意绕开 @TenantId 过滤：
     * 平台侧运维需要跨租户总量，业务查询绝不允许走这条路。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tenants", count("tenants"));
        stats.put("customers", count("customers"));
        stats.put("orders", count("orders"));
        stats.put("logisticsNodes", count("logistics"));
        stats.put("refunds", count("refunds"));
        stats.put("tickets", count("tickets"));
        return stats;
    }

    /**
     * 店铺配额由业务侧持有，网关只读不写。
     *
     * <p>限流配置放在 tenants 表而不是网关 yml，是为了让"改某店配额"是一个业务动作，
     * 不需要改网关配置再重启；网关侧带 60 秒缓存，改动的生效延迟在可接受范围内。
     */
    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants() {
        return jdbcTemplate.queryForList("select id, name, rate_limit_qps from tenants order by id").stream()
                .map(row -> {
                    Map<String, Object> tenant = new LinkedHashMap<>();
                    tenant.put("tenantId", row.get("id"));
                    tenant.put("name", row.get("name"));
                    tenant.put("rateLimitQps", row.get("rate_limit_qps"));
                    return tenant;
                })
                .toList();
    }

    @GetMapping("/fault")
    public Map<String, Object> getFault() {
        Map<String, Object> current = new LinkedHashMap<>();
        current.put("delayMs", faultInjector.getDelayMs());
        current.put("failRate", faultInjector.getFailRate());
        return current;
    }

    @PutMapping("/fault")
    public Map<String, Object> setFault(@RequestBody FaultRequest request) {
        faultInjector.configure(request.delayMs(), request.failRate());
        return getFault();
    }

    @PostMapping("/seed")
    public Map<String, Object> reseed() {
        seedRunner.seedIfEmpty();
        return stats();
    }

    /** 演示复位：把四张固定演示单恢复初始状态，供彩排与验收脚本重复执行。 */
    @PostMapping("/demo/reset")
    public Map<String, Object> resetDemo() {
        seedRunner.resetDemoFixtures();
        return stats();
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    public record FaultRequest(Long delayMs, Double failRate) {
    }
}
