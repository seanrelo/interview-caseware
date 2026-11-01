package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.DeadLetter;
import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.domain.WorkKey;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryDeadLetterSinkTest {

  private static DeadLetter dl(String ef) {
    return new DeadLetter(
        new WorkKey(new EfId(ef), new TemplateId("t"), new VersionId("v1")),
        new FirmId("f"),
        5,
        "boom",
        Instant.parse("2026-10-30T10:00:00Z"));
  }

  @Test
  @DisplayName("NFR-29: entries are retained in order with depth, and the view is a copy")
  void retainsEntries() {
    InMemoryDeadLetterSink sink = new InMemoryDeadLetterSink(java.time.Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), java.time.ZoneOffset.UTC), java.time.Duration.ofDays(14));
    assertThat(sink.depth()).isZero();

    sink.put(dl("a"));
    sink.put(dl("b"));

    assertThat(sink.depth()).isEqualTo(2);
    var snapshot = sink.entries();
    sink.put(dl("c"));
    assertThat(snapshot).containsExactly(dl("a"), dl("b"));
    assertThat(sink.entries()).hasSize(3);
  }
}
