package com.softcatch.smart.auth;

import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.auth.dto.response.LoginResponse;
import com.softcatch.smart.auth.dto.response.SignupResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

  private final AuthService authService;

  AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/api/v1/auth/signup")
  ResponseEntity<ApiResponse<SignupResponse>> signup(@RequestBody SignupRequest request) {
    Long memberId = authService.signup(request.loginId(), request.password(), request.name());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(new SignupResponse(memberId)));
  }

  @PostMapping("/api/v1/auth/login")
  ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
    return ApiResponse.ok(
        new LoginResponse(authService.login(request.loginId(), request.password())));
  }
}
