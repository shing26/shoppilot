package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.tool.ToolName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 演示档（{@code demo}）：确定性、意图感知的本地答案。
 *
 * <p>为什么不是直接复用 {@link MockLlmClient}：perf 的 Mock 是压测桩，固定输出一段退货政策，
 * 任何问题都回同一段话——在演示里就是“牛头不对马嘴”。这里按问题类型、工具结果和店铺能力
 * 生成可读答案，但仍是**模板**，不是真模型；质量指标不能拿它冒充 dev/local。
 *
 * <p>延迟沿用 perf 的两个配置项（首字/总时长），让演示与压测的延迟形状一致。
 */
public class DemoLlmClient implements LlmClient {

    private static final Pattern ORDER_NO = Pattern.compile("(?<!\\d)(\\d{5,8})(?!\\d)");
    private static final String RETURN_ANSWER =
            "满足条件的订单支持七天无理由退换：商品不影响二次销售、配件齐全即可申请；"
                    + "退款审核通过后会按原支付渠道退回，通常 1 到 3 个工作日到账。";
    private static final String FRESH_ANSWER =
            "生鲜商品出现品质问题，请在签收后 48 小时内上传凭证照片申请理赔；"
                    + "店铺审核通过后按原支付渠道退款。";
    private static final String STORE_ANSWER =
            "本店主营日用百货、生鲜果蔬、数码配件和服饰鞋包；可以帮你查订单、退换货、生鲜理赔。";
    private static final String SHIPPING_ANSWER =
            "发货后可以在订单详情里看物流；把订单号发我，我帮你查承运商和最新节点。";
    private static final String HOTEL_ANSWER =
            "入住通常在 14:00 后，退房在 12:00 前；具体以你的订单确认信息为准。";
    private static final String GREETING_ANSWER =
            "你好，我是本店在线客服。可以帮你查订单、退换货、生鲜理赔；需要人工时直接转，不用重复描述。";
    private static final String DEFAULT_ANSWER =
            "我可以帮你查订单状态、物流、退换货和生鲜理赔。请把订单号发我，或直接描述遇到的问题。";

    private final GatewayProperties.Llm config;
    private final ObjectMapper mapper;

    public DemoLlmClient(GatewayProperties.Llm config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public LlmTypes.Reply complete(LlmTypes.Request request) {
        sleep(config.perfFirstTokenLatency().toMillis());
        String lastUser = buyerQuestion(lastUserText(request));
        String toolResult = lastToolContent(request);
        if (toolResult != null) {
            String answer = answerFromTool(toolResult);
            return new LlmTypes.Reply(answer, List.of(), estimateTokens(promptText(request)), estimateTokens(answer), null);
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            Matcher matcher = ORDER_NO.matcher(lastUser);
            if (matcher.find()) {
                ToolName chosen = pickTool(lastUser, request.tools());
                if (chosen != null) {
                    Map<String, Object> arguments = new LinkedHashMap<>();
                    arguments.put("orderNo", matcher.group(1));
                    if (chosen == ToolName.APPLY_REFUND) {
                        arguments.put("reason", "用户申请退款");
                    }
                    if (chosen == ToolName.MODIFY_DELIVERY_ADDRESS) {
                        arguments.put("receiverName", "演示收件人");
                        arguments.put("receiverPhone", "13800001111");
                        arguments.put("province", "浙江省");
                        arguments.put("city", "杭州市");
                        arguments.put("district", "西湖区");
                        arguments.put("detailAddress", "文一西路 100 号");
                    }
                    return new LlmTypes.Reply(null,
                            List.of(new LlmTypes.ToolCall("demo-call-1", chosen.apiName(), arguments)),
                            estimateTokens(promptText(request)), 0, null);
                }
            }
        }
        String answer = answerFor(lastUser);
        return new LlmTypes.Reply(answer, List.of(), estimateTokens(promptText(request)), estimateTokens(answer), null);
    }

    @Override
    public LlmTypes.Reply stream(LlmTypes.Request request, Consumer<String> tokenSink) {
        String lastUser = buyerQuestion(lastUserText(request));
        String toolResult = lastToolContent(request);
        String answer = toolResult != null ? answerFromTool(toolResult) : answerFor(lastUser);
        long total = Math.max(1, config.perfTotalLatency().toMillis());
        long first = Math.min(config.perfFirstTokenLatency().toMillis(), total);
        sleep(first);
        int chunks = 8;
        long perChunk = Math.max(1, (total - first) / chunks);
        for (int i = 0; i < chunks; i++) {
            int from = i * answer.length() / chunks;
            int to = (i + 1) * answer.length() / chunks;
            if (to > from) {
                tokenSink.accept(answer.substring(from, to));
                sleep(perChunk);
            }
        }
        return new LlmTypes.Reply(answer, List.of(), estimateTokens(promptText(request)), estimateTokens(answer), null);
    }

    @Override
    public String mode() {
        return "demo";
    }

    @Override
    public String tokenSource() {
        return "estimate";
    }

    private String answerFor(String query) {
        String text = query == null ? "" : query;
        if (containsAny(text, "你好", "在吗", "hi", "hello", "您好")) {
            return GREETING_ANSWER;
        }
        if (containsAny(text, "卖什么", "主营", "你们店", "店铺介绍", "有什么")) {
            return STORE_ANSWER;
        }
        if (containsAny(text, "生鲜", "坏了", "腐烂", "死蟹", "理赔")) {
            return FRESH_ANSWER;
        }
        if (containsAny(text, "退货", "退款", "七天", "无理由", "退换")) {
            return RETURN_ANSWER;
        }
        if (containsAny(text, "快递", "物流", "发货", "到哪", "签收", "配送")) {
            return SHIPPING_ANSWER;
        }
        if (containsAny(text, "酒店", "入住", "退房")) {
            return HOTEL_ANSWER;
        }
        if (ORDER_NO.matcher(text).find()) {
            return "我收到订单号了。你想查这笔订单的状态、物流，还是申请退换/理赔？";
        }
        return DEFAULT_ANSWER;
    }

    private String answerFromTool(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String tool = root.path("tool").asText("");
            String status = root.path("status").asText("");
            String message = root.path("message").asText("");
            JsonNode payload = root.path("payload");
            if ("OK".equals(status) || "IDEMPOTENT_REPLAY".equals(status)) {
                return switch (tool) {
                    case "queryLogistics" -> logisticsAnswer(payload);
                    case "queryOrderDetail" -> orderAnswer(payload);
                    case "applyRefund" -> "退款申请已受理，等待人工审核；审核结果会回到本会话。";
                    case "modifyDeliveryAddress" -> "收货地址修改已受理，结果会回到本会话。";
                    default -> "已查到最新业务结果。" + (message.isBlank() ? "" : message);
                };
            }
            if ("PENDING_APPROVAL".equals(status)) {
                return "这笔退款已受理，正在等待人工审核；审核结果会回到本会话，不用重复提交。";
            }
            if ("STATE_NOT_ALLOWED".equals(status)) {
                return "这笔订单当前状态不支持该操作" + (message.isBlank() ? "。" : "：" + message);
            }
            if ("NOT_FOUND".equals(status)) {
                return "没有在本店查到这笔订单，请确认订单号是否属于本店。";
            }
            return "这次查询没有拿到可用结果，已为你转人工。";
        } catch (Exception unreadable) {
            return "我暂时读不到这笔订单的结果，已为你转人工。";
        }
    }

    private String logisticsAnswer(JsonNode payload) {
        String orderNo = payload.path("orderNo").asText("");
        String carrier = payload.path("cpName").asText("");
        String trackingNo = payload.path("trackingNo").asText("");
        JsonNode nodes = payload.path("nodes");
        String latest = "";
        if (nodes.isArray() && !nodes.isEmpty()) {
            latest = nodes.get(nodes.size() - 1).path("description").asText("");
        }
        StringBuilder answer = new StringBuilder("我查到订单 ").append(orderNo);
        if (!carrier.isBlank()) {
            answer.append(" 由 ").append(carrier).append(" 承运");
        }
        if (!trackingNo.isBlank()) {
            answer.append("，运单号 ").append(trackingNo);
        }
        if (!latest.isBlank()) {
            answer.append("；最新节点：").append(latest);
        }
        return answer.append("。").toString();
    }

    private String orderAnswer(JsonNode payload) {
        String orderNo = payload.path("orderNo").asText("");
        String status = payload.path("status").asText("");
        String category = payload.path("categoryName").asText("");
        long amountFen = payload.path("amountFen").asLong(0);
        StringBuilder answer = new StringBuilder("订单 ").append(orderNo).append(" 当前状态：").append(status);
        if (!category.isBlank()) {
            answer.append("，商品类目：").append(category);
        }
        if (amountFen > 0) {
            answer.append("，实付 ").append(String.format("%.2f", amountFen / 100.0d)).append(" 元");
        }
        return answer.append("。需要我继续查物流或发起售后吗？").toString();
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

    /** 状态机把检索到的政策条款拼在用户消息前面；演示答案只认「【买家问题】」之后那句。 */
    private String buyerQuestion(String content) {
        if (content == null) {
            return "";
        }
        int marker = content.lastIndexOf("【买家问题】");
        return marker >= 0 ? content.substring(marker + "【买家问题】".length()).trim() : content;
    }

    private String lastToolContent(LlmTypes.Request request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            LlmTypes.Message message = request.messages().get(i);
            if ("tool".equals(message.role()) && message.content() != null && !message.content().isBlank()) {
                return message.content();
            }
        }
        return null;
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

    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 2);
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase();
        for (String needle : needles) {
            if (lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
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
