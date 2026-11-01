package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.EfRef;
import java.util.Optional;

/** Resolves the owning firm of an EF from regional data; never loads the EF (NFR-21). */
public interface EfFirmLookup {
  Optional<EfRef> firmOf(EfId ef);
}
