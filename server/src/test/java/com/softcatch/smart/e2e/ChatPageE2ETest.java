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
import com.softcatch.smart.chat.FakeAiServer;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// AI 자리에는 가짜 FastAPI를 둔다. 화면·Spring·PostgreSQL은 실제로 띄운다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatPageE2ETest {

  private static final FakeAiServer FAKE_AI = startFakeAi();

  private static FakeAiServer startFakeAi() {
    try {
      return new FakeAiServer();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void aiProperties(DynamicPropertyRegistry registry) {
    registry.add("smart.ai.base-url", FAKE_AI::baseUrl);
    registry.add("smart.ai.read-timeout", () -> "5s");
  }

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
    FAKE_AI.close();
  }

  @BeforeEach
  void newPage() {
    FAKE_AI.reset();
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
  void 메시지를_보내면_답변이_보인다() {
    String token = signupAndLogin("채팅유저");
    context.addInitScript("localStorage.setItem('accessToken', '" + token + "')");

    page.navigate("http://localhost:" + port + "/chat");
    page.fill("input[name=message]", "안녕");
    page.click("#chat-form button");

    assertThat(page.locator("#log")).containsText("나: 안녕");
    assertThat(page.locator("#log")).containsText("AI: 안녕하세요"); // 가짜 FastAPI의 기본 답변
  }

  @Test
  void 토큰이_없으면_로그인_페이지로_이동한다() {
    page.navigate("http://localhost:" + port + "/chat");

    page.waitForURL("**/login");
  }

  @Test
  void 로그인_페이지의_링크로_채팅에_들어간다() {
    page.navigate("http://localhost:" + port + "/login");

    page.click("a[href='/chat']");

    page.waitForURL("**/chat");
  }

  // 회원가입·로그인을 API로 하고 액세스 토큰을 돌려준다.
  private String signupAndLogin(String name) {
    String loginId = "e2e" + System.nanoTime();
    post(
        "/api/v1/auth/signup",
        null,
        Map.of("loginId", loginId, "password", "pw12345!", "name", name));
    JsonNode login =
        post("/api/v1/auth/login", null, Map.of("loginId", loginId, "password", "pw12345!"));
    return login.at("/data/accessToken").asText();
  }

  private JsonNode post(String path, String token, Object body) {
    RequestOptions options =
        RequestOptions.create()
            .setHeader("Content-Type", "application/json")
            .setData(json.writeValueAsString(body));
    if (token != null) {
      options.setHeader("Authorization", "Bearer " + token);
    }
    return json.readTree(api.post(path, options).text());
  }
}
