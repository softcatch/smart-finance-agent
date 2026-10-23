package com.softcatch.smart;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// nginx(NFR-053)가 넘기는 X-Forwarded-For·X-Forwarded-Proto를 Spring이 알아보는지. 실제 서버(Tomcat)로 건다 —
// 이 헤더는 Tomcat이 처리해서 MockMvc로는 볼 수 없다. 테스트는 루프백(내부망)에서 부르므로 프록시로 믿는다.
@Import({TestcontainersConfiguration.class, ForwardedHeadersTest.Echo.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedHeadersTest {

  @RestController
  static class Echo {
    @GetMapping("/test-echo")
    String echo(HttpServletRequest request) {
      return request.getRemoteAddr() + " " + request.getScheme() + " " + request.isSecure();
    }
  }

  @LocalServerPort int port;

  @Test
  void 프록시가_넘긴_실제_IP와_https를_알아본다() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/test-echo"))
            .header("X-Forwarded-For", "203.0.113.9")
            .header("X-Forwarded-Proto", "https")
            .build();

    String body =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();

    Assertions.assertEquals("203.0.113.9 https true", body);
  }

  @Test
  void 헤더가_없으면_접속한_그대로다() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/test-echo")).build();

    String body =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();

    Assertions.assertEquals("127.0.0.1 http false", body);
  }
}
