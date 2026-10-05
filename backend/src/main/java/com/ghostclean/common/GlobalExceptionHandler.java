package com.ghostclean.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global error handler producing the stable {@link ApiError} envelope.
 * Intentionally generic: internal messages and stack traces never leave the
 * server.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    return body(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
        "Request validation failed", request);
  }

  /**
   * Unreadable request bodies (malformed JSON, empty body, wrong wire shape)
   * are client errors, not server failures: answer 400 through the same
   * validation envelope instead of falling through to the 500 handler.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadableBody(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return body(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
        "Request validation failed", request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(
      NoResourceFoundException ex, HttpServletRequest request) {
    return body(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND, "Resource not found", request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleForbidden(
      AccessDeniedException ex, HttpServletRequest request) {
    return body(HttpStatus.FORBIDDEN, ApiError.FORBIDDEN, "Access denied", request);
  }

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ApiError> handleAuth(
      AuthException ex, HttpServletRequest request) {
    return body(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
  }

  @ExceptionHandler(OAuthException.class)
  public ResponseEntity<ApiError> handleOAuth(
      OAuthException ex, HttpServletRequest request) {
    return body(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    // Server-side signal only (class + correlation id; never the message,
    // which could carry sensitive values). The client gets the generic body.
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    log.error("Unhandled error: {} requestId={}", ex.getClass().getName(), requestId);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.INTERNAL,
        "Unexpected error", request);
  }

  private ResponseEntity<ApiError> body(
      HttpStatus status, String code, String message, HttpServletRequest request) {
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId == null) {
      requestId = "n/a";
    }
    return ResponseEntity.status(status)
        .body(new ApiError(Instant.now(), status.value(), code, message,
            request.getRequestURI(), requestId));
  }
}
