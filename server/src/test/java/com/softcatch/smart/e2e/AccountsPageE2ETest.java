package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.softcatch.smart.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountsPageE2ETest {

  @LocalServerPort int port;

  static Playwright playwright;
  static Browser browser;
  BrowserContext context;
  Page page;
  APIRequestContext api;
  final ObjectMapper json = new ObjectMapper();

  @BeforeAll
  static void launchBrowser() {
    playwright = Playwright.create();
    browser = playwright.chromium().launch();
  }

  @AfterAll
  static void closeBrowser() {
    playwright.close();
  }

  @BeforeEach
  void newPage() {
    context = browser.newContext();
    page = context.newPage();
    api =
        playwright
            .request()
            .newContext(new APIRequest.NewContextOptions().setBaseURL("http://localhost:" + port));
  }

  @AfterEach
  void closeContext() {
    api.dispose();
    context.close();
  }

  @Test
  void 계좌를_개설하고_충전하면_목록에_잔액이_보인다() {
    String token = signupAndLogin("계좌유저");
    context.addInitScript("localStorage.setItem('accessToken', '" + token + "')");

    page.navigate("http://localhost:" + port + "/accounts");
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
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate("http://localhost:" + port + "/accounts");

    page.waitForURL("**/login");
  }

  // 회원가입·로그인을 API로 하고 액세스 토큰을 돌려준다.
  private String signupAndLogin(String name) {
    String loginId = "e2e" + System.nanoTime();
    post("/api/v1/auth/signup", Map.of("loginId", loginId, "password", "pw12345!", "name", name));
    JsonNode login = post("/api/v1/auth/login", Map.of("loginId", loginId, "password", "pw12345!"));
    return login.at("/data/accessToken").asText();
  }

  private JsonNode post(String path, Object body) {
    RequestOptions options =
        RequestOptions.create()
            .setHeader("Content-Type", "application/json")
            .setData(json.writeValueAsString(body));
    return json.readTree(api.post(path, options).text());
  }
}
