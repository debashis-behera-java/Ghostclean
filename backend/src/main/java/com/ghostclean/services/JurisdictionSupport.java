package com.ghostclean.services;

/**
 * Jurisdictional support metadata (Phase 5). These are coarse catalog tags
 * about which privacy regimes a service's route generally relates to —
 * never an individualized legal determination and never legal advice.
 */
public enum JurisdictionSupport {
  GDPR_RELEVANT,
  CCPA_CPRA_RELEVANT,
  GENERAL_PRIVACY_REQUEST,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static JurisdictionSupport parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return JurisdictionSupport.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
