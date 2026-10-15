package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.softcatch.smart.TestcontainersConfiguration;
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

    assertThat(page.locator("#log")).containsText("나: 안녕");
    assertThat(page.locator("#log")).containsText("AI: 안녕하세요"); // 가짜 FastAPI의 기본 답변
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
