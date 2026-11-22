package com.shoppilot.gateway.identity;

import com.shoppilot.tool.identity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final SecretKey key;
    private final Duration ttl;

    @Autowired
    public JwtService(@Value("${shoppilot.jwt-secret}") String secret,
                      @Value("${shoppilot.identity.access-ttl:15m}") Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("SHOPPILOT_JWT_SECRET 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
    }

    /** 测试缝：与生产默认 TTL 同值；生产装配仍走两参构造，TTL 由配置决定。 */
    public JwtService(String secret) {
        this(secret, Duration.ofMinutes(15));
    }

    public String issue(String tenantId, String customerId) {
        return issue(tenantId, customerId, ttl);
    }

    /** 测试缝：签发一个指定有效期的 token，用于验证过期即拒。生产路径只用无参版本。 */
    public String issue(String tenantId, String customerId, Duration ttl) {
        return build(tenantId, customerId, null, null, null, 0, ttl);
    }

    /**
     * 账号登录后签发：带角色与账号 id（round25 票 81 / ADR 0058 第 2 条）。
     *
     * <p>角色进令牌而不是每次去问身份域，是因为本服务自己就是验签方——令牌丢了就退化成
     * 「一个没有角色的买家」，那正好是 mock 令牌的老形状，于是调试台与那批验收脚本一行都不用改。
     */
    public String issue(String tenantId, String customerId, UserRole role, String accountId) {
        return build(tenantId, customerId, role, accountId, null, 0, ttl);
    }

    /** 真登录用这一支：额外带上用户名，供工单 assignee 与审计 actor 署名（票 83）。 */
    public String issue(String tenantId, String customerId, UserRole role, String accountId, String username) {
        return build(tenantId, customerId, role, accountId, username, 0, ttl);
    }

    /**
     * 账号登录的完整签发（round56 票 224 / ADR 0086）：额外带口令版本 {@code pv}。
     *
     * <p>版本不参与本服务的验签判定，它是「改密后旧令牌最迟在 TTL 内失效」的记账依据；
     * 真正即时生效的是 refresh 撤销。
     */
    public String issueAccess(String tenantId, String customerId, UserRole role, String accountId,
                              String username, int passwordVersion) {
        return build(tenantId, customerId, role, accountId, username, passwordVersion, ttl);
    }

    private String build(String tenantId, String customerId, UserRole role, String accountId, String username,
                         int passwordVersion, Duration lifetime) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(customerId)
                .claim("tid", tenantId)
                .claim("cid", customerId)
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key);
        // 角色与账号只在真登录时写进去：mock 令牌与历史令牌没有这两个 claim，
        // verify() 那侧按「没有就是 BUYER」解释，两边都必须这么处理，否则老令牌会验不过。
        if (role != null) {
            builder.claim("role", role.name());
        }
        if (accountId != null && !accountId.isBlank()) {
            builder.claim("aid", accountId);
        }
        if (username != null && !username.isBlank()) {
            builder.claim("usr", username);
        }
        // ADR 0086：pv 是固定的 access claim，0 也要写——「缺 claim」与「版本 0」
        // 必须可区分，否则将来有消费方时两种令牌会读成同一种。
        builder.claim("pv", passwordVersion);
        return builder.compact();
    }

    public Optional<TenantContext.Identity> verify(String token, String conversationId) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            // refresh 令牌不是 JWT，这里防的是「将来有人把别的类型塞进来」：
            // 带 typ 且不是 access 的一律不认，缺 typ 的旧令牌照旧放行（兼容 mock 令牌）。
            String type = claims.get("typ", String.class);
            if (type != null && !"access".equals(type)) {
                return Optional.empty();
            }
            String tenantId = claims.get("tid", String.class);
            String customerId = claims.get("cid", String.class);
            if (tenantId == null || tenantId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TenantContext.Identity(tenantId, customerId, conversationId,
                    roleOf(claims.get("role", String.class)), claims.get("aid", String.class),
                    claims.get("usr", String.class)));
        } catch (JwtException | IllegalArgumentException malformedOrExpired) {
            return Optional.empty();
        }
    }

    /**
     * 没有 role claim 的令牌一律当 {@code UserRole.BUYER}。
     *
     * <p>这是兼容性决定不是安全决定：{@code /auth/mock-token} 发的就是这种令牌，而它只回环可用。
     * 写成一个具名方法而不是内联三元，是为了让「这里的默认是 BUYER」这件事被读代码的人一眼看到。
     */
    private static UserRole roleOf(String claimed) {
        if (claimed == null || claimed.isBlank()) {
            return UserRole.BUYER;
        }
        try {
            return UserRole.valueOf(claimed);
        } catch (IllegalArgumentException unknownRole) {
            // 认不出来的角色按最低权限解释，而不是拒签：令牌是本服务自己签的，
            // 出现这种值只可能是手工构造，而手工构造的人已经有签名密钥了。
            return UserRole.BUYER;
        }
    }

    public Duration ttl() {
        return ttl;
    }
}
