package com.softcatch.smart.notification;

import com.softcatch.smart.common.ApiResponse;
import com.softcatch.smart.notification.dto.response.NotificationListResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class NotificationController {

  private final NotificationService notificationService;
  private final NotificationHub hub;

  NotificationController(NotificationService notificationService, NotificationHub hub) {
    this.notificationService = notificationService;
    this.hub = hub;
  }

  @GetMapping("/api/v1/notifications")
  ApiResponse<NotificationListResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) Long cursor,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(notificationService.list(Long.valueOf(jwt.getSubject()), cursor, size));
  }

  // 알림 스트림(E-15, SSE). 화면은 fetch로 연다 — EventSource는 Authorization 헤더를 못 붙인다.
  @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
    return hub.connect(Long.valueOf(jwt.getSubject()));
  }
}
