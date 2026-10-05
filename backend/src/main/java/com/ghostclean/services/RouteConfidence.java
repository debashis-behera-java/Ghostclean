package com.ghostclean.services;

/**
 * How well a route is verified (Phase 5). Reflects evidence quality only:
 * official portal → HIGH; official help page → MEDIUM/HIGH; third-party
 * article → LOW. Guessed URLs are never accepted anywhere.
 */
public enum RouteConfidence {
  HIGH,
  MEDIUM,
  LOW,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static RouteConfidence parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return RouteConfidence.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
