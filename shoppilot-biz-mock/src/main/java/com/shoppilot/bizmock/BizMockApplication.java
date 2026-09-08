package com.shoppilot.bizmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock 业务中台。与网关是两个独立进程，工具调用走真 HTTP 边界（ADR 0002）。
 */
@SpringBootApplication
public class BizMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizMockApplication.class, args);
    }
}
