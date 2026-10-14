package com.softcatch.smart.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

// 가짜 FastAPI: JDK 내장 HttpServer, 랜덤 포트. 받은 요청을 기록하고 정해 둔 응답을 돌려준다.
class FakeAiServer implements AutoCloseable {

  private final HttpServer server;
  volatile int status = 200;
  volatile String responseBody =
      "{\"success\":true,\"data\":{\"reply\":\"안녕하세요\",\"guard\":\"PASS\"},\"error\":null}";
  volatile long delayMillis = 0;
  volatile String lastAuthorization;
  volatile String lastBody;
  volatile int calls = 0;

  FakeAiServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/chat",
        exchange -> {
          calls++;
          lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
          lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          try {
            Thread.sleep(delayMillis);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
