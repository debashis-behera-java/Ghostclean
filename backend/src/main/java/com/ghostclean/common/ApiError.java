package com.ghostclean.common;

import java.time.Instant;

/**
 * Stable API error envelope. Carries a machine-readable {@code code}, a human
 * message, and the correlation id. Never carries stack traces or secrets.
 */
public record ApiError(
    Instant timestamp,
    int status,
    String code,
    String message,
    String path,
    String requestId) {

  /** Error codes shared across the API (extended by later phases). */
  public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
  public static final String UNAUTHORIZED = "UNAUTHORIZED";
  public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
  public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
  public static final String INVALID_TOKEN = "INVALID_TOKEN";
  public static final String EXPIRED_TOKEN = "EXPIRED_TOKEN";
  public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
  public static final String FORBIDDEN = "FORBIDDEN";
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String RATE_LIMITED =
      "RATE_LIMITED";
  public static final String OAUTH_DENIED = "OAUTH_DENIED";
  public static final String OAUTH_STATE_MISMATCH = "OAUTH_STATE_MISMATCH";
  public static final String PROVIDER_ERROR = "PROVIDER_ERROR";
  public static final String SCOPE_INSUFFICIENT = "SCOPE_INSUFFICIENT";
  public static final String OAUTH_PROVIDER_DISABLED = "OAUTH_PROVIDER_DISABLED";
  public static final String GOOGLE_CONNECTION_REQUIRED = "GOOGLE_CONNECTION_REQUIRED";
  public static final String GOOGLE_REAUTH_REQUIRED = "GOOGLE_REAUTH_REQUIRED";
  public static final String MICROSOFT_CONNECTION_REQUIRED = "MICROSOFT_CONNECTION_REQUIRED";
  public static final String MICROSOFT_REAUTH_REQUIRED = "MICROSOFT_REAUTH_REQUIRED";
  public static final String SCAN_CONFLICT = "SCAN_CONFLICT";
  public static final String REQUEST_CONFLICT = "REQUEST_CONFLICT";
  public static final String REQUEST_EXPIRED = "REQUEST_EXPIRED";
  public static final String DESTINATION_UNKNOWN = "DESTINATION_UNKNOWN";
  public static final String MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";
  public static final String INTERNAL = "INTERNAL";
}
