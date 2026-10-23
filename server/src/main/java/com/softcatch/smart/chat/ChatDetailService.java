package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatDetailResponse;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import org.springframework.stereotype.Service;

// 트랜잭션을 걸지 않는다 — 보고서를 만드는 동안 DB 커넥션을 잡지 않기 위해서다.
@Service
class ChatDetailService {

  private final ChatDetailRepository chatDetails;
  private final AiUsageGuard usageGuard;
  private final AiClient aiClient;

  ChatDetailService(ChatDetailRepository chatDetails, AiUsageGuard usageGuard, AiClient aiClient) {
    this.chatDetails = chatDetails;
    this.usageGuard = usageGuard;
    this.aiClient = aiClient;
  }

  ChatDetailResponse get(Long memberId, String token, String detailId) {
    // 남의 것도 없는 것과 같은 응답(존재 여부를 알리지 않는다)
    chatDetails
        .findByDetailId(detailId)
        .filter(detail -> detail.getMemberId().equals(memberId))
        .orElseThrow(() -> new ApiException(ErrorCode.DETAIL_NOT_FOUND));
    usageGuard.check(memberId); // 정지·하루 한도(NFR-054). 걸리면 FastAPI를 부르지 않는다
    return aiClient.detail(token, detailId);
  }
}
