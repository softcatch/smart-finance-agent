package com.softcatch.smart.admin;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.admin.dto.request.AiBlockRequest;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

  private record MemberSession(Long memberId, String token) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private MemberRepository members;
  @Autowired private JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 관리자가_AI_사용을_정지하고_해제한다_사유가_남는다() throws Exception {
    MemberSession admin = adminSession("관리자1");
    MemberSession target = signupAndLogin("blk" + System.nanoTime(), "대상1");

    block(admin.token(), target.memberId(), true, "하루 한도를 반복해서 넘김")
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.memberId").value(target.memberId()))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.blocked").value(true));
    Assertions.assertTrue(members.findById(target.memberId()).orElseThrow().isAiBlocked());
    Assertions.assertEquals("하루 한도를 반복해서 넘김", blockReason(target.memberId()));

    block(admin.token(), target.memberId(), false, null)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.blocked").value(false));
    Assertions.assertFalse(members.findById(target.memberId()).orElseThrow().isAiBlocked());
    Assertions.assertNull(blockReason(target.memberId()));
  }

  @Test
  void 관리자가_아니면_403_ADMIN_ONLY이고_정지되지_않는다() throws Exception {
    MemberSession user = signupAndLogin("usr" + System.nanoTime(), "일반1");
    MemberSession target = signupAndLogin("blk" + System.nanoTime(), "대상2");

    block(user.token(), target.memberId(), true, "권한 없음")
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ADMIN_ONLY"));

    Assertions.assertFalse(members.findById(target.memberId()).orElseThrow().isAiBlocked());
  }

  @Test
  void 역할을_DB에서_바꾸면_같은_토큰에_바로_적용된다() throws Exception {
    MemberSession member = signupAndLogin("role" + System.nanoTime(), "역할1");
    MemberSession target = signupAndLogin("blk" + System.nanoTime(), "대상3");

    block(member.token(), target.memberId(), true, null)
        .andExpect(MockMvcResultMatchers.status().isForbidden());

    setRole(member.memberId(), "ADMIN");
    block(member.token(), target.memberId(), true, null)
        .andExpect(MockMvcResultMatchers.status().isOk());

    setRole(member.memberId(), "USER");
    block(member.token(), target.memberId(), false, null)
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ADMIN_ONLY"));
  }

  @Test
  void 없는_회원이면_404_MEMBER_NOT_FOUND() throws Exception {
    MemberSession admin = adminSession("관리자2");

    block(admin.token(), Long.MAX_VALUE, true, null)
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
  }

  @Test
  void 인증_없으면_401() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/admin/members/1/ai-block")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blocked\":true}"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  private ResultActions block(String token, Long memberId, boolean blocked, String reason)
      throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.patch("/api/v1/admin/members/" + memberId + "/ai-block")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new AiBlockRequest(blocked, reason))));
  }

  private String blockReason(Long memberId) {
    return jdbc.queryForObject(
        "SELECT ai_block_reason FROM member WHERE id = ?", String.class, memberId);
  }

  // 운영자가 DB에서 지정하는 것과 같은 방법으로 관리자를 만든다.
  private MemberSession adminSession(String name) throws Exception {
    MemberSession session = signupAndLogin("adm" + System.nanoTime(), name);
    setRole(session.memberId(), "ADMIN");
    return session;
  }

  private void setRole(Long memberId, String role) {
    jdbc.update("UPDATE member SET role = ? WHERE id = ?", role, memberId);
  }

  private MemberSession signupAndLogin(String loginId, String name) throws Exception {
    String signupBody =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new SignupRequest(loginId, "pw12345!", name))))
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
                    .content(json.writeValueAsString(new LoginRequest(loginId, "pw12345!"))))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new MemberSession(memberId, json.readTree(loginBody).at("/data/accessToken").asText());
  }
}
