package com.softcatch.smart.transfer.dto.response;

public record TransferConfirmResponse(
    Long transferId,
    String recipientName,
    Long amount,
    String fromAccountNumber,
    Long balanceAfter) {}
