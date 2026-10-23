package com.softcatch.smart.admin;

import com.softcatch.smart.admin.dto.request.AiBlockRequest;
import com.softcatch.smart.admin.dto.response.AiBlockResponse;
import com.softcatch.smart.admin.dto.response.AiUsageResponse;
import com.softcatch.smart.common.ApiResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminController {

  private final AdminService adminService;

  AdminController(AdminService adminService) {
    this.adminService = adminService;
  }

  @GetMapping("/api/v1/admin/ai-usage")
  ApiResponse<AiUsageResponse> aiUsage(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return ApiResponse.ok(adminService.aiUsage(Long.valueOf(jwt.getSubject()), date));
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
