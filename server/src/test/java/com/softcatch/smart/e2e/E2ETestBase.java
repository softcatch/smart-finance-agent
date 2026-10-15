package com.softcatch.smart.e2e;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import com.softcatch.smart.chat.FakeAiServer;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// E2E 공통 기반: 브라우저와 API 호출 헬퍼. 화면·Spring·PostgreSQL은 실제로 띄우고, AI 자리만
// 가짜 FastAPI다. 모든 E2E가 같은 가짜 AI를 써서 같은 스프링 컨텍스트(와 DB 컨테이너)를 공유한다.
// 하위 클래스에는 @Import(TestcontainersConfiguration.class)와 @SpringBootTest(RANDOM_PORT)를 단다.
abstract class E2ETestBase {

  static final String ACCOUNT_PASSWORD = "1234";
  static final FakeAiServer FAKE_AI = startFakeAi();

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

  String url(String path) {
    return "http://localhost:" + port + path;
  }

  // 로그인한 상태로 페이지를 연다 — 화면이 쓰는 localStorage의 토큰을 미리 넣어 둔다.
  void signIn(String token) {
    context.addInitScript("localStorage.setItem('accessToken', '" + token + "')");
  }

  // 회원가입·로그인을 API로 하고 액세스 토큰을 돌려준다.
  String signupAndLogin(String name) {
    String loginId = "e2e" + System.nanoTime();
    post(
        "/api/v1/auth/signup",
        null,
        Map.of("loginId", loginId, "password", "pw12345!", "name", name));
    JsonNode login =
        post("/api/v1/auth/login", null, Map.of("loginId", loginId, "password", "pw12345!"));
    return login.at("/data/accessToken").asText();
  }

  JsonNode post(String path, String token, Object body) {
    RequestOptions options =
        RequestOptions.create()
            .setHeader("Content-Type", "application/json")
            .setData(json.writeValueAsString(body));
    if (token != null) {
      options.setHeader("Authorization", "Bearer " + token);
    }
    return json.readTree(api.post(path, options).text());
  }

  // 계좌를 열고(비밀번호 ACCOUNT_PASSWORD) 계좌번호를 돌려준다.
  String openAccount(String token) {
    RequestOptions options =
        RequestOptions.create()
            .setHeader("Content-Type", "application/json")
            .setHeader("Authorization", "Bearer " + token)
            .setHeader("Idempotency-Key", UUID.randomUUID().toString())
            .setData(json.writeValueAsString(Map.of("password", ACCOUNT_PASSWORD)));
    return json.readTree(api.post("/api/v1/accounts", options).text())
        .at("/data/accountNumber")
        .asText();
  }

  void charge(String token, String accountNumber, long amount) {
    long accountId = accountIdOf(token, accountNumber);
    post("/api/v1/accounts/" + accountId + "/charge", token, Map.of("amount", amount));
  }

  long accountIdOf(String token, String accountNumber) {
    for (JsonNode account : listAccounts(token)) {
      if (account.at("/accountNumber").asText().equals(accountNumber)) {
        return account.at("/accountId").asLong();
      }
    }
    throw new IllegalStateException("계좌 없음: " + accountNumber);
  }

  JsonNode listAccounts(String token) {
    RequestOptions options = RequestOptions.create().setHeader("Authorization", "Bearer " + token);
    return json.readTree(api.get("/api/v1/accounts", options).text()).at("/data/accounts");
  }

  // 첫 번째(대표) 계좌의 잔액
  long balanceOf(String token) {
    return listAccounts(token).get(0).at("/balance").asLong();
  }

  // 송금 요청을 만들어(I-03) 확인 카드 JSON({requestId, ...})을 돌려준다.
  JsonNode createTransferRequest(String token, String recipientAccount, long amount) {
    return post(
            "/internal/transfer-requests",
            token,
            Map.of("recipientAccountNumber", recipientAccount, "amount", amount))
        .at("/data");
  }

  // 요청을 올바른 비밀번호로 확정해 송금한다(E-12).
  void confirmTransfer(String token, String requestId) {
    post(
        "/api/v1/transfer-requests/" + requestId + "/confirm",
        token,
        Map.of("password", ACCOUNT_PASSWORD));
  }

  // 계좌 하나를 만들고 충전까지 해 둔다. 계좌번호를 돌려준다.
  String openFundedAccount(String token, long amount) {
    String accountNumber = openAccount(token);
    charge(token, accountNumber, amount);
    return accountNumber;
  }
}
