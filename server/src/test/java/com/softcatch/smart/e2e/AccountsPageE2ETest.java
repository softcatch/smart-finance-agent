package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Locator;
import com.softcatch.smart.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsPageE2ETest extends E2ETestBase {

  @Test
  void 계좌를_개설하고_충전하면_목록에_잔액이_보인다() {
    String token = signupAndLogin("계좌유저");
    signIn(token);

    page.navigate(url("/accounts"));
    page.fill("#open-form input[name=password]", "1234");
    page.click("#open-form button");

    assertThat(page.locator("#accounts tbody tr")).hasCount(1);
    assertThat(page.locator("#accounts tbody tr")).containsText("대표"); // 첫 계좌는 대표
    assertThat(page.locator("#accounts tbody tr .balance")).hasText("0원");

    page.fill("#accounts tbody tr input[name=amount]", "30000");
    page.click("#accounts tbody tr button");

    assertThat(page.locator("#accounts tbody tr .balance")).hasText("30,000원");
  }

  @Test
  void 두_번째_계좌를_대표로_지정하면_대표_표시가_옮겨_간다() {
    signIn(signupAndLogin("대표변경"));
    page.navigate(url("/accounts"));
    openAccountOnPage();
    assertThat(page.locator("#accounts tbody tr")).hasCount(1);
    openAccountOnPage();
    Locator rows = page.locator("#accounts tbody tr");
    assertThat(rows).hasCount(2);
    assertThat(rows.nth(0).locator(".primary")).hasText("대표"); // 첫 계좌가 대표

    rows.nth(1).locator(".make-primary").click();

    assertThat(rows.nth(1).locator(".primary")).hasText("대표");
    assertThat(rows.nth(0).locator(".primary .make-primary")).hasCount(1); // 첫 계좌는 이제 지정 가능
  }

  @Test
  void 충전_버튼을_더블클릭해도_한_번만_충전된다() {
    String token = signupAndLogin("더블클릭");
    signIn(token);
    List<String> chargeRequests = new CopyOnWriteArrayList<>();
    page.onRequest(
        request -> {
          if (request.method().equals("POST") && request.url().endsWith("/charge")) {
            chargeRequests.add(request.url());
          }
        });

    page.navigate(url("/accounts"));
    page.fill("#open-form input[name=password]", "1234");
    page.click("#open-form button");
    assertThat(page.locator("#accounts tbody tr")).hasCount(1);

    page.fill("#accounts tbody tr input[name=amount]", "10000");
    page.dblclick("#accounts tbody tr button");

    // 첫 응답이 와서 잔액이 보일 때는 두 번째 요청도 이미 나간 뒤다 — 충전 요청은 한 번이어야 한다.
    assertThat(page.locator("#accounts tbody tr .balance")).hasText("10,000원");
    assertEquals(1, chargeRequests.size());
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/accounts"));

    page.waitForURL("**/login");
  }

  private void openAccountOnPage() {
    page.fill("#open-form input[name=password]", ACCOUNT_PASSWORD);
    page.click("#open-form button");
  }
}
