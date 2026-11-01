package com.caseware.fanout.summary;

public class ModelException extends Exception {
  public ModelException(String message) {
    super(message);
  }

  public ModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
