package com.softcatch.smart.transfer;

import com.softcatch.smart.common.ApiResponse;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import com.softcatch.smart.transfer.dto.response.TransferCreateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
