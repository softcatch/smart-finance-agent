package com.softcatch.smart.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MemberRepository extends JpaRepository<Member, Long> {

  Optional<Member> findByLoginId(String loginId);

  // 계좌 개설·대표 계좌 변경에서 같은 회원의 동시 요청을 순차 처리하기 위한 잠금 조회.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<Member> findForUpdateById(Long id);
}
