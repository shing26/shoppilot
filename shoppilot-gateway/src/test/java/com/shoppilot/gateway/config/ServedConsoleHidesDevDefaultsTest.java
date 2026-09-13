package com.shoppilot.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票 21 的第七条勾：「页面不再预填运维令牌」。
 *
 * <p>这条判据原先没有任何量具守着 —— 谁把它加回 <code>value="dev-ops-token"</code>，
 * 门禁照样全绿，因为只有浏览器验收会用那个预填值。这里钉住两件事：网关下发的静态页里
 * 不许出现三处 dev 默认值本身，且那个输入框不许带 value 属性。常量取自
 * {@link DevDefaultsPolicy}，这样默认值一旦改名，本测试跟着走而不是各写一份。
 */
class ServedConsoleHidesDevDefaultsTest {

    private static String servedPage() throws Exception {
        try (InputStream in = ServedConsoleHidesDevDefaultsTest.class.getResourceAsStream("/static/index.html")) {
            return new String(Objects.requireNonNull(in, "静态页不在 classpath：/static/index.html")
                    .readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("静态页不含任何一处仓库默认凭证：能加载页面的人不该白拿运维令牌")
    void servedPageContainsNoRepoDefaultCredential() throws Exception {
        String html = servedPage();
        assertThat(html)
                .doesNotContain(DevDefaultsPolicy.OPS_TOKEN)
                .doesNotContain(DevDefaultsPolicy.INTERNAL_TOKEN)
                .doesNotContain(DevDefaultsPolicy.JWT_SECRET);
    }

    @Test
    @DisplayName("令牌输入框不预填：只许有 placeholder")
    void opsTokenInputIsNotPrefilled() throws Exception {
        String line = servedPage().lines()
                .filter((l) -> l.contains("id=\"opsToken\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("页面里找不到 opsToken 输入框"));
        assertThat(line).doesNotContain("value=");
        assertThat(line).contains("placeholder=");
    }
}
