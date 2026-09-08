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

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    public record FaultRequest(Long delayMs, Double failRate) {
    }
}
