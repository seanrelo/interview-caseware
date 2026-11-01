package com.caseware.fanout.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.app.CallGate;
import com.caseware.fanout.app.CallGate.Permit;
import com.caseware.fanout.app.MutableClock;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkItem;
import com.caseware.fanout.domain.WorkKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdaptiveLimiterTest {

  private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
  private static final FirmId FIRM = new FirmId("f1");

  private final MutableClock clock = new MutableClock(T0);

  static WorkItem item(FirmId firm, int i) {
    return WorkItem.pending(
        new WorkKey(new EfId("ef-" + i), new TemplateId("t"), new VersionId("v1")), firm, "p", T0);
  }

  private AdaptiveLimiter limiter(int max) {
    LimiterProperties p = LimiterProperties.defaults(max);
    return new AdaptiveLimiter(p, clock, new FirmFairness(p.firmShare(), Set::of));
  }

  private Permit acquire(AdaptiveLimiter l) {
    Optional<Permit> p = l.tryAcquire(item(FIRM, 0));
    assertThat(p).isPresent();
    return p.get();
  }

  @Test
  @SuppressWarnings("java:S2925")
  void c8_inFlightNeverAboveLimitUnderManyThreads() throws Exception {
    int max = 8;
    AdaptiveLimiter l = limiter(max);
    AtomicInteger inside = new AtomicInteger();
    AtomicInteger observedMax = new AtomicInteger();
    AtomicInteger granted = new AtomicInteger();
    AtomicInteger violations = new AtomicInteger();
    int threads = 32;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<?>> fs = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      fs.add(
          pool.submit(
              () -> {
                start.await();
                for (int n = 0; n < 2_000; n++) {
                  Optional<Permit> p = l.tryAcquire(item(FIRM, n));
                  if (p.isEmpty()) {
                    Thread.yield();
                    continue;
                  }
                  granted.incrementAndGet();
                  int now = inside.incrementAndGet();
                  observedMax.accumulateAndGet(now, Math::max);
                  if (now > max || l.inFlight() > max) {
                    violations.incrementAndGet();
                  }
                  int spins = ThreadLocalRandom.current().nextInt(200); // random latency, no sleep
                  for (int s = 0; s < spins; s++) {
                    Thread.onSpinWait();
                  }
                  inside.decrementAndGet();
                  p.get().release(CallGate.Outcome.SUCCESS);
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> f : fs) {
      f.get();
    }
    pool.shutdown();
    assertThat(violations).hasValue(0);
    assertThat(observedMax.get()).isBetween(1, max);
    assertThat(granted.get()).isPositive();
    assertThat(l.inFlight()).isZero();
  }

  @Test
  void c8_refusesWhenAtLimitAndGrantsAgainAfterRelease() {
    AdaptiveLimiter l = limiter(2);
    Permit a = acquire(l);
    acquire(l);
    assertThat(l.tryAcquire(item(FIRM, 1))).isEmpty();
    a.release(CallGate.Outcome.SUCCESS);
    assertThat(l.tryAcquire(item(FIRM, 2))).isPresent();
  }

  @Test
  void releaseIsIdempotent() {
    AdaptiveLimiter l = limiter(4);
    Permit p = acquire(l);
    p.release(CallGate.Outcome.SUCCESS);
    p.release(CallGate.Outcome.SUCCESS);
    assertThat(l.inFlight()).isZero();
  }

  @Test
  void f9_throttlingHalvesLimitThenItRecovers() {
    AdaptiveLimiter l = limiter(8);
    acquire(l).release(CallGate.Outcome.THROTTLED);
    assertThat(l.limit()).isEqualTo(4);

    // A burst of throttles from calls already in flight cuts once, not to the floor.
    acquire(l).release(CallGate.Outcome.THROTTLED);
    assertThat(l.limit()).isEqualTo(4);

    clock.advance(Duration.ofSeconds(2));
    acquire(l).release(CallGate.Outcome.THROTTLED);
    assertThat(l.limit()).isEqualTo(2);
    clock.advance(Duration.ofSeconds(2));
    acquire(l).release(CallGate.Outcome.THROTTLED);
    acquire(l).release(CallGate.Outcome.THROTTLED);
    assertThat(l.limit()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(2));
    acquire(l).release(CallGate.Outcome.THROTTLED);
    assertThat(l.limit()).as("floor is 1").isEqualTo(1);

    for (int i = 0; i < 1_000 && l.limit() < 8; i++) {
      acquire(l).release(CallGate.Outcome.SUCCESS);
    }
    assertThat(l.limit()).as("additive recovery up to the configured maximum").isEqualTo(8);
    for (int i = 0; i < 100; i++) {
      acquire(l).release(CallGate.Outcome.SUCCESS);
    }
    assertThat(l.limit()).as("never above the maximum").isEqualTo(8);
  }

  @Test
  void f9_errorRateAboveTenPercentOverOneMinuteShrinks() {
    AdaptiveLimiter l = limiter(8);
    for (int i = 0; i < 17; i++) {
      acquire(l).release(CallGate.Outcome.SUCCESS);
    }
    acquire(l).release(CallGate.Outcome.ERROR);
    acquire(l).release(CallGate.Outcome.ERROR);
    assertThat(l.limit()).as("below min samples").isEqualTo(8);
    acquire(l).release(CallGate.Outcome.ERROR); // 3 of 20 = 15%
    assertThat(l.limit()).isEqualTo(4);
  }

  @Test
  void f9_errorRateAtOrBelowThresholdOrOutsideWindowDoesNotShrink() {
    AdaptiveLimiter l = limiter(8);
    // 2 of 20 = exactly 10%: not above the threshold.
    acquire(l).release(CallGate.Outcome.ERROR);
    acquire(l).release(CallGate.Outcome.ERROR);
    for (int i = 0; i < 18; i++) {
      acquire(l).release(CallGate.Outcome.SUCCESS);
    }
    assertThat(l.limit()).isEqualTo(8);

    // Old errors age out of the 1 minute window.
    AdaptiveLimiter m = limiter(8);
    for (int i = 0; i < 3; i++) {
      acquire(m).release(CallGate.Outcome.ERROR);
    }
    clock.advance(Duration.ofSeconds(61));
    for (int i = 0; i < 20; i++) {
      acquire(m).release(CallGate.Outcome.SUCCESS);
    }
    assertThat(m.limit()).isEqualTo(8);
  }

  @Test
  void lowerLimitBlocksNewPermitsWhileExcessDrains() {
    AdaptiveLimiter l = limiter(4);
    Permit a = acquire(l);
    Permit b = acquire(l);
    Permit c = acquire(l);
    acquire(l);
    a.release(CallGate.Outcome.THROTTLED); // limit 2, in-flight 3
    assertThat(l.limit()).isEqualTo(2);
    assertThat(l.tryAcquire(item(FIRM, 9))).isEmpty();
    b.release(CallGate.Outcome.SUCCESS);
    assertThat(l.tryAcquire(item(FIRM, 9))).isEmpty(); // in-flight 2 == limit
    c.release(CallGate.Outcome.SUCCESS);
    assertThat(l.tryAcquire(item(FIRM, 9))).isPresent();
  }

  @Test
  void u38_metersRegistered() {
    AdaptiveLimiter l = limiter(8);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicInteger backlog = new AtomicInteger(42);
    LimiterMetrics.register(registry, l, backlog::get);
    Permit p = acquire(l);
    assertThat(registry.get(LimiterMetrics.BACKLOG).gauge().value()).isEqualTo(42);
    assertThat(registry.get(LimiterMetrics.IN_FLIGHT).gauge().value()).isEqualTo(1);
    assertThat(registry.get(LimiterMetrics.LIMIT).gauge().value()).isEqualTo(8);
    p.release(CallGate.Outcome.THROTTLED);
    assertThat(registry.get(LimiterMetrics.IN_FLIGHT).gauge().value()).isZero();
    assertThat(registry.get(LimiterMetrics.LIMIT).gauge().value()).isEqualTo(4);
  }
}
