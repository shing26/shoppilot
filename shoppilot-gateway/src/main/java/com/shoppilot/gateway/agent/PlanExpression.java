package com.shoppilot.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划步骤参数里的前序依赖表达式（ADR 0036）：只允许 {@code {steps[i].result.<field>}} 单一形态。
 *
 * <p>表达式只支持整值占位 + 简单字段名，正则可校验——防注入面收敛：把"取值路径"的自由交给模型，
 * 等于把归属校验的输入交给模型（ADR 0036 的否决项）。任何带花括号但不合语法的参数一律整条计划拒收
 * （fail-closed），不执行、不猜、不留半截执行状态。
 */
final class PlanExpression {

    private static final Pattern ALLOWED =
            Pattern.compile("^\\{steps\\[(\\d+)]\\.result\\.([A-Za-z][A-Za-z0-9_]*)\\}$");

    private PlanExpression() {
    }

    /**
     * @param arguments           解析后的字面参数（被拒时为 null）
     * @param rejectedExpression  不合法的表达式原文（通过时为 null）
     */
    record Resolution(Map<String, Object> arguments, String rejectedExpression) {

        boolean rejected() {
            return rejectedExpression != null;
        }
    }

    /**
     * 解析一步的参数：引用必须指向**已执行**的前步结果里的简单标量字段。
     *
     * @param executedStepResults 按执行顺序保存的前步结果 JSON
     */
    static Resolution resolve(Map<String, Object> arguments, List<String> executedStepResults, ObjectMapper mapper) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (arguments == null) {
            return new Resolution(resolved, null);
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String text) || (text.indexOf('{') < 0 && text.indexOf('}') < 0)) {
                resolved.put(entry.getKey(), value);
                continue;
            }
            Matcher match = ALLOWED.matcher(text);
            if (!match.matches()) {
                return new Resolution(null, text);
            }
            int index = Integer.parseInt(match.group(1));
            if (index >= executedStepResults.size()) {
                // 只能引用已执行的前步；引用未来步或越界索引同样按不合语法拒收
                return new Resolution(null, text);
            }
            JsonNode field = readField(executedStepResults.get(index), match.group(2), mapper);
            if (field == null || field.isContainerNode() || field.isNull()) {
                // 字段不存在或不是标量：计划的前提不成立，整条拒收
                return new Resolution(null, text);
            }
            resolved.put(entry.getKey(), field.isTextual() ? field.asText() : field.asText());
        }
        return new Resolution(resolved, null);
    }

    private static JsonNode readField(String stepResultJson, String field, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(stepResultJson);
            return root.path(field).isMissingNode() ? null : root.path(field);
        } catch (Exception unparsable) {
            return null;
        }
    }
}
