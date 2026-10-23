package com.softcatch.smart.notification;

import com.softcatch.smart.common.ApiResponse;
import com.softcatch.smart.notification.dto.response.NotificationListResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {

  private final NotificationService notificationService;

  NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping("/api/v1/notifications")
  ApiResponse<NotificationListResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long cursor,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(notificationService.list(Long.valueOf(jwt.getSubject()), cursor, size));
  }
}
