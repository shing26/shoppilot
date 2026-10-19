package com.shoppilot.gateway.triage;

import com.shoppilot.tool.Intent;

/**
 * 意图判定结果。
 *
 * @param layer 定案于哪一级：T0 规则 / T1 质心 / T2 模型 / NONE 未定案
 * @param entityFound 是否扫到订单号、运单号或手机号——命中即 dynamic，绝不进缓存（ADR 0003）
 */
public record TriageResult(Intent intent, boolean cacheAdmissible, String layer, boolean entityFound, double confidence,
                           boolean policyHuman) {

    /** 兼容旧构造：policyHuman 默认 false（显式转人工 / 普通判定）。 */
    public TriageResult(Intent intent, boolean cacheAdmissible, String layer, boolean entityFound, double confidence) {
        this(intent, cacheAdmissible, layer, entityFound, confidence, false);
    }

    public static TriageResult undecided() {
        return new TriageResult(Intent.UNKNOWN, false, "NONE", false, 0.0d);
    }

    public static TriageResult dynamic(Intent intent, String layer, boolean entityFound) {
        return new TriageResult(intent, false, layer, entityFound, 1.0d);
    }

    public static TriageResult policy(Intent intent, String layer, double confidence) {
        return new TriageResult(intent, true, layer, false, confidence);
    }

    /**
     * R7：门店细则要求「看照片不能定责」的转人工。意图仍是 {@link Intent#ESCALATE}（对外出口不变），
     * 但落单原因用 {@code INTENT_UNRESOLVED}（系统无法自动判定），而不是 {@code USER_REQUESTED}
     * （买家没有喊人工，是规则判定的）。
     */
    public static TriageResult policyEscalation(String layer) {
        return new TriageResult(Intent.ESCALATE, false, layer, false, 1.0d, true);
    }
}
