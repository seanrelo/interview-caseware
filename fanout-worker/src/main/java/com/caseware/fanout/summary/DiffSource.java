package com.caseware.fanout.summary;

import java.util.Optional;

/** Global-layer diff lookup. Empty means unknown or unreachable pair, never an empty diff. */
public interface DiffSource {
  Optional<Diff> diff(PairKey pair);
}
