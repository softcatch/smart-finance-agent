package com.softcatch.smart.alias;

import com.softcatch.smart.alias.dto.request.AliasRequest;
import com.softcatch.smart.alias.dto.response.AliasListResponse;
import com.softcatch.smart.alias.dto.response.AliasResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AliasController {

  private final AliasService aliasService;

  AliasController(AliasService aliasService) {
    this.aliasService = aliasService;
  }

  @PostMapping("/api/v1/aliases")
  ResponseEntity<ApiResponse<AliasResponse>> register(
      @AuthenticationPrincipal Jwt jwt, @RequestBody AliasRequest request) {
    AliasResponse response =
        aliasService.register(
            Long.valueOf(jwt.getSubject()), request.alias(), request.accountNumber());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
  }

  @GetMapping("/api/v1/aliases")
  ApiResponse<AliasListResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(new AliasListResponse(aliasService.list(Long.valueOf(jwt.getSubject()))));
  }
}
