package com.softcatch.smart.chat;

import static org.hamcrest.Matchers.startsWith;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatThreadControllerTest {

  private record MemberSession(Long memberId, String token) {}

  // 메시지 API가 부르는 FastAPI 자리에 가짜 서버를 둔다. 타임아웃은 짧게(1초).
  private static final FakeAiServer FAKE_AI = startFakeAi();

  private static FakeAiServer startFakeAi() {
    try {
      return new FakeAiServer();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void aiProperties(DynamicPropertyRegistry registry) {
    registry.add("smart.ai.base-url", FAKE_AI::baseUrl);
    registry.add("smart.ai.read-timeout", () -> "1s");
    registry.add("smart.ai.detail-read-timeout", () -> "1s");
  }

  @AfterAll
  static void stopFakeAi() {
    FAKE_AI.close();
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ChatThreadRepository chatThreads;
  @Autowired private ChatDetailRepository chatDetails;
  @Autowired private JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();
  private AiUsageFixture usage;

  @BeforeEach
  void resetFakeAi() {
    FAKE_AI.reset();
  }

  @BeforeEach
  void createUsageTable() {
    usage = new AiUsageFixture(jdbc);
    usage.create();
  }

  @Test
  void 새_대화_생성_성공() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅1");

    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/chat/threads")
                    .header("Authorization", "Bearer " + session.token()))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andExpect(MockMvcResultMatchers.jsonPath("$.data.threadId").value(startsWith("th_")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String threadId = json.readTree(body).at("/data/threadId").asText();

    ChatThread saved = chatThreads.findByThreadId(threadId).orElseThrow();
    Assertions.assertEquals(session.memberId(), saved.getMemberId());
  }

  @Test
  void 인증_없이_호출하면_401() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/chat/threads"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void 호출마다_다른_threadId_발급() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅2");

    String first = createThread(session.token());
    String second = createThread(session.token());

    Assertions.assertNotEquals(first, second);
  }

  @Test
  void 메시지_전송_성공_reply와_guard_반환_JWT_전달() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅3");
    String threadId = createThread(session.token());

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.reply").value("안녕하세요"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.guard").value("PASS"));

    Assertions.assertEquals("Bearer " + session.token(), FAKE_AI.lastAuthorization);
    Assertions.assertEquals(
        "{\"threadId\":\"" + threadId + "\",\"message\":\"안녕\"}", FAKE_AI.lastBody);
  }

  @Test
  void 확인_카드가_있으면_transferCard를_그대로_전달한다() throws Exception {
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"김영희님께 10,000원을 보낼까요?\",\"guard\":\"PASS\",\"transferCard\":{\"requestId\":\"tr_7f3a\",\"recipientName\":\"김영희\",\"recipientAccountNumber\":\"123456789012\",\"fromAccountNumber\":\"987654321098\",\"amount\":10000,\"expiresAt\":\"2026-09-27T10:05:00+09:00\"}},\"error\":null}";
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅카드");
    String threadId = createThread(session.token());

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.transferCard.requestId").value("tr_7f3a"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.transferCard.recipientName").value("김영희"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.transferCard.amount").value(10000));
  }

  @Test
  void 확인_카드가_없으면_transferCard_필드를_내리지_않는다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅무카드");
    String threadId = createThread(session.token());

    String body =
        sendMessage(session.token(), threadId)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertFalse(body.contains("transferCard"), body);
  }

  @Test
  void 표가_있으면_table을_그대로_전달한다() throws Exception {
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"재무 비교입니다.\",\"guard\":\"PASS\",\"table\":{\"columns\":[\"항목\",\"삼성전자\",\"SK하이닉스\"],\"rows\":[[\"매출액\",\"791,405억원 (+10.0%)\",\"미공시\"],[\"영업이익\",\"66,853억원 (+1.2%)\",\"미공시\"]],\"note\":\"2026년 1분기 · 연결 기준\"}},\"error\":null}";
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅표");
    String threadId = createThread(session.token());

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.table.columns[1]").value("삼성전자"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.table.rows[0][1]").value("791,405억원 (+10.0%)"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.table.note").value("2026년 1분기 · 연결 기준"));
  }

  @Test
  void 표가_없으면_table_필드를_내리지_않는다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅무표");
    String threadId = createThread(session.token());

    String body =
        sendMessage(session.token(), threadId)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertFalse(body.contains("table"), body);
  }

  @Test
  void 상세_보기_ID가_오면_그대로_전달하고_본인_것으로_기록한다() throws Exception {
    String detailId = "dt_" + System.nanoTime();
    FAKE_AI.responseBody =
        "{\"success\":true,\"data\":{\"reply\":\"재무 비교입니다.\",\"guard\":\"PASS\",\"detailId\":\""
            + detailId
            + "\"},\"error\":null}";
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅상세");
    String threadId = createThread(session.token());

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.detailId").value(detailId));

    Assertions.assertEquals(
        session.memberId(), chatDetails.findByDetailId(detailId).orElseThrow().getMemberId());
  }

  @Test
  void 상세_보기_ID가_없으면_detailId_필드를_내리지_않고_기록하지_않는다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅무상세");
    String threadId = createThread(session.token());
    long before = chatDetails.count();

    String body =
        sendMessage(session.token(), threadId)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertFalse(body.contains("detailId"), body);
    Assertions.assertEquals(before, chatDetails.count());
  }

  @Test
  void 남의_스레드면_404_THREAD_NOT_FOUND이고_FastAPI를_부르지_않는다() throws Exception {
    MemberSession owner = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "주인");
    MemberSession other = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "타인");
    String threadId = createThread(owner.token());

    sendMessage(other.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("THREAD_NOT_FOUND"));

    Assertions.assertEquals(0, FAKE_AI.calls.get());
  }

  @Test
  void 없는_스레드면_404_THREAD_NOT_FOUND() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅4");

    sendMessage(session.token(), "th_없는스레드")
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("THREAD_NOT_FOUND"));
  }

  @Test
  void 메시지_전송은_인증_없으면_401() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/chat/threads/th_1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"안녕\"}"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void FastAPI가_타임아웃이면_503_AI_SERVER_UNAVAILABLE() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅5");
    String threadId = createThread(session.token());
    FAKE_AI.delayMillis = 3000;

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_SERVER_UNAVAILABLE"));
  }

  @Test
  void FastAPI가_5xx면_503_AI_SERVER_UNAVAILABLE() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅6");
    String threadId = createThread(session.token());
    FAKE_AI.status = 500;

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_SERVER_UNAVAILABLE"));
  }

  @Test
  void 분당_10회를_넘으면_429_RATE_LIMITED() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅7");
    String threadId = createThread(session.token());

    for (int i = 0; i < ChatRateLimiter.LIMIT; i++) {
      sendMessage(session.token(), threadId).andExpect(MockMvcResultMatchers.status().isOk());
    }
    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("RATE_LIMITED"));

    Assertions.assertEquals(ChatRateLimiter.LIMIT, FAKE_AI.calls.get()); // 제한 초과분은 FastAPI로 안 간다
  }

  @Test
  void 상세_보기는_본인_것이면_보고서와_표를_돌려주고_JWT를_전달한다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세1");
    String detailId = ownedDetail(session);

    getDetail(session.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.detailId").value(detailId))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.report").value("보고서입니다"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.table.columns[2]").value("삼성전자"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.table.note").value("연결 기준"));

    Assertions.assertEquals("/internal/details/" + detailId, FAKE_AI.lastDetailPath);
    Assertions.assertEquals("Bearer " + session.token(), FAKE_AI.lastDetailAuthorization);
  }

  @Test
  void 상세_보기에서_표가_없으면_table_필드를_내리지_않는다() throws Exception {
    FAKE_AI.detailBody = "{\"success\":true,\"data\":{\"report\":\"수집 전입니다\"},\"error\":null}";
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세2");

    String body =
        getDetail(session.token(), ownedDetail(session))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Assertions.assertFalse(body.contains("table"), body);
  }

  @Test
  void 남의_상세_보기는_404_DETAIL_NOT_FOUND이고_FastAPI를_부르지_않는다() throws Exception {
    MemberSession owner = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세주인");
    MemberSession other = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세타인");
    String detailId = ownedDetail(owner);

    getDetail(other.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("DETAIL_NOT_FOUND"));

    Assertions.assertEquals(0, FAKE_AI.detailCalls.get());
  }

  @Test
  void 없는_상세_보기는_404_DETAIL_NOT_FOUND() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세3");

    getDetail(session.token(), "dt_없는ID")
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("DETAIL_NOT_FOUND"));
  }

  @Test
  void 상세_보기는_인증_없으면_401() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/chat/details/dt_1"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void FastAPI가_모르는_상세_보기면_404_DETAIL_NOT_FOUND() throws Exception {
    FAKE_AI.detailStatus = 404;
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세4");

    getDetail(session.token(), ownedDetail(session))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("DETAIL_NOT_FOUND"));
  }

  @Test
  void 상세_보기에서_FastAPI가_5xx거나_느리면_503_AI_SERVER_UNAVAILABLE() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세5");
    String detailId = ownedDetail(session);

    FAKE_AI.detailStatus = 500;
    getDetail(session.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_SERVER_UNAVAILABLE"));

    FAKE_AI.detailStatus = 200;
    FAKE_AI.detailDelayMillis = 3000;
    getDetail(session.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_SERVER_UNAVAILABLE"));
  }

  @Test
  void 채팅이_장애여도_이미_발급된_상세_보기는_열린다() throws Exception {
    // 채팅 호출(/internal/chat)이 5xx여도 상세 보기 호출(/internal/details)은 별개 경로다.
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "상세6");
    String detailId = ownedDetail(session);
    FAKE_AI.status = 500;

    getDetail(session.token(), detailId).andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void 정지된_회원은_403_AI_BLOCKED이고_FastAPI를_부르지_않는다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "정지1");
    String threadId = createThread(session.token());
    String detailId = ownedDetail(session);
    blockAi(session);

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_BLOCKED"));
    getDetail(session.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_BLOCKED"));

    Assertions.assertEquals(0, FAKE_AI.calls.get());
    Assertions.assertEquals(0, FAKE_AI.detailCalls.get());
  }

  @Test
  void 정지돼도_계좌_API는_정상이다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "정지2");
    blockAi(session);

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/accounts")
                .header("Authorization", "Bearer " + session.token()))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void 오늘_토큰_합계가_한도_이상이면_429_AI_QUOTA_EXCEEDED이고_FastAPI를_부르지_않는다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "한도1");
    String threadId = createThread(session.token());
    String detailId = ownedDetail(session);
    usage.addNow(session.memberId(), 300_000, 150_000);
    usage.addNow(session.memberId(), 0, 50_000); // 입력+출력, 여러 줄의 합이 한도(500,000)와 같다

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"));
    getDetail(session.token(), detailId)
        .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_QUOTA_EXCEEDED"));

    Assertions.assertEquals(0, FAKE_AI.calls.get());
    Assertions.assertEquals(0, FAKE_AI.detailCalls.get());
  }

  @Test
  void 한도_바로_아래면_통과한다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "한도2");
    String threadId = createThread(session.token());
    usage.addNow(session.memberId(), 300_000, 199_999);

    sendMessage(session.token(), threadId).andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void 어제_사용분은_세지_않고_오늘_0시_정각부터_센다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "한도3");
    String threadId = createThread(session.token());
    OffsetDateTime midnight = AiUsageFixture.startOfTodayKst();

    usage.add(session.memberId(), "gpt-5-nano", 600_000, 0, false, midnight.minusMinutes(1));
    sendMessage(session.token(), threadId).andExpect(MockMvcResultMatchers.status().isOk());

    usage.add(session.memberId(), "gpt-5-nano", 500_000, 0, false, midnight);
    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isTooManyRequests());
  }

  @Test
  void 다른_회원의_사용량은_세지_않는다() throws Exception {
    MemberSession heavy = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "많이쓴사람");
    MemberSession light = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "적게쓴사람");
    usage.addNow(heavy.memberId(), 600_000, 0);

    sendMessage(light.token(), createThread(light.token()))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  void 정지와_한도가_겹치면_AI_BLOCKED가_먼저다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "겹침");
    String threadId = createThread(session.token());
    blockAi(session);
    usage.addNow(session.memberId(), 600_000, 0);

    sendMessage(session.token(), threadId)
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AI_BLOCKED"));
  }

  @Test
  void ai_usage_테이블이_아직_없어도_채팅과_상세_보기가_동작한다() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "첫기록전");
    String threadId = createThread(session.token());
    String detailId = ownedDetail(session);
    usage.drop(); // FastAPI가 첫 기록 때 만들기 전

    sendMessage(session.token(), threadId).andExpect(MockMvcResultMatchers.status().isOk());
    getDetail(session.token(), detailId).andExpect(MockMvcResultMatchers.status().isOk());
  }

  // 관리자 정지(E-17)와 같은 상태로 만든다.
  private void blockAi(MemberSession session) {
    jdbc.update("UPDATE member SET ai_blocked = TRUE WHERE id = ?", session.memberId());
  }

  // 채팅 응답으로 발급된 상세 보기 ID를 본인 것으로 만든다(FAKE_AI가 detailId가 든 응답을 준다).
  private String ownedDetail(MemberSession session) throws Exception {
    String detailId = "dt_" + System.nanoTime();
    chatDetails.save(new ChatDetail(detailId, session.memberId()));
    return detailId;
  }

  private ResultActions getDetail(String token, String detailId) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.get("/api/v1/chat/details/" + detailId)
            .header("Authorization", "Bearer " + token));
  }

  private ResultActions sendMessage(String token, String threadId) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post("/api/v1/chat/threads/" + threadId + "/messages")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"안녕\"}"));
  }

  private String createThread(String token) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/chat/threads")
                    .header("Authorization", "Bearer " + token))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/threadId").asText();
  }

  private MemberSession signupAndLogin(String loginId, String password, String name)
      throws Exception {
    String signupBody =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new SignupRequest(loginId, password, name))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long memberId = json.readTree(signupBody).at("/data/memberId").asLong();

    String loginBody =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new LoginRequest(loginId, password))))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(loginBody).at("/data/accessToken").asText();

    return new MemberSession(memberId, token);
  }
}
