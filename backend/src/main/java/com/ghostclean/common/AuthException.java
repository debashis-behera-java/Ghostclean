package com.ghostclean.common;

import org.springframework.http.HttpStatus;

/**
 * Typed authentication failure carrying its own HTTP status and stable error
 * code, so controllers stay thin and responses stay consistent. Messages are
 * generic by design (no account-oracle, no internals).
 */
public class AuthException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public AuthException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public static AuthException invalidCredentials() {
    return new AuthException(HttpStatus.UNAUTHORIZED, ApiError.INVALID_CREDENTIALS,
        "Invalid email or password");
  }

  public static AuthException accountLocked() {
    return new AuthException(HttpStatus.LOCKED, ApiError.ACCOUNT_LOCKED,
        "Account temporarily locked after too many failed attempts");
  }

  public static AuthException rateLimited() {
    return new AuthException(HttpStatus.TOO_MANY_REQUESTS, ApiError.RATE_LIMITED,
        "Too many requests, please slow down");
  }

  public static AuthException invalidToken() {
    return new AuthException(HttpStatus.BAD_REQUEST, ApiError.INVALID_TOKEN,
        "Invalid verification token");
  }

  public static AuthException expiredToken() {
    return new AuthException(HttpStatus.GONE, ApiError.EXPIRED_TOKEN,
        "Verification token has expired");
  }

  public static AuthException refreshRequired() {
    return new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Valid refresh session required");
  }

  public static AuthException weakPassword() {
    return new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
        "Password does not meet the minimum requirements");
  }
}
