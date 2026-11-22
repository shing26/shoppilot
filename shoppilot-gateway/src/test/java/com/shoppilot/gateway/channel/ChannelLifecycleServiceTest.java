package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 票 225 / ADR 0087：探测 → 写盘 → 原子切换；失败保留 last-known-good。
 *
 * <p>连接用 fake factory，不碰真实飞书；这里守的是状态机与「旧连接有没有被停」。
 */
class ChannelLifecycleServiceTest {

    @TempDir
    Path tempDir;

    private ChannelConnectionStore store;
    private FakeFactory factory;
    private ChannelLifecycleService lifecycle;
    private Path file;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.feishu()).thenReturn(new GatewayProperties.Feishu(
                false, "", "", "T001", "C001", ""));
        file = tempDir.resolve("channels.json");
        store = spy(new ChannelConnectionStore(properties, file.toString()));
        factory = new FakeFactory();
        FeishuLongConnectionClient client = new FeishuLongConnectionClient(
                new FeishuAdapter(), mock(AgentStateMachine.class), mock(FeishuReplySender.class),
                properties, "127.0.0.1", new ObjectMapper(), factory);
        lifecycle = new ChannelLifecycleService(store, client);
    }

    @Test
    @DisplayName("启用但不提供凭据：探测失败，不写盘、不动连接")
    void probeFailureRollsBackWithoutWriting() {
        doReturn(new ChannelConnectionStore.Probe(false, "飞书返回 code=10003"))
                .when(store).probe(anyString(), anyString());

        ChannelLifecycleService.Result result = lifecycle.apply(update(true, "cli_bad", "bad"));

        assertThat(result.saved()).isFalse();
        assertThat(result.state()).isEqualTo("ROLLED_BACK");
        assertThat(result.restartRequired()).isFalse();
        assertThat(file).doesNotExist();
        assertThat(factory.created()).isEmpty();
    }

    @Test
    @DisplayName("探测通过：新连接接管，旧连接被停，状态 APPLIED")
    void successfulApplySwitchesConnection() {
        doReturn(new ChannelConnectionStore.Probe(true, "ok")).when(store).probe(anyString(), anyString());
        FakeConnection first = new FakeConnection(true);
        factory.enqueue(first);

        ChannelLifecycleService.Result result = lifecycle.apply(update(true, "cli_new", "secret"));

        assertThat(result.saved()).isTrue();
        assertThat(result.applied()).isTrue();
        assertThat(result.state()).isEqualTo("APPLIED");
        assertThat(result.restartRequired()).isFalse();
        assertThat(first.started).isTrue();
        assertThat(file).exists();
        assertThat(store.current().appId()).isEqualTo("cli_new");
    }

    @Test
    @DisplayName("旧连接无法安全关闭：新连接回滚，配置保留并标记需重启")
    void stopFailureDegradesToRestartRequired() throws Exception {
        doReturn(new ChannelConnectionStore.Probe(true, "ok")).when(store).probe(anyString(), anyString());
        FakeConnection first = new FakeConnection(true);
        FakeConnection second = new FakeConnection(true);
        factory.enqueue(first, second);
        lifecycle.apply(update(true, "cli_first", "secret"));

        // 第一轮已经 APPLIED；把当前连接改成「停不掉」，再切一次。
        factory.connections().get(0).stopSucceeds = false;
        ChannelLifecycleService.Result result = lifecycle.apply(update(true, "cli_second", "secret"));

        assertThat(result.saved()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.state()).isEqualTo("RESTART_REQUIRED");
        assertThat(result.restartRequired()).isTrue();
        assertThat(store.current().appId()).isEqualTo("cli_second");
        assertThat(second.started).isTrue();
        assertThat(second.stopped).as("新连接必须回滚").isTrue();
        String persisted = Files.readString(file);
        assertThat(persisted).contains("cli_second");
    }

    @Test
    @DisplayName("新连接启动失败：恢复旧配置与旧连接，状态 ROLLED_BACK")
    void startFailureRestoresLastKnownGood() throws Exception {
        doReturn(new ChannelConnectionStore.Probe(true, "ok")).when(store).probe(anyString(), anyString());
        FakeConnection first = new FakeConnection(true);
        factory.enqueue(first);
        lifecycle.apply(update(true, "cli_first", "secret"));

        FakeConnection broken = new FakeConnection(false);
        factory.enqueue(broken);
        ChannelLifecycleService.Result result = lifecycle.apply(update(true, "cli_second", "secret"));

        assertThat(result.saved()).isFalse();
        assertThat(result.applied()).isFalse();
        assertThat(result.state()).isEqualTo("ROLLED_BACK");
        assertThat(store.current().appId()).isEqualTo("cli_first");
        assertThat(Files.readString(file)).contains("cli_first");
        assertThat(first.stopped).as("旧连接从未被动过").isFalse();
    }

    @Test
    @DisplayName("停用配置：不需要探测，直接停连接并置 DISABLED")
    void disableStopsConnectionWithoutProbe() {
        doReturn(new ChannelConnectionStore.Probe(true, "ok")).when(store).probe(anyString(), anyString());
        FakeConnection first = new FakeConnection(true);
        factory.enqueue(first);
        lifecycle.apply(update(true, "cli_live", "secret"));

        ChannelLifecycleService.Result result = lifecycle.apply(update(false, "", ""));

        assertThat(result.applied()).isTrue();
        assertThat(result.state()).isEqualTo("DISABLED");
        assertThat(first.stopped).isTrue();
    }

    private static ChannelConnectionStore.FeishuConnection update(boolean enabled, String appId, String secret) {
        return new ChannelConnectionStore.FeishuConnection(enabled, appId, secret, "T001", "C001", "");
    }

    private static final class FakeFactory implements FeishuLongConnectionClient.FeishuConnectionFactory {

        private final Deque<FakeConnection> queue = new ArrayDeque<>();
        private final List<FakeConnection> connections = new ArrayList<>();

        void enqueue(FakeConnection... items) {
            for (FakeConnection item : items) {
                queue.add(item);
            }
        }

        List<FakeConnection> connections() {
            return connections;
        }

        List<FakeConnection> created() {
            return connections;
        }

        @Override
        public FeishuLongConnectionClient.ManagedFeishuConnection create(
                GatewayProperties.Feishu config) {
            FakeConnection connection = queue.isEmpty() ? new FakeConnection(true) : queue.removeFirst();
            connections.add(connection);
            return connection;
        }
    }

    private static final class FakeConnection
            implements FeishuLongConnectionClient.ManagedFeishuConnection {

        private final boolean startSucceeds;
        private boolean stopSucceeds = true;
        private boolean started;
        private boolean stopped;

        private FakeConnection(boolean startSucceeds) {
            this.startSucceeds = startSucceeds;
        }

        @Override
        public void start() {
            if (!startSucceeds) {
                throw new IllegalStateException("connect failed");
            }
            started = true;
        }

        @Override
        public boolean stop() {
            if (!stopSucceeds) {
                return false;
            }
            stopped = true;
            return true;
        }
    }
}
