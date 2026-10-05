package com.ghostclean.privacy;

/**
 * Deterministic privacy-request text generator (Phase 6). Pure function of
 * structured inputs — no AI, no network, no mailbox content. Uses minimal
 * evidence (service name, category, evidence count) and conservative,
 * non-absolute legal language: requests are made "under applicable
 * privacy/data protection law" with no deadlines, no guarantees, and no
 * individualized legal conclusions.
 */
public final class PrivacyRequestGenerator {

  /** Hard bounds mirror the V9 CHECK constraint; edits are validated too. */
  public static final int MAX_SUBJECT = 200;
  public static final int MAX_BODY = 8000;

  private PrivacyRequestGenerator() {
  }

  public record GeneratedContent(String subject, String body) {
  }

  /**
   * Resolves the stored request type: DELETE_ACCOUNT is kept only with a
   * trusted route; otherwise it coerces to GENERAL_PRIVACY_REQUEST rather
   * than claiming unsupported support.
   */
  public static PrivacyRequestType resolveType(
      PrivacyRequestType requested, boolean trustedRoute) {
    if (requested == PrivacyRequestType.DELETE_ACCOUNT && !trustedRoute) {
      return PrivacyRequestType.GENERAL_PRIVACY_REQUEST;
    }
    return requested;
  }

  /** Renders subject + body for the resolved type and jurisdiction. */
  public static GeneratedContent generate(
      String serviceName, String category, int evidenceCount,
      PrivacyRequestType type, PrivacyJurisdiction jurisdiction) {
    String service = serviceName == null || serviceName.isBlank() ? "your service" : serviceName;
    String subject = switch (type) {
      case DELETE_ACCOUNT -> "Privacy Request — Account Deletion";
      case DELETE_PERSONAL_DATA -> "Privacy Request — Deletion of Personal Data";
      case ACCESS_PERSONAL_DATA -> "Privacy Request — Access to Personal Data";
      default -> "Privacy Request";
    };
    String intent = switch (type) {
      case DELETE_ACCOUNT -> "deletion of my account and the personal data associated with it";
      case DELETE_PERSONAL_DATA -> "deletion of personal data associated with my account";
      case ACCESS_PERSONAL_DATA ->
          "provision of a copy of personal data associated with my account";
      default -> "a privacy request regarding personal data associated with my account";
    };
    StringBuilder body = new StringBuilder();
    body.append("Hello,\n\n");
    body.append("I am requesting ").append(intent).append(" with ").append(service).append(".\n\n");
    if (evidenceCount > 0) {
      body.append("This request relates to account evidence I found in my own mailbox (")
          .append(evidenceCount).append(" item")
          .append(evidenceCount == 1 ? "" : "s").append(").\n\n");
    }
    body.append(jurisdictionParagraph(jurisdiction)).append("\n\n");
    body.append("Please process this request through your applicable privacy/data "
        + "protection process.\n\n");
    body.append("If additional verification is required, please provide the required "
        + "steps through an appropriate secure channel.\n\n");
    body.append("Thank you.");
    return new GeneratedContent(subject, body.toString());
  }

  static String jurisdictionParagraph(PrivacyJurisdiction jurisdiction) {
    return switch (jurisdiction) {
      case GDPR -> "I am making this request in connection with data protection law "
          + "that may apply, such as the General Data Protection Regulation (EU) "
          + "2016/679, to the extent applicable. I understand that exceptions may apply.";
      case CCPA_CPRA -> "I am making this request in connection with privacy law that "
          + "may apply, such as the California Consumer Privacy Act as amended, to "
          + "the extent applicable. I understand that exceptions may apply.";
      default -> "I am requesting deletion of personal data under applicable "
          + "privacy/data protection law.";
    };
  }
}
