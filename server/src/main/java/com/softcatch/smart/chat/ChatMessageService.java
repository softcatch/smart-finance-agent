package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatMessageResponse;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import org.springframework.stereotype.Service;

// 트랜잭션을 걸지 않는다 — LLM을 기다리는 동안 DB 커넥션을 잡지 않기 위해서다.
@Service
class ChatMessageService {

  private final ChatThreadRepository chatThreads;
  private final ChatDetailRepository chatDetails;
  private final ChatRateLimiter rateLimiter;
  private final AiClient aiClient;

  ChatMessageService(
      ChatThreadRepository chatThreads,
      ChatDetailRepository chatDetails,
      ChatRateLimiter rateLimiter,
      AiClient aiClient) {
    this.chatThreads = chatThreads;
    this.chatDetails = chatDetails;
    this.rateLimiter = rateLimiter;
    this.aiClient = aiClient;
  }

  ChatMessageResponse send(Long memberId, String token, String threadId, String message) {
    // 남의 스레드도 없는 스레드와 같은 응답(존재 여부를 알리지 않는다)
    chatThreads
        .findByThreadId(threadId)
        .filter(thread -> thread.getMemberId().equals(memberId))
        .orElseThrow(() -> new ApiException(ErrorCode.THREAD_NOT_FOUND));
    rateLimiter.check(memberId);
    ChatMessageResponse response = aiClient.chat(token, threadId, message);
    // 답변에 상세 보기 ID가 붙어 오면 누구 것인지 기록한다(E-13의 본인 확인용)
    if (response.detailId() != null) {
      chatDetails.save(new ChatDetail(response.detailId(), memberId));
    }
    return response;
  }
}
