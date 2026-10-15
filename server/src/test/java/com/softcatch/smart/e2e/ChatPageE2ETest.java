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
import java.util.UUID;
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
import tools.jackson.databind.node.ObjectNode;

// AI 자리에는 가짜 FastAPI를 둔다. 화면·Spring·PostgreSQL은 실제로 띄운다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatPageE2ETest {

  private static final String ACCOUNT_PASSWORD = "1234";
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

  @Test
  void 확인_카드의_보내기로_송금하면_잔액이_반영된다() {
    String recipientToken = signupAndLogin("김영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("홍길동");
    String senderAccount = openAccount(senderToken);
    charge(senderToken, senderAccount, 50000L);
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    context.addInitScript("localStorage.setItem('accessToken', '" + senderToken + "')");

    page.navigate("http://localhost:" + port + "/chat");
    page.fill("input[name=message]", "김영희한테 1만원 보내줘");
    page.click("#chat-form button");
    assertThat(page.locator(".transfer-card")).containsText("김영희");
    assertThat(page.locator(".transfer-card")).containsText("10,000원");
    page.fill(".transfer-card input[name=password]", ACCOUNT_PASSWORD);
    page.click(".transfer-card button");

    assertThat(page.locator(".card-result")).containsText("송금 완료");
    assertThat(page.locator(".card-result")).containsText("잔액 40,000원");
    org.junit.jupiter.api.Assertions.assertEquals(40000L, balanceOf(senderToken));
    org.junit.jupiter.api.Assertions.assertEquals(10000L, balanceOf(recipientToken));
  }

  @Test
  void 비밀번호가_틀리면_요청이_취소됐다고_안내하고_송금되지_않는다() {
    String recipientToken = signupAndLogin("이영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("김철수");
    String senderAccount = openAccount(senderToken);
    charge(senderToken, senderAccount, 50000L);
    stubAiWithTransferCard(senderToken, recipientAccount, 10000L);
    context.addInitScript("localStorage.setItem('accessToken', '" + senderToken + "')");

    page.navigate("http://localhost:" + port + "/chat");
    page.fill("input[name=message]", "이영희한테 1만원 보내줘");
    page.click("#chat-form button");
    page.fill(".transfer-card input[name=password]", "0000");
    page.click(".transfer-card button");

    assertThat(page.locator(".card-result")).containsText("취소");
    assertThat(page.locator(".transfer-card button")).isDisabled();
    org.junit.jupiter.api.Assertions.assertEquals(50000L, balanceOf(senderToken));
    org.junit.jupiter.api.Assertions.assertEquals(0L, balanceOf(recipientToken));
  }

  @Test
  void 계좌_화면에서_만든_계좌로_채팅_송금이_된다() {
    String recipientToken = signupAndLogin("박영희");
    String recipientAccount = openAccount(recipientToken);
    String senderToken = signupAndLogin("최철수");
    context.addInitScript("localStorage.setItem('accessToken', '" + senderToken + "')");

    // 계좌 화면에서 개설·충전한다(사람이 하는 순서 그대로)
    page.navigate("http://localhost:" + port + "/accounts");
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
    org.junit.jupiter.api.Assertions.assertEquals(40000L, balanceOf(senderToken));
    org.junit.jupiter.api.Assertions.assertEquals(10000L, balanceOf(recipientToken));
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

  // 계좌를 열고(비밀번호 ACCOUNT_PASSWORD) 계좌번호를 돌려준다.
  private String openAccount(String token) {
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

  private void charge(String token, String accountNumber, long amount) {
    long accountId = accountIdOf(token, accountNumber);
    post("/api/v1/accounts/" + accountId + "/charge", token, Map.of("amount", amount));
  }

  private long accountIdOf(String token, String accountNumber) {
    for (JsonNode account : listAccounts(token)) {
      if (account.at("/accountNumber").asText().equals(accountNumber)) {
        return account.at("/accountId").asLong();
      }
    }
    throw new IllegalStateException("계좌 없음: " + accountNumber);
  }

  private JsonNode listAccounts(String token) {
    RequestOptions options = RequestOptions.create().setHeader("Authorization", "Bearer " + token);
    return json.readTree(api.get("/api/v1/accounts", options).text()).at("/data/accounts");
  }

  private long balanceOf(String token) {
    return listAccounts(token).get(0).at("/balance").asLong();
  }

  // 가짜 FastAPI가 확인 카드를 돌려주게 한다. 카드의 요청ID는 Spring이 실제로 발급한 값이다.
  private void stubAiWithTransferCard(String senderToken, String recipientAccount, long amount) {
    JsonNode card =
        post(
                "/internal/transfer-requests",
                senderToken,
                Map.of("recipientAccountNumber", recipientAccount, "amount", amount))
            .at("/data");
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
