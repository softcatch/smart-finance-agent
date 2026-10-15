package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.request.ChatMessageRequest;
import com.softcatch.smart.chat.dto.response.ChatMessageResponse;
import com.softcatch.smart.chat.dto.response.ChatThreadResponse;
import com.softcatch.smart.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatThreadController {

  private final ChatThreadService chatThreadService;
  private final ChatMessageService chatMessageService;

  ChatThreadController(ChatThreadService chatThreadService, ChatMessageService chatMessageService) {
    this.chatThreadService = chatThreadService;
    this.chatMessageService = chatMessageService;
  }

  @PostMapping("/api/v1/chat/threads")
  ResponseEntity<ApiResponse<ChatThreadResponse>> create(@AuthenticationPrincipal Jwt jwt) {
    ChatThread thread = chatThreadService.create(Long.valueOf(jwt.getSubject()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(new ChatThreadResponse(thread.getThreadId())));
  }

  @PostMapping("/api/v1/chat/threads/{threadId}/messages")
  ApiResponse<ChatMessageResponse> send(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String threadId,
      @RequestBody ChatMessageRequest request) {
    return ApiResponse.ok(
        chatMessageService.send(
            Long.valueOf(jwt.getSubject()), jwt.getTokenValue(), threadId, request.message()));
  }
}
