package com.caseware.fanout.port;

import com.caseware.fanout.domain.FirmId;

public interface FirmFlags {
  boolean enabled(FirmId firm);
}
