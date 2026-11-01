package com.caseware.fanout.summary;

public interface ModelClient {
  ModelOutput generate(ModelRequest request) throws ModelException;
}
