package com.caseware.fanout.graph;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Optional;

/**
 * Reads the STORED baseline of an EF (captured by the spec 01 hooks). This is a lookup of a
 * record, not an EF loader (NFR-22). Empty means no baseline is stored.
 */
public interface BaselineLookup {
  Optional<VersionId> baselineOf(Region region, EfRef ef, TemplateId templateId);
}
