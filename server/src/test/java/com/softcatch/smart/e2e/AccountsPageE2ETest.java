package com.softcatch.smart.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.softcatch.smart.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
  void 충전_버튼을_더블클릭해도_한_번만_충전된다() {
    String token = signupAndLogin("더블클릭");
    context.addInitScript("localStorage.setItem('accessToken', '" + token + "')");
    List<String> chargeRequests = new CopyOnWriteArrayList<>();
    page.onRequest(
        request -> {
          if (request.method().equals("POST") && request.url().endsWith("/charge")) {
            chargeRequests.add(request.url());
          }
        });

    page.navigate("http://localhost:" + port + "/accounts");
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
