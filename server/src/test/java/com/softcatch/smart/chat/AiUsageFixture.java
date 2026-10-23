package com.softcatch.smart.chat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

// 테스트용 ai_usage. 실제 테이블은 FastAPI가 첫 기록 때 만든다(ai/app/store.py) — 같은 정의를 여기서 만든다.
public class AiUsageFixture {

  private final JdbcTemplate jdbc;

  public AiUsageFixture(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void create() {
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS ai_usage (
            id             BIGSERIAL    PRIMARY KEY,
            created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
            user_id        BIGINT       NOT NULL,
            model          TEXT         NOT NULL,
            input_tokens   INTEGER      NOT NULL,
            output_tokens  INTEGER      NOT NULL,
            fallback       BOOLEAN      NOT NULL DEFAULT FALSE
        )""");
  }

  // 첫 기록 전의 상태(테이블 없음)
  public void drop() {
    jdbc.execute("DROP TABLE IF EXISTS ai_usage");
  }

  public void add(
      Long userId,
      String model,
      int inputTokens,
      int outputTokens,
      boolean fallback,
      OffsetDateTime createdAt) {
    jdbc.update(
        "INSERT INTO ai_usage (user_id, model, input_tokens, output_tokens, fallback, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        userId,
        model,
        inputTokens,
        outputTokens,
        fallback,
        createdAt);
  }

  // 지금 쓴 것으로 남긴다.
  public void addNow(Long userId, int inputTokens, int outputTokens) {
    add(userId, "gpt-5-nano", inputTokens, outputTokens, false, OffsetDateTime.now());
  }

  // 오늘(KST) 0시 — 하루 한도가 풀리는 경계
  public static OffsetDateTime startOfTodayKst() {
    return LocalDate.now(AiUsageRepository.KST)
        .atStartOfDay(AiUsageRepository.KST)
        .toOffsetDateTime();
  }
}
