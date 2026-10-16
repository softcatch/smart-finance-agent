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
public class FakeAiServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  public static final String DEFAULT_BODY =
      "{\"success\":true,\"data\":{\"reply\":\"안녕하세요\",\"guard\":\"PASS\"},\"error\":null}";
  public volatile int status = 200;
  public volatile String responseBody = DEFAULT_BODY;
  public volatile long delayMillis = 0;
  public volatile String lastAuthorization;
  public volatile String lastBody;
  public final AtomicInteger calls = new AtomicInteger();

  // 상세 보기(I-02): GET /internal/details/{detailId}
  public static final String DETAIL_BODY =
      "{\"success\":true,\"data\":{\"report\":\"보고서입니다\",\"table\":{\"columns\":[\"기간\",\"항목\",\"삼성전자\"],"
          + "\"rows\":[[\"2026년 1분기\",\"매출액\",\"791,405억원 (+10.0%)\"]],\"note\":\"연결 기준\"}},"
          + "\"error\":null}";
  public volatile int detailStatus = 200;
  public volatile String detailBody = DETAIL_BODY;
  public volatile long detailDelayMillis = 0;
  public volatile String lastDetailPath;
  public volatile String lastDetailAuthorization;
  public final AtomicInteger detailCalls = new AtomicInteger();

  public FakeAiServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/chat",
        exchange -> {
          calls.incrementAndGet();
          lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
          lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          // 실제 FastAPI(uvicorn)는 HTTP/2 업그레이드(Upgrade: h2c)가 붙은 POST를 400으로 거부한다.
          // JDK HttpClient는 기본값이 HTTP/2라 이 헤더를 붙이므로, 호출하는 쪽이 HTTP/1.1이어야 통과한다.
          if (exchange.getRequestHeaders().containsKey("Upgrade")) {
            byte[] rejected = "Invalid HTTP request received.".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, rejected.length);
            exchange.getResponseBody().write(rejected);
            exchange.close();
            return;
          }
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
    server.createContext(
        "/internal/details",
        exchange -> {
          detailCalls.incrementAndGet();
          lastDetailPath = exchange.getRequestURI().getPath();
          lastDetailAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
          try {
            Thread.sleep(detailDelayMillis);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          byte[] out = detailBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(detailStatus, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.setExecutor(executor);
    server.start();
  }

  public void reset() {
    status = 200;
    responseBody = DEFAULT_BODY;
    delayMillis = 0;
    calls.set(0);
    lastAuthorization = null;
    lastBody = null;
    detailStatus = 200;
    detailBody = DETAIL_BODY;
    detailDelayMillis = 0;
    detailCalls.set(0);
    lastDetailPath = null;
    lastDetailAuthorization = null;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
