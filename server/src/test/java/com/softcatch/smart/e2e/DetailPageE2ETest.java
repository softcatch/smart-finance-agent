package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.softcatch.smart.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DetailPageE2ETest extends E2ETestBase {

  // 채팅 API로 답변을 받아 상세 보기 ID가 본인 것으로 기록되게 한다(가짜 FastAPI가 detailId를 준다).
  private String issueDetail(String token) {
    String threadId = post("/api/v1/chat/threads", token, Map.of()).at("/data/threadId").asText();
    String detailId = "dt_e2e" + System.nanoTime();
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"삼성전자 재무 비교입니다.\",\"guard\":\"PASS\","
            + "\"detailId\":\""
            + detailId
            + "\"},\"error\":null}";
    post("/api/v1/chat/threads/" + threadId + "/messages", token, Map.of("message", "삼성전자 실적"));
    return detailId;
  }

  @Test
  void 상세_보기_화면에_보고서와_전체_표가_보인다() {
    String token = signupAndLogin("상세유저");
    String detailId = issueDetail(token);
    signIn(token);

    page.navigate(url("/chat/details/" + detailId));

    assertThat(page.locator("#report")).hasText("보고서입니다"); // 가짜 FastAPI의 기본 보고서
    assertThat(page.locator(".fin-table thead th")).hasText(new String[] {"기간", "항목", "삼성전자"});
    assertThat(page.locator(".fin-table tbody tr")).hasCount(1);
    assertThat(page.locator(".fin-table tbody tr").first()).containsText("791,405억원 (+10.0%)");
    assertThat(page.locator(".fin-note")).hasText("연결 기준");
    assertThat(page.locator("#msg")).hasText("");
  }

  @Test
  void 상세_보기_화면도_공통_스타일을_쓴다() {
    String token = signupAndLogin("상세스타일");
    String detailId = issueDetail(token);
    signIn(token);

    page.navigate(url("/chat/details/" + detailId));

    // smart.css가 적용돼야 본문 칸(main)의 최대 너비가 걸린다
    assertEquals(
        "720px", page.evaluate("getComputedStyle(document.querySelector('main')).maxWidth"));
  }

  @Test
  void 남의_상세_보기_주소를_열면_안내가_보인다() {
    String ownerToken = signupAndLogin("상세주인");
    String detailId = issueDetail(ownerToken);
    signIn(signupAndLogin("상세타인"));

    page.navigate(url("/chat/details/" + detailId));

    assertThat(page.locator("#msg")).hasText("상세 보기 대상을 찾을 수 없습니다");
    assertThat(page.locator("#report")).hasText("");
  }

  @Test
  void 보고서를_만들지_못하면_안내가_보인다() {
    String token = signupAndLogin("상세장애");
    String detailId = issueDetail(token);
    FAKE_AI.detailStatus = 500;
    signIn(token);

    page.navigate(url("/chat/details/" + detailId));

    assertThat(page.locator("#msg")).hasText("AI 서버에 연결할 수 없습니다");
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/chat/details/dt_any"));

    page.waitForURL("**/login");
  }
}
