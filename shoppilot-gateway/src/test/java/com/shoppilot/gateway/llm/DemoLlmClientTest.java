package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoLlmClientTest {

    private DemoLlmClient client;

    @BeforeEach
    void setUp() {
        GatewayProperties.Llm config = mock(GatewayProperties.Llm.class);
        when(config.perfFirstTokenLatency()).thenReturn(Duration.ZERO);
        when(config.perfTotalLatency()).thenReturn(Duration.ofMillis(1));
        client = new DemoLlmClient(config, new ObjectMapper());
    }

    @Test
    @DisplayName("店铺介绍问题不再回退货政策")
    void storeQuestionGetsStoreAnswer() {
        LlmTypes.Reply reply = client.complete(request("你们店是卖什么的？", List.of()));
        assertThat(reply.content()).contains("主营");
        assertThat(reply.content()).doesNotContain("七天无理由");
    }

    @Test
    @DisplayName("检索条款里的生鲜字样不能把快递问题带偏")
    void composedPromptUsesBuyerQuestionOnly() {
        String composed = "【政策条款】\n1. [本店] 生鲜商品 48 小时内可申请理赔\n\n【买家问题】\n发什么快递";
        LlmTypes.Reply reply = client.complete(request(composed, List.of()));
        assertThat(reply.content()).contains("物流");
        assertThat(reply.content()).doesNotContain("48 小时");
    }

    @Test
    @DisplayName("生鲜问题回到生鲜理赔口径")
    void freshQuestionGetsFreshAnswer() {
        LlmTypes.Reply reply = client.complete(request("生鲜坏了怎么赔", List.of()));
        assertThat(reply.content()).contains("48 小时");
    }

    @Test
    @DisplayName("订单号 + 物流问题 → 发起 queryLogistics 工具调用")
    void orderQuestionCallsTool() {
        LlmTypes.Reply reply = client.complete(request("订单 90001 到哪了", List.of(tool("queryLogistics"))));
        assertThat(reply.wantsTool()).isTrue();
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("queryLogistics");
        assertThat(reply.toolCalls().get(0).arguments()).containsEntry("orderNo", "90001");
    }

    @Test
    @DisplayName("工具结果回来后按结果回答，而不是再发工具调用")
    void toolResultBecomesAnswer() {
        LlmTypes.Request request = new LlmTypes.Request(List.of(
                LlmTypes.Message.user("订单 90001 到哪了"),
                LlmTypes.Message.tool("demo-call-1",
                        "{\"tool\":\"queryLogistics\",\"status\":\"OK\",\"payload\":{"
                                + "\"orderNo\":\"90001\",\"cpName\":\"顺丰\",\"trackingNo\":\"SF123\","
                                + "\"nodes\":[{\"seq\":1,\"description\":\"已签收\"}]}}")),
                List.of(tool("queryLogistics")), 0.0);
        LlmTypes.Reply reply = client.complete(request);
        assertThat(reply.wantsTool()).isFalse();
        assertThat(reply.content()).contains("顺丰").contains("SF123").contains("已签收");
    }

    @Test
    @DisplayName("流式输出同样按问题选择答案")
    void streamIsIntentAware() {
        List<String> chunks = new ArrayList<>();
        LlmTypes.Reply reply = client.stream(request("七天无理由怎么退", List.of()), chunks::add);
        assertThat(reply.content()).contains("七天无理由");
        assertThat(String.join("", chunks)).isEqualTo(reply.content());
    }

    @Test
    @DisplayName("mode 报 demo，不冒充 perf/dev")
    void modeIsDemo() {
        assertThat(client.mode()).isEqualTo("demo");
    }

    private static LlmTypes.Request request(String user, List<Map<String, Object>> tools) {
        return new LlmTypes.Request(List.of(LlmTypes.Message.user(user)), tools, 0.0);
    }

    private static Map<String, Object> tool(String name) {
        return Map.of("type", "function", "function", Map.of("name", name));
    }
}
