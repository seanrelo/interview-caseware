package com.caseware.fanout.web;

import com.caseware.fanout.status.DecisionRejectedException;
import com.caseware.fanout.status.StatusUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One place for error bodies. A foreign EF and an unknown EF map to the same 404 (NFR-33). */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<ErrorBody> unauthenticated(UnauthenticatedException e) {
    return of(HttpStatus.UNAUTHORIZED, ErrorBody.of("UNAUTHENTICATED"));
  }

  @ExceptionHandler({BadRequestException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ErrorBody> badRequest(Exception e) {
    return of(HttpStatus.BAD_REQUEST, ErrorBody.of("BAD_REQUEST"));
  }

  @ExceptionHandler(StatusUnavailableException.class)
  ResponseEntity<ErrorBody> statusUnavailable(StatusUnavailableException e) {
    return of(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ErrorBody("STATUS_UNAVAILABLE", "unknown, not up to date"));
  }

  @ExceptionHandler(DecisionRejectedException.class)
  ResponseEntity<ErrorBody> rejected(DecisionRejectedException e) {
    return switch (e.reason()) {
      case NOT_YOUR_FIRM -> of(HttpStatus.NOT_FOUND, ErrorBody.of("NOT_FOUND"));
      case FEATURE_OFF -> of(HttpStatus.NOT_FOUND, ErrorBody.of("FEATURE_OFF"));
      case UNKNOWN_VERSION -> of(HttpStatus.UNPROCESSABLE_ENTITY, ErrorBody.of("UNKNOWN_VERSION"));
    };
  }

  @ExceptionHandler(StaleOrNotPendingException.class)
  ResponseEntity<ErrorBody> staleOrNotPending(StaleOrNotPendingException e) {
    return of(HttpStatus.UNPROCESSABLE_ENTITY, ErrorBody.of("STALE_OR_NOT_PENDING"));
  }

  @ExceptionHandler(DecisionUnavailableException.class)
  ResponseEntity<ErrorBody> decisionUnavailable(DecisionUnavailableException e) {
    return of(HttpStatus.SERVICE_UNAVAILABLE, ErrorBody.of("DECISION_UNAVAILABLE"));
  }

  private static ResponseEntity<ErrorBody> of(HttpStatus status, ErrorBody body) {
    return ResponseEntity.status(status).body(body);
  }
}
