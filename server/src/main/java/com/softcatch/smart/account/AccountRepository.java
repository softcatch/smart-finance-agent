package com.softcatch.smart.account;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, Long> {

  Optional<Account> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

  long countByMemberId(Long memberId);

  Optional<Account> findByMemberIdAndIsPrimaryTrue(Long memberId);

  List<Account> findByMemberIdOrderByIdAsc(Long memberId);

  Optional<Account> findByAccountNumber(String accountNumber);

  // UPDATE 자체가 원자적이라 동시 충전도 잠금 없이 안전하다.
  // clearAutomatically=true — 벌크 UPDATE 후 1차 캐시를 비워, 뒤이은 조회가 옛 값을 반환하지 않게 한다.
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Account a SET a.balance = a.balance + :amount WHERE a.id = :id")
  void increaseBalance(Long id, Long amount);
}
