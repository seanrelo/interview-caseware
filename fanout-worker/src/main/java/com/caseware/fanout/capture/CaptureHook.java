package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;

/**
 * Called by the EF management system from create, open, save and accept. The caller passes values
 * it already has in memory (NFR-1). Implementations never throw (NFR-3) and do nothing for a
 * flag-off firm (NFR-41).
 */
public interface CaptureHook {
  void onCreate(Region region, EfRef ef, TemplateId template, VersionId version, Instant at);

  void onOpen(Region region, EfRef ef, TemplateId template, VersionId version, Instant at);

  void onSave(Region region, EfRef ef, TemplateId template, VersionId version, Instant at);

  void onAccept(
      Region region, EfRef ef, TemplateId template, VersionId accepted, Instant decidedAt);
}
