package com.caseware.fanout.status;

import com.caseware.fanout.domain.EfId;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.util.Set;

/** Read side of the append-only decision records (ADR 0009); decisions pin (EF, version). */
public interface DecisionReader {
  Set<VersionId> declined(EfId ef, TemplateId template);

  Set<VersionId> accepted(EfId ef, TemplateId template);
}
