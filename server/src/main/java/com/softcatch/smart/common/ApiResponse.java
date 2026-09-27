package com.softcatch.smart.common;

// 모든 응답 형식: {success, data, error:{code, message}}
public record ApiResponse<T>(boolean success, T data, Error error) {

  public record Error(String code, String message) {}

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> fail(ErrorCode code) {
    return new ApiResponse<>(false, null, new Error(code.name(), code.message));
  }
}
