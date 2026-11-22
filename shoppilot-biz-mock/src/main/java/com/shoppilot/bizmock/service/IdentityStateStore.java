package com.shoppilot.bizmock.service;

import com.shoppilot.bizmock.domain.RefreshToken;
import com.shoppilot.bizmock.domain.UserAccount;
import com.shoppilot.bizmock.repo.RefreshTokenRepository;
import com.shoppilot.bizmock.repo.UserAccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 登录失败计数与令牌撤销的**独立事务**写入（round56 票 224 / ADR 0086）。
 *
 * <p>为什么必须单独一个 bean：{@code IdentityService.authenticate} 在口令错时会抛异常，
 * 而 Spring 的默认回滚规则会把同一事务里刚写下的失败计数一起回滚——表现就是「连续失败 5 次
 * 也不锁定」。复用检测撤销令牌族同理：撤销后要抛 401，撤销本身必须留在库里。
 *
 * <p>所以这些「即使外层失败也必须生效」的写入走 {@link Propagation#REQUIRES_NEW}。
 * 与 {@code AuditEventWriter} 是同一套理由，不是重复投资：一个写审计，一个写身份状态。
 */
@Component
public class IdentityStateStore {

    private final UserAccountRepository accounts;
    private final RefreshTokenRepository refreshTokens;

    public IdentityStateStore(UserAccountRepository accounts, RefreshTokenRepository refreshTokens) {
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
    }

    /** 记一次失败并在达到阈值时锁定；返回本次失败后的连续次数。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(String accountId, Instant now, Duration window, int maxFailures,
                             Duration lockDuration) {
        UserAccount account = accounts.findById(accountId).orElseThrow();
        int attempts = account.recordFailure(now, window);
        if (attempts >= maxFailures) {
            account.lockUntil(now.plus(lockDuration));
        }
        accounts.saveAndFlush(account);
        return attempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String familyId, Instant now) {
        List<RefreshToken> family = refreshTokens.findByFamilyId(familyId);
        family.forEach(token -> token.revoke(now));
        refreshTokens.saveAllAndFlush(family);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForAccount(String accountId, Instant now) {
        List<RefreshToken> tokens = refreshTokens.findByAccountId(accountId);
        tokens.forEach(token -> token.revoke(now));
        refreshTokens.saveAllAndFlush(tokens);
    }
}
