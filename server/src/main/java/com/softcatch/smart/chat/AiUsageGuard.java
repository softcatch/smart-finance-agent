package com.softcatch.smart.chat;

import com.softcatch.smart.auth.Member;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 채팅·상세 보기에서 FastAPI를 부르기 전에 AI 사용을 막을지 본다(NFR-054). 정지가 먼저다.
// 확인 → 호출 → 기록 순이라 마지막 한 턴만큼 한도를 넘을 수 있다.
@Component
class AiUsageGuard {

  private final MemberRepository members;
  private final AiUsageRepository usage;
  private final long dailyTokenLimit;

  AiUsageGuard(
      MemberRepository members,
      AiUsageRepository usage,
      @Value("${smart.ai.daily-token-limit}") long dailyTokenLimit) {
    this.members = members;
    this.usage = usage;
    this.dailyTokenLimit = dailyTokenLimit;
  }

  void check(Long memberId) {
    if (members.findById(memberId).map(Member::isAiBlocked).orElse(false)) {
      throw new ApiException(ErrorCode.AI_BLOCKED);
    }
    if (usage.todayTokens(memberId) >= dailyTokenLimit) {
      throw new ApiException(ErrorCode.AI_QUOTA_EXCEEDED);
    }
  }
}
