package com.shoppilot.gateway.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 票 171：飞书 L2 身份映射。
 *
 * <p>守三件事：没有映射文件时保持 L1；有文件时 chat 优先于 user、user 优先于 default；
 * 严格模式没有 default 时未知身份必须拒绝，不能落默认租户。
 */
class FeishuIdentityResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("无映射文件：保持 L1 默认身份")
    void noFileKeepsL1Fallback() {
        FeishuIdentityResolver resolver = new FeishuIdentityResolver("", "T001", "C001");

        assertThat(resolver.resolve("oc_any", "ou_any"))
                .contains(new FeishuIdentityResolver.Identity("T001", "C001"));
    }

    @Test
    @DisplayName("有映射文件：chat 优先，其次 user，最后 default")
    void fileMapsChatUserAndDefault() throws Exception {
        Path file = tempDir.resolve("feishu-identities.json");
        Files.writeString(file, """
                {
                  "default": { "tenant": "T001", "customer": "C001" },
                  "chats": {
                    "oc_chat_hotel": { "tenant": "T004", "customer": "C001" }
                  },
                  "users": {
                    "ou_user_hotel": { "tenant": "T004", "customer": "C002" }
                  }
                }
                """);
        FeishuIdentityResolver resolver = new FeishuIdentityResolver(file.toString(), "T001", "C001");

        assertThat(resolver.resolve("oc_chat_hotel", "ou_unknown"))
                .contains(new FeishuIdentityResolver.Identity("T004", "C001"));
        assertThat(resolver.resolve("oc_other", "ou_user_hotel"))
                .contains(new FeishuIdentityResolver.Identity("T004", "C002"));
        assertThat(resolver.resolve("oc_other", "ou_unknown"))
                .contains(new FeishuIdentityResolver.Identity("T001", "C001"));
    }

    @Test
    @DisplayName("严格模式没有 default：未知身份拒绝，不落默认租户")
    void strictModeRejectsUnknown() throws Exception {
        Path file = tempDir.resolve("feishu-identities.json");
        Files.writeString(file, """
                {
                  "users": {
                    "ou_known": { "tenant": "T002", "customer": "C001" }
                  }
                }
                """);
        FeishuIdentityResolver resolver = new FeishuIdentityResolver(file.toString(), "T001", "C001");

        assertThat(resolver.resolve("oc_known_chat", "ou_known"))
                .contains(new FeishuIdentityResolver.Identity("T002", "C001"));
        assertThat(resolver.resolve("oc_unknown_chat", "ou_unknown")).isEmpty();
    }
}
