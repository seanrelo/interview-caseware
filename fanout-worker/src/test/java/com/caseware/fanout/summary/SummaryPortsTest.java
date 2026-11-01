package com.caseware.fanout.summary;

import static com.caseware.fanout.summary.SummaryFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.adapter.memory.FakeModelClient;
import com.caseware.fanout.adapter.memory.InMemorySingleFlight;
import com.caseware.fanout.adapter.memory.InMemorySummaryJobQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryPortsTest {
  private final PairKey p = pair("v1", "v2");

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId z) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  @Test
  @DisplayName("S6 FR-37 NFR-37: ModelRequest has only model, prompt and diff; no EF, firm or user field")
  void requestShape() {
    assertThat(ModelRequest.class.getRecordComponents()).extracting(c -> c.getName()).containsExactly("model", "prompt", "diff");
    assertThat(ModelClient.class.getDeclaredMethods()).hasSize(1);
    assertThat(ModelClient.class.getDeclaredMethods()[0].getParameterTypes()).containsExactly(ModelRequest.class);
    for (Class<?> c : new Class<?>[] {Diff.class, DiffItem.class, PairKey.class}) {
      assertThat(c.getRecordComponents()).extracting(x -> x.getName().toLowerCase()).noneMatch(n -> n.contains("ef") && !n.equals("sectionname") || n.contains("firm") || n.contains("user"));
    }
  }

  @Test
  @DisplayName("S6 FR-37: the fake captures exactly (model, prompt, diff); statements name sections, not paths")
  void fakeCaptures() throws Exception {
    FakeModelClient fake = new FakeModelClient();
    Diff d = diff(p);
    ModelOutput out = fake.generate(new ModelRequest(MODEL, PROMPT, d));
    assertThat(fake.calls()).isEqualTo(1);
    assertThat(fake.captured()).containsExactly(new ModelRequest(MODEL, PROMPT, d));
    assertThat(out.statements()).hasSize(2);
    assertThat(out.statements().get(0).text()).contains("Revenue", "title renamed").doesNotContain("$.");
    assertThat(new SummaryValidator().validate(d, out.statements())).isInstanceOf(ValidationResult.Valid.class);
  }

  @Test
  @DisplayName("S11: fake failNext fails n calls then recovers; override replaces output")
  void fakeHooks() throws Exception {
    FakeModelClient fake = new FakeModelClient();
    ModelRequest req = new ModelRequest(MODEL, PROMPT, diff(p));
    fake.failNext(2);
    for (int i = 0; i < 2; i++) {
      try {
        fake.generate(req);
        throw new AssertionError("expected failure");
      } catch (ModelException expected) {
        // expected
      }
    }
    assertThat(fake.generate(req).statements()).hasSize(2);
    fake.outputOverride(d -> new ModelOutput(List.of()));
    assertThat(fake.generate(req).statements()).isEmpty();
    assertThat(fake.calls()).isEqualTo(4);
  }

  @Test
  @DisplayName("S4 FR-36: 32 threads racing for one pair yield exactly one lease")
  void singleFlightRace() throws Exception {
    InMemorySingleFlight sf = new InMemorySingleFlight(clock());
    int n = 32;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService ex = Executors.newFixedThreadPool(n);
    List<Future<Optional<Lease>>> fs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      fs.add(ex.submit(() -> {
        start.await();
        return sf.tryAcquire(p, Duration.ofMinutes(2));
      }));
    }
    start.countDown();
    int won = 0;
    for (Future<Optional<Lease>> f : fs) {
      won += f.get().isPresent() ? 1 : 0;
    }
    ex.shutdown();
    assertThat(won).isEqualTo(1);
  }

  @Test
  @DisplayName("S4 ADR 0010: an expired lease is taken over; release frees the pair; stale release is a no-op")
  void leaseLifecycle() {
    MutableClock clock = new MutableClock();
    InMemorySingleFlight sf = new InMemorySingleFlight(clock);
    Lease a = sf.tryAcquire(p, Duration.ofMinutes(2)).orElseThrow();
    assertThat(sf.tryAcquire(p, Duration.ofMinutes(2))).isEmpty();
    assertThat(sf.tryAcquire(pair("v1", "v3"), Duration.ofMinutes(2))).isPresent();
    clock.advance(Duration.ofMinutes(2));
    Lease b = sf.tryAcquire(p, Duration.ofMinutes(2)).orElseThrow();
    assertThat(b.owner()).isNotEqualTo(a.owner());
    sf.release(a);
    assertThat(sf.tryAcquire(p, Duration.ofMinutes(2))).isEmpty();
    sf.release(b);
    assertThat(sf.tryAcquire(p, Duration.ofMinutes(2))).isPresent();
  }

  @Test
  @DisplayName("S14 NFR-27: enqueue is idempotent per pair while outstanding")
  void enqueueIdempotent() {
    InMemorySummaryJobQueue q = new InMemorySummaryJobQueue(clock());
    q.enqueue(p, JobReason.PUBLISH);
    q.enqueue(p, JobReason.ON_DEMAND);
    assertThat(q.depth()).isEqualTo(1);
    SummaryJob job = q.poll().orElseThrow();
    assertThat(job.reason()).isEqualTo(JobReason.PUBLISH);
    q.enqueue(p, JobReason.PUBLISH);
    assertThat(q.poll()).isEmpty();
    q.ack(job);
    assertThat(q.depth()).isZero();
    q.enqueue(p, JobReason.ON_DEMAND);
    assertThat(q.poll()).isPresent();
  }

  @Test
  @DisplayName("S11 FR-42: retryLater is invisible until the clock passes the delay, attempt grows")
  void retryLater() {
    MutableClock clock = new MutableClock();
    InMemorySummaryJobQueue q = new InMemorySummaryJobQueue(clock);
    q.enqueue(p, JobReason.PUBLISH);
    SummaryJob j1 = q.poll().orElseThrow();
    q.retryLater(j1, Duration.ofSeconds(10));
    assertThat(q.depth()).isEqualTo(1);
    assertThat(q.poll()).isEmpty();
    clock.advance(Duration.ofSeconds(9));
    assertThat(q.poll()).isEmpty();
    clock.advance(Duration.ofSeconds(1));
    SummaryJob j2 = q.poll().orElseThrow();
    assertThat(j2.attempt()).isEqualTo(2);
    assertThat(j2.reason()).isEqualTo(JobReason.RETRY);
  }

  @Test
  @DisplayName("S11 NFR-29: deadLetter keeps the entry with cause and time and frees the pair")
  void deadLetter() {
    MutableClock clock = new MutableClock();
    InMemorySummaryJobQueue q = new InMemorySummaryJobQueue(clock);
    q.enqueue(p, JobReason.PUBLISH);
    SummaryJob j = q.poll().orElseThrow();
    clock.advance(Duration.ofSeconds(5));
    q.deadLetter(j, "model failed 5 times");
    assertThat(q.depth()).isZero();
    assertThat(q.deadLetters()).singleElement().satisfies(d -> {
      assertThat(d.job()).isEqualTo(j);
      assertThat(d.cause()).isEqualTo("model failed 5 times");
      assertThat(d.at()).isEqualTo(T0.plusSeconds(5));
    });
  }
}
