package com.shoppilot.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 网关自产错误的统一出口（ADR 0028）。
 *
 * <p>只管两件事：Bean Validation 挡下来的入参，以及没人接的运行时异常。边界刻意收窄——
 * 只接 {@link RuntimeException}，不接 {@code Exception}。Spring MVC 自己那批标准异常
 * （405 / 404 / 缺参数 / 请求体读不出来）全是 {@code ServletException} 的后代，
 * 一旦写了 {@code Exception.class} 就会把它们一并吸进来判成 500，那是改 status code，
 * 而本票第二条勾明写「status code 一律保持现状，一个都不改」。
 *
 * <p>代理透传回来的下游体不经此处，逐字节原样出（ADR 0028 的取舍：谁拒的要分得清）。
 */
@RestControllerAdvice
public class GatewayErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ApiErrorWriter writer;

    public GatewayErrorHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    /** 校验文案第一次有地方可显：以前一具裸 Spring 错误体，前端只能显示「请求被拒绝：400」。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> onInvalidRequest(MethodArgumentNotValidException rejected) {
        String message = rejected.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + "：" + hint(error))
                .collect(Collectors.joining("；"));
        return writer.entity(400, ApiError.VALIDATION_FAILED, message.isEmpty() ? "请求参数不合法" : message);
    }

    /** 没人接的异常是缺陷：状态码仍按现状 500，只是形状收进同一套 code / message / traceId。 */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> onUnexpected(RuntimeException failure, HttpServletRequest request) {
        log.error("网关内部异常 uri={}", request.getRequestURI(), failure);
        return writer.entity(500, ApiError.INTERNAL_ERROR, "服务内部错误，详情已按链路号记入日志");
    }

    /** 约束自己写了文案就用它，没写才退回默认句；两条路都比裸字段名好读。 */
    private static String hint(FieldError error) {
        String custom = error.getDefaultMessage();
        return custom == null || custom.isBlank() ? "取值不合法" : custom.trim();
    }
}
