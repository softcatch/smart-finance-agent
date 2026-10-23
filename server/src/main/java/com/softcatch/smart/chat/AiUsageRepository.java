package com.softcatch.smart.chat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// FastAPI가 쓰는 ai_usage(NFR-054)를 읽는다. 엔티티로 두지 않는다 — 테이블을 FastAPI가 첫 기록 때 만들어서
// 그 전에는 없고, 엔티티면 JPA 스키마 검증이 시작할 때 실패한다.
@Repository
public class AiUsageRepository {

  public static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
}
