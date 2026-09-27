package com.softcatch.smart.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

  Optional<Account> findByMemberIdAndIdempotencyKey(Long memberId, String idempotencyKey);

  long countByMemberId(Long memberId);
}
