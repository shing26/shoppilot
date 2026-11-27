package com.shoppilot.commerce;

/**
 * 端口结果的小工具。避免每个实现都写一遍 {@code instanceof} 与转型。
 */
public final class CommerceResults {

    private CommerceResults() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T unwrap(CommerceResult<T> result) {
        if (!(result instanceof CommerceResult.Ok<?> ok)) {
            throw new IllegalArgumentException("result is not Ok");
        }
        return (T) ok.value();
    }

    public static <T> CommerceResult<T> copyErr(CommerceResult<?> result) {
        if (!(result instanceof CommerceResult.Err<?> err)) {
            throw new IllegalArgumentException("result is not Err");
        }
        return CommerceResult.err(err.error(), err.message());
    }
}
