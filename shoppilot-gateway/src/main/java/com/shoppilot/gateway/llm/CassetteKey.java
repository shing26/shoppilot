package com.shoppilot.gateway.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 录放门的 key（ADR 0083）：{@code sha256(model + promptVersion + 规范化 messages + tools + 采样参数)}。
 *
 * <p>key 必须对**同一请求**稳定，对**任何提示词/工具 schema/采样参数变化**敏感：
 * 改了 prompt 或工具描述，旧卡自然找不到，门禁红——这正是"回放守编排与提示词回归"的机制。
 * 规范化不是把 JSON 原样哈希：Map 的遍历顺序、字段顺序都会让同一个语义请求得到不同 key，
 * 所以对象键一律排序，数组保持下标顺序。
 *
 * <p>采样参数用**实际生效值**：请求里 temperature &lt;= 0 时客户端会回落到配置值（与
 * {@code OpenAiCompatibleLlmClient} 同一条判据），key 里也必须是回落后的那个数，否则录制与
 * 回放会各算一个 key。
 */
public final class CassetteKey {

    private CassetteKey() {
    }

    public static String of(ObjectMapper mapper, String model, String promptVersion,
                            LlmTypes.Request request, int maxTokens, double fallbackTemperature) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("promptVersion", promptVersion);
        root.put("maxTokens", maxTokens);
        root.put("temperature", effectiveTemperature(request, fallbackTemperature));
        root.set("messages", mapper.valueToTree(request.messages() == null ? List.of() : request.messages()));
        root.set("tools", mapper.valueToTree(request.tools() == null ? List.of() : request.tools()));
        return sha256(canonical(mapper, root));
    }

    static double effectiveTemperature(LlmTypes.Request request, double fallbackTemperature) {
        return request.temperature() <= 0 ? fallbackTemperature : request.temperature();
    }

    /** 目录段只留文件名安全字符：{@code qwen2.5:3b} 在 Windows 上不能直接当目录名。 */
    public static String modelSegment(String model) {
        if (model == null || model.isBlank()) {
            return "unknown";
        }
        return model.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    static String canonical(ObjectMapper mapper, JsonNode node) {
        StringBuilder out = new StringBuilder();
        append(mapper, node, out);
        return out.toString();
    }

    private static void append(ObjectMapper mapper, JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(mapper, names.get(i))).append(':');
                append(mapper, node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(mapper, node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            out.append(quote(mapper, node.textValue()));
        } else {
            // number / boolean 保持字面量；其它标量（二进制、POJO）退化为其 JSON 文本。
            out.append(node.isNumber() || node.isBoolean() ? node.asText() : node.toString());
        }
    }

    private static String quote(ObjectMapper mapper, String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception unserializable) {
            return "\"\"";
        }
    }

    static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
