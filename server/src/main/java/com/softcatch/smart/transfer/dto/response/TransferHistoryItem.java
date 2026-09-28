package com.softcatch.smart.transfer.dto.response;

import java.time.Instant;

public record TransferHistoryItem(
    Long transferId,
    String direction,
    String counterpartName,
    String counterpartAccountNumber,
    Long amount,
    Instant createdAt) {}
