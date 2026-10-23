package com.softcatch.smart.admin;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.admin.dto.request.AiBlockRequest;
import com.softcatch.smart.auth.MemberRepository;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.chat.AiUsageFixture;
import com.softcatch.smart.chat.AiUsageRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
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
  private AiUsageFixture usage;

  @BeforeEach
  void createUsageTable() {
    usage = new AiUsageFixture(jdbc);
    usage.create();
  }

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

  @Test
  void 사용량_목록은_그날_KST_사용자별_합계를_많이_쓴_순으로_주고_비용을_추정한다() throws Exception {
    MemberSession admin = adminSession("관리자3");
    MemberSession heavy = signupAndLogin("hv" + System.nanoTime(), "많이");
    MemberSession light = signupAndLogin("lt" + System.nanoTime(), "적게");
    LocalDate day = LocalDate.of(2026, 3, 2); // 다른 테스트와 겹치지 않는 날
    usage.add(heavy.memberId(), "gpt-5-nano", 200_000, 60_000, false, at(day, 9, 0, 0));
    usage.add(heavy.memberId(), "gpt-5-nano", 130_000, 50_000, false, at(day, 13, 0, 0));
    usage.add(heavy.memberId(), "claude-haiku-5-5", 10_000, 4_000, true, at(day, 13, 1, 0));
    // 경계: 그날 0시 정각과 23:59:59는 그날이고, 전날 23:59:59와 다음 날 0시 정각은 아니다
    usage.add(light.memberId(), "gpt-5-nano", 1_000, 500, false, at(day, 0, 0, 0));
    usage.add(light.memberId(), "gpt-5-nano", 0, 0, false, at(day, 23, 59, 59));
    usage.add(
        light.memberId(), "gpt-5-nano", 999_999, 999_999, false, at(day.minusDays(1), 23, 59, 59));
    usage.add(
        light.memberId(), "gpt-5-nano", 999_999, 999_999, false, at(day.plusDays(1), 0, 0, 0));

    JsonNode items = items(usageList(admin.token(), day.toString()));

    JsonNode first = itemOf(items, heavy.memberId());
    Assertions.assertEquals(3, first.at("/calls").asInt());
    Assertions.assertEquals(1, first.at("/fallbackCalls").asInt()); // Claude로 간 호출
    Assertions.assertEquals(340_000, first.at("/inputTokens").asLong());
    Assertions.assertEquals(114_000, first.at("/outputTokens").asLong());
    // gpt-5-nano (330,000×$0.05 + 110,000×$0.40) + Claude Haiku 5.5 (10,000×$0.10 + 4,000×$0.50),
    // 백만 토큰당
    Assertions.assertEquals(0.0635, first.at("/costUsd").asDouble(), 1e-9);
    Assertions.assertFalse(first.at("/blocked").asBoolean());
    JsonNode second = itemOf(items, light.memberId());
    Assertions.assertEquals(2, second.at("/calls").asInt());
    Assertions.assertEquals(1_000, second.at("/inputTokens").asLong());
    Assertions.assertEquals(0.00025, second.at("/costUsd").asDouble(), 1e-9);
    Assertions.assertTrue(indexOf(items, heavy.memberId()) < indexOf(items, light.memberId()));
  }

  @Test
  void 응답에_하루_한도가_있다() throws Exception {
    MemberSession admin = adminSession("관리자4");

    usageList(admin.token(), "2026-03-03")
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.dailyLimitTokens").value(500_000));
  }

  @Test
  void 정지된_회원은_그날_사용이_없어도_목록에_있고_해제하면_빠진다() throws Exception {
    MemberSession admin = adminSession("관리자5");
    MemberSession idle = signupAndLogin("idle" + System.nanoTime(), "쉬는사람");
    block(admin.token(), idle.memberId(), true, "테스트")
        .andExpect(MockMvcResultMatchers.status().isOk());

    JsonNode item = itemOf(items(usageList(admin.token(), "2026-03-04")), idle.memberId());

    Assertions.assertNotNull(item);
    Assertions.assertEquals(0, item.at("/calls").asInt());
    Assertions.assertEquals(0, item.at("/inputTokens").asLong());
    Assertions.assertEquals(0.0, item.at("/costUsd").asDouble());
    Assertions.assertTrue(item.at("/blocked").asBoolean());

    block(admin.token(), idle.memberId(), false, null)
        .andExpect(MockMvcResultMatchers.status().isOk());
    Assertions.assertNull(itemOf(items(usageList(admin.token(), "2026-03-04")), idle.memberId()));
  }

  @Test
  void 날짜를_안_주면_오늘_KST의_사용량이다() throws Exception {
    MemberSession admin = adminSession("관리자6");
    MemberSession today = signupAndLogin("td" + System.nanoTime(), "오늘");
    MemberSession yesterday = signupAndLogin("yd" + System.nanoTime(), "어제");
    usage.addNow(today.memberId(), 100, 50);
    usage.add(
        yesterday.memberId(),
        "gpt-5-nano",
        100,
        50,
        false,
        AiUsageFixture.startOfTodayKst().minusMinutes(1));

    JsonNode items = items(usageList(admin.token(), null));

    Assertions.assertEquals(
        150,
        itemOf(items, today.memberId()).at("/inputTokens").asLong()
            + itemOf(items, today.memberId()).at("/outputTokens").asLong());
    Assertions.assertNull(itemOf(items, yesterday.memberId()));
  }

  @Test
  void 사용량_조회는_관리자가_아니면_403_ADMIN_ONLY이고_인증_없으면_401이다() throws Exception {
    MemberSession user = signupAndLogin("usr" + System.nanoTime(), "일반2");

    usageList(user.token(), null)
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ADMIN_ONLY"));
    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/admin/ai-usage"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void ai_usage_테이블이_아직_없어도_조회가_되고_정지된_회원은_보인다() throws Exception {
    MemberSession admin = adminSession("관리자7");
    MemberSession target = signupAndLogin("blk" + System.nanoTime(), "대상4");
    block(admin.token(), target.memberId(), true, null)
        .andExpect(MockMvcResultMatchers.status().isOk());
    usage.drop(); // FastAPI가 첫 기록 때 만들기 전

    JsonNode item = itemOf(items(usageList(admin.token(), null)), target.memberId());

    Assertions.assertNotNull(item);
    Assertions.assertEquals(0, item.at("/calls").asInt());
  }

  private ResultActions usageList(String token, String date) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.get("/api/v1/admin/ai-usage")
            .header("Authorization", "Bearer " + token);
    if (date != null) {
      request = request.param("date", date);
    }
    return mockMvc.perform(request);
  }

  // 성공 응답의 items 배열
  private JsonNode items(ResultActions result) throws Exception {
    String body =
        result
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/items");
  }

  // 다른 테스트가 정지시킨 회원도 목록에 있을 수 있어서, 이 테스트의 회원만 찾아 본다.
  private static JsonNode itemOf(JsonNode items, Long memberId) {
    int index = indexOf(items, memberId);
    return index < 0 ? null : items.get(index);
  }

  private static int indexOf(JsonNode items, Long memberId) {
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).at("/memberId").asLong() == memberId) {
        return i;
      }
    }
    return -1;
  }

  private static OffsetDateTime at(LocalDate day, int hour, int minute, int second) {
    return day.atTime(hour, minute, second).atZone(AiUsageRepository.KST).toOffsetDateTime();
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
