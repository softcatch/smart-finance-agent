package com.softcatch.smart.account;

import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

  record AccountResponse(Long accountId, String accountNumber, Long balance, boolean primary) {}

  record PrimaryResponse(Long accountId, boolean primary) {}

  record ChargeRequest(Long amount) {}

  record ChargeResponse(Long accountId, Long balance) {}

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
                    account.getId(),
                    account.getAccountNumber(),
                    account.getBalance(),
                    account.isPrimary())));
  }

  @PatchMapping("/api/v1/accounts/{accountId}/primary")
  ResponseEntity<ApiResponse<PrimaryResponse>> changePrimary(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long accountId) {
    Account account = accountService.changePrimary(Long.valueOf(jwt.getSubject()), accountId);
    return ResponseEntity.ok(
        ApiResponse.ok(new PrimaryResponse(account.getId(), account.isPrimary())));
  }

  @PostMapping("/api/v1/accounts/{accountId}/charge")
  ResponseEntity<ApiResponse<ChargeResponse>> charge(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long accountId,
      @RequestBody ChargeRequest request) {
    Account account =
        accountService.charge(Long.valueOf(jwt.getSubject()), accountId, request.amount());
    return ResponseEntity.ok(
        ApiResponse.ok(new ChargeResponse(account.getId(), account.getBalance())));
  }
}
