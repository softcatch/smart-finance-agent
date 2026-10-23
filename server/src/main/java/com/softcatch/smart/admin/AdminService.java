package com.softcatch.smart.admin;

import com.softcatch.smart.admin.dto.response.AiBlockResponse;
import com.softcatch.smart.admin.dto.response.AiUsageItem;
import com.softcatch.smart.admin.dto.response.AiUsageResponse;
import com.softcatch.smart.auth.Member;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.chat.AiUsageRepository;
import com.softcatch.smart.chat.AiUsageRepository.UsageRow;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminService {

  private record Price(double input, double output) {}

  // 비용 추정용 단가(USD/백만 토큰). 모델을 바꾸면 여기에 더한다. 없는 모델은 0으로 센다. 장부 금액이 아니다.
  // gpt-5-nano 입력 $0.05·출력 $0.40, Claude Haiku 5.5 입력 $0.10·출력 $0.50(프롬프트 10만 토큰 이하).
  private static final Map<String, Price> PRICES =
      Map.of(
          "gpt-5-nano", new Price(0.05, 0.40),
          "claude-haiku-5-5", new Price(0.10, 0.50));
  private static final Price NO_PRICE = new Price(0, 0);

  private final MemberRepository members;
  private final AiUsageRepository usage;
  private final long dailyTokenLimit;

  AdminService(
      MemberRepository members,
      AiUsageRepository usage,
      @Value("${smart.ai.daily-token-limit}") long dailyTokenLimit) {
    this.members = members;
    this.usage = usage;
    this.dailyTokenLimit = dailyTokenLimit;
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

  // 그날(KST, 기본 오늘)의 회원별 사용량을 많이 쓴 순으로 준다.
  AiUsageResponse aiUsage(Long adminId, LocalDate date) {
    requireAdmin(adminId);
    LocalDate day = date != null ? date : LocalDate.now(AiUsageRepository.KST);
    Map<Long, List<UsageRow>> byUser =
        usage.byUserAndModel(day).stream().collect(Collectors.groupingBy(UsageRow::userId));
    List<AiUsageItem> items = new ArrayList<>();
    byUser.values().forEach(rows -> items.add(item(rows)));
    // 정지된 회원은 그날 사용이 없어도 둔다 — 정지하면 새 사용량이 안 생겨서, 빼면 화면에서 해제할 길이 없다.
    members.findByAiBlockedTrue().stream()
        .filter(member -> !byUser.containsKey(member.getId()))
        .forEach(
            member ->
                items.add(
                    new AiUsageItem(member.getId(), member.getLoginId(), 0, 0, 0, 0, 0, true)));
    items.sort(
        Comparator.comparingLong((AiUsageItem item) -> item.inputTokens() + item.outputTokens())
            .reversed()
            .thenComparing(AiUsageItem::memberId));
    return new AiUsageResponse(dailyTokenLimit, items);
  }

  // 한 회원의 모델별 줄을 합친다.
  private static AiUsageItem item(List<UsageRow> rows) {
    long calls = 0;
    long fallbackCalls = 0;
    long input = 0;
    long output = 0;
    double cost = 0;
    for (UsageRow row : rows) {
      Price price = PRICES.getOrDefault(row.model(), NO_PRICE);
      calls += row.calls();
      fallbackCalls += row.fallbackCalls();
      input += row.inputTokens();
      output += row.outputTokens();
      cost += (row.inputTokens() * price.input() + row.outputTokens() * price.output()) / 1_000_000;
    }
    UsageRow first = rows.get(0);
    return new AiUsageItem(
        first.userId(),
        first.loginId(),
        Math.toIntExact(calls),
        Math.toIntExact(fallbackCalls),
        input,
        output,
        Math.round(cost * 1_000_000) / 1_000_000.0,
        first.blocked());
  }
}
