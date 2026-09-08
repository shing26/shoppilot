package com.shoppilot.gateway.llm;

import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.ToolName;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * perf 模式专用：固定延迟 + 确定性输出，只用于压测网关编排层与缓存层（ADR 0001）。
 *
 * <p>它不"聪明"，这是刻意的：压测要的是可预算的延迟形状，不是答案质量。
 * 任何质量类指标都不得用这个客户端测出来。
 */
public class MockLlmClient implements LlmClient {

    private static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)(\\d{5,8})(?!\\d)");
    private static final String ANSWER =
            "根据平台规则，满足条件的订单支持七天无理由退换；生鲜类商品出现品质问题可在签收后 48 小时内申请理赔，"
                    + "需上传凭证照片并由店铺审核，审核通过后款项将按原支付渠道退回，通常 1 到 3 个工作日到账。";

    private final GatewayProperties.Llm config;

    public MockLlmClient(GatewayProperties.Llm config) {
        this.config = config;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        sleep(config.perfFirstTokenLatency().toMillis());
        if (request.tools() == null || request.tools().isEmpty()) {
            return new LlmTypes.Reply(ANSWER, List.of(), estimateTokens(promptText(request)), estimateTokens(ANSWER), null);
        }
        String lastUser = lastUserText(request);
        Matcher matcher = ORDER_NO.matcher(lastUser);
        if (!matcher.find()) {
            return new LlmTypes.Reply(null, List.of(), estimateTokens(promptText(request)), 0, null);
        }
        ToolName chosen = pickTool(lastUser, request.tools());
        if (chosen == null) {
            return new LlmTypes.Reply(ANSWER, List.of(), estimateTokens(promptText(request)), estimateTokens(ANSWER), null);
        }
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        arguments.put("orderNo", matcher.group(1));
        if (chosen == ToolName.APPLY_REFUND) {
            arguments.put("reason", "压测占位原因");
        }
        if (chosen == ToolName.MODIFY_DELIVERY_ADDRESS) {
            arguments.put("receiverName", "压测收件人");
            arguments.put("receiverPhone", "13800001111");
            arguments.put("province", "浙江省");
            arguments.put("city", "杭州市");
            arguments.put("district", "西湖区");
            arguments.put("detailAddress", "文一西路 100 号");
        }
        return new LlmTypes.Reply(null, List.of(new LlmTypes.ToolCall("mock-call-1", chosen.apiName(), arguments)),
                estimateTokens(promptText(request)), 0, null);
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        long total = Math.max(1, config.perfTotalLatency().toMillis());
        long first = Math.min(config.perfFirstTokenLatency().toMillis(), total);
        sleep(first);
        int chunks = 8;
        long perChunk = Math.max(1, (total - first) / chunks);
        for (int i = 0; i < chunks; i++) {
            int from = i * ANSWER.length() / chunks;
            int to = (i + 1) * ANSWER.length() / chunks;
            tokenSink.accept(ANSWER.substring(from, to));
            sleep(perChunk);
        }
        return new LlmTypes.Reply(ANSWER, List.of(), estimateTokens(promptText(request)), estimateTokens(ANSWER), null);
    }

    @Override
    public String mode() {
        return "perf";
    }

    private ToolName pickTool(String text, List<Map<String, Object>> available) {
        ToolName candidate;
        if (text.contains("地址") || text.contains("收货")) {
            candidate = ToolName.MODIFY_DELIVERY_ADDRESS;
        } else if (text.contains("退款") || text.contains("退钱")) {
            candidate = ToolName.APPLY_REFUND;
        } else if (text.contains("物流") || text.contains("快递") || text.contains("到哪") || text.contains("签收")) {
            candidate = ToolName.QUERY_LOGISTICS;
        } else {
            candidate = ToolName.QUERY_ORDER_DETAIL;
        }
        return available.stream()
                .map(tool -> (String) ((Map<?, ?>) tool.get("function")).get("name"))
                .filter(candidate.apiName()::equals)
                .findFirst()
                .map(name -> candidate)
                .orElse(null);
    }

    private String lastUserText(LlmTypes.Request request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            LlmTypes.Message message = request.messages().get(i);
            if ("user".equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    private String promptText(LlmTypes.Request request) {
        StringBuilder text = new StringBuilder();
        request.messages().forEach(message -> text.append(message.content() == null ? "" : message.content()));
        return text.toString();
    }

    /** 粗估：中文约两字符一 token，够用于展示"节约率"的量级。 */
    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 2);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
