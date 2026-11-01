package com.caseware.fanout.fault;

/** Thrown by {@link CrashingStore} to model a process death. Not a DownstreamException. */
public class SimulatedCrashException extends RuntimeException {
  public SimulatedCrashException(String message) {
    super(message);
  }
}
