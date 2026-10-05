package com.ghostclean.privacy;

/**
 * How a deletion/privacy request can be pursued for a service (Phase 8).
 * Only EMAIL is eligible for the Phase 7 controlled dispatch; every other
 * method means manual handling through verified metadata. Descriptive
 * only — resolving a method never performs anything.
 */
public enum DeletionMethod {
  EMAIL,
  PRIVACY_PORTAL,
  ACCOUNT_SETTINGS,
  WEB_FORM,
  MANUAL,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static DeletionMethod parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return DeletionMethod.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
