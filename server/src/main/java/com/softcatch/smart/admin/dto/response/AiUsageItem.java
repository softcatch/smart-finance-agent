package com.softcatch.smart.admin.dto.response;

public record AiUsageItem(
    Long memberId,
    String loginId,
    int calls,
    int fallbackCalls,
    long inputTokens,
    long outputTokens,
    double costUsd,
    boolean blocked) {}
