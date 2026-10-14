package com.softcatch.smart.chat;

import static org.hamcrest.Matchers.startsWith;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
  }

  @AfterAll
  static void stopFakeAi() {
    FAKE_AI.close();
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ChatThreadRepository chatThreads;
  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void resetFakeAi() {
    FAKE_AI.reset();
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
