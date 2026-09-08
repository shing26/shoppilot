package com.shoppilot.gateway.llm;

/** 模型侧失败的结构化表达，供状态机映射到降级原因（ADR 0009）。 */
public class LlmException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        UNAVAILABLE,
        BUDGET_EXCEEDED
    }

    private final Kind kind;

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static LlmException timeout(String message, Throwable cause) {
        return new LlmException(Kind.TIMEOUT, message, cause);
    }

    public static LlmException unavailable(String message, Throwable cause) {
        return new LlmException(Kind.UNAVAILABLE, message, cause);
    }

    public static LlmException budgetExceeded(long used, long budget) {
        return new LlmException(Kind.BUDGET_EXCEEDED,
                "今日 token 预算已用尽 " + used + "/" + budget, null);
    }

    public Kind kind() {
        return kind;
    }
}
