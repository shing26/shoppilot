package com.shoppilot.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * 网关自产错误的统一出口（ADR 0028）。
 *
 * <p>只管两类：入参没敲对，以及没人接的运行时异常。边界靠**继承** {@link ResponseEntityExceptionHandler}
 * 来划，而不是靠 catch-all：Spring MVC 自己那批入参异常里，405 那一支确实是 {@code ServletException} 的后代，
 * 「请求体读不出来」那一支（{@code HttpMessageNotReadableException}）却是 {@code NestedRuntimeException}
 * 的后代——只写一条 {@code RuntimeException} 的 catch-all 就会把它一起吸进 500，而它本来判的是 400。
 * 那是改 status code，本票第二条勾明写「status code 一律保持现状，一个都不改」。
 *
 * <p>所以状态码由父类判定，本类只把形状换成那一套三键；catch-all 仍然留着，收的是真缺陷
 * （票 23 转过来的那具裸 500 就在这一支）。类型深浅的匹配交给 Spring 的异常解析器：父类那批注册得比
 * {@code RuntimeException} 更具体，两边各走各的，不互相抢。
 *
 * <p>代理透传回来的下游体不经此处，逐字节原样出（ADR 0028 的取舍：谁拒的要分得清）。
 */
@RestControllerAdvice
public class GatewayErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ApiErrorWriter writer;

    public GatewayErrorHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    /**
     * 校验文案第一次有地方可显：以前一具裸 Spring 错误体，前端只能显示「请求被拒绝：400」。
     * 状态码用父类传进来的那一个，本类不自己写死数字。
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException rejected,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = rejected.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + "：" + hint(error))
                .collect(Collectors.joining("；"));
        return writer.entityForAdvice(status.value(), ApiError.VALIDATION_FAILED,
                message.isEmpty() ? "请求参数不合法" : message);
    }

    /**
     * 父类那批入参异常的公共落笔处：状态码保持它判定的结果，形状换成同一套三键。
     * 走到这里的都是「调用方那一单没敲对」，不是网关的缺陷。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception rejected, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return writer.entityForAdvice(status.value(), codeFor(status), reasonFor(status));
    }

    /** 4xx 统一归到既有的 {@code invalid_request}，5xx 仍是 {@code internal_error}：不新增第六种 code。 */
    private static String codeFor(HttpStatusCode status) {
        return status.is4xxClientError() ? ApiError.INVALID_REQUEST : ApiError.INTERNAL_ERROR;
    }

    /** 不把 Spring 的英文话术原样回给客户端，也不回显 path（ADR 0028 明写这两样不要）。 */
    private static String reasonFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "请求无法解析或缺少必要参数";
            case 404 -> "接口不存在";
            case 405 -> "该接口不支持这个 HTTP 方法";
            case 406 -> "该接口无法按请求要求的格式应答";
            case 415 -> "Content-Type 不被支持";
            default -> status.is4xxClientError() ? "请求不被接受" : "服务内部错误，详情已按链路号记入日志";
        };
    }

    /** 没人接的异常是缺陷：状态码仍是 500，但不再是一具连类都没有的裸错误体。 */
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
