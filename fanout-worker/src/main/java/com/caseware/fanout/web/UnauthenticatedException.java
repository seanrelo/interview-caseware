package com.caseware.fanout.web;

/** No caller could be resolved from the request. Mapped to 401 UNAUTHENTICATED. */
public class UnauthenticatedException extends RuntimeException {
  public UnauthenticatedException() {
    super("UNAUTHENTICATED", null, false, false);
  }
}
