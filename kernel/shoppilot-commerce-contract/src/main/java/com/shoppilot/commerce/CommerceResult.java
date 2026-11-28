package com.shoppilot.commerce;

import java.util.Objects;

/**
 * 端口返回值。用类型化失败代替异常，方便内核决定“重试、转人工还是直接拒绝”。
 */
public sealed interface CommerceResult<T> permits CommerceResult.Ok, CommerceResult.Err {

    record Ok<T>(T value) implements CommerceResult<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Err<T>(CommerceError error, String message) implements CommerceResult<T> {
        public Err {
            Objects.requireNonNull(error, "error");
            message = message == null ? "" : message;
        }
    }

    static <T> CommerceResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> CommerceResult<T> err(CommerceError error, String message) {
        return new Err<>(error, message);
    }

    default boolean isOk() {
        return this instanceof Ok<?>;
    }
}
