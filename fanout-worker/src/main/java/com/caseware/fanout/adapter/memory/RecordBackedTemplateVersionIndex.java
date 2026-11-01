package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.capture.TemplateVersionRecordScan;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Page;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.port.TemplateVersionIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fan-out index over the template-version records (FR-14). Same semantics as the stub: pages ordered
 * by EF id, token = last EF id returned, region filtered. Buckets by the CURRENT template id of
 * the record; EFs without a record are absent. The scan is O(records) per page, a stand-in for the
 * template-id GSI. Reads records only, never an EF (NFR-22).
 */
public class RecordBackedTemplateVersionIndex implements TemplateVersionIndex {

  private final TemplateVersionRecordScan records;

  public RecordBackedTemplateVersionIndex(TemplateVersionRecordScan records) {
    this.records = Objects.requireNonNull(records);
  }

  @Override
  public Page<EfRef> findEfsByTemplate(
      Region region, TemplateId templateId, Optional<String> pageToken, int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be > 0");
    }
    List<EfRef> items = new ArrayList<>(pageSize);
    String last = null;
    for (TemplateVersionRecord r : records.scan(region, pageToken)) {
      if (!r.template().equals(templateId)) {
        continue;
      }
      if (items.size() == pageSize) {
        return new Page<>(items, Optional.of(last));
      }
      items.add(r.ef());
      last = r.ef().ef().value();
    }
    return new Page<>(items, Optional.empty());
  }
}
