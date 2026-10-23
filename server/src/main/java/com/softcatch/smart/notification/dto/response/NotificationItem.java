package com.softcatch.smart.notification.dto.response;

import com.softcatch.smart.notification.Notification;
import java.time.Instant;

// 알림 한 건. 목록(E-14)의 items[]와 스트림(E-15)의 data가 같은 모양이다.
public record NotificationItem(
    Long notificationId,
    String direction,
    Long transferId,
    String counterpartName,
    Long amount,
    Instant createdAt) {

  public static NotificationItem of(Notification n) {
    return new NotificationItem(
        n.getId(),
        n.getDirection().name(),
        n.getTransferId(),
        n.getCounterpartName(),
        n.getAmount(),
        n.getCreatedAt());
  }
}
