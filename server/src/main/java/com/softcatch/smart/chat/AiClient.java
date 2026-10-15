package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatMessageResponse;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// FastAPI(내부 전용) 호출. 사용자 JWT를 그대로 전달하고, 실패·타임아웃은 채팅만 실패시킨다(NFR-003).
@Component
class AiClient {

  private record AiRequest(String threadId, String message) {}

  private record AiEnvelope(boolean success, ChatMessageResponse data, Object error) {}

  private final RestClient rest;

  AiClient(
      @Value("${smart.ai.base-url}") String baseUrl,
      @Value("${smart.ai.read-timeout}") Duration readTimeout) {
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(readTimeout);
    this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
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
      throw new ApiException(ErrorCode.AI_SERVER_UNAVAILABLE);
    }
  }
}
