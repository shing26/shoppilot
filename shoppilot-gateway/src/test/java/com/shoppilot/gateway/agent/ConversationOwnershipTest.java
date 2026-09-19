package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.cache.CacheService;
import com.shoppilot.gateway.cache.SingleFlight;
import com.shoppilot.gateway.cache.WriteBackPolicy;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.identity.TenantContext;
import com.shoppilot.gateway.knowledge.HybridRetriever;
import com.shoppilot.gateway.knowledge.KbEpoch;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.sentiment.SentimentGate;
import com.shoppilot.gateway.llm.LlmTypes;
import com.shoppilot.gateway.triage.TriageEngine;
import com.shoppilot.gateway.triage.TriageResult;
import com.shoppilot.tool.Intent;
import com.shoppilot.tool.ToolName;
import com.shoppilot.tool.view.ToolStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.shoppilot.gateway.cache.WriteBackPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话归属是「店铺 + 买家」两者（ADR 0025，票 22）。
 *
 * <p>缺陷本体：会话状态外置在 Redis，旧键形状 {@code shoppilot:session:{tenant}:{conv}} 不含买家，
 * 而 {@code X-Conversation-Id} 由客户端自带。同店铺里 B 把这一头填成 A 的值，就能载出 A 的对话轮次喂进
 * prompt、把新轮次写回 A 的会话，并接着办 A 办到一半的待办动作。词汇表把这件事叫**串号**。
 *
 * <p>用例 {@link #ownerStillResumesItsOwnPendingAction()} 是**正对照**：它证明「续办待办动作
 * 并把工具返回喂进 prompt」这条通路本身是活的。没有它，前面那些 never() 断言可能只是因为整条链路
 * 压根没跑起来而白绿。
 */
class ConversationOwnershipTest {

    private static final String TENANT = "T001";
    private static final String OWNER = "C001";
    private static final String INTRUDER = "C002";
    private static final String CONV = "conv-reused-by-b";

    /** A 说出口的东西，一件都不许出现在 B 的 prompt 里。 */
    private static final String OWNER_PHONE = "13800001111";
    private static final String OWNER_ORDER = "90001";
    private static final String OWNER_STREET = "余杭区文一西路969号";
    private static final String OWNER_RECEIVER = "张三";
    private static final String OWNER_TURN = "订单" + OWNER_ORDER + " 收货人" + OWNER_RECEIVER
            + " 手机" + OWNER_PHONE + "，地址改成" + OWNER_STREET;
    private static final String INTRUDER_TURN = "麻烦帮我看下这单到哪了 90002 手机 13900002222";

    private final Map<String, String> table = new HashMap<>();
    private final SessionStore store = new SessionStore(redis(), new ObjectMapper(), properties());

    @AfterEach
    void clearIdentity() {
        TenantContext.clear();
    }

    // ---- 键形状与仓储层归属 ----

    @Test
    @DisplayName("外置会话状态的键含买家段：形状按 ADR 0025")
    void keyCarriesTheBuyerSegment() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());

        assertThat(table.keySet()).containsExactly("shoppilot:session:T001:C001:conv-reused-by-b");
    }

    @Test
    @DisplayName("跨买家拿同一个会话 id 载入，得到空会话（缺陷本体）")
    void intruderLoadsAnEmptySession() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());

        SessionStore.Session loaded = store.load(TENANT, INTRUDER, CONV);

        assertThat(loaded.turns()).isEmpty();
        assertThat(loaded.hasPending()).isFalse();
        // 会话 id 本身不是秘密，B 用自己的 id 继续说话是合法的；泄漏的是内容，不是 id
        assertThat(loaded.conversationId()).isEqualTo(CONV);
    }

    @Test
    @DisplayName("跨买家写入不落到对方会话里，双向不污染")
    void crossBuyerWritesDoNotPolluteEitherSide() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());

        SessionStore.Session intruderView = store.load(TENANT, INTRUDER, CONV);
        store.save(TENANT, INTRUDER, store.appendTurn(intruderView, INTRUDER_TURN, "在查了"));

        SessionStore.Session ownerAgain = store.load(TENANT, OWNER, CONV);
        assertThat(ownerAgain.turns()).extracting(SessionStore.Turn::text)
                .containsExactly(OWNER_TURN, "请补充收件人姓名")
                .doesNotContain(INTRUDER_TURN);
        assertThat(ownerAgain.hasPending()).isTrue();

        SessionStore.Session intruderAgain = store.load(TENANT, INTRUDER, CONV);
        assertThat(intruderAgain.turns()).extracting(SessionStore.Turn::text)
                .containsExactly(INTRUDER_TURN, "在查了");
    }

    @Test
    @DisplayName("旧键形状不迁移：读不到、也不报错，靠 TTL 自然走")
    void legacyKeyShapeIsIgnoredNotBroken() {
        // 键形状换代之前写进去的那一份。本票不迁移，只要求它载不出来、也没被人改写成新键。
        String legacyKey = "shoppilot:session:T001:conv-reused-by-b";
        String legacyBody = "{\"conversationId\":\"conv-reused-by-b\",\"turns\":[{\"role\":\"user\",\"text\":\""
                + OWNER_TURN + "\"}],\"pendingTool\":\"modifyDeliveryAddress\",\"pendingArgs\":{},\"slotAskCount\":1}";
        table.put(legacyKey, legacyBody);
        assertThat(table).hasSize(1);

        SessionStore.Session loaded = store.load(TENANT, OWNER, CONV);
        assertThat(loaded.turns()).isEmpty();
        assertThat(loaded.hasPending()).isFalse();

        store.save(TENANT, OWNER, store.appendTurn(loaded, INTRUDER_TURN, "在查了"));

        assertThat(table).containsKey("shoppilot:session:T001:C001:conv-reused-by-b");
        // 旧键原样躺在原地：不读、不改、不搬，等自己的 TTL 走完
        assertThat(table.get(legacyKey)).isEqualTo(legacyBody);
        assertThat(table).hasSize(2);
    }

    @Test
    @DisplayName("token 里没有买家声明时退化成无上下文单轮，不开匿名共享桶")
    void missingBuyerClaimDegradesToStateless() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());

        assertThat(store.load(TENANT, null, CONV).turns()).isEmpty();
        assertThat(store.load(TENANT, "  ", CONV).turns()).isEmpty();

        store.save(TENANT, "", store.appendTurn(store.load(TENANT, "", CONV), INTRUDER_TURN, "在查了"));

        assertThat(store.load(TENANT, OWNER, CONV).turns()).extracting(SessionStore.Turn::text)
                .containsExactly(OWNER_TURN, "请补充收件人姓名");
    }

    @Test
    @DisplayName("买家标识不归一化：只差一处空白的两个 cid 不共享会话键")
    void whitespaceInBuyerClaimDoesNotMergeSessions() {
        // 订单行侧的守卫拿原样 cid 比人，会话键要是先 trim 一下，两边对"谁是同一个人"就分家了。
        store.save(TENANT, OWNER, sessionWithOwnerHistory());
        store.save(TENANT, OWNER + " ", store.appendTurn(store.load(TENANT, OWNER + " ", CONV), INTRUDER_TURN, "在查了"));

        assertThat(store.load(TENANT, OWNER + " ", CONV).turns()).extracting(SessionStore.Turn::text)
                .containsExactly(INTRUDER_TURN, "在查了");
        assertThat(store.load(TENANT, OWNER, CONV).turns()).extracting(SessionStore.Turn::text)
                .containsExactly(OWNER_TURN, "请补充收件人姓名");
    }

    // ---- 状态机层：待办动作不许跨买家续办 ----

    @Test
    @DisplayName("跨买家不得续办别人的待办动作：对方的参数既不进 prompt，也不被拿去发动作")
    void intruderCannotResumeOwnersPendingAction() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());
        List<LlmTypes.Request> prompts = new ArrayList<>();
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.complete(any())).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return LlmTypes.Reply.text("好的，这就帮您查");
        });
        ToolDispatcher dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.missingSlots(any(), any())).thenReturn(List.of());
        when(dispatcher.dispatch(any(), any())).thenReturn(ownerAddressResult());

        AgentResult result = runAs(INTRUDER, INTRUDER_TURN, llm, dispatcher);

        // 动作没被发动：A 办到一半的改址不该由 B 的输入触发
        verify(dispatcher, never()).dispatch(any(), any());
        // A 说过的任何一件参数都没进模型
        assertThat(prompts).isNotEmpty();
        for (LlmTypes.Request prompt : prompts) {
            for (LlmTypes.Message message : prompt.messages()) {
                assertThat(String.valueOf(message.content()))
                        .doesNotContain(OWNER_PHONE, OWNER_ORDER, OWNER_STREET, OWNER_RECEIVER)
                        .doesNotContain(OWNER_TURN);
            }
        }
        // A 的会话原样还在，pending 没被 B 清掉，也没被写入 B 的轮次
        SessionStore.Session ownerAgain = store.load(TENANT, OWNER, CONV);
        assertThat(ownerAgain.hasPending()).isTrue();
        assertThat(ownerAgain.turns()).extracting(SessionStore.Turn::text)
                .containsExactly(OWNER_TURN, "请补充收件人姓名");
        assertThat(result.answer()).isEqualTo("好的，这就帮您查");
    }

    @Test
    @DisplayName("正对照：同一家法，A 自己的待办动作确实会被续办并真的发动作")
    void ownerStillResumesItsOwnPendingAction() {
        store.save(TENANT, OWNER, sessionWithOwnerHistory());
        List<LlmTypes.Request> prompts = new ArrayList<>();
        LlmGateway llm = mock(LlmGateway.class);
        when(llm.stream(any(), any())).thenAnswer(call -> {
            prompts.add(call.getArgument(0));
            return new LlmTypes.Reply("已帮您改到" + OWNER_STREET, List.of(), 3, 5, null);
        });
        ToolDispatcher dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.missingSlots(any(), any())).thenReturn(List.of());
        when(dispatcher.dispatch(any(), any())).thenReturn(ownerAddressResult());

        AgentResult result = runAs(OWNER, OWNER_TURN, llm, dispatcher);

        // 这条通路是活的：没有它，上面那条 never() 可能是白绿
        verify(dispatcher).dispatch(any(), any());
        assertThat(prompts).hasSize(1);
        assertThat(result.answer()).contains(OWNER_STREET);
        // 办完之后待办清掉，会话回到无 pending 状态
        assertThat(store.load(TENANT, OWNER, CONV).hasPending()).isFalse();
    }

    // ---- 页面层：换身份不许把上一位买家的气泡留在屏上 ----

    @Test
    @DisplayName("调试台换身份后清空对话区：清 #chat、换会话 id，且只在身份真的变了时执行")
    void servedConsoleResetsConversationOnIdentitySwitch() throws Exception {
        String html = servedPage();
        String reset = functionBody(html, "function resetConversation()");
        assertThat(reset)
                .contains("$('chat').innerHTML = ''")
                .contains("clearTimeline()")
                .contains("newConv()");

        String login = functionBody(html, "async function login()");
        assertThat(login).contains("resetConversation()");
        // 同一个人重签一次不该把对话抹掉：清屏必须挂在身份比较之后
        assertThat(login).contains("tenant !== state.tenant").contains("customer !== state.customer");
        // 签发失败不该清屏：先有 res.status !== 200 的早退，才轮得到清屏那一步
        assertThat(login.indexOf("resetConversation()")).isGreaterThan(login.indexOf("res.status !== 200"));
        // 比较必须发生在赋值之前：先把新身份写进 state 再比，两侧永远相等，这道判断就成了死码
        assertThat(login.indexOf("state.tenant = tenant")).isGreaterThan(login.indexOf("resetConversation()"));
    }

    // ---- 夹具 ----

    /** 以某位买家的身份跑一轮编排：身份经 TenantContext 进，与真实请求路径同源。 */
    private AgentResult runAs(String customerId, String query, LlmGateway llm, ToolDispatcher dispatcher) {
        AgentStateMachine machine = stateMachine(llm, dispatcher);
        TenantContext.set(new TenantContext.Identity(TENANT, customerId, CONV));
        try {
            return machine.run(query, "tok-" + customerId, EventSink.NOOP);
        } finally {
            TenantContext.clear();
        }
    }

    private AgentStateMachine stateMachine(LlmGateway llm, ToolDispatcher dispatcher) {
        TriageEngine triage = mock(TriageEngine.class);
        when(triage.triage(anyString())).thenReturn(new TriageEngine.Outcome(
                TriageResult.dynamic(Intent.ACTION_ADDRESS, "T0", true), null));
        WriteBackPolicy policy = mock(WriteBackPolicy.class);
        when(policy.evaluate(any())).thenReturn(new WriteBackPolicy.Verdict(false, "单元测试不外写缓存"));
        KbEpoch epoch = mock(KbEpoch.class);
        when(epoch.current()).thenReturn(7L);
        return new AgentStateMachine(triage, mock(CacheService.class), mock(SingleFlight.class), policy, epoch,
                mock(HybridRetriever.class), llm, dispatcher, store, mock(FallbackService.class), properties(),
                mock(WriteBackPool.class), new PromptCatalog(), perfModeGate(), new SimpleMeterRegistry());
    }

    /** 情绪门只走词典层（perf-mode 跳过 LLM 分类）：会话归属测试不关心情绪第二层。 */
    private SentimentGate perfModeGate() {
        LlmGateway gateLlm = mock(LlmGateway.class);
        when(gateLlm.mode()).thenReturn("perf");
        return new SentimentGate(gateLlm, new ObjectMapper(), new SimpleMeterRegistry());
    }

    private static GatewayProperties properties() {
        GatewayProperties properties = mock(GatewayProperties.class);
        when(properties.agent()).thenReturn(new GatewayProperties.Agent(2, 2, Duration.ofMinutes(30), 4));
        when(properties.llm()).thenReturn(new GatewayProperties.Llm("local", "http://127.0.0.1:1", "unused",
                "qwen2.5:3b", 0.0d, Duration.ofSeconds(2), Duration.ofSeconds(30), 0L, null, null, null, null));
        return properties;
    }

    private StringRedisTemplate redis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = newTableBackedOperations();
        when(template.opsForValue()).thenReturn(values);
        return template;
    }

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> newTableBackedOperations() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(values.get(anyString())).thenAnswer(call -> table.get(call.getArgument(0, String.class)));
        doAnswer(call -> table.put(call.getArgument(0), call.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        return values;
    }

    private static SessionStore.Session sessionWithOwnerHistory() {
        Map<String, Object> pendingArgs = new LinkedHashMap<>();
        pendingArgs.put("askedSlot", "receiverPhone");
        return new SessionStore.Session(CONV,
                new ArrayList<>(List.of(new SessionStore.Turn("user", OWNER_TURN),
                        new SessionStore.Turn("assistant", "请补充收件人姓名"))),
                ToolName.MODIFY_DELIVERY_ADDRESS.apiName(), pendingArgs, 1);
    }

    /** 工具返回体里带着 A 的详址：一旦这条路径被跨买家走到，它就会被复述给 B。 */
    private static ToolDispatcher.Dispatch ownerAddressResult() {
        String json = "{\"status\":\"OK\",\"orderNo\":\"" + OWNER_ORDER + "\",\"receiverName\":\"" + OWNER_RECEIVER
                + "\",\"receiverPhone\":\"" + OWNER_PHONE + "\",\"address\":\"" + OWNER_STREET + "\"}";
        return new ToolDispatcher.Dispatch(ToolName.MODIFY_DELIVERY_ADDRESS, ToolStatus.OK, json, List.of(),
                "修改收货地址", false, false);
    }

    private static String servedPage() throws Exception {
        try (InputStream in = ConversationOwnershipTest.class.getResourceAsStream("/static/index.html")) {
            return new String(Objects.requireNonNull(in, "静态页不在 classpath：/static/index.html")
                    .readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 取一个函数从声明行到第一个顶格右花括号之间的正文。 */
    private static String functionBody(String html, String signature) {
        int start = html.indexOf(signature);
        assertThat(start).as("静态页里找不到 " + signature).isNotNegative();
        int end = html.indexOf("\n}", start);
        assertThat(end).as(signature + " 的结尾大括号不在顶格").isGreaterThan(start);
        return html.substring(start, end);
    }
}
