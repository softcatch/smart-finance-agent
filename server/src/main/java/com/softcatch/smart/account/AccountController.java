package com.softcatch.smart.account;

import com.softcatch.smart.account.dto.request.ChargeRequest;
import com.softcatch.smart.account.dto.response.AccountListResponse;
import com.softcatch.smart.account.dto.response.AccountResponse;
import com.softcatch.smart.account.dto.response.ChargeResponse;
import com.softcatch.smart.account.dto.response.PrimaryResponse;
import com.softcatch.smart.common.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

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

  @GetMapping("/api/v1/accounts")
  ApiResponse<AccountListResponse> list(@AuthenticationPrincipal Jwt jwt) {
    List<AccountResponse> accounts =
        accountService.list(Long.valueOf(jwt.getSubject())).stream()
            .map(
                account ->
                    new AccountResponse(
                        account.getId(),
                        account.getAccountNumber(),
                        account.getBalance(),
                        account.isPrimary()))
            .toList();
    return ApiResponse.ok(new AccountListResponse(accounts));
  }
}
