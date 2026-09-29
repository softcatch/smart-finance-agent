package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatThreadResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatThreadController {

  private final ChatThreadService chatThreadService;

  ChatThreadController(ChatThreadService chatThreadService) {
    this.chatThreadService = chatThreadService;
  }

  @PostMapping("/api/v1/chat/threads")
  ResponseEntity<ApiResponse<ChatThreadResponse>> create(@AuthenticationPrincipal Jwt jwt) {
    ChatThread thread = chatThreadService.create(Long.valueOf(jwt.getSubject()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(new ChatThreadResponse(thread.getThreadId())));
  }
}
