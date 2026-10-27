package com.shoppilot.gateway.channel;

import com.shoppilot.gateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 票 173：渠道连接文件优先、回退 env、保存后标记重启、secret 不回显。 */
class ChannelConnectionStoreTest {

    @TempDir
    Path tempDir;

    private GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.feishu()).thenReturn(new GatewayProperties.Feishu(
                true, "cli_env", "env-secret", "T001", "C001", ""));
        return properties;
    }

    @Test
    @DisplayName("无文件：回退 .env 的 Feishu 配置")
    void fallsBackToEnvWhenFileMissing() {
        ChannelConnectionStore store = new ChannelConnectionStore(
                properties(), tempDir.resolve("channels.json").toString());

        assertThat(store.fromFile()).isFalse();
        assertThat(store.current().appId()).isEqualTo("cli_env");
        assertThat(store.maskedAppId()).isEqualTo("cli_en****");
    }

    @Test
    @DisplayName("保存后写文件、标记重启、secret 不回显；重新构造能从文件读回")
    void saveWritesFileAndMarksRestart() {
        Path file = tempDir.resolve("channels.json");
        ChannelConnectionStore store = new ChannelConnectionStore(properties(), file.toString());

        ChannelConnectionStore.FeishuConnection saved = store.save(
                new ChannelConnectionStore.FeishuConnection(
                        true, "cli_new_app", "new-secret", "T004", "C001", ""));

        assertThat(saved.appSecret()).isEmpty();
        assertThat(store.fromFile()).isTrue();
        assertThat(store.restartRequired()).isTrue();
        assertThat(store.current().appId()).isEqualTo("cli_new_app");
        assertThat(store.current().tenant()).isEqualTo("T004");
        assertThat(file).exists();

        ChannelConnectionStore reloaded = new ChannelConnectionStore(properties(), file.toString());
        assertThat(reloaded.fromFile()).isTrue();
        assertThat(reloaded.current().appId()).isEqualTo("cli_new_app");
        assertThat(reloaded.current().appSecret()).isEqualTo("new-secret");
        assertThat(reloaded.current().tenant()).isEqualTo("T004");
    }

    @Test
    @DisplayName("启用但缺 appId/appSecret：拒绝保存")
    void rejectsEnabledWithoutCredentials() {
        ChannelConnectionStore store = new ChannelConnectionStore(
                properties(), tempDir.resolve("channels.json").toString());

        assertThatThrownBy(() -> store.save(new ChannelConnectionStore.FeishuConnection(
                true, "", "", "T001", "C001", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
    }

    @Test
    @DisplayName("测试凭据缺配置：直接失败，不发网络请求")
    void probeWithoutCredentialsFailsFast() {
        GatewayProperties blank = mock(GatewayProperties.class);
        when(blank.feishu()).thenReturn(new GatewayProperties.Feishu(
                false, "", "", "T001", "C001", ""));
        ChannelConnectionStore store = new ChannelConnectionStore(
                blank, tempDir.resolve("channels.json").toString());

        ChannelConnectionStore.Probe probe = store.probe("", "");
        assertThat(probe.ok()).isFalse();
        assertThat(probe.message()).contains("未配置");
    }

    @Test
    @DisplayName("配置文件坏了：拒启，不静默回退 env")
    void malformedFileFailsFast() throws Exception {
        Path file = tempDir.resolve("channels.json");
        Files.writeString(file, "{ not-json");

        assertThatThrownBy(() -> new ChannelConnectionStore(properties(), file.toString()))
                .isInstanceOf(IllegalStateException.class);
    }
}
