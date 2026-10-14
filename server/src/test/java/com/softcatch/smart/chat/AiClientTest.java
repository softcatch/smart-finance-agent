package com.softcatch.smart.chat;

import com.softcatch.smart.chat.dto.response.ChatMessageResponse;
import com.softcatch.smart.common.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiClientTest {

  private FakeAiServer fake;
  private AiClient client;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeAiServer();
    client = new AiClient(fake.baseUrl(), Duration.ofSeconds(1));
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  @Test
  void 성공하면_reply와_guard를_돌려주고_JWT와_본문을_전달한다() {
    ChatMessageResponse res = client.chat("jwt-token", "th_1", "안녕");

    Assertions.assertEquals(new ChatMessageResponse("안녕하세요", "PASS"), res);
    Assertions.assertEquals("Bearer jwt-token", fake.lastAuthorization);
    Assertions.assertEquals("{\"threadId\":\"th_1\",\"message\":\"안녕\"}", fake.lastBody);
  }

  @Test
  void 서버가_5xx면_AI_SERVER_UNAVAILABLE() {
    fake.status = 500;

    Assertions.assertThrows(ApiException.class, () -> client.chat("t", "th_1", "안녕"));
  }

  @Test
  void 응답이_타임아웃을_넘으면_AI_SERVER_UNAVAILABLE() {
    fake.delayMillis = 3000;

    Assertions.assertThrows(ApiException.class, () -> client.chat("t", "th_1", "안녕"));
  }

  @Test
  void 타임아웃_뒤에_지연_핸들러가_남아도_다음_요청은_막히지_않는다() {
    fake.delayMillis = 3000;
    Assertions.assertThrows(ApiException.class, () -> client.chat("t", "th_1", "안녕"));

    fake.delayMillis = 0; // 앞 핸들러는 아직 잠들어 있다
    ChatMessageResponse res = client.chat("t", "th_1", "안녕");

    Assertions.assertEquals("PASS", res.guard());
  }

  @Test
  void 서버가_내려가_있으면_AI_SERVER_UNAVAILABLE() {
    fake.close();

    Assertions.assertThrows(ApiException.class, () -> client.chat("t", "th_1", "안녕"));
  }
}
