package com.softcatch.smart.transfer.dto.response;

public record TransferCreateResponse(
    String requestId,
    String recipientName,
    String recipientAccountNumber,
    String fromAccountNumber,
    Long amount,
    String expiresAt) {}
