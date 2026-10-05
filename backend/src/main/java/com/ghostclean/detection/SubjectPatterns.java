package com.ghostclean.detection;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subject-signal keyword bundles (Phase 4: English only). Kept small and
 * curated by design; locale bundles plug into {@link #BY_LOCALE} later
 * without touching the engine. Matching is case-insensitive substring on the
 * normalized subject. The engine never asserts an account from a mention
 * alone; weights do that work in {@link DetectionEngine}.
 */
public final class SubjectPatterns {

  private SubjectPatterns() {
  }

  /** Registration confirmations — strongest subject signal. */
  public static final List<String> REGISTRATION = List.of(
      "welcome", "thanks for joining", "thanks for signing up",
      "confirm your account", "account created", "registration complete",
      "get started", "activate your account", "welcome to");

  /** Explicit verification requests. */
  public static final List<String> VERIFICATION = List.of(
      "verify your", "verification code", "confirm your email",
      "confirm your email address", "verify your email");

  /** Security notices — corroborating only, never sufficient alone. */
  public static final List<String> SECURITY = List.of(
      "password reset", "reset your password", "new sign-in",
      "security alert", "unusual activity");

  /** Commercial relationship hints (design: receipt|invoice|subscription|trial ending|order). */
  public static final List<String> COMMERCIAL = List.of(
      "receipt", "invoice", "subscription", "trial", "order", "order confirmation",
      "billing", "payment receipt");

  /** Bulk/marketing markers that discount generic mentions. */
  public static final List<String> NEWSLETTER = List.of(
      "newsletter", "unsubscribe", "marketing", "bulk", "promo",
      "special offer", "deals", "sale ends");

  public static final Map<String, LocaleBundle> BY_LOCALE = Map.of("en", new LocaleBundle(
      REGISTRATION, VERIFICATION, SECURITY, COMMERCIAL, NEWSLETTER));

  public static String normalize(String subject) {
    return subject == null ? "" : subject.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean matchesAny(String normalizedSubject, List<String> keywords) {
    for (String keyword : keywords) {
      if (!keyword.isEmpty() && normalizedSubject.contains(keyword)) {
        return true;
      }
    }
    return false;
  }

  public record LocaleBundle(
      List<String> registration,
      List<String> verification,
      List<String> security,
      List<String> commercial,
      List<String> newsletter) {
  }
}
