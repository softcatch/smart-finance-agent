package com.softcatch.smart.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  AUTH_INVALID(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다"),
  DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다"),
  ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다"),
  ACCOUNT_NOT_OWNED(HttpStatus.FORBIDDEN, "본인 계좌가 아닙니다"),
  ACCOUNT_PASSWORD_INVALID(HttpStatus.FORBIDDEN, "계좌 비밀번호가 맞지 않습니다"),
  INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "충전 금액은 0보다 커야 합니다"),
  ALIAS_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 별칭입니다"),
  TRANSFER_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "송금 요청을 찾을 수 없습니다"),
  TRANSFER_REQUEST_EXPIRED(HttpStatus.GONE, "송금 요청이 만료되었습니다"),
  TRANSFER_REQUEST_ALREADY_USED(HttpStatus.CONFLICT, "이미 확정된 송금 요청입니다"),
  INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "잔액이 부족합니다"),
  DUPLICATE_ALIAS(HttpStatus.CONFLICT, "이미 등록된 별칭입니다"),
  THREAD_NOT_FOUND(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다"),
  DETAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "상세 보기 대상을 찾을 수 없습니다"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),
  AI_SERVER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없습니다"),
  ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 사용할 수 있습니다"),
  MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다");

  final HttpStatus status;
  final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }
}
