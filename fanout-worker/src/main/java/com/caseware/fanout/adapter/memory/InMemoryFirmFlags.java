package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.port.FirmFlags;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Every firm is enabled unless switched off. */
public class InMemoryFirmFlags implements FirmFlags {

  private final Set<FirmId> disabled = ConcurrentHashMap.newKeySet();

  public void setEnabled(FirmId firm, boolean enabled) {
    if (enabled) {
      disabled.remove(firm);
    } else {
      disabled.add(firm);
    }
  }

  @Override
  public boolean enabled(FirmId firm) {
    return !disabled.contains(firm);
  }
}
