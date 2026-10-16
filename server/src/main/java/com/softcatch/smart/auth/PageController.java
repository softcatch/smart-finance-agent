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
}
