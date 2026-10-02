package com.shoppilot.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * **不带任何属性覆盖**地起生产上下文（round23 票 75 清场日抓到之后补的）。
 *
 * <p>这个用例存在的理由是一次真实的事故：工单服务的 {@code shoppilot.ticket.internal-token}
 * 曾经漏在 {@code application.yml} 里，而**每一个**测试都传了同名覆盖，所以 JVM 全绿；
 * 真正起服务时死在 bean 构造上（{@code Could not resolve placeholder}），是清场日的活体验收抓出来的。
 *
 * <p>所以这里刻意不传 {@code shoppilot.ticket.internal-token}——它要证明的是
 * 「用仓里那份 yml 与默认值，服务就能起」，而不是「测试喂进去的配置能起」。
 * 缺一个键、或者默认值不合法，这一格当场红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductionConfigSelfCheckTest {

    @Test
    @DisplayName("生产 application.yml 自身足以启动上下文（不带任何属性覆盖）")
    void productionConfigurationIsSelfSufficient() {
        // 能进到这个方法，就说明上下文起好了。写成用例而不是空类，是因为
        // 「类上的 @SpringBootTest 起不来」时 surefire 记的是 context load error，
        // 那条信息里带着真正的缺失键——所以断言本身不必再说什么。
    }
}