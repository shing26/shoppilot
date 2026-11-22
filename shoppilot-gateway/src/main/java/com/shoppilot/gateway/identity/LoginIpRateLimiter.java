package com.shoppilot.gateway.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录入口的每 IP 限流（round56 票 224 / ADR 0086 §4）。
 *
 * <p>账号锁定挡的是「盯着一个账号试」，挡不住「换账号扫库」。这里按固定分钟窗口
 * 给每个来源 IP 一个上限，超限同样 429 + Retry-After，文案与账号锁定一致。
 *
 * <p>进程内计数：网关重启即清零。它不是账本，是热路径上的减震器；真正的账号锁定
 * 仍落在 biz-mock 的持久列里。
 */
@Component
public class LoginIpRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Value("${shoppilot.identity.login-ip-per-minute:10}")
    private int limit = 10;

    /** 当前窗口是否已被失败尝试打满；成功登录不计入，避免正常用户被自己“登录成功”挡住。 */
    public boolean blocked(String ip) {
        Window window = window(ip, false);
        return window != null && window.count.get() >= limit;
    }

    /** 只在认证失败后调用；锁定与口令错都算一次失败尝试。 */
    public void recordFailure(String ip) {
        window(ip, true).count.incrementAndGet();
    }

    private Window window(String ip, boolean create) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long minute = Instant.now().getEpochSecond() / 60;
        if (!create) {
            Window existing = windows.get(key);
            return existing != null && existing.minute == minute ? existing : null;
        }
        return windows.compute(key, (ignored, existing) ->
                existing == null || existing.minute != minute ? new Window(minute) : existing);
    }

    /** 测试与诊断用：当前窗口已计数。 */
    int count(String ip) {
        Window window = windows.get(ip == null || ip.isBlank() ? "unknown" : ip);
        return window == null ? 0 : window.count.get();
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
