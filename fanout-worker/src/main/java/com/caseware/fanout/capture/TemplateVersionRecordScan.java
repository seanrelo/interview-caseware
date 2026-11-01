package com.caseware.fanout.capture;

import com.caseware.fanout.domain.Region;
import java.util.Optional;

/**
 * Read side used by the fan-out index adapter (FR-14): records of one region in EF id order, after
 * the given EF id. Stands in for the template-id GSI of ADR 0002. Reads records only.
 */
public interface TemplateVersionRecordScan {
  Iterable<TemplateVersionRecord> scan(Region region, Optional<String> afterEfId);
}
