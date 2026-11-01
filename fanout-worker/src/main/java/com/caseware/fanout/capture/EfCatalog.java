package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import java.util.Optional;

/**
 * Enumerates the EF identifiers of one region, page by page. Returns ids only and never loads an EF
 * (NFR-22). The catalog is regional: ids never leave their region (NFR-35).
 */
public interface EfCatalog {
  Page<EfRef> listEfs(Region region, Optional<String> token, int pageSize);
}
