package com.caseware.fanout.graph;

import com.caseware.fanout.domain.TemplateId;
import com.caseware.fanout.domain.VersionId;

/** References to the immutable content and diff of a version. Kept after withdrawal (NFR-7). */
public record VersionContent(TemplateId templateId, VersionId version, String contentRef, String diffRef) {}
