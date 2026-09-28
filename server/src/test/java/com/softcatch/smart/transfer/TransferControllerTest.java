package com.softcatch.smart.transfer;

import com.softcatch.smart.TestcontainersConfiguration;
import com.softcatch.smart.account.Account;
import com.softcatch.smart.account.AccountRepository;
import com.softcatch.smart.account.dto.request.ChargeRequest;
import com.softcatch.smart.alias.dto.request.AliasRequest;
import com.softcatch.smart.auth.dto.request.LoginRequest;
import com.softcatch.smart.auth.dto.request.SignupRequest;
import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.transfer.dto.request.TransferCreateRequest;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransferControllerTest {

  private record MemberSession(Long memberId, String token) {}

  private record AccountInfo(Long accountId, String accountNumber) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private TransferService transferService;
  @Autowired private TransferRequestRepository transferRequests;
  @Autowired private TransferRepository transfers;
  @Autowired private AccountRepository accounts;
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
                .header("Authorization", "Bearer " + sender.token()))
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
                .header("Authorization", "Bearer " + sender.token()))
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
                .header("Authorization", "Bearer " + sender.token()))
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
                .header("Authorization", "Bearer " + sender.token()))
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
                .header("Authorization", "Bearer " + stranger.token()))
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
                .header("Authorization", "Bearer " + sender.token()))
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
                .header("Authorization", "Bearer " + sender.token()))
        .andExpect(MockMvcResultMatchers.status().isConflict())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("INSUFFICIENT_BALANCE"));
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
            transferService.confirm(sender.memberId(), requestId);
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
                  transferService.confirm(sender.memberId(), requestId);
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
          transferService.confirm(memberA.memberId(), requestAtoB);
          return true;
        };
    Callable<Boolean> confirmBtoA =
        () -> {
          barrier.await();
          transferService.confirm(memberB.memberId(), requestBtoA);
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

  private AccountInfo openAccount(String token) throws Exception {
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

  private void confirmTransfer(String token, String requestId) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/transfer-requests/{id}/confirm", requestId)
                .header("Authorization", "Bearer " + token))
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
