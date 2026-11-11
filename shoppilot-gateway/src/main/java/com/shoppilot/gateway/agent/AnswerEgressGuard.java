package com.shoppilot.gateway.agent;

import com.shoppilot.tool.ToolContracts;
import com.shoppilot.tool.ToolName;
import io.micrometer.core.instrument.MeterRegistry;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 买家可见答案的内部标识出站守卫（round50 票 217 / SEC-INJ-04）。
 *
 * <p>提示词 v1.2.0 第 4 条已明文禁止对买家说工具名/字段名，但活体实测模型仍然把
 * {@code applyRefund} 的参数表发给了买家——提示词不是强制边界。本类是确定性守卫：
 * 买家可见文本里出现**已注册工具面**的标识符时，按类别替换成不泄露内部契约的中文占位词。
 *
 * <p>禁用清单**派生**自工具契约，不是三词黑名单：{@link ToolName#values()} 的全部
 * {@link ToolName#apiName()} ＋ {@link ToolContracts#requestType} 指向的 {@code *Request}
 * record 组件名。工具或参数以后新增/改名，清单跟着变，这里一行都不用动。
 *
 * <p>两个出口共用同一套匹配规则：
 * <ul>
 *   <li>{@link #sanitize(String)}：一次性文本（同步 answer、缓存答案、兜底返回值）；</li>
 *   <li>{@link Streaming}：SSE 逐 token 流。带"标识符前缀"holdback——只把缓冲区尾部
 *       可能补全成标识符的那一小段压住，中文正文照常即时放出，因此 TTFT 不受影响；
 *       跨 chunk 拆开的标识符（{@code apply} + {@code Refund}）也会在拼齐时被替换。</li>
 * </ul>
 *
 * <p>匹配是**大小写敏感 + ASCII 词边界**的：注册标识符就是模型 schema 里的驼峰原名；中文里的
 * 「订单号」「收件人姓名」等正常说法不是标识符，原样通过。替换只作用于标识符本身，不删句子、
 * 不动 {@link ComposeHandler#dropPolicyOrderAsk} 的作用面。
 */
final class AnswerEgressGuard {

    /** 命中计数：一个标识符被替换时 +1；{@code outlet} 标出是哪条出站口（answer / sse_token / cache_reuse / slot_resume）。 */
    static final String METRIC = "shoppilot_answer_egress_guard_total";

    static final String TOOL_PLACEHOLDER = "相关操作";
    static final String PARAM_PLACEHOLDER = "相关字段";

    private static final List<String> TOOL_IDENTIFIERS = deriveToolIdentifiers();
    private static final List<String> PARAM_IDENTIFIERS = deriveParameterIdentifiers();
    /** 最长优先，保证 {@code receiverPhone} 不会被更短的 {@code receiver} 之类抢先命中（当前无此例，规则先立着）。 */
    private static final List<String> IDENTIFIERS = mergedLongestFirst();
    private static final Set<String> TOOL_IDENTIFIER_SET = Set.copyOf(TOOL_IDENTIFIERS);
    private static final int MAX_IDENTIFIER_LENGTH =
            IDENTIFIERS.stream().mapToInt(String::length).max().orElse(0);

    private AnswerEgressGuard() {
    }

    /** 一次替换的结果：改写后的文本 + 命中的标识符个数（0 表示原样）。 */
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

    static Result sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text, 0);
        }
        StringBuilder out = new StringBuilder(text.length());
        int hits = 0;
        int index = 0;
        while (index < text.length()) {
            String identifier = matchAt(text, index);
            if (identifier != null) {
                out.append(placeholder(identifier));
                index += identifier.length();
                hits++;
            } else {
                out.append(text.charAt(index));
                index++;
            }
        }
        return new Result(out.toString(), hits);
    }

    /** 命中计数；{@code outlet} 是低基数封闭值，由调用点给出。 */
    static void count(MeterRegistry registry, String outlet, int hits) {
        if (registry != null && hits > 0) {
            registry.counter(METRIC, "outlet", outlet).increment(hits);
        }
    }

    // ── 流式 ──────────────────────────────────────────────────────────────

    /**
     * 逐 token 过滤器。{@link #accept} 返回可以立即送出的已过滤文本（可能为空串），
     * 流结束时必须调用 {@link #flush} 把 holdback 尾部放出来。
     */
    static final class Streaming {

        private final StringBuilder buffer = new StringBuilder();
        private int hits;

        String accept(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            buffer.append(delta);
            return drain(false);
        }

        String flush() {
            return drain(true);
        }

        int hits() {
            return hits;
        }

        boolean hit() {
            return hits > 0;
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
                    out.append(placeholder(identifier));
                    index += identifier.length();
                    hits++;
                } else {
                    out.append(buffer.charAt(index));
                    index++;
                }
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

    private static String placeholder(String identifier) {
        return TOOL_IDENTIFIER_SET.contains(identifier) ? TOOL_PLACEHOLDER : PARAM_PLACEHOLDER;
    }

    private static boolean isIdentifierChar(char c) {
        return c < 0x80 && (Character.isLetterOrDigit(c) || c == '_');
    }
}
