package com.softcatch.smart.notification;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  // 내 알림을 최신순으로 — 커서는 이전 묶음의 마지막 알림 ID다(송금 내역 E-09와 같은 규칙).
  @Query(
      "SELECT n FROM Notification n WHERE n.memberId = :memberId "
          + "AND (:cursor IS NULL OR n.id < :cursor) ORDER BY n.id DESC")
  List<Notification> findPage(Long memberId, Long cursor, Pageable pageable);

  // 송금 하나의 알림(보낸 사람·받은 사람). 커밋 뒤 전달에 쓴다.
  List<Notification> findByTransferId(Long transferId);
}
