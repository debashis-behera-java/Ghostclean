package com.ghostclean.services;

/**
 * Whether the registry holds a usable route description for a service
 * (Phase 5). {@code KNOWN} additionally requires a VERIFIED source —
 * an unverified row is never presented as trusted.
 */
public enum RouteAvailability {
  KNOWN,
  UNKNOWN,
  AMBIGUOUS;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static RouteAvailability parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return RouteAvailability.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
