package com.shoppilot.gateway.orchestration;

import java.util.Map;

/** Handoff 时必须带走的未完成动作状态；空 pending 用 {@link #none()}，不用 null 语义。 */
public record PendingState(boolean pending, String tool, Map<String, Object> arguments, int slotAskCount) {

    public PendingState {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static PendingState none() {
        return new PendingState(false, null, Map.of(), 0);
    }
}
