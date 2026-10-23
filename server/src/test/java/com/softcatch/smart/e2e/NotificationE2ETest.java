package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.softcatch.smart.TestcontainersConfiguration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

// 송금 완료 알림(FR-024). 받는 사람·보낸 사람 두 브라우저를 열고 확정은 API로 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationE2ETest extends E2ETestBase {

  // 스트림이 열려 알림을 받을 준비가 됐다(공통 스크립트가 표시한다).
  private static void waitForStream(Page page) {
    assertThat(page.locator("html")).hasAttribute("data-notifications", "open");
  }

  // 보낸 사람 홍길동이 받는 사람 김영희에게 amount원을 확정한다(계좌 개설·충전 포함 준비는 호출하는 쪽에서).
  private void transfer(String senderToken, String recipientAccount, long amount) {
    JsonNode card = createTransferRequest(senderToken, recipientAccount, amount);
    confirmTransfer(senderToken, card.at("/requestId").asText());
  }

  @Test
  void 접속_중인_받는_사람과_보낸_사람에게_바로_보이고_열면_읽음이_된다() {
    String senderToken = signupAndLogin("홍길동");
    openFundedAccount(senderToken, 50_000);
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    signIn(recipientToken);
    page.navigate(url("/accounts"));
    waitForStream(page);
    BrowserContext senderContext = browser.newContext();
    try {
      senderContext.addInitScript("localStorage.setItem('accessToken', '" + senderToken + "')");
      Page senderPage = senderContext.newPage();
      senderPage.navigate(url("/transfers"));
      waitForStream(senderPage);

      transfer(senderToken, recipientAccount, 10_000);

      // 받는 사람: 안 읽은 알림 1건. 열면 문장이 보이고 읽은 것이 된다
      assertThat(page.locator("#bell")).hasText("알림 (1)");
      page.locator("#bell").click();
      assertThat(page.locator("#bell-list li")).hasCount(1);
      assertThat(page.locator("#bell-list li")).containsText("홍길동님이 10,000원을 보냈습니다");
      assertThat(page.locator("#bell")).hasText("알림");
      // 보낸 사람도 알림을 받는다
      assertThat(senderPage.locator("#bell")).hasText("알림 (1)");
      senderPage.locator("#bell").click();
      assertThat(senderPage.locator("#bell-list li")).containsText("김영희님께 10,000원을 보냈습니다");
    } finally {
      senderContext.close();
    }
  }

  @Test
  void 연결이_없는_사이_확정된_알림은_접속하면_목록으로_채워진다() {
    String senderToken = signupAndLogin("홍길동");
    openFundedAccount(senderToken, 50_000);
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    transfer(senderToken, recipientAccount, 3_000);
    transfer(senderToken, recipientAccount, 4_000);
    signIn(recipientToken);

    page.navigate(url("/aliases"));

    assertThat(page.locator("#bell")).hasText("알림 (2)");
    page.locator("#bell").click();
    assertThat(page.locator("#bell-list li")).hasCount(2);
    assertThat(page.locator("#bell-list li").first()).containsText("4,000원"); // 최신순
  }

  @Test
  void 읽음은_새로고침해도_유지되고_새_알림만_다시_센다() {
    String senderToken = signupAndLogin("홍길동");
    openFundedAccount(senderToken, 50_000);
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    transfer(senderToken, recipientAccount, 1_000);
    signIn(recipientToken);
    page.navigate(url("/accounts"));
    assertThat(page.locator("#bell")).hasText("알림 (1)");
    page.locator("#bell").click();
    assertThat(page.locator("#bell")).hasText("알림");

    page.reload();
    waitForStream(page);

    assertThat(page.locator("#bell")).hasText("알림"); // 읽은 알림은 다시 세지 않는다
    transfer(senderToken, recipientAccount, 2_000);
    assertThat(page.locator("#bell")).hasText("알림 (1)");
  }

  @Test
  void 연결을_못_열면_다시_시도하고_그사이_확정된_알림을_목록으로_채운다() {
    String senderToken = signupAndLogin("홍길동");
    openFundedAccount(senderToken, 50_000);
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    signIn(recipientToken);
    AtomicInteger attempts = new AtomicInteger();
    page.route(
        "**/api/v1/notifications/stream",
        route -> {
          if (attempts.getAndIncrement() == 0) {
            route.abort(); // 첫 연결은 실패한다
          } else {
            route.resume();
          }
        });
    page.navigate(url("/accounts"));

    transfer(senderToken, recipientAccount, 5_000); // 연결이 없는 사이에 확정된다

    waitForStream(page); // 잠시 뒤 다시 열린다
    assertThat(page.locator("#bell")).hasText("알림 (1)");
  }

  @Test
  void 만료된_토큰이_남아_있어도_로그인_화면은_토큰을_지우고_로그인할_수_있다() {
    String loginId = "exp" + System.nanoTime();
    post(
        "/api/v1/auth/signup",
        null,
        Map.of("loginId", loginId, "password", "pw12345!", "name", "만료"));
    page.navigate(url("/signup")); // 알림 스크립트가 없는 화면에서 만료된 토큰을 심는다
    page.evaluate("localStorage.setItem('accessToken', 'expired.token.value')");

    page.navigate(url("/login")); // 로그인 화면도 공통 스크립트를 쓴다

    // 스트림이 401이면 토큰을 지운다. 안 지우면 같은 토큰으로 스트림 401 → 로그인 화면 이동이 되풀이된다
    page.waitForFunction(
        "localStorage.getItem('accessToken') === null",
        null,
        new Page.WaitForFunctionOptions().setTimeout(5_000));
    page.locator("input[name=loginId]").fill(loginId);
    page.locator("input[name=password]").fill("pw12345!");
    page.locator("#login-form button").click();
    assertThat(page.locator("#msg")).hasText("로그인 성공");
  }

  @Test
  void 로그인하지_않은_화면에는_알림_버튼이_없다() {
    page.navigate(url("/login"));

    assertThat(page.locator("#bell")).isHidden();
  }
}
