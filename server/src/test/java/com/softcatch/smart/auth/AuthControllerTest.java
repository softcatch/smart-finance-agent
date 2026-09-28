package com.softcatch.smart.auth;

import static org.hamcrest.Matchers.not;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.common.ApiException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class AuthControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthService authService;
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
        .andExpect(MockMvcResultMatchers.status().isUnauthorized())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AUTH_INVALID"));
  }

  @Test
  void 토큰_없이_내부API_접근하면_401() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/internal/v1/accounts"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AUTH_INVALID"));
  }

  @Test
  void 아이디_중복이면_409() throws Exception {
    signup("dup01", "pw12345!", "김철수");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SignupRequest("dup01", "pw12345!", "김철수"))))
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
                .content(json.writeValueAsString(new LoginRequest("wrongpw01", "다른비번"))))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("AUTH_INVALID"));
  }

  @Test
  void 동시_중복_가입은_하나만_성공하고_나머지는_409로_처리된다() throws Exception {
    String loginId = "race" + System.nanoTime();
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Long> attempt =
        () -> {
          barrier.await();
          return authService.signup(loginId, "pw12345!", "동시가입");
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Long> first = pool.submit(attempt);
    Future<Long> second = pool.submit(attempt);

    int success = 0;
    int duplicate = 0;
    for (Future<Long> result : List.of(first, second)) {
      try {
        result.get();
        success++;
      } catch (ExecutionException e) {
        Assertions.assertInstanceOf(ApiException.class, e.getCause());
        duplicate++;
      }
    }
    pool.shutdown();

    Assertions.assertEquals(1, success);
    Assertions.assertEquals(1, duplicate);
  }

  private void signup(String loginId, String password, String name) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new SignupRequest(loginId, password, name))))
        .andExpect(MockMvcResultMatchers.status().isCreated());
  }

  private String login(String loginId, String password) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new LoginRequest(loginId, password))))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/accessToken").asText();
  }
}
