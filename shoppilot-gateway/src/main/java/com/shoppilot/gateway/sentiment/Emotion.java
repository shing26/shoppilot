package com.shoppilot.gateway.sentiment;

/** 四分类 + 门级不确定（ADR 0034）。UNCERTAIN 是情绪门的 fail-open 输出，不是第五种情绪。 */
public enum Emotion {
    CALM,
    DISSATISFIED,
    ANGRY,
    URGENT,
    UNCERTAIN;

    /** 升级判据：ANGRY 恒升级；URGENT 需置信度达标（ADR 0034）。 */
    public boolean escalates(double confidence) {
        return this == ANGRY || (this == URGENT && confidence >= 0.8);
    }
}
