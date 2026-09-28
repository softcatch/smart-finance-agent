package com.softcatch.smart.alias;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.alias.dto.request.AliasRequest;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import java.util.UUID;
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
class AliasControllerTest {

  private record MemberSession(Long memberId, String token) {}

  @Autowired private MockMvc mockMvc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 별칭_등록_성공() throws Exception {
    String ownerName = "홍길동";
    MemberSession owner = signupAndLogin("ali" + System.nanoTime(), "pw12345!", ownerName);
    String ownerAccountNumber = openAccount(owner.token());

    MemberSession registrant = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "김철수");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/aliases")
                .header("Authorization", "Bearer " + registrant.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AliasRequest("엄마", ownerAccountNumber))))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.alias").value("엄마"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.accountNumber").value(ownerAccountNumber))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.ownerName").value(ownerName));
  }

  @Test
  void 별칭_등록_없는_계좌번호면_404() throws Exception {
    MemberSession session = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "이영희");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/aliases")
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AliasRequest("친구", "999999999999"))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
  }

  @Test
  void 같은_별칭_두번_등록하면_409() throws Exception {
    MemberSession owner = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "계좌주인2");
    String accountNumber = openAccount(owner.token());

    MemberSession session = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "중복등록자");
    registerAlias(session.token(), "친구", accountNumber);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/aliases")
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AliasRequest("친구", accountNumber))))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("DUPLICATE_ALIAS"));
  }

  @Test
  void 별칭_목록_조회() throws Exception {
    String motherName = "엄마이름";
    MemberSession motherAccount = signupAndLogin("ali" + System.nanoTime(), "pw12345!", motherName);
    String accountA = openAccount(motherAccount.token());

    String friendName = "친구이름";
    MemberSession friendAccount = signupAndLogin("ali" + System.nanoTime(), "pw12345!", friendName);
    String accountB = openAccount(friendAccount.token());

    MemberSession session = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "본인");
    registerAlias(session.token(), "엄마", accountA);
    registerAlias(session.token(), "친구", accountB);

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/aliases")
                .header("Authorization", "Bearer " + session.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases.length()").value(2))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases[0].alias").value("엄마"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.aliases[0].accountNumber").value(accountA))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases[0].ownerName").value(motherName))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases[1].alias").value("친구"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.aliases[1].accountNumber").value(accountB))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases[1].ownerName").value(friendName));
  }

  @Test
  void 별칭_없으면_빈_배열() throws Exception {
    MemberSession session = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "빈목록");

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/aliases")
                .header("Authorization", "Bearer " + session.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases.length()").value(0));
  }

  @Test
  void 다른_회원_별칭은_안_섞임() throws Exception {
    MemberSession owner = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "계좌주인");
    String accountNumber = openAccount(owner.token());

    MemberSession registrant = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "등록자");
    registerAlias(registrant.token(), "별명", accountNumber);

    MemberSession stranger = signupAndLogin("ali" + System.nanoTime(), "pw12345!", "타인");

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/aliases")
                .header("Authorization", "Bearer " + stranger.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.aliases.length()").value(0));
  }

  private String openAccount(String token) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/accountNumber").asText();
  }

  private void registerAlias(String token, String alias, String accountNumber) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/aliases")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AliasRequest(alias, accountNumber))))
        .andExpect(MockMvcResultMatchers.status().isCreated());
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
