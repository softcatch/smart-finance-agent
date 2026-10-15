package com.softcatch.smart.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatDetailRepository extends JpaRepository<ChatDetail, Long> {

  Optional<ChatDetail> findByDetailId(String detailId);
}
