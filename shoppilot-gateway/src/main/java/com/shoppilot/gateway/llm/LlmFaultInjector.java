package com.shoppilot.gateway.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 模型侧故障注入（ticket 14）。
 *
 * <p>降级链路里最难自证的部分是"这三种 LLM 降级不是写在纸上"。真等 Ollama 超时不可控，
 * 把端点指到黑洞又要重启进程，所以给模型调用一个运行期开关，让脚本能稳定复现。
 *
 * <p>只有运维代理端点开着才生效（见 {@code LlmGateway}），perf/prod 关掉 ops 即彻底失效。
 */
@Component
public class LlmFaultInjector {

    private static final Logger log = LoggerFactory.getLogger(LlmFaultInjector.class);

    public static final List<String> MODES = List.of("none", "timeout", "unavailable", "budget");

    private volatile String mode = "none";

    public String mode() {
        return mode;
    }

    public void configure(String requested) {
        String normalized = requested == null ? "none" : requested.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(normalized)) {
            throw new IllegalArgumentException("未知注入模式 " + requested + "，可选 " + MODES);
        }
        this.mode = normalized;
        log.warn("模型故障注入已切换为 {}", normalized);
    }

    /** 命中注入时抛结构化异常，由状态机映射到对应降级原因。 */
    public void checkOrThrow() {
        switch (mode) {
            case "timeout" -> throw LlmException.timeout("注入的模型超时", null);
            case "unavailable" -> throw LlmException.unavailable("注入的模型不可用", null);
            case "budget" -> throw LlmException.budgetExceeded(1L, 1L);
            default -> {
            }
        }
    }
}
