package com.shoppilot.bizmock.service;

/**
 * 工单 payload 的极简拼装（round23 票 69 / ADR 0055）。
 *
 * <p>payload 只承载「有上游记录时指回上游」的那几个 id，所以刻意不进 JSON 库：
 * 领域层不引序列化框架，这个类就是全部。值来自内部 id 与渠道字段，转义只处理引号与反斜杠——
 * 遇到需要更严格转义的输入，正确做法是把它变成一列（refunds.ticket_id 就是这么处理的）。
 */
final class WorkItemPayload {

    private WorkItemPayload() {
    }

    /** @param keyValuePairs 交替的键与值；值里的 {@code null} 会被跳过。 */
    static String of(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("payload 需要键值成对，收到 " + keyValuePairs.length + " 个参数");
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (keyValuePairs[i + 1] == null) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append('"').append(escape(keyValuePairs[i])).append("\":\"")
                    .append(escape(keyValuePairs[i + 1])).append('"');
        }
        return json.append('}').toString();
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
