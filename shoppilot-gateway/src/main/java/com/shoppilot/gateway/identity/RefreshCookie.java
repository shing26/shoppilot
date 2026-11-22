package com.shoppilot.gateway.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * refresh token 的唯一出入口（round56 票 224 / ADR 0086）。
 *
 * <p>它是 HttpOnly Cookie，不是 JSON 字段：契约禁止把 refresh token 写进 localStorage、
 * URL、日志或响应体。`Path=/auth` 让浏览器只在刷新/登出两个端点上带它，
 * 业务请求仍然只认 `Authorization` 头，Cookie 不构成第二种认证方式。
 */
public final class RefreshCookie {

    public static final String NAME = "sp_rt";

    private final Duration ttl;
    private final boolean secure;

    public RefreshCookie(Duration ttl, boolean secure) {
        this.ttl = ttl;
        this.secure = secure;
    }

    public ResponseCookie issue(String token) {
        return base(token).maxAge(ttl).build();
    }

    /** 清 Cookie：复用检测与登出都走这一条，`Max-Age=0` 让浏览器立即丢弃。 */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/auth");
    }

    /** 从请求里取 refresh；没有就返回空。 */
    public static Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(cookie -> cookie.getValue() == null ? "" : cookie.getValue().trim())
                .filter(value -> !value.isEmpty())
                .findFirst();
    }
}
