package com.shoppilot.bizmock.fault;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 故障注入（ADR 0002）：让"外部依赖抖动"可以被现场演示，而不是文档里的一句话。
 */
@Component
public class FaultInjector {

    private volatile long delayMs;
    private volatile double failRate;

    public FaultInjector() {
        this.delayMs = 0;
        this.failRate = 0.0d;
    }

    /** 阻塞当前虚拟线程；虚拟线程下 park 不占用载体线程。 */
    public void applyDelay() {
        long current = delayMs;
        if (current <= 0) {
            return;
        }
        try {
            Thread.sleep(current);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean shouldFail() {
        double current = failRate;
        return current > 0 && ThreadLocalRandom.current().nextDouble() < current;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public double getFailRate() {
        return failRate;
    }

    public void configure(Long delayMs, Double failRate) {
        if (delayMs != null) {
            this.delayMs = Math.max(0, delayMs);
        }
        if (failRate != null) {
            this.failRate = Math.min(1.0d, Math.max(0.0d, failRate));
        }
    }
}
