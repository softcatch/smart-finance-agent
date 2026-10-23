package com.softcatch.smart.admin.dto.response;

import java.util.List;

public record AiUsageResponse(long dailyLimitTokens, List<AiUsageItem> items) {}
