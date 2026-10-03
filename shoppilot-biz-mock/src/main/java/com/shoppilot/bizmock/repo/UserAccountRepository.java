package com.shoppilot.bizmock.repo;

import com.shoppilot.bizmock.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 账号仓储。
 *
 * <p>刻意**没有** {@code findByUsername(String)} 这种不带租户的重载：{@code UserAccount} 挂了
 * {@code @TenantId}，而租户上下文由请求头装（ADR 0005 防线一）。少写一个租户参数的重载，
 * 就不存在「哪天有人顺手用了它」的机会——那种洞在 round21 的 {@code findById} 上已经出过一次。
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> findByRole(com.shoppilot.tool.identity.UserRole role);
}