package com.caseware.fanout.status;

import com.caseware.fanout.graph.VersionGraph;

/** Fresh immutable graph snapshot; {@code registry::snapshot} fits. */
@FunctionalInterface
public interface VersionGraphProvider {
  VersionGraph snapshot();
}
