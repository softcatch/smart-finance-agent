package com.softcatch.smart.account;

import com.softcatch.smart.TestcontainersConfiguration;
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
class AccountControllerTest {

  private record MemberSession(Long memberId, String token) {}

  private record SignupRequest(String loginId, String password, String name) {}

  private record LoginRequest(String loginId, String password) {}

  private record ChargeRequest(Long amount) {}

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountService accountService;
  @Autowired private AccountRepository accounts;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void 계좌_개설_첫_계좌는_대표() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌1");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts")
                .header("Authorization", "Bearer " + session.token())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.primary").value(true))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.balance").value(0));
  }

  @Test
  void 두번째_계좌는_비대표() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌2");
    openAccount(session.token(), UUID.randomUUID().toString());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts")
                .header("Authorization", "Bearer " + session.token())
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(MockMvcResultMatchers.status().isCreated())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.primary").value(false));
  }

  @Test
  void 같은_멱등키로_재요청하면_기존_계좌_반환() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌3");
    String key = UUID.randomUUID().toString();

    Long first = openAccount(session.token(), key);
    Long second = openAccount(session.token(), key);

    Assertions.assertEquals(first, second);
  }

  @Test
  void 대표_계좌_변경_성공() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌4");
    Long first = openAccount(session.token(), UUID.randomUUID().toString());
    Long second = openAccount(session.token(), UUID.randomUUID().toString());

    changePrimary(session.token(), second);

    Assertions.assertTrue(accounts.findById(second).orElseThrow().isPrimary());
    Assertions.assertFalse(accounts.findById(first).orElseThrow().isPrimary());
  }

  @Test
  void 대표_계좌_변경_소유자_아니면_403() throws Exception {
    MemberSession owner = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌5A");
    Long account = openAccount(owner.token(), UUID.randomUUID().toString());

    MemberSession stranger = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌5B");

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/accounts/{id}/primary", account)
                .header("Authorization", "Bearer " + stranger.token()))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_OWNED"));
  }

  @Test
  void 대표_계좌_변경_없는_계좌면_404() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "계좌6");

    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/accounts/{id}/primary", 999_999_999L)
                .header("Authorization", "Bearer " + session.token()))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
  }

  @Test
  void 동시_계좌_개설_같은_멱등키는_계좌_1개만_생성() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "동시개설1");
    String key = UUID.randomUUID().toString();
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Long> attempt =
        () -> {
          barrier.await();
          return accountService.open(session.memberId(), key).getId();
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Long> first = pool.submit(attempt);
    Future<Long> second = pool.submit(attempt);
    Long a = first.get();
    Long b = second.get();
    pool.shutdown();

    Assertions.assertEquals(a, b);
    Assertions.assertEquals(1, accounts.countByMemberId(session.memberId()));
  }

  @Test
  void 동시_계좌_개설_다른_멱등키는_대표가_정확히_1개() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "동시개설2");
    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Long> attempt =
        () -> {
          barrier.await();
          return accountService.open(session.memberId(), UUID.randomUUID().toString()).getId();
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Long> first = pool.submit(attempt);
    Future<Long> second = pool.submit(attempt);
    Long a = first.get();
    Long b = second.get();
    pool.shutdown();

    Assertions.assertNotEquals(a, b);
    long primaryCount =
        List.of(a, b).stream()
            .filter(id -> accounts.findById(id).orElseThrow().isPrimary())
            .count();
    Assertions.assertEquals(1, primaryCount);
  }

  @Test
  void 동시_대표_계좌_변경_후에도_대표는_정확히_1개() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "동시대표");
    Long accountA = openAccount(session.token(), UUID.randomUUID().toString());
    Long accountB = openAccount(session.token(), UUID.randomUUID().toString());

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<Void> changeToA =
        () -> {
          barrier.await();
          accountService.changePrimary(session.memberId(), accountA);
          return null;
        };
    Callable<Void> changeToB =
        () -> {
          barrier.await();
          accountService.changePrimary(session.memberId(), accountB);
          return null;
        };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<Void> first = pool.submit(changeToA);
    Future<Void> second = pool.submit(changeToB);
    first.get();
    second.get();
    pool.shutdown();

    long primaryCount =
        accounts.findAll().stream()
            .filter(a -> a.getMemberId().equals(session.memberId()) && a.isPrimary())
            .count();
    Assertions.assertEquals(1, primaryCount);
  }

  @Test
  void 충전_성공() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "충전1");
    Long accountId = openAccount(session.token(), UUID.randomUUID().toString());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", accountId)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(1000L))))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.accountId").value(accountId))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.balance").value(1000));

    Assertions.assertEquals(1000L, accounts.findById(accountId).orElseThrow().getBalance());
  }

  @Test
  void 충전_금액이_0이하면_잔액_변경없이_400() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "충전음수");
    Long accountId = openAccount(session.token(), UUID.randomUUID().toString());

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", accountId)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(-1000L))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("INVALID_AMOUNT"));

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", accountId)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(0L))))
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("INVALID_AMOUNT"));

    Assertions.assertEquals(0L, accounts.findById(accountId).orElseThrow().getBalance());
  }

  @Test
  void 충전_소유자_아니면_403() throws Exception {
    MemberSession owner = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "충전2A");
    Long accountId = openAccount(owner.token(), UUID.randomUUID().toString());

    MemberSession stranger = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "충전2B");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", accountId)
                .header("Authorization", "Bearer " + stranger.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(1000L))))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_OWNED"));
  }

  @Test
  void 충전_없는_계좌면_404() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "충전3");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/v1/accounts/{id}/charge", 999_999_999L)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ChargeRequest(1000L))))
        .andExpect(MockMvcResultMatchers.status().isNotFound())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
  }

  @Test
  void 동시_충전_후_잔액이_합계와_일치() throws Exception {
    MemberSession session = signupAndLogin("acc" + System.nanoTime(), "pw12345!", "동시충전");
    Long accountId = openAccount(session.token(), UUID.randomUUID().toString());

    int threads = 10;
    long amountEach = 1000L;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    Callable<Void> attempt =
        () -> {
          barrier.await();
          accountService.charge(session.memberId(), accountId, amountEach);
          return null;
        };

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(pool.submit(attempt));
    }
    for (Future<Void> future : futures) {
      future.get();
    }
    pool.shutdown();

    Assertions.assertEquals(
        threads * amountEach, accounts.findById(accountId).orElseThrow().getBalance());
  }

  private Long openAccount(String token, String idempotencyKey) throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", idempotencyKey))
            .andExpect(MockMvcResultMatchers.status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).at("/data/accountId").asLong();
  }

  private void changePrimary(String token, Long accountId) throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.patch("/api/v1/accounts/{id}/primary", accountId)
                .header("Authorization", "Bearer " + token))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.accountId").value(accountId))
        .andExpect(MockMvcResultMatchers.jsonPath("$.data.primary").value(true));
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
