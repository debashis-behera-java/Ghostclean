package com.ghostclean.services;

/**
 * Verification lifecycle of a route description (Phase 5). Only
 * {@code VERIFIED} rows are ever presented as trusted routes;
 * {@code UNVERIFIED} rows stay UNKNOWN-facing and {@code REJECTED} rows
 * are suppressed from route presentation entirely.
 */
public enum RouteVerificationStatus {
  VERIFIED,
  UNVERIFIED,
  REJECTED;

  /** Lenient parse: blank or unrecognized values become UNVERIFIED, never throw. */
  public static RouteVerificationStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNVERIFIED;
    }
    try {
      return RouteVerificationStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNVERIFIED;
    }
  }
}
