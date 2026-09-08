package com.shoppilot.gateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 三道防线的第一道由架构守住（ADR 0005、ADR 0014）：身份只能经 TenantContext 流动。
 *
 * <p>靠 code review 守不住这条，因为违规写法看起来完全正常：
 * 在 controller 里读一个 header 或者 body 字段当租户标识，一行代码就能绕过全部隔离。
 */
@AnalyzeClasses(packages = "com.shoppilot.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class IdentityArchitectureTest {

    @ArchTest
    static final ArchRule identityOnlyThroughTenantContext = noClasses()
            .that().resideOutsideOfPackages("com.shoppilot.gateway.identity..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "com.shoppilot.gateway.identity.JwtService")
            .because("验签只属于 identity 包，业务侧必须通过 TenantContext 取身份");

    @ArchTest
    static final ArchRule businessLayersDoNotTouchServletIdentity = noClasses()
            .that().resideInAnyPackage("com.shoppilot.gateway.agent..", "com.shoppilot.gateway.cache..",
                    "com.shoppilot.gateway.triage..", "com.shoppilot.gateway.knowledge..")
            .should().accessClassesThat().haveFullyQualifiedName("jakarta.servlet.http.HttpServletRequest")
            .because("从原始请求里取租户等于把身份来源重新打开一个口子");

    @ArchTest
    static final ArchRule cacheNeverBypassesAdmission = noClasses()
            .that().resideInAnyPackage("com.shoppilot.gateway.agent..", "com.shoppilot.gateway.web..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.shoppilot.gateway.cache.L1Cache")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.shoppilot.gateway.cache.L2SemanticCache")
            .because("缓存读写必须经 CacheService，否则准入判定会被绕过");
}
