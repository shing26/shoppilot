package com.shoppilot.gateway.identity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * mock 身份提供方签发的 HS256 token（ADR 0014）。
 *
 * <p>发 token 是假的（谁都能来要），验签是真的（改一个字符就废）。
 * 这个区分很重要：它让"改 Header 即可越权"这类追问有可运行的反证。
 */
@Component
public class JwtService {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final SecretKey key;

    public JwtService(@Value("${shoppilot.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("SHOPPILOT_JWT_SECRET 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issue(String tenantId, String customerId) {
        return issue(tenantId, customerId, TTL);
    }

    /** 测试缝：签发一个指定有效期的 token，用于验证过期即拒。生产路径只用无参版本。 */
    public String issue(String tenantId, String customerId, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(customerId)
                .claim("tid", tenantId)
                .claim("cid", customerId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<TenantContext.Identity> verify(String token, String conversationId) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String tenantId = claims.get("tid", String.class);
            String customerId = claims.get("cid", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TenantContext.Identity(tenantId, customerId, conversationId));
        } catch (JwtException | IllegalArgumentException malformedOrExpired) {
            return Optional.empty();
        }
    }

    public Duration ttl() {
        return TTL;
    }
}
