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

  @GetMapping("/accounts")
  String accountsPage() {
    return "accounts";
  }

  @GetMapping("/transfers")
  String transfersPage() {
    return "transfers";
  }
}
