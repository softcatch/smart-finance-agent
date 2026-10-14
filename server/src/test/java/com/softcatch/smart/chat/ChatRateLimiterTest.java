package com.softcatch.smart.chat;

import com.softcatch.smart.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChatRateLimiterTest {

  @Test
  void 같은_사용자가_동시에_15건_보내면_정확히_10건만_통과() throws Exception {
    ChatRateLimiter limiter = new ChatRateLimiter();
    ExecutorService pool = Executors.newFixedThreadPool(15);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger passed = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();

    List<Future<?>> tasks = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      tasks.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  limiter.check(1L);
                  passed.incrementAndGet();
                } catch (ApiException e) {
                  rejected.incrementAndGet();
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> task : tasks) {
      task.get();
    }
    pool.shutdown();

    Assertions.assertEquals(10, passed.get());
    Assertions.assertEquals(5, rejected.get());
  }

  @Test
  void 다른_사용자는_서로_영향_없음() {
    ChatRateLimiter limiter = new ChatRateLimiter();
    for (int i = 0; i < ChatRateLimiter.LIMIT; i++) {
      limiter.check(1L);
    }

    Assertions.assertThrows(ApiException.class, () -> limiter.check(1L));
    Assertions.assertDoesNotThrow(() -> limiter.check(2L));
  }
}
