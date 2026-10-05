package com.ghostclean.privacy;

/**
 * Configurable jurisdiction wording (Phase 6). Selects a canned,
 * conservative paragraph — never an individualized legal determination.
 */
public enum PrivacyJurisdiction {
  GENERAL,
  GDPR,
  CCPA_CPRA;

  /** Strict parse for user input: empty → default, garbage → empty. */
  public static java.util.Optional<PrivacyJurisdiction> parseStrict(String raw) {
    if (raw == null || raw.isBlank()) {
      return java.util.Optional.of(GENERAL);
    }
    try {
      return java.util.Optional.of(PrivacyJurisdiction.valueOf(raw.trim().toUpperCase()));
    } catch (IllegalArgumentException ex) {
      return java.util.Optional.empty();
    }
  }
}
