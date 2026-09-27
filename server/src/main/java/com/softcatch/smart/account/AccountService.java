package com.softcatch.smart.account;

import com.softcatch.smart.auth.MemberRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService {

  private static final long ACCOUNT_NUMBER_BOUND = 1_000_000_000_000L; // 12자리

  private final AccountRepository accounts;
  private final MemberRepository members;
  private final SecureRandom random = new SecureRandom();

  AccountService(AccountRepository accounts, MemberRepository members) {
    this.accounts = accounts;
    this.members = members;
  }

  @Transactional
  Account open(Long memberId, String idempotencyKey) {
    // 같은 회원의 동시 개설 요청을 잠금으로 순서대로 처리한다 — 두 요청이 동시에
    // "내가 첫 계좌"라고 오판하지 않게 한다.
    members.findForUpdateById(memberId);

    return accounts
        .findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
        .orElseGet(() -> createAccount(memberId, idempotencyKey));
  }

  private Account createAccount(Long memberId, String idempotencyKey) {
    boolean primary = accounts.countByMemberId(memberId) == 0;
    String accountNumber = String.format("%012d", random.nextLong(ACCOUNT_NUMBER_BOUND));
    return accounts.save(new Account(memberId, accountNumber, idempotencyKey, primary));
  }
}
