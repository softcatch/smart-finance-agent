package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatDetailResponse;
import com.softcatch.smart.chat.dto.response.ChatMessageResponse;
import com.softcatch.smart.chat.dto.response.ChatTable;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// FastAPI(내부 전용) 호출. 사용자 JWT를 그대로 전달하고, 실패·타임아웃은 채팅만 실패시킨다(NFR-003).
@Component
class AiClient {

  private static final Logger log = LoggerFactory.getLogger(AiClient.class);

  private record AiRequest(String threadId, String message) {}

  private record AiEnvelope(boolean success, ChatMessageResponse data, Object error) {}

  private record DetailData(String report, ChatTable table) {}

  private record DetailEnvelope(boolean success, DetailData data, Object error) {}

  private final RestClient rest;
  private final RestClient detailRest; // 보고서 생성은 오래 걸려 읽기 제한이 더 길다

  AiClient(
      @Value("${smart.ai.base-url}") String baseUrl,
      @Value("${smart.ai.read-timeout}") Duration readTimeout,
      @Value("${smart.ai.detail-read-timeout}") Duration detailReadTimeout) {
    this.rest = client(baseUrl, readTimeout);
    this.detailRest = client(baseUrl, detailReadTimeout);
  }

  private static RestClient client(String baseUrl, Duration readTimeout) {
    // HTTP/1.1로 고정한다 — JDK HttpClient는 기본이 HTTP/2라 평문 HTTP에 업그레이드(Upgrade: h2c)를
    // 붙이는데, FastAPI(uvicorn)는 본문이 있는 POST에서 이를 400으로 거부한다.
    HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  ChatMessageResponse chat(String token, String threadId, String message) {
    try {
      AiEnvelope res =
          rest.post()
              .uri("/internal/chat")
              .header("Authorization", "Bearer " + token)
              .body(new AiRequest(threadId, message))
              .retrieve()
              .body(AiEnvelope.class);
      if (res == null || !res.success() || res.data() == null) {
        throw new ApiException(ErrorCode.AI_SERVER_UNAVAILABLE);
      }
      return res.data();
    } catch (RestClientException e) { // 연결 실패·타임아웃·4xx/5xx
      log.warn("AI 서버 호출 실패: {}", e.toString()); // 응답 코드에는 원인이 안 담기므로 로그로 남긴다
      throw new ApiException(ErrorCode.AI_SERVER_UNAVAILABLE);
    }
  }

  // 상세 보기 보고서(I-02). FastAPI가 모르는 ID(404)는 DETAIL_NOT_FOUND, 그 밖의 실패는 채팅과 같다.
  ChatDetailResponse detail(String token, String detailId) {
    try {
      DetailEnvelope res =
          detailRest
              .get()
              .uri("/internal/details/{detailId}", detailId)
              .header("Authorization", "Bearer " + token)
              .retrieve()
              .body(DetailEnvelope.class);
      if (res == null || !res.success() || res.data() == null) {
        throw new ApiException(ErrorCode.AI_SERVER_UNAVAILABLE);
      }
      return new ChatDetailResponse(detailId, res.data().report(), res.data().table());
    } catch (HttpClientErrorException.NotFound e) {
      throw new ApiException(ErrorCode.DETAIL_NOT_FOUND);
    } catch (RestClientException e) {
      log.warn("AI 서버 상세 보기 호출 실패: {}", e.toString());
      throw new ApiException(ErrorCode.AI_SERVER_UNAVAILABLE);
    }
  }
}
