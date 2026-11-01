package com.caseware.fanout.port;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import java.util.Optional;

/**
 * Index over template-version records (FR-14). Reads records, never loads an EF (NFR-22). Returns
 * only EFs of the given region (NFR-35).
 */
public interface TemplateVersionIndex {
  Page<EfRef> findEfsByTemplate(
      Region region, TemplateId templateId, Optional<String> pageToken, int pageSize);
}
