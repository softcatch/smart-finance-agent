package com.softcatch.smart.notification;

import com.softcatch.smart.notification.dto.response.NotificationItem;
import com.softcatch.smart.notification.dto.response.NotificationListResponse;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final NotificationRepository notifications;

  NotificationService(NotificationRepository notifications) {
    this.notifications = notifications;
  }

  // 송금 확정 트랜잭션 안에서 부른다 — 알림이 송금과 함께 커밋되거나 함께 사라진다(FR-024).
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordTransfer(
      Long transferId,
      Long senderMemberId,
      String senderName,
      Long recipientMemberId,
      String recipientName,
      Long amount) {
    notifications.save(
        new Notification(senderMemberId, transferId, Direction.SENT, recipientName, amount));
    // 내 계좌끼리 보내면 받는 사람이 곧 보낸 사람이라 한 줄만 남긴다((송금 ID, 받는 사람)이 유일하다).
    if (!recipientMemberId.equals(senderMemberId)) {
      notifications.save(
          new Notification(recipientMemberId, transferId, Direction.RECEIVED, senderName, amount));
    }
  }

  NotificationListResponse list(Long memberId, Long cursor, Integer size) {
    int pageSize = size != null ? size : DEFAULT_PAGE_SIZE;
    // size보다 하나 더 가져와서, 남는 게 있으면 hasNext=true로 판단한다.
    List<Notification> rows =
        notifications.findPage(memberId, cursor, Pageable.ofSize(pageSize + 1));
    boolean hasNext = rows.size() > pageSize;
    List<NotificationItem> items =
        (hasNext ? rows.subList(0, pageSize) : rows).stream().map(NotificationItem::of).toList();
    Long nextCursor = hasNext ? items.get(items.size() - 1).notificationId() : null;
    return new NotificationListResponse(items, nextCursor, hasNext);
  }
}
