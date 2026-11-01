package com.caseware.fanout.port;

import com.caseware.fanout.domain.DeadLetter;

public interface DeadLetterSink {
  void put(DeadLetter deadLetter);

  int depth();
}
