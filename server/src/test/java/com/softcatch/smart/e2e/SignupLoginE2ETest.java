package com.softcatch.smart.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.softcatch.smart.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignupLoginE2ETest {

  @LocalServerPort int port;

  static Playwright playwright;
  static Browser browser;
  BrowserContext context;
  Page page;

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
  }

  @AfterEach
  void closeContext() {
    context.close();
  }

  @Test
  void 회원가입_후_로그인_성공() {
    String loginId = "e2e" + System.nanoTime();

    page.navigate("http://localhost:" + port + "/signup");
    page.fill("input[name=loginId]", loginId);
    page.fill("input[name=password]", "pw12345!");
    page.fill("input[name=name]", "테스트유저");
    page.click("button[type=submit]");
    page.waitForURL("**/login", new Page.WaitForURLOptions().setWaitUntil(WaitUntilState.LOAD));

    page.fill("input[name=loginId]", loginId);
    page.fill("input[name=password]", "pw12345!");
    page.click("button[type=submit]");
    page.waitForCondition(() -> "로그인 성공".equals(page.textContent("#msg")));

    Object accessToken = page.evaluate("() => localStorage.getItem('accessToken')");
    assertEquals("로그인 성공", page.textContent("#msg"));
    assertNotNull(accessToken);
    assertFalse(accessToken.toString().isBlank());
  }
}
