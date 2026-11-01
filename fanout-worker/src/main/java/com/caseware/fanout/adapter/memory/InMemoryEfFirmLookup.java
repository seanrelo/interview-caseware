package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.status.EfFirmLookup;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEfFirmLookup implements EfFirmLookup {
  private final Map<EfId, EfRef> byEf = new ConcurrentHashMap<>();

  public void add(EfRef ef) {
    byEf.put(ef.ef(), ef);
  }

  @Override
  public Optional<EfRef> firmOf(EfId ef) {
    return Optional.ofNullable(byEf.get(ef));
  }
}
