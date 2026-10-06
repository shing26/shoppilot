package com.shoppilot.bizmock.config;

import com.shoppilot.tool.config.PostureGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 非回环姿势守卫——数据侧接入（round29 票 104 / ADR 0062）。
 *
 * <p>ADR 0029 的启动阻断此前只活在网关，而持有订单、退款与全部账号的**本服务**在容器档绑
 * {@code 0.0.0.0}——仓库默认的内部令牌能一路活到启动完成。账本两格：
 * <ol>
 *   <li><b>内部令牌</b>：仍是仓库默认值即阻断（与网关同一家法）；</li>
 *   <li><b>数据库口令</b>：仅当数据源是 {@code jdbc:postgresql:}（持久档）且为空即阻断——
 *       默认档的 H2 无口令可言，不许误伤；compose 侧的 {@code :?} 必需语法是第一道，
 *       这道防的是绕过 compose 直接起 jar 的路径。</li>
 * </ol>
 *
 * <p>回环上是 no-op：本机 {@code up.ps1} 链路全部绑 127.0.0.1，干净克隆判据与全部活体判据不受影响。
 * 与网关的第二道同形：构造即检查，防「绕过环境后置处理的启动方式」。
 */
@Configuration
public class PostureGuardConfiguration {

    public PostureGuardConfiguration(Environment env) {
        String bindAddress = env.getProperty("server.address", "");
        if (PostureGuard.isLoopback(bindAddress)) {
            return;
        }
        List<String> blockers = new ArrayList<>();
        String internalToken = env.getProperty("shoppilot.bizmock.internal-token", "");
        if (PostureGuard.stillDefault(internalToken, PostureGuard.INTERNAL_TOKEN)) {
            blockers.add("SHOPPILOT_INTERNAL_TOKEN 未覆盖：网关到业务层的内部令牌明文在仓库里");
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
