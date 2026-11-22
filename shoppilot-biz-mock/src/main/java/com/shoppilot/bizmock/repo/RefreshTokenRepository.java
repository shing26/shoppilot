package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 刷新令牌仓储（round56 票 224 / ADR 0086）。
 *
 * <p>与 {@code UserAccountRepository} 同口径：不带租户参数的重载刻意不存在，
 * 租户由 {@code @TenantId} 过滤器从请求上下文拼接。
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(String familyId);

    List<RefreshToken> findByAccountId(String accountId);
}
