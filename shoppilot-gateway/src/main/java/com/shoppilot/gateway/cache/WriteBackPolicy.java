package com.shoppilot.gateway.cache;

import com.shoppilot.tool.Intent;
import org.springframework.stereotype.Component;

/**
 * 写回资格判定（ADR 0006）。
 *
 * <p>没有这一层，缓存会慢慢积累"抱歉系统繁忙已为您转人工"和拒答话术，
 * 然后在大促里被高速复用——监控显示拦截率 85%、TP99 22ms，实际在批量复读道歉语。
 */
@Component
public class WriteBackPolicy {

    /** 低于这个长度的回答基本是"好的""嗯"一类无信息回复。 */
    private static final int MIN_ANSWER_LENGTH = 20;

    public record Request(Intent intent, String answer, boolean retrievalHit, boolean toolUsed,
                          boolean degraded, boolean error) {
    }

    public record Verdict(boolean eligible, String reason) {
    }

    public Verdict evaluate(Request request) {
        if (request.intent() == null || !request.intent().cacheAdmissible()) {
            return new Verdict(false, "intent-not-admissible");
        }
        if (request.answer() == null || request.answer().isBlank()) {
            return new Verdict(false, "empty-answer");
        }
        if (request.answer().length() < MIN_ANSWER_LENGTH) {
            return new Verdict(false, "answer-too-short");
        }
        if (!request.retrievalHit()) {
            return new Verdict(false, "no-retrieval-hit");
        }
        if (request.toolUsed()) {
            return new Verdict(false, "tool-used");
        }
        if (request.degraded()) {
            return new Verdict(false, "degraded");
        }
        if (request.error()) {
            return new Verdict(false, "error");
        }
        return new Verdict(true, "eligible");
    }
}
