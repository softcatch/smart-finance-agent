package com.softcatch.smart.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// ApiException → 에러 코드의 HTTP 상태 + 공통 응답
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler
  ResponseEntity<ApiResponse<Void>> handle(ApiException e) {
    return ResponseEntity.status(e.code.status).body(ApiResponse.fail(e.code));
  }
}
