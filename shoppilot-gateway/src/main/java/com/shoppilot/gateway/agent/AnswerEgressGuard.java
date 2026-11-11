package com.shoppilot.gateway.agent;

import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.MeterRegistry;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 买家可见答案的内部标识出站守卫（round50 票 217 / SEC-INJ-04；契约修订 1 AC4b）。
 *
 * <p>提示词 v1.2.0 第 4 条已明文禁止对买家说工具名/字段名，但活体实测模型仍然把
 * {@code applyRefund} 的参数表发给了买家——提示词不是强制边界。本类是确定性守卫：
 * 买家可见文本里出现**已注册工具面**的标识符时，不再替换成占位词继续转发，而是**整段换成
 * 固定安全回复**。理由见契约修订 1：把标识符换成「相关操作/相关字段」后，参数表仍原样留在买家
 * 可见文本里——缺陷的实质（把内部工具参数规格发给买家）没有消除。命中即不给模型正文任何出口。
 *
 * <p>禁用清单**派生**自工具契约，不是三词黑名单：{@link ToolName#values()} 的全部
 * {@link ToolName#apiName()} ＋ {@link ToolContracts#requestType} 指向的 {@code *Request}
 * record 组件名。工具或参数以后新增/改名，清单跟着变，这里一行都不用动。
 *
 * <p>两个出口共用同一套匹配规则：
 * <ul>
 *   <li>{@link #sanitize(String)}：一次性文本（同步 answer、缓存答案、兜底返回值）——命中即整段替换；</li>
 *   <li>{@link Streaming}：SSE 逐 token 流。命中前用「标识符前缀」holdback（中文正文照常即时放出，
 *       不引入 TTFT 缓冲延迟）；一旦命中，转发固定安全回复并**丢弃此后所有模型正文**，
 *       跨 chunk 拆开的标识符（{@code apply} + {@code Refund}）同样在拼齐时触发。</li>
 * </ul>
 *
 * <p>匹配是**大小写敏感 + ASCII 词边界**的：注册标识符就是模型 schema 里的驼峰原名；中文里的
 * 「订单号」「收件人姓名」等正常说法不是标识符，原样通过。未命中时文本逐字节不变；
 * 不动 {@link ComposeHandler#dropPolicyOrderAsk} 的作用面。
 */
final class AnswerEgressGuard {

    /** 命中计数：{@code outlet} 标出是哪条出站口（answer / sse_token / cache_reuse / slot_resume）。 */
    static final String METRIC = "shoppilot_answer_egress_guard_total";

    /**
     * 命中后的固定安全回复：不含参数枚举、不含内部标识，只给下一步动作。
     * 所有出口最终交付的正文必须是这一句（流式命中前已放出的普通前缀不追回，但命中后不再转发正文）。
     */
    static final String SAFE_REPLY = "这属于内部处理细节，不便向您展开。"
            + "如需办理退款或修改收货地址，请告诉我订单号，我来帮您处理。";

    private static final List<String> TOOL_IDENTIFIERS = deriveToolIdentifiers();
    private static final List<String> PARAM_IDENTIFIERS = deriveParameterIdentifiers();
    /** 最长优先，保证 {@code receiverPhone} 不会被更短的 {@code receiver} 之类抢先命中（当前无此例，规则先立着）。 */
    private static final List<String> IDENTIFIERS = mergedLongestFirst();
    private static final int MAX_IDENTIFIER_LENGTH =
            IDENTIFIERS.stream().mapToInt(String::length).max().orElse(0);

    private AnswerEgressGuard() {
    }

    /** 一次过滤的结果：命中时 text 恒为 {@link #SAFE_REPLY}，hits 为检出的标识符个数。 */
    record Result(String text, int hits) {

        boolean hit() {
            return hits > 0;
        }
    }

    // ── 派生清单 ──────────────────────────────────────────────────────────

    private static List<String> deriveToolIdentifiers() {
        List<String> identifiers = new ArrayList<>();
        for (ToolName tool : ToolName.values()) {
            identifiers.add(tool.apiName());
        }
        return List.copyOf(identifiers);
    }

    private static List<String> deriveParameterIdentifiers() {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        for (ToolName tool : ToolName.values()) {
            Class<?> requestType = ToolContracts.requestType(tool);
            if (requestType == null || !requestType.isRecord()) {
                continue;
            }
            for (RecordComponent component : requestType.getRecordComponents()) {
                identifiers.add(component.getName());
            }
        }
        return List.copyOf(identifiers);
    }

    private static List<String> mergedLongestFirst() {
        LinkedHashSet<String> merged = new LinkedHashSet<>(TOOL_IDENTIFIERS);
        merged.addAll(PARAM_IDENTIFIERS);
        List<String> ordered = new ArrayList<>(merged);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return List.copyOf(ordered);
    }

    /** 只读视图供验收用例证明「清单来自契约」；生产路径不需要。 */
    static List<String> identifiers() {
        return IDENTIFIERS;
    }

    // ── 一次性文本 ────────────────────────────────────────────────────────

    /**
     * 命中任一标识符即整段替换为 {@link #SAFE_REPLY}（AC4b：不允许把掩码后的参数表交给买家）。
     * 未命中时返回原字符串，逐字节不变。
     */
    static Result sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        int hits = 0;
        int index = 0;
        while (index < text.length()) {
            String identifier = matchAt(text, index);
            if (identifier != null) {
                hits++;
                index += identifier.length();
            } else {
                index++;
            }
        }
        return hits == 0 ? new Result(text, 0) : new Result(SAFE_REPLY, hits);
    }

    /** 命中计数；{@code outlet} 是低基数封闭值，由调用点给出。 */
    static void count(MeterRegistry registry, String outlet, int hits) {
        if (registry != null && hits > 0) {
            registry.counter(METRIC, "outlet", outlet).increment(hits);
        }
    }

    // ── 流式 ──────────────────────────────────────────────────────────────

    /**
     * 逐 token 过滤器。命中前 {@link #accept} 返回可立即送出的普通文本（可能为空串）；
     * 命中时返回固定安全回复，之后所有 {@link #accept} 与 {@link #flush} 都返回空串（丢弃模型正文）。
     */
    static final class Streaming {

        private final StringBuilder buffer = new StringBuilder();
        private boolean hit;
        private int hits;

        String accept(String delta) {
            if (hit || delta == null || delta.isEmpty()) {
                return "";
            }
            buffer.append(delta);
            return drain(false);
        }

        String flush() {
            if (hit) {
                buffer.setLength(0);
                return "";
            }
            return drain(true);
        }

        int hits() {
            return hits;
        }

        boolean hit() {
            return hit;
        }

        private String drain(boolean end) {
            int length = buffer.length();
            if (length == 0) {
                return "";
            }
            int keep = end ? 0 : retainedSuffixLength();
            int limit = length - keep;
            // 不把代理对劈成两半（中文正文基本走不到，但流式出口不能制造半个字符）。
            if (limit > 0 && limit < length && Character.isHighSurrogate(buffer.charAt(limit - 1))) {
                limit--;
            }
            if (limit <= 0) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            int index = 0;
            while (index < limit) {
                String identifier = matchAt(buffer, index);
                if (identifier != null) {
                    // AC4b：命中即停止转发模型正文，补安全回复并丢弃后续（含本帧剩余与后续帧）。
                    hit = true;
                    hits++;
                    buffer.setLength(0);
                    return out.append(SAFE_REPLY).toString();
                }
                out.append(buffer.charAt(index));
                index++;
            }
            buffer.delete(0, index);
            return out.toString();
        }

        /**
         * 缓冲区尾部最长的一段「可能是某个标识符前缀」的长度。中文正文恒为 0，所以首帧不被拖延；
         * 只有半截英文标识符会被压住，等下一帧拼齐再判定。
         */
        private int retainedSuffixLength() {
            int length = buffer.length();
            int longest = Math.min(MAX_IDENTIFIER_LENGTH, length);
            for (int suffix = longest; suffix > 0; suffix--) {
                int from = length - suffix;
                for (String identifier : IDENTIFIERS) {
                    if (identifier.length() >= suffix && regionMatches(identifier, 0, buffer, from, suffix)) {
                        return suffix;
                    }
                }
            }
            return 0;
        }
    }

    // ── 匹配 ──────────────────────────────────────────────────────────────

    /** 在第 {@code index} 位找一个带 ASCII 词边界的完整标识符；命中返回标识符本身。 */
    private static String matchAt(CharSequence text, int index) {
        for (String identifier : IDENTIFIERS) {
            int end = index + identifier.length();
            if (end > text.length()) {
                continue;
            }
            if (!regionMatches(identifier, 0, text, index, identifier.length())) {
                continue;
            }
            if (index > 0 && isIdentifierChar(text.charAt(index - 1))) {
                continue;
            }
            if (end < text.length() && isIdentifierChar(text.charAt(end))) {
                continue;
            }
            return identifier;
        }
        return null;
    }

    private static boolean regionMatches(CharSequence left, int leftIndex, CharSequence right, int rightIndex,
                                         int length) {
        for (int offset = 0; offset < length; offset++) {
            if (left.charAt(leftIndex + offset) != right.charAt(rightIndex + offset)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return c < 0x80 && (Character.isLetterOrDigit(c) || c == '_');
    }
}
