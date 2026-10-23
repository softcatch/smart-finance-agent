package com.softcatch.smart.notification;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.account.dto.request.ChargeRequest;
import com.softcatch.smart.account.dto.request.OpenAccountRequest;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.transfer.dto.request.TransferConfirmRequest;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationControllerTest {

  private static final String ACCOUNT_PASSWORD = "1234";

  private record MemberSession(Long memberId, String token) {}

  private record AccountInfo(Long accountId, String accountNumber) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 확정하면_보낸_사람과_받은_사람에게_알림이_한_줄씩_남는다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "홍길동");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 50_000L);
    MemberSession recipient = signupAndLogin("nt" + System.nanoTime(), "김영희");
    AccountInfo to = openAccount(recipient.token());

    long transferId = confirmTransfer(sender.token(), to.accountNumber(), 10_000L);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT member_id, direction, counterpart_name, amount FROM notification "
                + "WHERE transfer_id = ? ORDER BY direction DESC",
            transferId);
    Assertions.assertEquals(2, rows.size());
    Assertions.assertEquals(sender.memberId(), rows.get(0).get("member_id")); // SENT
    Assertions.assertEquals("SENT", rows.get(0).get("direction"));
    Assertions.assertEquals("김영희", rows.get(0).get("counterpart_name")); // 상대 이름
    Assertions.assertEquals(10_000L, rows.get(0).get("amount"));
    Assertions.assertEquals(recipient.memberId(), rows.get(1).get("member_id"));
    Assertions.assertEquals("RECEIVED", rows.get(1).get("direction"));
    Assertions.assertEquals("홍길동", rows.get(1).get("counterpart_name"));
    Assertions.assertEquals(10_000L, rows.get(1).get("amount"));
  }

  @Test
  void 알림의_시각은_송금_확정_시각과_같다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "시각보낸이");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 50_000L);
    AccountInfo to = openAccount(signupAndLogin("nt" + System.nanoTime(), "시각받는이").token());

    long transferId = confirmTransfer(sender.token(), to.accountNumber(), 1_000L);

    Integer sameTime =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification n JOIN transfer t ON t.id = n.transfer_id "
                + "WHERE n.transfer_id = ? AND n.created_at = t.created_at",
            Integer.class,
            transferId);
    Assertions.assertEquals(2, sameTime);
  }

  @Test
  void 비밀번호가_틀려_확정이_실패하면_알림이_없다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "실패보낸이");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 50_000L);
    MemberSession recipient = signupAndLogin("nt" + System.nanoTime(), "실패받는이");
    AccountInfo to = openAccount(recipient.token());

    confirm(sender.token(), createRequest(sender.token(), to.accountNumber(), 1_000L), "9999")
        .andExpect(MockMvcResultMatchers.status().isForbidden());

    Assertions.assertEquals(0, countFor(sender.memberId()));
    Assertions.assertEquals(0, countFor(recipient.memberId()));
  }

  @Test
  void 잔액이_모자라_확정이_롤백되면_알림도_없다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "부족보낸이");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 500L);
    MemberSession recipient = signupAndLogin("nt" + System.nanoTime(), "부족받는이");
    AccountInfo to = openAccount(recipient.token());

    confirm(
            sender.token(),
            createRequest(sender.token(), to.accountNumber(), 1_000L),
            ACCOUNT_PASSWORD)
        .andExpect(MockMvcResultMatchers.status().isConflict());

    Assertions.assertEquals(0, countFor(sender.memberId()));
    Assertions.assertEquals(0, countFor(recipient.memberId()));
  }

  @Test
  void 이미_확정한_요청을_다시_확정해도_알림은_늘지_않는다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "중복보낸이");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 50_000L);
    MemberSession recipient = signupAndLogin("nt" + System.nanoTime(), "중복받는이");
    AccountInfo to = openAccount(recipient.token());
    String requestId = createRequest(sender.token(), to.accountNumber(), 1_000L);
    confirm(sender.token(), requestId, ACCOUNT_PASSWORD)
        .andExpect(MockMvcResultMatchers.status().isOk());

    confirm(sender.token(), requestId, ACCOUNT_PASSWORD)
        .andExpect(MockMvcResultMatchers.status().isConflict());

    Assertions.assertEquals(1, countFor(sender.memberId()));
    Assertions.assertEquals(1, countFor(recipient.memberId()));
  }

  @Test
  void 내_계좌끼리_보내면_보낸_쪽_한_줄만_남는다() throws Exception {
    MemberSession member = signupAndLogin("nt" + System.nanoTime(), "혼자");
    AccountInfo primary = openAccount(member.token());
    charge(member.token(), primary.accountId(), 50_000L);
    AccountInfo second = openAccount(member.token());

    long transferId = confirmTransfer(member.token(), second.accountNumber(), 1_000L);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT member_id, direction, counterpart_name FROM notification WHERE transfer_id = ?",
            transferId);
    Assertions.assertEquals(1, rows.size());
    Assertions.assertEquals("SENT", rows.get(0).get("direction"));
    Assertions.assertEquals("혼자", rows.get(0).get("counterpart_name"));
  }

  @Test
  void 목록은_본인_알림만_최신순으로_준다() throws Exception {
    MemberSession a = signupAndLogin("nt" + System.nanoTime(), "목록A");
    AccountInfo accountA = openAccount(a.token());
    charge(a.token(), accountA.accountId(), 100_000L);
    MemberSession b = signupAndLogin("nt" + System.nanoTime(), "목록B");
    AccountInfo accountB = openAccount(b.token());
    charge(b.token(), accountB.accountId(), 100_000L);
    MemberSession c = signupAndLogin("nt" + System.nanoTime(), "목록C");
    AccountInfo accountC = openAccount(c.token());
    long first = confirmTransfer(a.token(), accountB.accountNumber(), 1_000L);
    long second = confirmTransfer(a.token(), accountB.accountNumber(), 2_000L);
    long third = confirmTransfer(b.token(), accountC.accountNumber(), 3_000L);

    list(b.token(), null, null)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(3))
        // B의 알림: 방금 C에게 보낸 것(SENT), 그 전에 A에게서 받은 둘(RECEIVED) — 최신순
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].direction").value("SENT"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].transferId").value(third))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].counterpartName").value("목록C"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].amount").value(3000))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].createdAt").isNotEmpty())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[1].direction").value("RECEIVED"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[1].transferId").value(second))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[1].counterpartName").value("목록A"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[2].transferId").value(first))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.hasNext").value(false))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.nextCursor").doesNotExist());
    // A는 자기 것(보낸 둘)만 본다
    list(a.token(), null, null)
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(2))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].direction").value("SENT"));
  }

  @Test
  void 커서와_size로_이어_받는다() throws Exception {
    MemberSession sender = signupAndLogin("nt" + System.nanoTime(), "페이지보낸이");
    AccountInfo from = openAccount(sender.token());
    charge(sender.token(), from.accountId(), 100_000L);
    AccountInfo to = openAccount(signupAndLogin("nt" + System.nanoTime(), "페이지받는이").token());
    for (int i = 0; i < 5; i++) {
      confirmTransfer(sender.token(), to.accountNumber(), 1_000L);
    }

    JsonNode page1 = body(list(sender.token(), null, 2)).at("/data");
    Assertions.assertEquals(2, page1.at("/items").size());
    Assertions.assertTrue(page1.at("/hasNext").asBoolean());
    long cursor = page1.at("/nextCursor").asLong();
    Assertions.assertEquals(page1.at("/items/1/notificationId").asLong(), cursor); // 마지막 알림 ID

    JsonNode page2 = body(list(sender.token(), cursor, 2)).at("/data");
    Assertions.assertEquals(2, page2.at("/items").size());
    Assertions.assertTrue(page2.at("/items/0/notificationId").asLong() < cursor);
    Assertions.assertTrue(page2.at("/hasNext").asBoolean());

    JsonNode page3 = body(list(sender.token(), page2.at("/nextCursor").asLong(), 2)).at("/data");
    Assertions.assertEquals(1, page3.at("/items").size());
    Assertions.assertFalse(page3.at("/hasNext").asBoolean());
    Assertions.assertTrue(
        page3.at("/nextCursor").isNull() || page3.at("/nextCursor").isMissingNode());

    // 딱 size건만 있으면 다음 묶음은 없다
    JsonNode exact = body(list(sender.token(), null, 5)).at("/data");
    Assertions.assertEquals(5, exact.at("/items").size());
    Assertions.assertFalse(exact.at("/hasNext").asBoolean());

    // size를 안 주면 20개씩이라 5건이 한 번에 온다
    Assertions.assertEquals(5, body(list(sender.token(), null, null)).at("/data/items").size());
  }

  @Test
  void 알림이_없으면_빈_목록이다() throws Exception {
    MemberSession member = signupAndLogin("nt" + System.nanoTime(), "알림없음");

    list(member.token(), null, null)
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(0))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.hasNext").value(false));
  }

  @Test
  void 목록은_인증_없으면_401이다() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/notifications"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  private ResultActions list(String token, Long cursor, Integer size) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.get("/api/v1/notifications")
            .header("Authorization", "Bearer " + token);
    if (cursor != null) {
      request = request.param("cursor", cursor.toString());
    }
    if (size != null) {
      request = request.param("size", size.toString());
    }
    return mockMvc.perform(request);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return json.readTree(
        result
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private int countFor(Long memberId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE member_id = ?", Integer.class, memberId);
  }

  // 요청을 만들고 올바른 비밀번호로 확정해 송금 ID를 돌려준다.
  private long confirmTransfer(String token, String recipientAccountNumber, Long amount)
      throws Exception {
    String body =
        confirm(token, createRequest(token, recipientAccountNumber, amount), ACCOUNT_PASSWORD)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/transferId").asLong();
  }

  private String createRequest(String token, String recipientAccountNumber, Long amount)
      throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/internal/transfer-requests")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new TransferCreateRequest(recipientAccountNumber, null, amount))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/requestId").asText();
  }

  private ResultActions confirm(String token, String requestId, String password) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new TransferConfirmRequest(password))));
  }

  private AccountInfo openAccount(String token) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(new OpenAccountRequest(ACCOUNT_PASSWORD))))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new AccountInfo(
        json.readTree(body).at("/data/accountId").asLong(),
        json.readTree(body).at("/data/accountNumber").asText());
  }

  private void charge(String token, Long accountId, Long amount) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", accountId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(amount))))
        .andExpect(MockMvcResultMatchers.status().isOk());
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
