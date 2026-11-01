package com.caseware.fanout.web;

/** A malformed client input (size not a number, missing version). Mapped to 400. */
public class BadRequestException extends RuntimeException {
  public BadRequestException(String message) {
    super(message, null, false, false);
  }
}
