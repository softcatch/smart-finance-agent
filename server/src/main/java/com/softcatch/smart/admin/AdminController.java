package com.softcatch.smart.admin;

import com.softcatch.smart.admin.dto.request.AiBlockRequest;
import com.softcatch.smart.admin.dto.response.AiBlockResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminController {

  private final AdminService adminService;

  AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  @PatchMapping("/api/v1/admin/members/{memberId}/ai-block")
  ApiResponse<AiBlockResponse> block(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long memberId,
      @RequestBody AiBlockRequest request) {
    return ApiResponse.ok(
        adminService.block(
            Long.valueOf(jwt.getSubject()), memberId, request.blocked(), request.reason()));
  }
}
