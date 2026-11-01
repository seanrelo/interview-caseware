package com.caseware.fanout.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.FirmId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryFirmFlagsTest {

  @Test
  @DisplayName("NFR-41: firms are enabled by default, can be switched off and on per firm")
  void toggles() {
    InMemoryFirmFlags flags = new InMemoryFirmFlags();
    FirmId a = new FirmId("a");
    FirmId b = new FirmId("b");
    assertThat(flags.enabled(a)).isTrue();

    flags.setEnabled(a, false);
    assertThat(flags.enabled(a)).isFalse();
    assertThat(flags.enabled(b)).isTrue();

    flags.setEnabled(a, true);
    assertThat(flags.enabled(a)).isTrue();
  }
}
