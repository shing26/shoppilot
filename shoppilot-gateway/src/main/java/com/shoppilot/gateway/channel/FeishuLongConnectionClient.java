package com.shoppilot.gateway.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.ws.Client;
import com.shoppilot.gateway.agent.AgentResult;
import com.shoppilot.gateway.agent.AgentStateMachine;
import com.shoppilot.gateway.agent.EventSink;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.tool.config.PostureGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 飞书长连接客户端（round31 票 113 / ADR 0065）。
 *
 * <p>通过 WebSocket 长连接接收飞书事件，无需公网 IP 或回调地址（零暴露红线）。
 * SDK 负责心跳、重连和生命周期管理。
 *
 * <p>事件处理流程：
 * <ol>
 *   <li>FeishuAdapter.normalize() 归一化事件</li>
 *   <li>手动设置 TenantContext（飞书无 JWT，使用默认身份）</li>
 *   <li>手动设置 ChannelContext（FEISHU）</li>
 *   <li>调用 AgentStateMachine.run() 获取答案</li>
 *   <li>通过 FeishuReplySender 发送回复</li>
 *   <li>finally 清理 ThreadLocal</li>
 * </ol>
 *
 * <p><b>可测试性</b>：{@link #handleEvent(Map)} 是包级可见的测试入口，
 * 不依赖真实 WebSocket 连接。SDK 客户端的启动/断开由 {@link #start()} 和 {@link #stop()} 管理，
 * 测试时直接调用 {@code handleEvent()} 即可。
 *
 * <p><b>身份推导</b>：飞书长连接没有 JWT，无法从令牌解析租户和买家。
 * 使用默认身份：{@code tenantId="feishu"}，{@code customerId=chatId}，
 * {@code conversationId} 由 FeishuAdapter 派生（{@code "feishu:chat:<chatId>"}）。
 * 这是票 96 确定的两字段派生方案。
 *
 * <p><b>断连语义</b>：SDK 的 {@code autoReconnect=true} 自动重连。
 * {@link #stop()} 在应用关闭时断开连接。
 */
@Component
public class FeishuLongConnectionClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuLongConnectionClient.class);

    /**
     * 蛇形命名 ObjectMapper：飞书 SDK 的 Java 字段是驼峰（messageId），
     * 但 FeishuAdapter.normalize() 期望飞书 JSON 蛇形格式（message_id）。
     * 用 SNAKE_CASE 策略做 convertValue 转换。
     */
    private static final ObjectMapper SNAKE_MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final FeishuAdapter adapter;
    private final AgentStateMachine agentStateMachine;
    private final FeishuReplySender replySender;
    private final GatewayProperties properties;
    private final String bindAddress;
    private final ObjectMapper objectMapper;

    /** SDK 长连接客户端，未启用时为 null。 */
    private volatile Client wsClient;

    public FeishuLongConnectionClient(FeishuAdapter adapter, AgentStateMachine agentStateMachine,
                                       FeishuReplySender replySender, GatewayProperties properties,
                                       @Value("${server.address:}") String bindAddress,
                                       ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.agentStateMachine = agentStateMachine;
        this.replySender = replySender;
        this.properties = properties;
        this.bindAddress = bindAddress;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动飞书长连接（如果已启用）。
     *
     * <p>凭据家法（PostureGuard 集成，票 114 / ADR 0065）：
     * <ul>
     *   <li>非回环绑定 + 启用 + 空凭据 → 拒启（{@link IllegalStateException}）</li>
     *   <li>回环绑定 + 空凭据 → WARN + 不注册长连接</li>
     *   <li>凭据非空 → 正常启动 SDK 长连接</li>
     * </ul>
     */
    @PostConstruct
    void start() {
        GatewayProperties.Feishu feishu = properties.feishu();
        if (feishu == null || !feishu.enabled()) {
            log.info("飞书长连接未启用（enabled=false）");
            return;
        }

        boolean appIdBlank = isBlank(feishu.appId());
        boolean appSecretBlank = isBlank(feishu.appSecret());

        if (appIdBlank || appSecretBlank) {
            if (PostureGuard.isLoopback(bindAddress)) {
                log.warn("飞书长连接启用但凭据为空，回环绑定下不注册长连接；"
                        + "请在 .env 中配置 SHOPPILOT_IM_FEISHU_APP_ID 和 SHOPPILOT_IM_FEISHU_APP_SECRET");
                return;
            }
            throw new IllegalStateException(feishuStartupBlockerMessage(feishu, bindAddress));
        }

        try {
            EventDispatcher eventDispatcher = EventDispatcher.newBuilder(feishu.appId(), feishu.appSecret())
                    .onP2MessageReceiveV1(new com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(com.lark.oapi.service.im.v1.model.P2MessageReceiveV1 event) {
                            Map<String, Object> payload = SNAKE_MAPPER.convertValue(event, Map.class);
                            FeishuLongConnectionClient.this.handleEvent(payload);
                        }
                    })
                    .onP2ChatAccessEventBotP2pChatEnteredV1(new com.lark.oapi.service.im.ImService.P2ChatAccessEventBotP2pChatEnteredV1Handler() {
                        @Override
                        public void handle(com.lark.oapi.service.im.v1.model.P2ChatAccessEventBotP2pChatEnteredV1 event) {
                            log.debug("用户进入飞书单聊，无需处理");
                        }
                    })
                    .build();
            Client client = new Client.Builder(feishu.appId(), feishu.appSecret())
                    .eventHandler(eventDispatcher)
                    .autoReconnect(true)
                    .build();
            client.start();
            this.wsClient = client;
            log.info("飞书长连接已启动");
        } catch (Exception e) {
            log.error("飞书长连接启动失败", e);
        }
    }

    /**
     * 断开飞书长连接。
     *
     * <p>SDK 的 {@code disconnect()} 是 protected，不能直接调用。
     * JVM 关闭时 WebSocket 连接会自动断开，这里只清理引用。
     */
    @PreDestroy
    void stop() {
        this.wsClient = null;
        log.info("飞书长连接已清理");
    }

    /**
     * 处理飞书事件（可测试入口）。
     *
     * <p>SDK 的 {@code EventDispatcher.doWithoutValidation()} 返回反序列化后的事件对象，
     * 需要转为 {@code Map<String, Object>} 供 {@link FeishuAdapter#normalize(Map)} 使用。
     *
     * <p>本方法不依赖真实 WebSocket 连接，测试时直接调用即可。
     *
     * @param payload 飞书事件报文（JSON 对象）
     */
    void handleEvent(Map<String, Object> payload) {
        ChannelAdapter.NormalizedChat chat = adapter.normalize(payload);

        // 从 conversationId 提取 chatId
        // conversationId = "feishu:chat:<chatId>"
        String conversationId = chat.conversationId();
        String chatId = extractChatId(conversationId);

        // 设置上下文（飞书无 JWT，使用默认身份）
        TenantContext.Identity identity = new TenantContext.Identity(
                "feishu", chatId, conversationId);
        TenantContext.set(identity);
        ChannelContext.set(Channel.FEISHU);

        try {
            // 调用状态机（使用 NOOP sink，回复通过飞书 API 发送）
            AgentResult result = agentStateMachine.run(chat.query(), chat.clientToken(), EventSink.NOOP);

            // 发送回复
            if (result.answer() != null && !result.answer().isBlank()) {
                replySender.send(chatId, result.answer());
            }
        } finally {
            TenantContext.clear();
            ChannelContext.clear();
        }
    }

    /**
     * 从 conversationId 提取 chatId。
     *
     * <p>conversationId 格式为 {@code "feishu:chat:<chatId>"}，
     * 由 {@link ChannelAdapter#deriveConversationId(Channel, String)} 派生。
     */
    private static String extractChatId(String conversationId) {
        String prefix = "feishu:chat:";
        if (conversationId != null && conversationId.startsWith(prefix)) {
            return conversationId.substring(prefix.length());
        }
        return conversationId;
    }

    /**
     * 拒启消息三要素：哪个凭据缺、为什么危险、怎么配（同 B2 拒启消息的家法）。
     */
    private static String feishuStartupBlockerMessage(GatewayProperties.Feishu feishu, String bindAddress) {
        StringBuilder sb = new StringBuilder("拒绝启动飞书长连接：");
        boolean appIdBlank = isBlank(feishu.appId());
        boolean appSecretBlank = isBlank(feishu.appSecret());
        if (appIdBlank) {
            sb.append("SHOPPILOT_IM_FEISHU_APP_ID 未设置");
        }
        if (appIdBlank && appSecretBlank) {
            sb.append("、");
        }
        if (appSecretBlank) {
            sb.append("SHOPPILOT_IM_FEISHU_APP_SECRET 未设置");
        }
        sb.append("。非回环绑定（").append(bindAddress).append("）下空凭据不能启动长连接")
                .append("——飞书 SDK 需要有效凭据才能建立 WebSocket 连接。")
                .append("请在 .env 中配置这两个环境变量。");
        return sb.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
