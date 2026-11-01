package com.caseware.fanout.summary;

import java.util.Objects;

/** The ONLY input to a model call: pinned model, prompt version and the diff (FR-37, NFR-37). */
public record ModelRequest(ModelId model, PromptVersion prompt, Diff diff) {
  public ModelRequest {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(prompt, "prompt");
    Objects.requireNonNull(diff, "diff");
  }
}
