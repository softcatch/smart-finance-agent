package com.softcatch.smart.notification;

// 송금 하나의 알림이 저장됐다(확정 트랜잭션 안에서 발행). 커밋된 뒤에만 전달한다.
record NotificationsSaved(Long transferId) {}
