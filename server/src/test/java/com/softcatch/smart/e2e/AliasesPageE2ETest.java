package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.softcatch.smart.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AliasesPageE2ETest extends E2ETestBase {

  @Test
  void 별칭을_등록하면_목록에_예금주_이름과_함께_보인다() {
    String accountNumber = openAccount(signupAndLogin("김영희"));
    signIn(signupAndLogin("나"));
    page.navigate(url("/aliases"));

    register("엄마", accountNumber);

    Locator row = page.locator("#aliases tbody tr");
    assertThat(row).hasCount(1);
    assertThat(row).containsText("엄마");
    assertThat(row).containsText(accountNumber);
    assertThat(row).containsText("김영희"); // 예금주 이름
  }

  @Test
  void 없는_계좌번호면_오류_메시지를_보여준다() {
    signIn(signupAndLogin("나"));
    page.navigate(url("/aliases"));

    register("친구", "999999999999");

    assertThat(page.locator("#msg")).hasText("계좌를 찾을 수 없습니다");
    assertThat(page.locator("#aliases tbody tr")).hasCount(0);
  }

  @Test
  void 같은_별칭을_다시_등록하면_오류_메시지를_보여준다() {
    String accountNumber = openAccount(signupAndLogin("박영희"));
    signIn(signupAndLogin("나"));
    page.navigate(url("/aliases"));
    register("동생", accountNumber);
    assertThat(page.locator("#aliases tbody tr")).hasCount(1);

    register("동생", accountNumber);

    assertThat(page.locator("#msg")).hasText("이미 등록된 별칭입니다");
    assertThat(page.locator("#aliases tbody tr")).hasCount(1);
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate(url("/aliases"));

    page.waitForURL("**/login");
  }

  @Test
  void 메뉴로_별칭에_들어간다() {
    signIn(signupAndLogin("메뉴유저"));
    page.navigate(url("/accounts"));

    page.click("nav a[href='/aliases']");

    page.waitForURL("**/aliases");
  }

  private void register(String alias, String accountNumber) {
    page.fill("#alias-form input[name=alias]", alias);
    page.fill("#alias-form input[name=accountNumber]", accountNumber);
    page.click("#alias-form button");
  }
}
