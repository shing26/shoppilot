package com.shoppilot.tool.identity;

/** 账号启停请求（round56 票 224 / ADR 0086）。{@code active=false} 即停用。 */
public record AccountStatusRequest(boolean active) {
}
