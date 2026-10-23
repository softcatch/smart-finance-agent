package com.softcatch.smart.admin;

import com.softcatch.smart.admin.dto.response.AiBlockResponse;
import com.softcatch.smart.auth.Member;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminService {

  private final MemberRepository members;

  AdminService(MemberRepository members) {
    this.members = members;
  }

  // 역할은 JWT가 아니라 요청마다 DB에서 읽는다 — 운영자가 바꾸면 다음 요청부터 적용된다(FR-030).
  private void requireAdmin(Long memberId) {
    members
        .findById(memberId)
        .filter(Member::isAdmin)
        .orElseThrow(() -> new ApiException(ErrorCode.ADMIN_ONLY));
  }

  @Transactional
  AiBlockResponse block(Long adminId, Long memberId, boolean blocked, String reason) {
    requireAdmin(adminId);
    Member member =
        members.findById(memberId).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
    member.blockAi(blocked, reason);
    return new AiBlockResponse(member.getId(), member.isAiBlocked());
  }
}
