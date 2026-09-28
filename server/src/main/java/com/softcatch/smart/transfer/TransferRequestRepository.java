package com.softcatch.smart.transfer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, Long> {

  Optional<TransferRequest> findByRequestId(String requestId);

  // 아직 안 쓴 요청만 선점 — 두 확정 요청이 동시에 와도 하나만 1행을 얻는다(중복 확정 방지).
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE TransferRequest t SET t.used = true WHERE t.requestId = :requestId AND t.used = false")
  int markUsedIfNotUsed(String requestId);
}
