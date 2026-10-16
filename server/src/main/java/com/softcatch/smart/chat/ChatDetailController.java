package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatDetailResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatDetailController {

  private final ChatDetailService chatDetailService;

  ChatDetailController(ChatDetailService chatDetailService) {
    this.chatDetailService = chatDetailService;
  }

  @GetMapping("/api/v1/chat/details/{detailId}")
  ApiResponse<ChatDetailResponse> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String detailId) {
    return ApiResponse.ok(
        chatDetailService.get(Long.valueOf(jwt.getSubject()), jwt.getTokenValue(), detailId));
  }
}
