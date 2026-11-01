package com.caseware.fanout.capture;

import com.caseware.fanout.domain.EfRef;
import com.caseware.fanout.domain.Region;
import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;
import java.time.Instant;
import java.util.Objects;

/**
 * One captured fact travelling hook -> outbox -> relay -> store. {@code attempt} is the number of
 * failed deliveries so far (0 on first delivery). {@code at} is the capturedAt (VERSION) or
 * decidedAt (BASELINE).
 */
public record CaptureMessage(
    String id,
    Kind kind,
    Region region,
    EfRef ef,
    TemplateId template,
    VersionId version,
    Instant at,
    CaptureSource source,
    int attempt) {

  public enum Kind {
    VERSION,
    BASELINE
  }

  public CaptureMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(ef, "ef");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(source, "source");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must be >= 0");
    }
    if ((kind == Kind.BASELINE) != (source == CaptureSource.ACCEPT)) {
      throw new IllegalArgumentException("BASELINE messages have source ACCEPT and only them");
    }
  }

  public CaptureMessage withAttempt(int newAttempt) {
    return new CaptureMessage(id, kind, region, ef, template, version, at, source, newAttempt);
  }
}
