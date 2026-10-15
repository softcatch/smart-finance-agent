package com.softcatch.smart.chat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// 가짜 FastAPI: JDK 내장 HttpServer, 랜덤 포트. 받은 요청을 기록하고 정해 둔 응답을 돌려준다.
// 요청마다 스레드를 써서, 타임아웃 테스트가 남긴 지연 핸들러가 다음 요청을 막지 않게 한다.
class FakeAiServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  static final String DEFAULT_BODY =
      "{\"success\":true,\"data\":{\"reply\":\"안녕하세요\",\"guard\":\"PASS\"},\"error\":null}";
  volatile int status = 200;
  volatile String responseBody = DEFAULT_BODY;
  volatile long delayMillis = 0;
  volatile String lastAuthorization;
  volatile String lastBody;
  final AtomicInteger calls = new AtomicInteger();

  FakeAiServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/chat",
        exchange -> {
          calls.incrementAndGet();
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
    server.setExecutor(executor);
    server.start();
  }

  void reset() {
    status = 200;
    responseBody = DEFAULT_BODY;
    delayMillis = 0;
    calls.set(0);
    lastAuthorization = null;
    lastBody = null;
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
