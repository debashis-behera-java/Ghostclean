package com.ghostclean.detection;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic rule-based scorer (Phase 4). Pure function of the normalized
 * message plus an optional directory hit: no I/O, no randomness, no ML —
 * identical inputs always produce identical scores. Bands are public labels,
 * not certainties; WEAK evidence never becomes a confirmed account because
 * Phase 4 has no "confirmed" state at all.
 *
 * <p>Weights (see {@code docs/SERVICE_DETECTION_DESIGN.md}):
 * domain exact +30 / subdomain +18, sender-alias exact +20, known service
 * +10, registration subject +25, verification +20, security +12, commercial
 * +10, newsletter-only −20, list-header-only −10, security-only −15.
 */
@Component
public class DetectionEngine {

  public static final int DOMAIN_EXACT = 30;
  public static final int DOMAIN_SUBDOMAIN = 18;
  public static final int SENDER_ALIAS_EXACT = 20;
  public static final int KNOWN_SERVICE = 10;
  public static final int SUBJECT_REGISTRATION = 25;
  public static final int SUBJECT_VERIFICATION = 20;
  public static final int SUBJECT_SECURITY = 12;
  public static final int SUBJECT_COMMERCIAL = 10;
  public static final int NEWSLETTER_PENALTY = -20;
  public static final int LIST_HEADER_PENALTY = -10;
  public static final int SECURITY_ONLY_PENALTY = -15;

  /**
   * Scores one normalized message. Unknown senders (no directory hit) are
   * scored on subject signals alone, so generic mentions stay LOW.
   */
  public ScoredEvidence score(NormalizedMessage message, DirectoryHit hit) {
    int score = 0;
    List<String> rules = new ArrayList<>();
    String subject = SubjectPatterns.normalize(message.subject());
    SubjectPatterns.LocaleBundle bundle = SubjectPatterns.BY_LOCALE.get("en");

    boolean domainExact = false;
    boolean domainSubdomain = false;
    boolean aliasExact = false;
    if (hit != null) {
      domainExact = hit.matchesDomain(message.senderDomain());
      domainSubdomain = !domainExact && hit.matchesSubdomain(message.senderDomain());
      aliasExact = hit.matchesSender(message.senderAddress());
      if (domainExact) {
        score += DOMAIN_EXACT;
        rules.add("SENDER_DOMAIN_EXACT");
      } else if (domainSubdomain) {
        score += DOMAIN_SUBDOMAIN;
        rules.add("SENDER_DOMAIN_SUBDOMAIN");
      }
      if (aliasExact) {
        score += SENDER_ALIAS_EXACT;
        rules.add("SENDER_ADDRESS_ALIAS");
      }
      score += KNOWN_SERVICE;
      rules.add("KNOWN_SERVICE");
    }

    boolean registration = SubjectPatterns.matchesAny(subject, bundle.registration());
    boolean verification = SubjectPatterns.matchesAny(subject, bundle.verification());
    boolean security = SubjectPatterns.matchesAny(subject, bundle.security());
    boolean commercial = SubjectPatterns.matchesAny(subject, bundle.commercial());
    boolean newsletter = SubjectPatterns.matchesAny(subject, bundle.newsletter());
    if (registration) {
      score += SUBJECT_REGISTRATION;
      rules.add("SUBJECT_REGISTRATION");
    }
    if (verification) {
      score += SUBJECT_VERIFICATION;
      rules.add("SUBJECT_VERIFICATION");
    }
    if (security) {
      score += SUBJECT_SECURITY;
      rules.add("SUBJECT_SECURITY");
    }
    if (commercial) {
      score += SUBJECT_COMMERCIAL;
      rules.add("SUBJECT_COMMERCIAL");
    }

    boolean positiveSignal = domainExact || domainSubdomain || aliasExact
        || registration || verification || commercial;
    if (newsletter && !registration && !verification) {
      score += NEWSLETTER_PENALTY;
      rules.add("NEWSLETTER_DISCOUNT");
    }
    if (message.listUnsubscribePresent() && !registration && !verification) {
      score += LIST_HEADER_PENALTY;
      rules.add("LIST_HEADER_DISCOUNT");
    }
    if (security && !positiveSignal) {
      // A lone security notice (e.g. an isolated reset mail) corroborates
      // nothing on its own — documented interpretation of the design's
      // "security-only" floor.
      score += SECURITY_ONLY_PENALTY;
      rules.add("SECURITY_ONLY_DISCOUNT");
    }

    score = Math.max(0, Math.min(100, score));
    return new ScoredEvidence(score, ConfidenceBand.of(score), List.copyOf(rules));
  }

  /** Public confidence label. Bands, not certainties. */
  public enum ConfidenceBand {
    LOW, MEDIUM, HIGH;

    public static ConfidenceBand of(int score) {
      if (score >= 70) {
        return HIGH;
      }
      if (score >= 40) {
        return MEDIUM;
      }
      return LOW;
    }
  }

  /** Sanitized detection input: domain, address, truncated subject, date. */
  public record NormalizedMessage(
      String senderDomain,
      String senderAddress,
      String subject,
      long receivedAtMillis,
      boolean listUnsubscribePresent) {
  }

  public record ScoredEvidence(int score, ConfidenceBand band, List<String> matchedRules) {
  }
}
