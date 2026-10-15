package com.softcatch.smart.chat;

import com.softcatch.smart.common.ApiException;
import com.softcatch.smart.common.ErrorCode;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 사용자당 분당 10회(NFR-024). 첫 호출부터 1분 고정 창을 센다.
// ponytail: 메모리 카운터(서버 1대·사용자 수만큼 항목 유지). 서버를 늘리면 Redis로 옮긴다.
@Component
class ChatRateLimiter {

  static final int LIMIT = 10;
  private static final long WINDOW_MILLIS = 60_000;

  private record Window(long startMillis, int count) {}

  private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

  // compute는 같은 키에 대해 원자적이라 동시 호출에도 정확히 LIMIT건만 통과한다.
  void check(Long memberId) {
    long now = System.currentTimeMillis();
    Window window =
        windows.compute(
            memberId,
            (id, old) ->
                old == null || now - old.startMillis() >= WINDOW_MILLIS
                    ? new Window(now, 1)
                    : new Window(old.startMillis(), old.count() + 1));
    if (window.count() > LIMIT) {
      throw new ApiException(ErrorCode.RATE_LIMITED);
    }
  }
}
