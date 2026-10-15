package com.softcatch.smart.transfer;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.account.dto.request.ChargeRequest;
import com.softcatch.smart.account.dto.request.OpenAccountRequest;
import com.softcatch.smart.alias.dto.request.AliasRequest;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.transfer.dto.request.TransferConfirmRequest;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
class TransferControllerTest {

  private static final String ACCOUNT_PASSWORD = "1234";
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private record MemberSession(Long memberId, String token) {}

  private record AccountInfo(Long accountId, String accountNumber) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private TransferService transferService;
  @Autowired private TransferRequestRepository transferRequests;
  @Autowired private TransferRepository transfers;
  @Autowired private AccountRepository accounts;
  @Autowired private JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 요청_생성_계좌번호로_성공() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "김영희");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "홍길동");
    openAccount(sender.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/internal/transfer-requests")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new TransferCreateRequest(recipientAccount.accountNumber(), null, 10000L))))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.recipientName").value("김영희"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.recipientAccountNumber")
                .value(recipientAccount.accountNumber()))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.amount").value(10000))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.requestId").isNotEmpty());
  }

  @Test
  void 요청_생성_별칭으로_성공() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "이영희");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "김철수");
    openAccount(sender.token());
    registerAlias(sender.token(), "친구", recipientAccount.accountNumber());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/internal/transfer-requests")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferCreateRequest(null, "친구", 3000L))))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.recipientName").value("이영희"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.recipientAccountNumber")
                .value(recipientAccount.accountNumber()));
  }

  @Test
  void 요청_생성_없는_계좌번호면_404() throws Exception {
    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자1");
    openAccount(sender.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/internal/transfer-requests")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new TransferCreateRequest("999999999999", null, 1000L))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
  }

  @Test
  void 요청_생성_없는_별칭이면_404() throws Exception {
    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자2");
    openAccount(sender.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/internal/transfer-requests")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferCreateRequest(null, "없는별칭", 1000L))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ALIAS_NOT_FOUND"));
  }

  @Test
  void 요청_생성_금액이_0이하면_400() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인0");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자0");
    openAccount(sender.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/internal/transfer-requests")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new TransferCreateRequest(recipientAccount.accountNumber(), null, -1000L))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("INVALID_AMOUNT"));
  }

  @Test
  void 확정_성공() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인1");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자3");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 50000L);

    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 10000L);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.amount").value(10000))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.balanceAfter").value(40000))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.recipientName").value("수취인1"));

    Account recipientAcc =
        accounts.findByAccountNumber(recipientAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(10000L, recipientAcc.getBalance());

    List<Transfer> history =
        transfers.findAll().stream()
            .filter(
                t ->
                    t.getFromAccountNumber().equals(senderAccount.accountNumber())
                        && t.getToAccountNumber().equals(recipientAccount.accountNumber()))
            .toList();
    Assertions.assertEquals(1, history.size());
    Assertions.assertEquals(10000L, history.get(0).getAmount());
  }

  @Test
  void 확정_이미_사용된_요청이면_409() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인2");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자4");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 10000L);

    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 1000L);
    confirmTransfer(sender.token(), requestId);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("TRANSFER_REQUEST_ALREADY_USED"));
  }

  @Test
  void 확정_만료된_요청이면_410() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인3");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자5");
    AccountInfo senderAccount = openAccount(sender.token());

    TransferRequest expired =
        transferRequests.save(
            new TransferRequest(
                "tr_test_" + UUID.randomUUID(),
                sender.memberId(),
                senderAccount.accountNumber(),
                recipientAccount.accountNumber(),
                "수취인3",
                1000L,
                Instant.now().minusSeconds(1)));

    mockMvc
        .perform(
            MockMvcRequestBuilders.post(
                    "/api/v1/transfer-requests/{id}/confirm", expired.getRequestId())
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isGone())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("TRANSFER_REQUEST_EXPIRED"));
  }

  @Test
  void 확정_존재하지_않는_요청이면_404() throws Exception {
    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자6");
    openAccount(sender.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", "tr_없는요청")
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("TRANSFER_REQUEST_NOT_FOUND"));
  }

  @Test
  void 확정_남의_요청이면_404() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인4");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자7");
    openAccount(sender.token());
    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 1000L);

    MemberSession stranger = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "타인");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + stranger.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("TRANSFER_REQUEST_NOT_FOUND"));
  }

  @Test
  void 확정_소유자_아니면_403() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인5");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자8");
    openAccount(sender.token());

    MemberSession other = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "다른사람");
    AccountInfo otherAccount = openAccount(other.token());

    // 정상 흐름에선 나올 수 없는 상태(요청의 보낸 사람과 출금 계좌 소유자가 다름)를
    // 방어 로직 검증을 위해 리포지토리로 직접 만든다 — 같은 패키지라 패키지 프라이빗
    // 생성자에 접근 가능.
    TransferRequest inconsistent =
        transferRequests.save(
            new TransferRequest(
                "tr_test_" + UUID.randomUUID(),
                sender.memberId(),
                otherAccount.accountNumber(),
                recipientAccount.accountNumber(),
                "수취인5",
                1000L,
                Instant.now().plusSeconds(300)));

    mockMvc
        .perform(
            MockMvcRequestBuilders.post(
                    "/api/v1/transfer-requests/{id}/confirm", inconsistent.getRequestId())
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_OWNED"));
  }

  @Test
  void 확정_잔액_부족이면_409() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인6");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자9");
    openAccount(sender.token());

    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 999999L);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("INSUFFICIENT_BALANCE"));
  }

  @Test
  void 확정_비밀번호가_틀리면_403이고_송금되지_않는다() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인9");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자12");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 50000L);
    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 10000L);

    confirm(sender.token(), requestId, "0000")
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_PASSWORD_INVALID"));

    Account senderAcc = accounts.findByAccountNumber(senderAccount.accountNumber()).orElseThrow();
    Account recipientAcc =
        accounts.findByAccountNumber(recipientAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(50000L, senderAcc.getBalance());
    Assertions.assertEquals(0L, recipientAcc.getBalance());
  }

  @Test
  void 비밀번호가_틀리면_요청ID가_폐기되어_맞는_비밀번호로도_다시_쓸_수_없다() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인10");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자13");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 50000L);
    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 10000L);

    confirm(sender.token(), requestId, "0000")
        .andExpect(MockMvcResultMatchers.status().isForbidden());

    confirm(sender.token(), requestId, ACCOUNT_PASSWORD)
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.error.code").value("TRANSFER_REQUEST_ALREADY_USED"));

    Account senderAcc = accounts.findByAccountNumber(senderAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(50000L, senderAcc.getBalance());
  }

  @Test
  void 내역_요약_기간_내_보낸_받은_합계와_건수() throws Exception {
    MemberSession a = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "요약A");
    AccountInfo accountA = openAccount(a.token());
    charge(a.token(), accountA.accountId(), 20000L);
    MemberSession b = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "요약B");
    AccountInfo accountB = openAccount(b.token());
    charge(b.token(), accountB.accountId(), 5000L);
    sendAndConfirm(a.token(), accountB.accountNumber(), 3000L);
    sendAndConfirm(a.token(), accountB.accountNumber(), 2000L);
    sendAndConfirm(b.token(), accountA.accountNumber(), 1000L);

    // A와 무관한 송금은 합계에 들어가지 않는다
    MemberSession c = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "요약C");
    AccountInfo accountC = openAccount(c.token());
    charge(c.token(), accountC.accountId(), 9000L);
    sendAndConfirm(c.token(), accountB.accountNumber(), 9000L);

    LocalDate today = LocalDate.now(KST);
    JsonNode data = summary(a.token(), today, today, null, null);

    Assertions.assertEquals(5000L, data.at("/sentTotal").asLong());
    Assertions.assertEquals(1000L, data.at("/receivedTotal").asLong());
    Assertions.assertEquals(3, data.at("/count").asInt());
    Assertions.assertEquals(3, data.at("/items").size());
    Assertions.assertEquals("RECEIVED", data.at("/items/0/direction").asText()); // 최신순
    Assertions.assertEquals("요약B", data.at("/items/0/counterpartName").asText());
  }

  @Test
  void 내역_요약_기간_경계는_한국_시간_하루() throws Exception {
    MemberSession a = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "경계A");
    AccountInfo accountA = openAccount(a.token());
    charge(a.token(), accountA.accountId(), 10000L);
    MemberSession b = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "경계B");
    AccountInfo accountB = openAccount(b.token());
    sendAndConfirm(a.token(), accountB.accountNumber(), 1000L);
    sendAndConfirm(a.token(), accountB.accountNumber(), 2000L);

    // 3/10 23:59:59 KST와 3/11 00:00:00 KST — 하루 경계의 양쪽
    List<Long> ids =
        transfers.findAll().stream()
            .filter(t -> t.getFromAccountNumber().equals(accountA.accountNumber()))
            .map(Transfer::getId)
            .sorted()
            .toList();
    setCreatedAt(ids.get(0), "2026-03-10T23:59:59+09:00");
    setCreatedAt(ids.get(1), "2026-03-11T00:00:00+09:00");

    JsonNode tenth =
        summary(
            a.token(), LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-10"), null, null);
    JsonNode eleventh =
        summary(
            a.token(), LocalDate.parse("2026-03-11"), LocalDate.parse("2026-03-11"), null, null);
    JsonNode both =
        summary(
            a.token(), LocalDate.parse("2026-03-10"), LocalDate.parse("2026-03-11"), null, null);

    Assertions.assertEquals(1000L, tenth.at("/sentTotal").asLong());
    Assertions.assertEquals(2000L, eleventh.at("/sentTotal").asLong());
    Assertions.assertEquals(3000L, both.at("/sentTotal").asLong());
    Assertions.assertEquals(2, both.at("/count").asInt());
  }

  @Test
  void 내역_요약_페이지를_나눠도_합계는_전체_기간() throws Exception {
    MemberSession a = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "페이지A");
    AccountInfo accountA = openAccount(a.token());
    charge(a.token(), accountA.accountId(), 10000L);
    MemberSession b = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "페이지B");
    AccountInfo accountB = openAccount(b.token());
    for (int i = 0; i < 3; i++) {
      sendAndConfirm(a.token(), accountB.accountNumber(), 1000L);
    }

    LocalDate today = LocalDate.now(KST);
    JsonNode first = summary(a.token(), today, today, null, 2);
    JsonNode second = summary(a.token(), today, today, first.at("/nextCursor").asLong(), 2);

    Assertions.assertEquals(2, first.at("/items").size());
    Assertions.assertTrue(first.at("/hasNext").asBoolean());
    Assertions.assertEquals(3000L, first.at("/sentTotal").asLong()); // 페이지가 아니라 전체 기간
    Assertions.assertEquals(3, first.at("/count").asInt());
    Assertions.assertEquals(1, second.at("/items").size());
    Assertions.assertFalse(second.at("/hasNext").asBoolean());
  }

  @Test
  void 내역_요약_내_계좌끼리_송금은_보낸_쪽으로만_센다() throws Exception {
    MemberSession a = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "내계좌");
    AccountInfo primary = openAccount(a.token());
    AccountInfo second = openAccount(a.token());
    charge(a.token(), primary.accountId(), 5000L);
    sendAndConfirm(a.token(), second.accountNumber(), 1000L);

    LocalDate today = LocalDate.now(KST);
    JsonNode data = summary(a.token(), today, today, null, null);

    Assertions.assertEquals(1000L, data.at("/sentTotal").asLong());
    Assertions.assertEquals(0L, data.at("/receivedTotal").asLong());
    Assertions.assertEquals(1, data.at("/count").asInt());
    Assertions.assertEquals("SENT", data.at("/items/0/direction").asText());
  }

  @Test
  void 내역_요약_계좌가_없으면_0과_빈_목록() throws Exception {
    MemberSession a = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "무계좌");

    LocalDate today = LocalDate.now(KST);
    JsonNode data = summary(a.token(), today, today, null, null);

    Assertions.assertEquals(0L, data.at("/sentTotal").asLong());
    Assertions.assertEquals(0L, data.at("/receivedTotal").asLong());
    Assertions.assertEquals(0, data.at("/count").asInt());
    Assertions.assertEquals(0, data.at("/items").size());
  }

  @Test
  void 내역_요약_인증_없으면_401() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/internal/transfers")
                .param("from", "2026-03-10")
                .param("to", "2026-03-11"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  void 동시_확정_후_성공은_1번만() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인7");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자10");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 10000L);

    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 5000L);

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Boolean> attempt =
        () -> {
          barrier.await();
          try {
            transferService.confirm(sender.memberId(), requestId, ACCOUNT_PASSWORD);
            return true;
          } catch (ApiException e) {
            return false;
          }
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Boolean> first = pool.submit(attempt);
    Future<Boolean> second = pool.submit(attempt);
    int successCount = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
    pool.shutdown();

    Assertions.assertEquals(1, successCount);
    Account senderAcc = accounts.findByAccountNumber(senderAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(5000L, senderAcc.getBalance());
  }

  @Test
  void 같은_요청ID로_맞는_비밀번호와_틀린_비밀번호가_동시에_와도_송금은_최대_1번() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인11");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자14");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 50000L);
    String requestId =
        createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 10000L);

    // 맞는 비밀번호 1개와 틀린 비밀번호 7개 — 틀린 쪽이 먼저 요청ID를 잡는 경우가 많게 한다.
    int attempts = 8;
    CyclicBarrier barrier = new CyclicBarrier(attempts);
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      String password = i == 0 ? ACCOUNT_PASSWORD : "0000";
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return confirm(sender.token(), requestId, password)
                    .andReturn()
                    .getResponse()
                    .getStatus();
              }));
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> future : futures) {
      statuses.add(future.get());
    }
    pool.shutdown();

    // 요청ID를 먼저 잡은 한 요청만 비밀번호 확인까지 가고, 나머지는 전부 409(이미 사용)다.
    Assertions.assertEquals(attempts - 1, statuses.stream().filter(s -> s == 409).count());
    // 그 한 요청이 맞는 비밀번호였으면 송금 1번, 틀렸으면 송금 없음(폐기)이다.
    long sentCount = statuses.stream().filter(s -> s == 200).count();
    Account senderAcc = accounts.findByAccountNumber(senderAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(50000L - 10000L * sentCount, senderAcc.getBalance());
    // 어느 쪽이든 이후에는 맞는 비밀번호로도 다시 쓸 수 없다.
    confirm(sender.token(), requestId, ACCOUNT_PASSWORD)
        .andExpect(MockMvcResultMatchers.status().isConflict());
  }

  @Test
  void 동시_송금_후_잔액이_음수_안_됨() throws Exception {
    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "수취인8");
    AccountInfo recipientAccount = openAccount(recipient.token());

    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "발신자11");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 5000L);

    int requestCount = 10;
    long amountEach = 1000L;
    List<String> requestIds = new ArrayList<>();
    for (int i = 0; i < requestCount; i++) {
      requestIds.add(
          createTransferRequest(
              sender.token(), recipientAccount.accountNumber(), null, amountEach));
    }

    CyclicBarrier barrier = new CyclicBarrier(requestCount);
    ExecutorService pool = Executors.newFixedThreadPool(requestCount);
    List<Future<Boolean>> futures = new ArrayList<>();
    for (String requestId : requestIds) {
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                try {
                  transferService.confirm(sender.memberId(), requestId, ACCOUNT_PASSWORD);
                  return true;
                } catch (ApiException e) {
                  return false;
                }
              }));
    }

    int successCount = 0;
    for (Future<Boolean> future : futures) {
      if (future.get()) {
        successCount++;
      }
    }
    pool.shutdown();

    Assertions.assertEquals(5, successCount);
    Account senderAcc = accounts.findByAccountNumber(senderAccount.accountNumber()).orElseThrow();
    Assertions.assertEquals(0L, senderAcc.getBalance());
  }

  @Test
  void 반대_방향_동시_송금은_교착_없이_둘_다_성공() throws Exception {
    MemberSession memberA = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "A");
    AccountInfo accountA = openAccount(memberA.token());
    charge(memberA.token(), accountA.accountId(), 10000L);

    MemberSession memberB = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "B");
    AccountInfo accountB = openAccount(memberB.token());
    charge(memberB.token(), accountB.accountId(), 10000L);

    // A→B 3000원, B→A 2000원을 동시에 확정한다. 잠금 순서가 방향마다 다르면
    // (A를 먼저 잠그는 트랜잭션과 B를 먼저 잠그는 트랜잭션이 서로를 기다리며) 교착 상태가
    // 나서 한쪽이 예외로 끝난다 — id 순서로 고정한 뒤에는 항상 둘 다 성공해야 한다.
    String requestAtoB =
        createTransferRequest(memberA.token(), accountB.accountNumber(), null, 3000L);
    String requestBtoA =
        createTransferRequest(memberB.token(), accountA.accountNumber(), null, 2000L);

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Boolean> confirmAtoB =
        () -> {
          barrier.await();
          transferService.confirm(memberA.memberId(), requestAtoB, ACCOUNT_PASSWORD);
          return true;
        };
    Callable<Boolean> confirmBtoA =
        () -> {
          barrier.await();
          transferService.confirm(memberB.memberId(), requestBtoA, ACCOUNT_PASSWORD);
          return true;
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Boolean> first = pool.submit(confirmAtoB);
    Future<Boolean> second = pool.submit(confirmBtoA);
    Assertions.assertTrue(first.get());
    Assertions.assertTrue(second.get());
    pool.shutdown();

    Account finalA = accounts.findByAccountNumber(accountA.accountNumber()).orElseThrow();
    Account finalB = accounts.findByAccountNumber(accountB.accountNumber()).orElseThrow();
    Assertions.assertEquals(9000L, finalA.getBalance()); // 10000 - 3000 + 2000
    Assertions.assertEquals(11000L, finalB.getBalance()); // 10000 - 2000 + 3000
  }

  @Test
  void 목록_조회_방향과_상대방_확인() throws Exception {
    MemberSession memberA = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "목록A");
    AccountInfo accountA = openAccount(memberA.token());
    charge(memberA.token(), accountA.accountId(), 10000L);

    MemberSession memberB = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "목록B");
    AccountInfo accountB = openAccount(memberB.token());

    String requestId =
        createTransferRequest(memberA.token(), accountB.accountNumber(), null, 3000L);
    confirmTransfer(memberA.token(), requestId);

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/transfers")
                .header("Authorization", "Bearer " + memberA.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(1))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].direction").value("SENT"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].counterpartName").value("목록B"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.items[0].counterpartAccountNumber")
                .value(accountB.accountNumber()))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].amount").value(3000))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].createdAt").isNotEmpty())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.hasNext").value(false));

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/transfers")
                .header("Authorization", "Bearer " + memberB.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].direction").value("RECEIVED"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items[0].counterpartName").value("목록A"))
        .andExpect(
            MockMvcResultMatchers.jsonPath("$.data.items[0].counterpartAccountNumber")
                .value(accountA.accountNumber()));
  }

  @Test
  void 목록_조회_내역_없으면_빈배열() throws Exception {
    MemberSession member = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "빈내역");
    openAccount(member.token());

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/v1/transfers")
                .header("Authorization", "Bearer " + member.token()))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.items.length()").value(0))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.hasNext").value(false));
  }

  @Test
  void 목록_조회_커서로_다음_페이지() throws Exception {
    MemberSession sender = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "커서발신자");
    AccountInfo senderAccount = openAccount(sender.token());
    charge(sender.token(), senderAccount.accountId(), 10000L);

    MemberSession recipient = signupAndLogin("tf" + System.nanoTime(), "pw12345!", "커서수취인");
    AccountInfo recipientAccount = openAccount(recipient.token());

    for (int i = 0; i < 3; i++) {
      String requestId =
          createTransferRequest(sender.token(), recipientAccount.accountNumber(), null, 1000L);
      confirmTransfer(sender.token(), requestId);
    }

    JsonNode firstPage = listHistory(sender.token(), null, 2);
    Assertions.assertEquals(2, firstPage.at("/items").size());
    Assertions.assertTrue(firstPage.at("/hasNext").asBoolean());
    Long nextCursor = firstPage.at("/nextCursor").asLong();

    JsonNode secondPage = listHistory(sender.token(), nextCursor, 2);
    Assertions.assertEquals(1, secondPage.at("/items").size());
    Assertions.assertFalse(secondPage.at("/hasNext").asBoolean());
    Assertions.assertTrue(secondPage.at("/nextCursor").isNull());
  }

  private JsonNode listHistory(String token, Long cursor, Integer size) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.get("/api/v1/transfers").header("Authorization", "Bearer " + token);
    if (cursor != null) {
      request = request.param("cursor", cursor.toString());
    }
    if (size != null) {
      request = request.param("size", size.toString());
    }
    String body =
        mockMvc
            .perform(request)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data");
  }

  private void sendAndConfirm(String token, String recipientAccountNumber, Long amount)
      throws Exception {
    confirmTransfer(token, createTransferRequest(token, recipientAccountNumber, null, amount));
  }

  private void setCreatedAt(Long transferId, String isoOffsetDateTime) {
    jdbc.update(
        "UPDATE transfer SET created_at = ?::timestamptz WHERE id = ?",
        isoOffsetDateTime,
        transferId);
  }

  private JsonNode summary(String token, LocalDate from, LocalDate to, Long cursor, Integer size)
      throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.get("/internal/transfers")
            .header("Authorization", "Bearer " + token)
            .param("from", from.toString())
            .param("to", to.toString());
    if (cursor != null) {
      request = request.param("cursor", cursor.toString());
    }
    if (size != null) {
      request = request.param("size", size.toString());
    }
    String body =
        mockMvc
            .perform(request)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data");
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
    Long accountId = json.readTree(body).at("/data/accountId").asLong();
    String accountNumber = json.readTree(body).at("/data/accountNumber").asText();
    return new AccountInfo(accountId, accountNumber);
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

  private void registerAlias(String token, String alias, String accountNumber) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/aliases")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AliasRequest(alias, accountNumber))))
        .andExpect(MockMvcResultMatchers.status().isCreated());
  }

  private String createTransferRequest(
      String token, String recipientAccountNumber, String alias, Long amount) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/internal/transfer-requests")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            new TransferCreateRequest(recipientAccountNumber, alias, amount))))
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

  private void confirmTransfer(String token, String requestId) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new TransferConfirmRequest(ACCOUNT_PASSWORD))))
        .andExpect(MockMvcResultMatchers.status().isOk());
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
