package com.softcatch.smart.notification;

import com.softcatch.smart.notification.dto.response.NotificationItem;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 접속 중인 사람의 SSE 연결(FR-024). 서버 메모리에만 있어 서버 1대에서만 맞다 — 여러 대면 Redis pub/sub(NFR-049).
// 전달은 실패해도 잃는 것이 없다. 알림 행이 이미 저장돼 있어 화면이 목록(E-14)으로 채운다.
@Component
class NotificationHub {

  private static final Logger log = LoggerFactory.getLogger(NotificationHub.class);

  private final ConcurrentHashMap<Long, Set<SseEmitter>> connections = new ConcurrentHashMap<>();
  private final NotificationRepository notifications;
  private final Executor executor;

  NotificationHub(
      NotificationRepository notifications,
      @Qualifier("applicationTaskExecutor") Executor executor) {
    this.notifications = notifications;
    this.executor = executor;
  }

  // 주석 줄은 ": connected"·": ping"으로 나간다(콜론 뒤 공백은 comment의 앞 공백이다).
  // 연결을 열고 바로 응답을 시작한다 — 헤더가 나가야 화면이 열린 줄 알고, 그 시점엔 이미 등록돼 있다.
  SseEmitter connect(Long memberId) {
    SseEmitter emitter = new SseEmitter(0L); // 시간 제한 없음. 끊김은 하트비트·전달 실패로 알아챈다
    register(memberId, emitter);
    send(memberId, emitter, SseEmitter.event().comment(" connected"));
    return emitter;
  }

  void register(Long memberId, SseEmitter emitter) {
    connections.computeIfAbsent(memberId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
    emitter.onCompletion(() -> remove(memberId, emitter));
    emitter.onTimeout(() -> remove(memberId, emitter));
    emitter.onError(e -> remove(memberId, emitter));
  }

  int connectionCount(Long memberId) {
    return connections.getOrDefault(memberId, Set.of()).size();
  }

  // 커밋 뒤에 불린다(롤백되면 불리지 않는다). 전달은 다른 스레드에서 해서 확정 응답이 기다리지 않는다.
  @TransactionalEventListener
  void onSaved(NotificationsSaved event) {
    executor.execute(() -> deliver(event.transferId()));
  }

  void deliver(Long transferId) {
    try {
      for (Notification n : notifications.findByTransferId(transferId)) {
        NotificationItem item = NotificationItem.of(n);
        for (SseEmitter emitter : connections.getOrDefault(n.getMemberId(), Set.of())) {
          send(
              n.getMemberId(),
              emitter,
              SseEmitter.event().name("notification").data(item, MediaType.APPLICATION_JSON));
        }
      }
    } catch (RuntimeException e) {
      log.warn("알림 전달 실패(목록에는 남아 있다): transferId={}", transferId, e);
    }
  }

  // 하트비트: 연결을 유지하고 끊긴 연결을 알아챈다. nginx 읽기 제한(기본 60초)보다 짧게 둔다.
  @Scheduled(fixedRate = 15_000)
  void ping() {
    connections.forEach(
        (memberId, emitters) ->
            emitters.forEach(e -> send(memberId, e, SseEmitter.event().comment(" ping"))));
  }

  private void send(Long memberId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException e) { // 끊기거나 이미 끝난 연결
      remove(memberId, emitter);
      emitter.completeWithError(e);
    }
  }

  private void remove(Long memberId, SseEmitter emitter) {
    connections.computeIfPresent(
        memberId,
        (id, emitters) -> {
          emitters.remove(emitter);
          return emitters.isEmpty() ? null : emitters;
        });
  }
}
