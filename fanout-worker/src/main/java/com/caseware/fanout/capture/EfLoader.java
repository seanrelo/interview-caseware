package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;

/**
 * The EF loading system, owned by another team (about one minute per call, limited capacity).
 * Only {@link BackfillJob} may depend on this port (NFR-22, ADR 0003); hooks, the relay and the
 * publish path must not.
 */
public interface EfLoader {
  TemplateVersionRef load(Region region, EfRef ef) throws EfLoadException;
}
