package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryPublishProgressStoreTest {

  @Test
  @DisplayName("NFR-27: addAndSeal is atomic and a second call after sealing is a no-op")
  void addAndSealOnce() {
    InMemoryPublishProgressStore s = new InMemoryPublishProgressStore();
    s.addAndSeal("p", 3);
    s.addAndSeal("p", 99);

    assertThat(s.totals("p").total()).isEqualTo(3);
    assertThat(s.totals("p").sealed()).isTrue();
    assertThat(s.totals("other").sealed()).isFalse();
  }
}
