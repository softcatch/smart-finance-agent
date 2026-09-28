package com.softcatch.smart.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.softcatch.smart.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// MockMvc는 JSP를 실제로 렌더링하지 않는다 — 내장 톰캣을 실제로 띄워야 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginPageE2ETest {

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
  void 로그인_페이지가_실제_브라우저에_렌더링된다() {
    page.navigate("http://localhost:" + port + "/login");

    assertEquals("로그인", page.title());
  }
}
