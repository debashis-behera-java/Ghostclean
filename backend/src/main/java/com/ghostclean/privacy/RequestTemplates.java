package com.ghostclean.privacy;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned request-template registry (Phase 8). Templates are code-held,
 * deterministic string skeletons — no AI, no expression engine, no
 * network. Only allow-listed {@code {{variables}}} are substituted and
 * every value is treated as plain text; anything unlisted survives
 * verbatim, so injection cannot execute. Rendered output passes the same
 * content validation as hand-edited text.
 *
 * <p>Requests record {@code template_id + template_version} at generation,
 * so later template changes can never silently mutate stored content.
 */
public final class RequestTemplates {

  public static final String CURRENT_TEMPLATE_ID = "privacy-request-v1";
  public static final int CURRENT_TEMPLATE_VERSION = 1;

  /** Approved variables. {@code display_name}/{@code account_identifier} resolve empty in Phase 8. */
  static final Set<String> ALLOWED_VARIABLES = Set.of(
      "service_name", "display_name", "account_identifier",
      "request_type", "jurisdiction", "evidence_count");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z_]+)\\}\\}");

  private RequestTemplates() {
  }

  public record RenderedTemplate(String subject, String body) {
  }

  /** Renders the current template for the given structured inputs. */
  public static RenderedTemplate render(
      PrivacyRequestType type, PrivacyJurisdiction jurisdiction,
      String serviceName, int evidenceCount) {
    return render(CURRENT_TEMPLATE_ID, CURRENT_TEMPLATE_VERSION,
        type, jurisdiction, serviceName, evidenceCount);
  }

  /** Renders a pinned template version (stored requests always keep theirs). */
  public static RenderedTemplate render(
      String templateId, int templateVersion,
      PrivacyRequestType type, PrivacyJurisdiction jurisdiction,
      String serviceName, int evidenceCount) {
    if (!CURRENT_TEMPLATE_ID.equals(templateId) || templateVersion != CURRENT_TEMPLATE_VERSION) {
      throw new IllegalArgumentException("Unknown template: " + templateId + " v" + templateVersion);
    }
    Map<String, String> variables = Map.of(
        "service_name", serviceName == null || serviceName.isBlank() ? "your service" : serviceName,
        "display_name", "",
        "account_identifier", "",
        "request_type", humanType(type),
        "jurisdiction", jurisdiction.name(),
        "evidence_count", String.valueOf(Math.max(0, evidenceCount)));
    String subject = substitute(subjectSkeleton(type), variables);
    String body = substitute(bodySkeleton(type, jurisdiction, evidenceCount), variables);
    return new RenderedTemplate(subject, body);
  }

  private static String subjectSkeleton(PrivacyRequestType type) {
    return switch (type) {
      case DELETE_ACCOUNT -> "Privacy Request — Account Deletion";
      case DELETE_PERSONAL_DATA -> "Privacy Request — Deletion of Personal Data";
      case ACCESS_PERSONAL_DATA -> "Privacy Request — Access to Personal Data";
      default -> "Privacy Request";
    };
  }

  private static String bodySkeleton(
      PrivacyRequestType type, PrivacyJurisdiction jurisdiction, int evidenceCount) {
    StringBuilder skeleton = new StringBuilder();
    skeleton.append("Hello,\n\n");
    skeleton.append("I am requesting ").append(intentFragment(type))
        .append(" with {{service_name}}.\n\n");
    if (evidenceCount > 0) {
      skeleton.append("This request relates to account evidence I found in my own "
          + "mailbox ({{evidence_count}} item(s)).\n\n");
    }
    skeleton.append(PrivacyRequestGenerator.jurisdictionParagraph(jurisdiction)).append("\n\n");
    skeleton.append("Please process this request through your applicable privacy/data "
        + "protection process.\n\n");
    skeleton.append("If additional verification is required, please provide the required "
        + "steps through an appropriate secure channel.\n\n");
    skeleton.append("Thank you.");
    return skeleton.toString();
  }

  private static String intentFragment(PrivacyRequestType type) {
    return switch (type) {
      case DELETE_ACCOUNT -> "deletion of my account and the personal data associated with it";
      case DELETE_PERSONAL_DATA -> "deletion of personal data associated with my account";
      case ACCESS_PERSONAL_DATA ->
          "provision of a copy of personal data associated with my account";
      default -> "a privacy request regarding personal data associated with my account";
    };
  }

  private static String humanType(PrivacyRequestType type) {
    return switch (type) {
      case DELETE_ACCOUNT -> "account deletion";
      case DELETE_PERSONAL_DATA -> "deletion of personal data";
      case ACCESS_PERSONAL_DATA -> "access to personal data";
      default -> "privacy request";
    };
  }

  /** Plain-text substitution of allow-listed variables only. */
  public static String substitute(String skeleton, Map<String, String> variables) {
    Matcher matcher = PLACEHOLDER.matcher(skeleton);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = ALLOWED_VARIABLES.contains(name)
          ? variables.getOrDefault(name, "") : matcher.group(0);
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }
}
