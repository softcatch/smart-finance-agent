package com.softcatch.smart.common;

public class ApiException extends RuntimeException {

  final ErrorCode code;

  public ApiException(ErrorCode code) {
    super(code.message);
    this.code = code;
  }
}
