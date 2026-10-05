package com.ghostclean.services;

/**
 * How a privacy/deletion request can be raised with a service (Phase 5).
 * Descriptive only — GhostClean never executes any of these.
 */
public enum RouteType {
  SELF_SERVICE_ACCOUNT_DELETE,
  PRIVACY_REQUEST_FORM,
  PRIVACY_PORTAL,
  EMAIL_REQUEST,
  ACCOUNT_SETTINGS,
  HELP_CENTER,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static RouteType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return RouteType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
