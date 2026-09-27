package com.softcatch.smart.auth;

import static org.hamcrest.Matchers.not;

import com.softcatch.smart.TestcontainersConfiguration;
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
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 가입_로그인_토큰으로_보호API_접근() throws Exception {
    signup("jinho01", "pw12345!", "홍길동");

    String token = login("jinho01", "pw12345!");

    // /api/v1/transfers 같은 실제 엔드포인트는 아직 없다. 인증을 통과하면 라우팅 실패로 404가 나고,
    // 인증에서 막히면 401이 난다 — 두 값의 차이로 Bearer 검증이 도는지 확인한다.
    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/transfers")
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().is(not(401)));
  }

  @Test
  void 토큰_없이_보호API_접근하면_401() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/transfers"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void 아이디_중복이면_409() throws Exception {
    signup("dup01", "pw12345!", "김철수");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new AuthController.SignupRequest("dup01", "pw12345!", "김철수"))))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("DUPLICATE_LOGIN_ID"));
  }

  @Test
  void 비밀번호_틀리면_401() throws Exception {
    signup("wrongpw01", "pw12345!", "이영희");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(new AuthController.LoginRequest("wrongpw01", "다른비번"))))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AUTH_INVALID"));
  }

  private void signup(String loginId, String password, String name) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new AuthController.SignupRequest(loginId, password, name))))
        .andExpect(MockMvcResultMatchers.status().isCreated());
  }

  private String login(String loginId, String password) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new AuthController.LoginRequest(loginId, password))))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/accessToken").asText();
  }
}
