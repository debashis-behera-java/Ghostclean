package com.ghostclean.services;

/**
 * The actual mechanism a route uses (Phase 5). Kept separate from
 * {@link RouteType}: the route says <em>where</em>, the method says
 * <em>how</em>. Descriptive only — Phase 5 implements no sending.
 */
public enum RequestMethod {
  WEB_FORM,
  PRIVACY_PORTAL,
  EMAIL,
  ACCOUNT_SETTINGS,
  MANUAL,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static RequestMethod parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return RequestMethod.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
