package com.caseware.fanout.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {"error": CODE} with an optional message. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String error, String message) {
  static ErrorBody of(String error) {
    return new ErrorBody(error, null);
  }
}
