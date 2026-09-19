package com.shoppilot.gateway.sentiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.llm.LlmException;
import com.shoppilot.gateway.agent.PromptCatalog;
import com.shoppilot.gateway.llm.LlmGateway;
import com.shoppilot.gateway.llm.LlmTypes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 情绪门的 0 token 单测（票 36 / ADR 0034）：词典层 8 条升级用例的定案、12 条非升级用例的
 * 不误伤、第二层 LLM 分类的升级判据与 fail-open、perf 口径的词典层-only。
 */
class SentimentGateTest {

    private LlmGateway llm;
    private SimpleMeterRegistry registry;
    private SentimentGate gate;

    /** 票 36 评测集的 8 条升级样本（6 ANGRY + 2 URGENT），全部必须由词典层 0 token 定案。 */
    private static final List<String> LEXICON_ESCALATIONS = List.of(
            "你们就是骗子！订单SO20260901001拖了半个月不退款，我今天必须拿到说法，不然就去投诉到底",
            "说的三天到现在一周了物流一动不动，什么破店，给我转人工马上",
            "买了这么个破玩意儿还敢收我运费？黑心商家，我要曝光你们",
            "第三次问了！每次都说稍等每次都没结果，再不解决我直接打12315投诉你们店",
            "客服全是机器人踢皮球，一个能办事的都没有，我要找真人领导谈",
            "退款拖了这么久还有脸让我等？废物系统，给我人工处理现在",
            "急急急！快递马上就要发货了，我下单地址填错了，现在立刻马上帮我改掉",
            "我妈住院等着这笔退款交费，求你们今天一定帮我处理一下订单SO20260901002的退款");

    /** 12 条非升级样本（CALM/DISSATISFIED/UNCERTAIN），词典层一个都不能误伤。 */
    private static final List<String> NON_ESCALATIONS = List.of(
            "请问七天无理由退货需要我自己出运费吗",
            "帮我查一下订单SO20260901003现在什么状态了",
            "物流一般多久更新一次轨迹呀",
            "之前申请的退款进度帮忙看一下，不着急，麻烦了",
            "物流有点慢了啊，这都第五天了还没到，能帮我看看吗",
            "运费还要我承担，感觉不太合理吧，你们这政策是不是该改改",
            "商品和描述有点出入，虽然不算大问题但还是有点失望，帮我查下能不能退",
            "你们服务可真是太棒了呢，问什么都是让看FAQ，真有你们的",
            "哦，又是在路上呢，行吧，反正也不差这两天了",
            "这个商品可以退吗",
            "帮我改一下收货地址，订单号SO20260901004，新的地址是上海市浦东新区世纪大道100号",
            "快递说派送了但我没收到货，这是怎么回事");

    @BeforeEach
    void setUp() {
        llm = mock(LlmGateway.class);
        when(llm.mode()).thenReturn("dev");
        registry = new SimpleMeterRegistry();
        gate = new SentimentGate(llm, new ObjectMapper(), registry, new PromptCatalog("prompts/sentiment-classifier/"));
    }

    @Test
    @DisplayName("词典层：8 条升级样本 0 token 定案，完全不触碰 LLM")
    void lexiconEscalationsNeverTouchTheModel() {
        for (String query : LEXICON_ESCALATIONS) {
            SentimentGate.Verdict verdict = gate.evaluate(query);
            assertTrue(verdict.escalated(), "必须升级: " + query);
            assertEquals("lexicon", verdict.source(), "必须由词典层定案: " + query);
            assertTrue(verdict.emotion() == Emotion.ANGRY || verdict.emotion() == Emotion.URGENT,
                    "定案 emotion 必须是 ANGRY/URGENT: " + query + " -> " + verdict.emotion());
        }
        verifyNoInteractions(llm);
        assertEquals(8.0d, registry.get("shoppilot_sentiment_lexicon_decided_total").counter().count());
    }

    @Test
    @DisplayName("词典层 ANGRY 优先于 URGENT：又急又气按愤怒处理")
    void angryWinsOverUrgent() {
        SentimentGate.Verdict verdict = gate.evaluate("你们就是骗子！急死我了，我住院等这笔钱，马上给我处理！");
        assertEquals(Emotion.ANGRY, verdict.emotion());
        assertTrue(verdict.escalated());
    }

    @Test
    @DisplayName("非升级样本：词典层零误伤（LLM 分类按 fail-open 处理也不升级）")
    void nonEscalationQueriesPassThrough() {
        when(llm.complete(any())).thenThrow(new LlmException(LlmException.Kind.UNAVAILABLE, "mock 下线", null));
        for (String query : NON_ESCALATIONS) {
            SentimentGate.Verdict verdict = gate.evaluate(query);
            assertFalse(verdict.escalated(), "不许误升级: " + query);
            assertEquals(Emotion.UNCERTAIN, verdict.emotion(), "LLM 不可用时必须 fail-open: " + query);
        }
    }

    @Test
    @DisplayName("第二层升级判据：ANGRY 恒升级，URGENT 要置信度 ≥ 0.8，DISSATISFIED 再高也不升")
    void classificationEscalationRule() {
        when(llm.complete(any())).thenReturn(
                reply("{\"emotion\":\"ANGRY\",\"confidence\":0.5}"),
                reply("{\"emotion\":\"URGENT\",\"confidence\":0.9}"),
                reply("{\"emotion\":\"URGENT\",\"confidence\":0.5}"),
                reply("{\"emotion\":\"DISSATISFIED\",\"confidence\":0.99}"),
                reply("{\"emotion\":\"CALM\",\"confidence\":0.9}"));

        assertTrue(gate.evaluate("classify me 1").escalated(), "ANGRY 无视置信度恒升级");
        assertTrue(gate.evaluate("classify me 2").escalated(), "URGENT 高置信度升级");
        assertFalse(gate.evaluate("classify me 3").escalated(), "URGENT 低置信度不升");
        assertFalse(gate.evaluate("classify me 4").escalated(), "DISSATISFIED 不升");
        assertFalse(gate.evaluate("classify me 5").escalated(), "CALM 不升");
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("分类输出裹在散文或代码围栏里也能解析；解析不出 fail-open")
    void toleratesWrappingAndFailsOpen() {
        when(llm.complete(any())).thenReturn(
                reply("分类结果如下：\n```json\n{\"emotion\":\"URGENT\",\"confidence\":0.95}\n```"),
                reply("这不是 JSON"),
                null);

        assertTrue(gate.evaluate("围栏里的 JSON").escalated());
        assertFalse(gate.evaluate("纯散文").escalated());
        assertFalse(gate.evaluate("空回复").escalated());
    }

    @Test
    @DisplayName("perf 口径只有词典层：不调用 LLM 分类，词典未命中直接 UNCERTAIN")
    void perfModeIsLexiconOnly() {
        when(llm.mode()).thenReturn("perf");
        SentimentGate.Verdict verdict = gate.evaluate("帮我查一下订单什么状态了");
        assertEquals(Emotion.UNCERTAIN, verdict.emotion());
        assertFalse(verdict.escalated());
        verify(llm, never()).complete(any());
        verify(llm, never()).stream(any(), any());
    }

    private static LlmTypes.Reply reply(String content) {
        return LlmTypes.Reply.text(content);
    }
}
