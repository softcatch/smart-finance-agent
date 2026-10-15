package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.softcatch.smart.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransfersPageE2ETest extends E2ETestBase {

  @Autowired JdbcTemplate jdbc;

  @Test
  void 송금_내역이_최신순으로_보인다() {
    String recipientToken = signupAndLogin("받는이");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("보내는이");
    openFundedAccount(senderToken, 50000L);
    send(senderToken, recipientAccount, 3000L);
    send(senderToken, recipientAccount, 7000L);
    signIn(senderToken);

    page.navigate(url("/transfers"));

    Locator rows = page.locator("#transfers tbody tr");
    assertThat(rows).hasCount(2);
    assertThat(rows.nth(0)).containsText("보냄");
    assertThat(rows.nth(0)).containsText("받는이");
    assertThat(rows.nth(0)).containsText("7,000원"); // 최신순
    assertThat(rows.nth(1)).containsText("3,000원");
  }

  @Test
  void 받은_송금은_받음으로_보인다() {
    String recipientToken = signupAndLogin("수취인");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("발신인");
    openFundedAccount(senderToken, 10000L);
    send(senderToken, recipientAccount, 4000L);
    signIn(recipientToken);

    page.navigate(url("/transfers"));

    Locator row = page.locator("#transfers tbody tr");
    assertThat(row).hasCount(1);
    assertThat(row).containsText("받음");
    assertThat(row).containsText("발신인");
    assertThat(row).containsText("4,000원");
  }

  @Test
  void 스크롤하면_다음_묶음을_불러온다() {
    String recipientToken = signupAndLogin("스크롤수취");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("스크롤발신");
    String senderAccount = openAccount(senderToken);
    // 25건을 직접 넣는다(확정 API를 25번 부르지 않는다)
    jdbc.update(
        "INSERT INTO transfer (from_account_number, to_account_number, amount) "
            + "SELECT ?::varchar, ?::varchar, 1000 FROM generate_series(1, 25)",
        senderAccount,
        recipientAccount);
    signIn(senderToken);
    page.setViewportSize(800, 200); // 20건이 화면을 넘게 해서, 스크롤해야 다음 묶음이 열리게 한다

    page.navigate(url("/transfers"));
    assertThat(page.locator("#transfers tbody tr")).hasCount(20);
    page.locator("#sentinel").scrollIntoViewIfNeeded();

    assertThat(page.locator("#transfers tbody tr")).hasCount(25);
  }

  @Test
  void 화면이_넓으면_스크롤_없이_모두_불러온다() {
    String recipientToken = signupAndLogin("넓은수취");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("넓은발신");
    String senderAccount = openAccount(senderToken);
    jdbc.update(
        "INSERT INTO transfer (from_account_number, to_account_number, amount) "
            + "SELECT ?::varchar, ?::varchar, 1000 FROM generate_series(1, 25)",
        senderAccount,
        recipientAccount);
    signIn(senderToken);
    page.setViewportSize(1280, 1600); // 25건이 한 화면에 다 들어와 감시 요소가 계속 보인다

    page.navigate(url("/transfers"));

    // 첫 묶음 뒤에도 감시 요소가 보이면 한 번 더 불러와야 한다(옵저버를 다시 걸지 않으면 20건에서 멈춘다)
    assertThat(page.locator("#transfers tbody tr")).hasCount(25);
  }

  @Test
  void 내역이_없으면_안내한다() {
    signIn(signupAndLogin("무내역"));

    page.navigate(url("/transfers"));

    assertThat(page.locator("#msg")).hasText("송금 내역이 없습니다.");
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/transfers"));

    page.waitForURL("**/login");
  }

  @Test
  void 메뉴로_송금_내역에_들어간다() {
    signIn(signupAndLogin("메뉴유저"));
    page.navigate(url("/accounts"));

    page.click("nav a[href='/transfers']");

    page.waitForURL("**/transfers");
  }

  private void send(String token, String recipientAccount, long amount) {
    String requestId =
        createTransferRequest(token, recipientAccount, amount).at("/requestId").asText();
    confirmTransfer(token, requestId);
  }
}
