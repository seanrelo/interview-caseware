package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;

/** Event emitted when a version is published. Carries identifiers only, never content (NFR-6). */
public record GraphPublishEvent(TemplateId templateId, VersionId version) {}
