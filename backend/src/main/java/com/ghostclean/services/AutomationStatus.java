package com.ghostclean.services;

/**
 * Whether a route is structurally compatible with future automation
 * (Phase 5). <em>Compatiblity is not capability</em>: even
 * {@code AUTOMATABLE} means "could one day be automated", never that
 * GhostClean performs the action — Phase 5 executes zero deletions.
 */
public enum AutomationStatus {
  NOT_SUPPORTED,
  MANUAL_ONLY,
  FUTURE_AUTOMATION,
  AUTOMATABLE,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static AutomationStatus parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return AutomationStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
