package com.softcatch.smart.account.dto.response;

public record AccountResponse(
    Long accountId, String accountNumber, Long balance, boolean primary) {}
