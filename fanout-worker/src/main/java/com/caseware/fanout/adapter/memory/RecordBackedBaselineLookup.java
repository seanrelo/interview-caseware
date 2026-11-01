package com.caseware.fanout.adapter.memory;

import com.caseware.fanout.capture.TemplateVersionRecord;
import com.caseware.fanout.capture.TemplateVersionRecordStore;
import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import com.caseware.fanout.graph.BaselineLookup;
import java.util.Objects;
import java.util.Optional;

/**
 * Baseline lookup over the stored record. Returns the baseline field only (not the current
 * version). Empty when there is no record, no baseline, or the requested template differs from
 * the record's. Not an EF loader (NFR-22).
 */
public class RecordBackedBaselineLookup implements BaselineLookup {

  private final TemplateVersionRecordStore store;

  public RecordBackedBaselineLookup(TemplateVersionRecordStore store) {
    this.store = Objects.requireNonNull(store);
  }

  @Override
  public Optional<VersionId> baselineOf(Region region, EfRef ef, TemplateId templateId) {
    return store
        .find(region, ef)
        .filter(r -> r.template().equals(templateId))
        .flatMap(TemplateVersionRecord::baseline);
  }
}
