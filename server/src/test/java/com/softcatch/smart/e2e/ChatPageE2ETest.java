package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import com.softcatch.smart.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatPageE2ETest extends E2ETestBase {

  @Test
  void 메시지를_보내면_답변이_보인다() {
    String token = signupAndLogin("채팅유저");
    signIn(token);

    page.navigate(url("/chat"));
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");

    assertThat(page.locator(".msg-me .bubble")).hasText("안녕");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요"); // 가짜 FastAPI의 기본 답변
    assertThat(page.locator(".detail-link")).hasCount(0); // detailId가 없는 답변에는 링크가 없다
  }

  @Test
  void 내_말풍선은_오른쪽_AI_말풍선은_왼쪽에_보인다() {
    signIn(signupAndLogin("말풍선유저"));

    page.navigate(url("/chat"));
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");

    BoundingBox me = page.locator(".msg-me .bubble").boundingBox();
    BoundingBox ai = page.locator(".msg-ai .bubble").boundingBox();
    assertTrue(me.x > ai.x, "내 말풍선이 AI 말풍선보다 오른쪽에 있어야 한다");
  }

  @Test
  void 다른_메뉴를_다녀와도_대화와_threadId가_이어진다() {
    signIn(signupAndLogin("이어가기유저"));
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");
    String firstThreadId = json.readTree(FAKE_AI.lastBody).get("threadId").asText();

    openMenuAndBack("/accounts");

    assertThat(page.locator(".msg-me .bubble")).hasText("안녕");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");
    page.fill("input[name=message]", "또 안녕");
    page.click("#chat-form button");
    assertThat(page.locator(".msg-ai .bubble")).hasCount(2);
    assertEquals(firstThreadId, json.readTree(FAKE_AI.lastBody).get("threadId").asText());
  }

  @Test
  void 새_대화를_누르면_기록이_비워지고_새_threadId를_쓴다() {
    signIn(signupAndLogin("새대화유저"));
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");
    String firstThreadId = json.readTree(FAKE_AI.lastBody).get("threadId").asText();

    page.click("#new-chat");
    assertThat(page.locator(".msg")).hasCount(0);
    page.fill("input[name=message]", "다시 안녕");
    page.click("#chat-form button");

    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");
    assertNotEquals(firstThreadId, json.readTree(FAKE_AI.lastBody).get("threadId").asText());
  }

  @Test
  void 먼저_보낸_대화_생성_응답이_늦게_와도_새_대화의_threadId를_덮어쓰지_않는다() {
    signIn(signupAndLogin("연타유저"));
    List<Route> held = new ArrayList<>();
    page.route(
        "**/api/v1/chat/threads",
        route -> {
          if (held.isEmpty()) {
            held.add(route); // 화면을 열 때 나간 첫 요청은 잡아 둔다
          } else {
            route.resume(); // [새 대화]의 요청은 바로 보낸다
          }
        });
    page.navigate(url("/chat"));
    page.click("#new-chat");
    page.waitForFunction("JSON.parse(sessionStorage.getItem('chat'))?.threadId");
    Object latest = page.evaluate("JSON.parse(sessionStorage.getItem('chat')).threadId");

    page.waitForResponse("**/api/v1/chat/threads", () -> held.get(0).resume()); // 첫 응답이 뒤늦게 온다
    page.waitForTimeout(100); // 화면이 그 응답을 처리할 시간

    assertEquals(latest, page.evaluate("JSON.parse(sessionStorage.getItem('chat')).threadId"));
  }

  @Test
  void 송금을_마친_확인_카드는_메뉴를_다녀와도_다시_눌리지_않는다() {
    String recipientAccount = openAccount(signupAndLogin("이어받는이"));
    String senderToken = signupAndLogin("이어보내는이");
    charge(senderToken, openAccount(senderToken), 50000L);
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    signIn(senderToken);
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "1만원 보내줘");
    page.click("#chat-form button");
    page.fill(".transfer-card input[name=password]", ACCOUNT_PASSWORD);
    page.click(".transfer-card button");
    assertThat(page.locator(".card-result")).containsText("송금 완료");

    openMenuAndBack("/transfers");

    assertThat(page.locator(".card-result")).containsText("송금 완료");
    assertThat(page.locator(".transfer-card button")).isDisabled();
  }

  @Test
  void 로그아웃하면_토큰과_대화가_지워지고_로그인_화면으로_이동한다() {
    String token = signupAndLogin("로그아웃유저");
    page.navigate(url("/login"));
    assertThat(page.locator("#logout")).isHidden(); // 로그인 전에는 보이지 않는다
    page.evaluate("t => localStorage.setItem('accessToken', t)", token);
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");
    assertThat(page.locator(".msg-ai .bubble")).hasText("안녕하세요");

    page.click("#logout");

    page.waitForURL("**/login");
    assertNull(page.evaluate("localStorage.getItem('accessToken')"));
    assertNull(page.evaluate("sessionStorage.getItem('chat')"));
  }

  @Test
  void 로그인하면_이전_대화_기록이_지워진다() {
    String loginId = "chat" + System.nanoTime();
    post(
        "/api/v1/auth/signup",
        null,
        Map.of("loginId", loginId, "password", "pw12345!", "name", "로그인유저"));
    page.navigate(url("/login"));
    page.evaluate("sessionStorage.setItem('chat', '{\"threadId\":\"th_old\",\"items\":[]}')");

    page.fill("input[name=loginId]", loginId);
    page.fill("input[name=password]", "pw12345!");
    page.click("#login-form button");

    assertThat(page.locator("#msg")).hasText("로그인 성공");
    assertNull(page.evaluate("sessionStorage.getItem('chat')"));
    assertThat(page.locator("#logout")).isVisible();
  }

  @Test
  void 재무_비교_표가_화면에_표로_보인다() {
    signIn(signupAndLogin("표유저"));
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"삼성전자 재무 비교입니다.\",\"guard\":\"PASS\","
            + "\"table\":{\"columns\":[\"항목\",\"삼성전자\"],"
            + "\"rows\":[[\"매출액\",\"791,405억원 (+10.0%)\"],[\"영업이익\",\"66,853억원 (+1.2%)\"]],"
            + "\"note\":\"2026년 1분기 · 연결 기준\"}},\"error\":null}";

    page.navigate(url("/chat"));
    page.fill("input[name=message]", "삼성전자 실적 알려줘");
    page.click("#chat-form button");

    Locator table = page.locator(".fin-table");
    assertThat(table.locator("thead th")).hasText(new String[] {"항목", "삼성전자"});
    assertThat(table.locator("tbody tr")).hasCount(2);
    assertThat(table.locator("tbody tr").nth(1)).containsText("66,853억원 (+1.2%)");
    assertThat(page.locator(".fin-note")).hasText("2026년 1분기 · 연결 기준");
  }

  @Test
  void 재무_비교_답변의_상세_보기_링크로_상세_화면이_열린다() {
    signIn(signupAndLogin("링크유저"));
    String detailId = stubAiWithDetail();
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "삼성전자 실적 알려줘");
    page.click("#chat-form button");

    page.click(".detail-link");

    page.waitForURL("**/chat/details/" + detailId);
    assertThat(page.locator("#report")).hasText("보고서입니다"); // 가짜 FastAPI의 기본 보고서
  }

  @Test
  void 상세_보기_링크는_메뉴를_다녀와도_남는다() {
    signIn(signupAndLogin("링크유지유저"));
    String detailId = stubAiWithDetail();
    page.navigate(url("/chat"));
    page.fill("input[name=message]", "삼성전자 실적 알려줘");
    page.click("#chat-form button");
    assertThat(page.locator(".detail-link")).hasAttribute("href", "/chat/details/" + detailId);

    openMenuAndBack("/accounts");

    assertThat(page.locator(".detail-link")).hasAttribute("href", "/chat/details/" + detailId);
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/chat"));

    page.waitForURL("**/login");
  }

  @Test
  void 로그인_페이지의_링크로_채팅에_들어간다() {
    page.navigate(url("/login"));

    page.click("a[href='/chat']");

    page.waitForURL("**/chat");
  }

  @Test
  void 확인_카드의_보내기로_송금하면_잔액이_반영된다() {
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("홍길동");
    String senderAccount = openAccount(senderToken);
    charge(senderToken, senderAccount, 50000L);
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    signIn(senderToken);

    page.navigate(url("/chat"));
    page.fill("input[name=message]", "김영희한테 1만원 보내줘");
    page.click("#chat-form button");
    assertThat(page.locator(".transfer-card")).containsText("김영희");
    assertThat(page.locator(".transfer-card")).containsText("10,000원");
    page.fill(".transfer-card input[name=password]", ACCOUNT_PASSWORD);
    page.click(".transfer-card button");

    assertThat(page.locator(".card-result")).containsText("송금 완료");
    assertThat(page.locator(".card-result")).containsText("잔액 40,000원");
    assertEquals(40000L, balanceOf(senderToken));
    assertEquals(10000L, balanceOf(recipientToken));
  }

  @Test
  void 비밀번호가_틀리면_요청이_취소됐다고_안내하고_송금되지_않는다() {
    String recipientToken = signupAndLogin("이영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("김철수");
    String senderAccount = openAccount(senderToken);
    charge(senderToken, senderAccount, 50000L);
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    signIn(senderToken);

    page.navigate(url("/chat"));
    page.fill("input[name=message]", "이영희한테 1만원 보내줘");
    page.click("#chat-form button");
    page.fill(".transfer-card input[name=password]", "0000");
    page.click(".transfer-card button");

    assertThat(page.locator(".card-result")).containsText("취소");
    assertThat(page.locator(".transfer-card button")).isDisabled();
    assertEquals(50000L, balanceOf(senderToken));
    assertEquals(0L, balanceOf(recipientToken));
  }

  @Test
  void 계좌_화면에서_만든_계좌로_채팅_송금이_된다() {
    String recipientToken = signupAndLogin("박영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("최철수");
    signIn(senderToken);

    // 계좌 화면에서 개설·충전한다(사람이 하는 순서 그대로)
    page.navigate(url("/accounts"));
    page.fill("#open-form input[name=password]", ACCOUNT_PASSWORD);
    page.click("#open-form button");
    page.fill("#accounts tbody tr input[name=amount]", "50000");
    page.click("#accounts tbody tr button");
    assertThat(page.locator("#accounts tbody tr .balance")).hasText("50,000원");

    // 채팅으로 이동해 확인 카드로 송금한다. 비밀번호는 계좌 화면에서 정한 값이다.
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    page.click("a[href='/chat']");
    page.waitForURL("**/chat");
    page.fill("input[name=message]", "박영희한테 1만원 보내줘");
    page.click("#chat-form button");
    page.fill(".transfer-card input[name=password]", ACCOUNT_PASSWORD);
    page.click(".transfer-card button");

    assertThat(page.locator(".card-result")).containsText("송금 완료");
    assertEquals(40000L, balanceOf(senderToken));
    assertEquals(10000L, balanceOf(recipientToken));
  }

  // 메뉴로 다른 화면에 갔다가 메뉴로 채팅에 돌아온다.
  private void openMenuAndBack(String otherPath) {
    page.click("nav a[href='" + otherPath + "']");
    page.waitForURL("**" + otherPath);
    page.click("nav a[href='/chat']");
    page.waitForURL("**/chat");
  }

  // 가짜 FastAPI가 재무 비교 표와 상세 보기 ID를 돌려주게 하고, 그 ID를 돌려준다.
  // Spring이 이 답변을 받아 ID의 주인을 기록하므로 상세 화면이 실제로 열린다.
  private String stubAiWithDetail() {
    String detailId = "dt_e2e" + System.nanoTime();
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"삼성전자 재무 비교입니다.\",\"guard\":\"PASS\","
            + "\"table\":{\"columns\":[\"항목\",\"삼성전자\"],\"rows\":[[\"매출액\",\"791,405억원 (+10.0%)\"]],"
            + "\"note\":\"2026년 1분기 · 연결 기준\"},\"detailId\":\""
            + detailId
            + "\"},\"error\":null}";
    return detailId;
  }

  // 가짜 FastAPI가 확인 카드를 돌려주게 한다. 카드의 요청ID는 Spring이 실제로 발급한 값이다.
  private void stubAiWithTransferCard(String senderToken, String recipientAccount, long amount) {
    JsonNode card = createTransferRequest(senderToken, recipientAccount, amount);
    ObjectNode data = json.createObjectNode();
    data.put("reply", "송금 요청을 만들었습니다.");
    data.put("guard", "PASS");
    data.set("transferCard", card);
    ObjectNode envelope = json.createObjectNode();
    envelope.put("success", true);
    envelope.set("data", data);
    FAKE_AI.responseBody = json.writeValueAsString(envelope);
  }
}
