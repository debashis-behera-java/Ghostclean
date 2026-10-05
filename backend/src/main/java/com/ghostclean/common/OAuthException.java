package com.ghostclean.common;

import org.springframework.http.HttpStatus;

/**
 * Typed OAuth failure carrying its own HTTP status and stable error code,
 * mirroring {@code AuthException}. Messages are generic by design: provider
 * error details, codes, tokens, and secrets never reach the caller.
 */
public class OAuthException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public OAuthException(HttpStatus status, String code, String message) {
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

  /** User denied the grant, or the provider reported access_denied. */
  public static OAuthException denied() {
    return new OAuthException(HttpStatus.BAD_REQUEST, ApiError.OAUTH_DENIED,
        "Authorization was denied");
  }

  /** Missing, unknown, expired, or already-consumed state. */
  public static OAuthException stateMismatch() {
    return new OAuthException(HttpStatus.BAD_REQUEST, ApiError.OAUTH_STATE_MISMATCH,
        "Invalid or expired authorization request");
  }

  /** Token exchange, account lookup, or revocation prerequisite failed. */
  public static OAuthException providerError() {
    return new OAuthException(HttpStatus.BAD_GATEWAY, ApiError.PROVIDER_ERROR,
        "Provider request failed; please try again");
  }

  /** Provider is not wired in this build (e.g. Microsoft before Phase 10). */
  public static OAuthException providerDisabled() {
    return new OAuthException(HttpStatus.GONE, ApiError.OAUTH_PROVIDER_DISABLED,
        "This provider is not available");
  }

  /** Consent did not include a scope the flow requires. */
  public static OAuthException scopeInsufficient() {
    return new OAuthException(HttpStatus.FORBIDDEN, ApiError.SCOPE_INSUFFICIENT,
        "Required permissions were not granted");
  }

  /** Stored connection is unusable; the user must reconnect. */
  public static OAuthException reconnectRequired() {
    return new OAuthException(HttpStatus.BAD_REQUEST, ApiError.PROVIDER_ERROR,
        "Connection is no longer valid; please reconnect");
  }
}
