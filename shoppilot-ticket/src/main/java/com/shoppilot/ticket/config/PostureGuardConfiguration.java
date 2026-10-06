package com.shoppilot.ticket.config;

import com.shoppilot.tool.config.PostureGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 非回环姿势守卫——数据侧接入（round29 票 104 / ADR 0062）。
 *
 * <p>与 biz-mock 同形状（见那边的类注释）：ADR 0029 的启动阻断此前只活在网关，
 * 而工单是审计资产、账号口令哈希的兄弟库在这里。账本两格：内部令牌 + 持久档数据库口令
 * （仅当数据源是 {@code jdbc:postgresql:} 时查口令，默认档 H2 不受影响）。回环上是 no-op。
 */
@Configuration
public class PostureGuardConfiguration {

    public PostureGuardConfiguration(Environment env) {
        String bindAddress = env.getProperty("server.address", "");
        if (PostureGuard.isLoopback(bindAddress)) {
            return;
        }
        List<String> blockers = new ArrayList<>();
        String internalToken = env.getProperty("shoppilot.ticket.internal-token", "");
        if (PostureGuard.stillDefault(internalToken, PostureGuard.INTERNAL_TOKEN)) {
            blockers.add("SHOPPILOT_INTERNAL_TOKEN 未覆盖：网关到工单服务的内部令牌明文在仓库里");
        }
        if (isPostgresDatasource(env) && PostureGuard.stillDefault(env.getProperty("spring.datasource.password", ""), "")) {
            blockers.add("数据库口令未设置（SHOPPILOT_DB_PASSWORD）：持久档的连接凭据不能为空");
        }
        if (!blockers.isEmpty()) {
            throw new IllegalStateException("拒绝启动：当前监听 " + (bindAddress.isBlank() ? "所有网卡（server.address 未设置）" : bindAddress)
                    + "，不是回环，而以下凭证不合规——" + String.join("；", blockers)
                    + "。要用默认值跑本机演示就把 server.address 改回 127.0.0.1（ADR 0029 / ADR 0062）。");
        }
    }

    /** 只在持久档查口令；以连接串为准而不是 profile 名——直接设 SPRING_DATASOURCE_URL 的路径也要被守到。 */
    private static boolean isPostgresDatasource(Environment env) {
        String url = env.getProperty("spring.datasource.url", "");
        return url.contains("jdbc:postgresql:");
    }
}
