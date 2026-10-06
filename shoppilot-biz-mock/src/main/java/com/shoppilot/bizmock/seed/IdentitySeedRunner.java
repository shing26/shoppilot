package com.shoppilot.bizmock.seed;

import com.shoppilot.bizmock.repo.TenantRepository;
import com.shoppilot.bizmock.service.IdentityService;
import com.shoppilot.bizmock.tenant.TenantContextHolder;
import com.shoppilot.tool.identity.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 演示账号播种（round25 票 80）。
 *
 * <p><b>仓库里没有任何默认口令。</b>属性 {@code shoppilot.bizmock.identity.demo-password} 为空时
 * 一条演示账号都不建——这与 ADR 0029 对三处服务凭证的处理是同一条家法：默认凭证进了仓库，
 * 非回环时它就是一把没换过的锁。要演示账号就显式给一个口令（{@code up.ps1} / {@code .env} /
 * 容器档的 {@code .env}），不给就只有一个干净的身份域。
 *
 * <p>顺带这一格也说明「账号」与「运维凭证」是两件事：ops token 守运维面，账号守「谁」。
 * 演示账号的口令不进 {@code application.yml}，因此不在 {@code ConfigValidationTest} 钉住的那个
 * {@code SHOPPILOT_*} 占位符集合里——不需要为了它开口子。
 *
 * <p>{@code @Order(20)} 让它排在 {@link SeedRunner}（{@code @Order(10)}）之后：它要遍历租户表。
 * 买家账号的 {@code subjectRef} 直接写 {@code C001}——那是演示段（{@code seed.demo-data}）播种的
 * 演示买家；持久档默认不播演示段，但 {@code subjectRef} 只是字符串引用不是外键，
 * C001 不存在时建号照常成立（它只是个标注）。
 */
@Component
@Order(20)
public class IdentitySeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentitySeedRunner.class);

    /** 演示买家的业务主体，与 SeedRunner 的 {@code DEMO_CUSTOMER} 同值。 */
    private static final String DEMO_BUYER_SUBJECT = "C001";

    private final TenantRepository tenants;
    private final IdentityService identity;
    private final TransactionTemplate transactionTemplate;

    @Value("${shoppilot.bizmock.identity.demo-password:}")
    private String demoPassword;

    public IdentitySeedRunner(TenantRepository tenants, IdentityService identity,
                              TransactionTemplate transactionTemplate) {
        this.tenants = tenants;
        this.identity = identity;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (demoPassword == null || demoPassword.isBlank()) {
            log.info("未提供 SHOPPILOT_IDENTITY_DEMO_PASSWORD，跳过演示账号播种（身份域仍可用，只是没有演示账号）");
            return;
        }
        int created = 0;
        try {
            List<String> tenantIds = tenants.findAll().stream().map(t -> t.getId()).sorted().toList();
            for (String tenantId : tenantIds) {
                TenantContextHolder.set(tenantId, null);
                created += seedOneTenant(tenantId) ? 3 : 0;
            }
            if (created > 0) {
                log.info("演示账号就绪：{} 个租户 × (buyer/agent/admin)，口令来自 SHOPPILOT_IDENTITY_DEMO_PASSWORD",
                        created / 3);
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** 一个租户一套三角色。已存在就整户跳过，不去覆盖既有账号的口令。 */
    private boolean seedOneTenant(String tenantId) {
        if (identity.findByUsername("buyer").isPresent()) {
            return false;
        }
        transactionTemplate.executeWithoutResult(status -> {
            identity.createRoleAccount("buyer", demoPassword, UserRole.BUYER, "演示买家", DEMO_BUYER_SUBJECT);
            identity.createRoleAccount("agent", demoPassword, UserRole.AGENT, "演示坐席", null);
            identity.createRoleAccount("admin", demoPassword, UserRole.ADMIN, "演示管理员", null);
        });
        return true;
    }
}