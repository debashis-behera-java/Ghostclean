package com.ghostclean.privacy;

/**
 * Request intent (Phase 6). Stored as the authoritative type; a
 * DELETE_ACCOUNT claim is only kept when the service-intelligence layer
 * reports a trusted route — otherwise creation coerces to
 * GENERAL_PRIVACY_REQUEST rather than inventing support.
 */
public enum PrivacyRequestType {
  DELETE_PERSONAL_DATA,
  DELETE_ACCOUNT,
  ACCESS_PERSONAL_DATA,
  GENERAL_PRIVACY_REQUEST,
  UNKNOWN;

  /** Strict parse for user input: empty → default, garbage → empty. */
  public static java.util.Optional<PrivacyRequestType> parseStrict(String raw) {
    if (raw == null || raw.isBlank()) {
      return java.util.Optional.of(GENERAL_PRIVACY_REQUEST);
    }
    try {
      return java.util.Optional.of(PrivacyRequestType.valueOf(raw.trim().toUpperCase()));
    } catch (IllegalArgumentException ex) {
      return java.util.Optional.empty();
    }
  }

  /** Lenient parse for stored values: never throws, defaults to UNKNOWN. */
  public static PrivacyRequestType parseLenient(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return PrivacyRequestType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
