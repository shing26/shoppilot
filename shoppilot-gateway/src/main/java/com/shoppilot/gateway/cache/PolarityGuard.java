package com.shoppilot.gateway.cache;

/**
 * 同义桶内的极性守卫（ticket 17 实测驱动，补 ADR 0003 的缺口）。
 *
 * <p>ADR 0003 把语义漂移转化为跨意图隔离问题，但 ticket 17 的标定实测证明这个转化不完整：
 * 「这个能退吗」与「这个是不是不能退」同属 POLICY_RETURN，余弦 0.9682，已经越过 0.95 工作点。
 * 同一个意图桶内的反义提问，意图分区拦不住，阈值也拦不住（调高阈值会让召回直接归零）。
 *
 * <p>所以这里加一道确定性的词法守卫：L2 命中之后、复用答案之前，比较缓存问法与当前问法的
 * 否定极性，极性不一致就拒绝复用。守卫只做词法判定，不做语义理解——它宁可多打穿一次模型，
 * 也不能把「能退」的答案发给问「是不是不能退」的人。
 *
 * <p>三条判定：含否定标记为 NEGATED；正反问（"是不是""能不能"）说话人未表态，记为 NEUTRAL，
 * 与任何极性都不冲突；句末的"不/没"（"可以退货不""发货了没"）是省略问句的语气词，不计为否定。
 *
 * <p>代价是召回率：NEGATED 与 AFFIRMING 两桶永不互用。这个代价在本项目里不可测量，
 * 因为 0.95 工作点上 L2 召回本来就是 0（见 {@code docs/threshold-calibration.md}）。
 */
public final class PolarityGuard {

    /** 中文否定标记。归一化后已去标点与空白，这里只按字符存在性判定。 */
    private static final String NEGATION_MARKERS = "不没未非别勿毋";

    /** 正反问（X不X）里"不"两侧同字，说话人没有表态。 */
    private static final int A_NOT_A_WINDOW = 1;

    /** 句末的"不/没"是疑问语气词（"可以退货不"），不是否定。 */
    private static final int FINAL_PARTICLE_WINDOW = 1;

    public enum Polarity {
        AFFIRMING, NEGATED, NEUTRAL
    }

    private PolarityGuard() {
    }

    /**
     * 判定问法的否定极性。
     *
     * <p>无标记为 AFFIRMING；出现有效否定标记为 NEGATED；只有正反问时为 NEUTRAL。
     */
    public static Polarity polarity(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return Polarity.AFFIRMING;
        }
        boolean sawNeutral = false;
        for (int index = 0; index < normalizedQuery.length(); index++) {
            if (NEGATION_MARKERS.indexOf(normalizedQuery.charAt(index)) < 0) {
                continue;
            }
            if (isANotA(normalizedQuery, index)) {
                sawNeutral = true;
                index += 1;
                continue;
            }
            if (index >= normalizedQuery.length() - FINAL_PARTICLE_WINDOW) {
                sawNeutral = true;
                continue;
            }
            return Polarity.NEGATED;
        }
        return sawNeutral ? Polarity.NEUTRAL : Polarity.AFFIRMING;
    }

    /**
     * @return null 表示可以复用；非 null 是被拒绝的原因，用于打点与日志
     */
    public static String blocked(String incomingNormalizedQuery, String cachedNormalizedQuery) {
        if (cachedNormalizedQuery == null || cachedNormalizedQuery.isEmpty()) {
            // 历史条目没带问法，无从校验：fail-closed，宁可回源
            return "polarity-unknown";
        }
        Polarity incoming = polarity(incomingNormalizedQuery);
        Polarity cached = polarity(cachedNormalizedQuery);
        if (incoming == Polarity.NEUTRAL || cached == Polarity.NEUTRAL) {
            return null;
        }
        return incoming == cached ? null : "polarity-conflict";
    }

    private static boolean isANotA(String text, int markerIndex) {
        int left = markerIndex - A_NOT_A_WINDOW;
        int right = markerIndex + A_NOT_A_WINDOW;
        return left >= 0 && right < text.length() && text.charAt(left) == text.charAt(right);
    }
}
