package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import java.util.Optional;

/**
 * Read API of the pending-update view (no HTTP here). The firm comes from the authenticated
 * {@link Caller} (NFR-33). Empty means the feature is off for the firm (NFR-41) or, for detail,
 * that the caller has no such EF. A read failure raises {@link StatusUnavailableException}.
 */
public interface PendingUpdateQuery {
  Optional<StatusPage> list(Caller caller, Optional<String> token, int size);

  Optional<StatusRow> detail(Caller caller, EfId ef);
}
