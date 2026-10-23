package com.softcatch.smart.auth;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class PageController {

  @GetMapping("/login")
  String loginPage() {
    return "login";
  }

  @GetMapping("/signup")
  String signupPage() {
    return "signup";
  }

  @GetMapping("/chat")
  String chatPage() {
    return "chat";
  }

  // 상세 보기(FR-016). 채팅 화면의 [상세 보기]가 이 주소를 연다.
  @GetMapping("/chat/details/{detailId}")
  String detailPage() {
    return "detail";
  }

  @GetMapping("/accounts")
  String accountsPage() {
    return "accounts";
  }

  @GetMapping("/transfers")
  String transfersPage() {
    return "transfers";
  }

  @GetMapping("/aliases")
  String aliasesPage() {
    return "aliases";
  }

  // AI 사용량 관리(FR-031). 메뉴에는 없고 주소로 들어간다. 화면은 누구나 열 수 있고, 관리자가 아니면 데이터 API가 403으로 막는다.
  @GetMapping("/admin/ai-usage")
  String adminAiUsagePage() {
    return "admin-ai-usage";
  }
}
