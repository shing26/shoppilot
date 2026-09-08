package com.shoppilot.tool.view;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogisticsNodeView(
        int seq,
        String nodeCode,
        String description,
        Instant occurredAt) {
}
