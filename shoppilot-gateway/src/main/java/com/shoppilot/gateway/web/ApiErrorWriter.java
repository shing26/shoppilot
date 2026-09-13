package com.shoppilot.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.identity.RequestTrace;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 网关自产错误的唯一落笔处（ADR 0028）。
 *
 * <p>{@code @RestControllerAdvice} 与 {@code AuthFilter} 共用这一个 writer：filter 跑在
 * DispatcherServlet 之前，advice 天生看不见它那一处，两边各写一遍就会长成两种形状。
 * 方向上 identity 依赖 web 是破例，但「同一件事只有一个 writer」是 ADR 0028 明写的取舍，
 * 比让两处各自拼字符串划算。
 *
 * <p>message 一律经 Jackson 序列化。原先那几处是 {@code "{\"error\":\"" + 变量 + "\"}"} 的拼法，
 * 变量里出现引号或换行就产出一具非法 JSON——错误出口自己先把客户端绊倒。
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper mapper;

    public ApiErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 三个字符串字段序列化不会失败；真失败时也必须回一具能解析的形状，不能让错误出口自己先失态。 */
    public String body(String code, String message) {
        try {
            return mapper.writeValueAsString(new ApiError(code, message, RequestTrace.traceId()));
        } catch (JsonProcessingException impossible) {
            return "{\"code\":\"" + ApiError.INTERNAL_ERROR + "\",\"message\":null,\"traceId\":null}";
        }
    }

    /** advice 与 controller 用的出口：status code 由调用点给，本类不改码（ADR 0028：改码是另一笔契约变更）。 */
    public ResponseEntity<String> entity(int status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(code, message));
    }

    /**
     * {@code ResponseEntityExceptionHandler} 那两个钩子的签名要的是 {@code ResponseEntity<Object>}。
     * 同一支笔，只是泛型对不上；这里只做一次无害的形参化，落笔仍然只有 {@link #entity} 一处。
     */
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> entityForAdvice(int status, String code, String message) {
        return (ResponseEntity<Object>) (ResponseEntity<?>) entity(status, code, message);
    }

    /** filter 用的出口：直写 response，形状与 {@link #entity} 完全一致。 */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(body(code, message));
    }
}
