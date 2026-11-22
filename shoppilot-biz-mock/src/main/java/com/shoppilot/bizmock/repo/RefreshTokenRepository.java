package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 刷新路径专用：行级排他锁让「同一 refresh 并发双用」串行化。
     *
     * <p>没有它时两个请求都会读到 {@code used_at = null}，各自轮换成功——票 224 的
     * 独立 Verifier 实测 8/8 次双 200。加锁后第二个请求看到已使用，按复用撤族。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByFamilyId(String familyId);

    List<RefreshToken> findByAccountId(String accountId);
}
