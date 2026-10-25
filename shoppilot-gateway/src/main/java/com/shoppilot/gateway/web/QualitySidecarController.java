package com.shoppilot.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppilot.gateway.config.GatewayProperties;
import com.shoppilot.gateway.config.OpsAccess;
import com.shoppilot.gateway.quality.QualitySidecarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读编排质量 sidecar（ADR 0072 H4）。
 *
 * <p>它只做报表：不改路由、不处理工单、不调用模型。运维凭证与既有 `/ops/**` 面一致。
 */
@RestController
@RequestMapping("/api/v1/support/ops")
public class QualitySidecarController {

    private final QualitySidecarService service;
    private final GatewayProperties properties;
    private final ObjectMapper mapper;
    private final ApiErrorWriter errors;

    public QualitySidecarController(QualitySidecarService service, GatewayProperties properties,
                                    ObjectMapper mapper, ApiErrorWriter errors) {
        this.service = service;
        this.properties = properties;
        this.mapper = mapper;
        this.errors = errors;
    }

    @GetMapping("/quality/report")
    public ResponseEntity<String> report(
            @RequestHeader(value = "X-Ops-Token", required = false) String opsToken) {
        OpsAccess access = OpsAccess.evaluate(properties.ops().enabled(), properties.ops().token(), opsToken);
        if (!access.allowed()) {
            return errors.entity(org.springframework.http.HttpStatus.FORBIDDEN.value(),
                    access.code(), access.message());
        }
        try {
            return ResponseEntity.ok(mapper.writeValueAsString(service.report()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return errors.entity(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    ApiError.INTERNAL_ERROR, "quality report 序列化失败");
        }
    }
}
