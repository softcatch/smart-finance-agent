package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.chat.AiUsageFixture;
import com.softcatch.smart.chat.AiUsageRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAiUsagePageE2ETest extends E2ETestBase {

  @Autowired private JdbcTemplate jdbc;
  private AiUsageFixture usage;

  @BeforeEach
  void createUsageTable() {
    usage = new AiUsageFixture(jdbc);
    usage.create();
  }

  // 토큰(JWT)의 sub가 회원 ID다.
  private long memberIdOf(String token) {
    String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), UTF_8);
    return json.readTree(payload).at("/sub").asLong();
  }

  // 운영자가 DB에서 지정하는 것과 같은 방법으로 관리자를 만든다.
  private String adminToken(String name) {
    String token = signupAndLogin(name);
    jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", memberIdOf(token));
    return token;
  }

  private static String row(long memberId) {
    return "#usage tbody tr[data-member-id='" + memberId + "']";
  }

  @Test
  void 관리자가_주소로_들어가_오늘의_사용량을_많이_쓴_순으로_본다() {
    String admin = adminToken("사용량관리자");
    long heavy = memberIdOf(signupAndLogin("많이쓴사람"));
    long light = memberIdOf(signupAndLogin("적게쓴사람"));
    usage.addNow(heavy, 400_000, 200_000); // 합계 600,000 — 하루 한도(500,000) 이상
    usage.add(light, "claude-haiku-5-5", 2_000, 1_000, true, OffsetDateTime.now());
    signIn(admin);

    page.navigate(url("/admin/ai-usage"));

    assertThat(page.locator("#limit")).containsText("500,000");
    assertThat(page.locator(row(heavy) + " td").nth(1)).hasText("1 (0)");
    assertThat(page.locator(row(heavy) + " td").nth(2)).hasText("400,000");
    assertThat(page.locator(row(heavy) + " td").nth(3)).hasText("200,000");
    assertThat(page.locator(row(heavy) + " td").nth(4)).hasText("$0.1000"); // gpt-5-nano 단가
    assertThat(page.locator(row(heavy) + " .status")).hasText("한도 초과");
    assertThat(page.locator(row(light) + " td").nth(1)).hasText("1 (1)"); // 대체(Claude) 호출 1건
    assertThat(page.locator(row(light) + " td").nth(4)).hasText("$0.0007"); // Haiku 5.5 단가
    assertThat(page.locator(row(light) + " .status")).hasText("정상");
    List<?> order =
        (List<?>)
            page.evaluate(
                "[...document.querySelectorAll('#usage tbody tr')].map(r => r.dataset.memberId)");
    assertTrue(order.indexOf(String.valueOf(heavy)) < order.indexOf(String.valueOf(light)));
  }

  @Test
  void 사유를_적어_정지하면_채팅이_막히고_해제하면_다시_열린다() {
    String admin = adminToken("정지관리자");
    String target = signupAndLogin("정지대상");
    long targetId = memberIdOf(target);
    usage.addNow(targetId, 100, 50);
    String threadId = post("/api/v1/chat/threads", target, Map.of()).at("/data/threadId").asText();
    signIn(admin);
    page.navigate(url("/admin/ai-usage"));

    page.locator(row(targetId) + " .reason").fill("하루 한도를 반복해서 넘김");
    page.locator(row(targetId) + " .toggle").click();

    assertThat(page.locator(row(targetId) + " .status")).hasText("정지");
    assertThat(page.locator(row(targetId) + " .toggle")).hasText("해제");
    assertEquals(
        "하루 한도를 반복해서 넘김",
        jdbc.queryForObject(
            "SELECT ai_block_reason FROM member WHERE id = ?", String.class, targetId));
    assertEquals("AI_BLOCKED", chat(target, threadId).at("/error/code").asText());

    page.locator(row(targetId) + " .toggle").click();

    assertThat(page.locator(row(targetId) + " .status")).hasText("정상");
    assertTrue(chat(target, threadId).at("/success").asBoolean());
  }

  private JsonNode chat(String token, String threadId) {
    return post("/api/v1/chat/threads/" + threadId + "/messages", token, Map.of("message", "안녕"));
  }

  @Test
  void 날짜를_바꿔_조회하면_그날_사용량이_보인다() {
    String admin = adminToken("날짜관리자");
    long member = memberIdOf(signupAndLogin("지난날사용자"));
    LocalDate day = LocalDate.of(2026, 3, 10);
    usage.add(
        member,
        "gpt-5-nano",
        1_000,
        500,
        false,
        day.atTime(12, 0).atZone(AiUsageRepository.KST).toOffsetDateTime());
    signIn(admin);
    page.navigate(url("/admin/ai-usage"));
    assertThat(page.locator(row(member))).hasCount(0); // 오늘은 사용이 없다

    page.locator("#date").fill(day.toString());
    page.locator("#date-form button").click();

    assertThat(page.locator(row(member) + " td").nth(1)).hasText("1 (0)");
    assertThat(page.locator(row(member) + " td").nth(2)).hasText("1,000");
  }

  @Test
  void 관리자가_아니면_안내만_보인다() {
    signIn(signupAndLogin("일반사용자"));

    page.navigate(url("/admin/ai-usage"));

    assertThat(page.locator("#msg")).hasText("관리자만 사용할 수 있습니다");
    assertThat(page.locator("#usage tbody tr")).hasCount(0);
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/admin/ai-usage"));

    page.waitForURL("**/login");
  }
}
