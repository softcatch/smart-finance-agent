package com.softcatch.smart.transfer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, Long> {

  Optional<TransferRequest> findByRequestId(String requestId);
}
