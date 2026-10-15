package com.softcatch.smart.transfer;

import com.softcatch.smart.common.ApiResponse;
import com.softcatch.smart.transfer.dto.request.TransferConfirmRequest;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import com.softcatch.smart.transfer.dto.response.TransferConfirmResponse;
import com.softcatch.smart.transfer.dto.response.TransferCreateResponse;
import com.softcatch.smart.transfer.dto.response.TransferHistoryListResponse;
import com.softcatch.smart.transfer.dto.response.TransferSummaryResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TransferController {

  private final TransferService transferService;

  TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  @PostMapping("/internal/transfer-requests")
  ResponseEntity<ApiResponse<TransferCreateResponse>> createRequest(
      @AuthenticationPrincipal Jwt jwt, @RequestBody TransferCreateRequest request) {
    TransferCreateResponse response =
        transferService.createRequest(Long.valueOf(jwt.getSubject()), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @PostMapping("/api/v1/transfer-requests/{requestId}/confirm")
  ApiResponse<TransferConfirmResponse> confirm(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String requestId,
      @RequestBody TransferConfirmRequest request) {
    return ApiResponse.ok(
        transferService.confirm(Long.valueOf(jwt.getSubject()), requestId, request.password()));
  }

  @GetMapping("/api/v1/transfers")
  ApiResponse<TransferHistoryListResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long cursor,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(transferService.list(Long.valueOf(jwt.getSubject()), cursor, size));
  }

  // FastAPI(LangGraph 도구)용 — 사용자 JWT로 본인 내역만 본다. 합계는 Spring이 계산한다(NFR-002).
  @GetMapping("/internal/transfers")
  ApiResponse<TransferSummaryResponse> summary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Long cursor,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(
        transferService.summary(Long.valueOf(jwt.getSubject()), from, to, cursor, size));
  }
}
