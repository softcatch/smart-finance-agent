package com.softcatch.smart.chat;

import static org.hamcrest.Matchers.startsWith;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatThreadControllerTest {

  private record MemberSession(Long memberId, String token) {}

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 새_대화_생성_성공() throws Exception {
    MemberSession session = signupAndLogin("chat" + System.nanoTime(), "pw12345!", "채팅1");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/chat/threads")
                .header("Authorization", "Bearer " + session.token()))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.threadId").value(startsWith("th_")));
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
