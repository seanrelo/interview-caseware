package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.FirmId;
import com.caseware.fanout.domain.Page;
import java.util.Optional;

/** Regional status rows, partition = firm, sort = EF id (ADR 0008). No EF-load method (NFR-21). */
public interface StatusStore {
  /** Upserts the row and keeps the firm counts current in O(1). */
  void put(StatusRow row);

  Optional<StatusRow> find(FirmId firm, EfId ef);

  /** Rows of one firm in EF id order after the token (the last EF id of the previous page). */
  Page<StatusRow> page(FirmId firm, Optional<String> token, int size);

  StatusCounts counts(FirmId firm);
}
