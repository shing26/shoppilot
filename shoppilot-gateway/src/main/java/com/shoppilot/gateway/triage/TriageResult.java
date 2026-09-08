package com.shoppilot.gateway.triage;

import com.shoppilot.tool.Intent;

/**
 * 意图判定结果。
 *
 * @param layer 定案于哪一级：T0 规则 / T1 质心 / T2 模型 / NONE 未定案
 * @param entityFound 是否扫到订单号、运单号或手机号——命中即 dynamic，绝不进缓存（ADR 0003）
 */
public record TriageResult(Intent intent, boolean cacheAdmissible, String layer, boolean entityFound, double confidence) {

    public static TriageResult undecided() {
        return new TriageResult(Intent.UNKNOWN, false, "NONE", false, 0.0d);
    }

    public static TriageResult dynamic(Intent intent, String layer, boolean entityFound) {
        return new TriageResult(intent, false, layer, entityFound, 1.0d);
    }

    public static TriageResult policy(Intent intent, String layer, double confidence) {
        return new TriageResult(intent, true, layer, false, confidence);
    }
}
