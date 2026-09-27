package com.softcatch.smart.account;

import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

  record AccountResponse(Long accountId, String accountNumber, boolean primary) {}

  private final AccountService accountService;

  AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/api/v1/accounts")
  ResponseEntity<ApiResponse<AccountResponse>> open(
      @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String idempotencyKey) {
    Account account = accountService.open(Long.valueOf(jwt.getSubject()), idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.ok(
                new AccountResponse(
                    account.getId(), account.getAccountNumber(), account.isPrimary())));
  }
}
