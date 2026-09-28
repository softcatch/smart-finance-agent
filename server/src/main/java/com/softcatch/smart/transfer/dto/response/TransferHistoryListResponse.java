package com.softcatch.smart.transfer.dto.response;

import java.util.List;

public record TransferHistoryListResponse(
    List<TransferHistoryItem> items, Long nextCursor, boolean hasNext) {}
