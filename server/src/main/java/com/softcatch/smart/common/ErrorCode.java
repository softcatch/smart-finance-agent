package com.softcatch.smart.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  AUTH_INVALID(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다"),
  DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다"),
  ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다"),
  ACCOUNT_NOT_OWNED(HttpStatus.FORBIDDEN, "본인 계좌가 아닙니다"),
  INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액은 0보다 커야 합니다"),
  ALIAS_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 별칭입니다");

  final HttpStatus status;
  final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }
}
