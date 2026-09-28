package com.softcatch.smart.transfer.dto.request;

public record TransferCreateRequest(String recipientAccountNumber, String alias, Long amount) {}
