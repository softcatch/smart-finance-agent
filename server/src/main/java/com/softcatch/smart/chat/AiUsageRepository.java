package com.softcatch.smart.chat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// FastAPI가 쓰는 ai_usage(NFR-054)를 읽는다. 엔티티로 두지 않는다 — 테이블을 FastAPI가 첫 기록 때 만들어서
// 그 전에는 없고, 엔티티면 JPA 스키마 검증이 시작할 때 실패한다.
@Repository
public class AiUsageRepository {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

  // 하루의 회원·모델별 합계. 회원의 아이디·정지 여부를 함께 읽는다.
  public record UsageRow(
      long userId,
      String loginId,
      boolean blocked,
      String model,
      long calls,
      long fallbackCalls,
      long inputTokens,
      long outputTokens) {}

  private final JdbcClient jdbc;

  AiUsageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // 오늘(KST 0시부터) 회원이 쓴 토큰(입력+출력). 테이블이 아직 없으면 0이다.
  long todayTokens(Long memberId) {
    OffsetDateTime since = LocalDate.now(KST).atStartOfDay(KST).toOffsetDateTime();
    try {
      return jdbc.sql(
              "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_usage "
                  + "WHERE user_id = :memberId AND created_at >= :since")
          .param("memberId", memberId)
          .param("since", since)
          .query(Long.class)
          .single();
    } catch (BadSqlGrammarException e) { // 첫 기록 전: relation "ai_usage" does not exist
      return 0;
    }
  }

  // 그날(KST 0시~다음 날 0시)의 회원·모델별 사용량. 테이블이 아직 없으면 빈 목록이다.
  public List<UsageRow> byUserAndModel(LocalDate day) {
    try {
      return jdbc.sql(
              "SELECT u.user_id, m.login_id, m.ai_blocked AS blocked, u.model, "
                  + "count(*) AS calls, count(*) FILTER (WHERE u.fallback) AS fallback_calls, "
                  + "sum(u.input_tokens) AS input_tokens, sum(u.output_tokens) AS output_tokens "
                  + "FROM ai_usage u JOIN member m ON m.id = u.user_id "
                  + "WHERE u.created_at >= :from AND u.created_at < :to "
                  + "GROUP BY u.user_id, m.login_id, m.ai_blocked, u.model")
          .param("from", day.atStartOfDay(KST).toOffsetDateTime())
          .param("to", day.plusDays(1).atStartOfDay(KST).toOffsetDateTime())
          .query(UsageRow.class)
          .list();
    } catch (BadSqlGrammarException e) { // 첫 기록 전
      return List.of();
    }
  }
}
