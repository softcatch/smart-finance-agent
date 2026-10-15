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

    Assertions.assertEquals(new ChatMessageResponse("안녕하세요", "PASS", null, null, null), res);
    Assertions.assertEquals("Bearer jwt-token", fake.lastAuthorization);
    Assertions.assertEquals("{\"threadId\":\"th_1\",\"message\":\"안녕\"}", fake.lastBody);
  }

  @Test
  void 확인_카드가_오면_transferCard로_파싱한다() {
    fake.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"김영희님께 10,000원을 보낼까요?\",\"guard\":\"PASS\",\"transferCard\":{\"requestId\":\"tr_7f3a\",\"recipientName\":\"김영희\",\"recipientAccountNumber\":\"123456789012\",\"fromAccountNumber\":\"987654321098\",\"amount\":10000,\"expiresAt\":\"2026-09-27T10:05:00+09:00\"}},\"error\":null}";

    ChatMessageResponse res = client.chat("t", "th_1", "엄마한테 1만원");

    Assertions.assertEquals("tr_7f3a", res.transferCard().requestId());
    Assertions.assertEquals("김영희", res.transferCard().recipientName());
    Assertions.assertEquals(10000L, res.transferCard().amount());
  }

  @Test
  void 표가_오면_table로_파싱한다() {
    fake.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"재무 비교입니다.\",\"guard\":\"PASS\",\"table\":{\"columns\":[\"항목\",\"삼성전자\",\"SK하이닉스\"],\"rows\":[[\"매출액\",\"791,405억원 (+10.0%)\",\"미공시\"],[\"영업이익\",\"66,853억원 (+1.2%)\",\"미공시\"]],\"note\":\"2026년 1분기 · 연결 기준\"}},\"error\":null}";

    ChatMessageResponse res = client.chat("t", "th_1", "삼성전자 실적");

    Assertions.assertEquals(java.util.List.of("항목", "삼성전자", "SK하이닉스"), res.table().columns());
    Assertions.assertEquals("영업이익", res.table().rows().get(1).get(0));
    Assertions.assertEquals("2026년 1분기 · 연결 기준", res.table().note());
  }

  @Test
  void 상세_보기_ID가_오면_detailId로_파싱한다() {
    fake.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"재무 비교입니다.\",\"guard\":\"PASS\",\"detailId\":\"dt_91c2\"},\"error\":null}";

    ChatMessageResponse res = client.chat("t", "th_1", "삼성전자 실적");

    Assertions.assertEquals("dt_91c2", res.detailId());
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
