package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.detection.DetectionEngine;
import com.ghostclean.services.ServiceDirectory;
import org.junit.jupiter.api.Test;

/**
 * Deterministic scoring matrix (no Spring, no I/O): identical inputs always
 * produce identical scores; bands follow the documented weights.
 */
class DetectionEngineTests {

  private final DetectionEngine engine = new DetectionEngine();

  private ServiceDirectory spotify() {
    ServiceDirectory service = new ServiceDirectory("spotify", "Spotify");
    service.setDomains(new String[]{"spotify.com"});
    service.setAliases(new String[]{"no-reply@spotify.com"});
    service.setCategory("media");
    return service;
  }

  private DetectionEngine.NormalizedMessage message(String domain, String from, String subject) {
    return new DetectionEngine.NormalizedMessage(domain, from, subject,
        System.currentTimeMillis(), false);
  }

  @Test
  void strongDirectoryWelcomeScoresHigh() {
    DetectionEngine.ScoredEvidence scored = engine.score(
        message("spotify.com", "welcome@spotify.com", "Welcome to Spotify — verify your email"),
        spotify());
    // 30 domain + 10 known + 25 registration + 20 verification = 85.
    assertEquals(85, scored.score());
    assertEquals(DetectionEngine.ConfidenceBand.HIGH, scored.band());
    assertTrue(scored.matchedRules().containsAll(
        java.util.List.of("SENDER_DOMAIN_EXACT", "KNOWN_SERVICE", "SUBJECT_REGISTRATION",
            "SUBJECT_VERIFICATION")));
  }

  @Test
  void commercialMailFromKnownServiceScoresMedium() {
    DetectionEngine.ScoredEvidence scored = engine.score(
        message("github.com", "billing@github.com", "Your receipt"), directory("github",
            "GitHub", new String[]{"github.com"}, new String[]{"noreply@github.com"}));
    // 30 + 10 + 10 = 50.
    assertEquals(50, scored.score());
    assertEquals(DetectionEngine.ConfidenceBand.MEDIUM, scored.band());
  }

  @Test
  void subdomainScoresBelowExact() {
    ServiceDirectory amazon = directory("amazon", "Amazon",
        new String[]{"amazon.co.uk"}, new String[0]);
    DetectionEngine.ScoredEvidence scored = engine.score(
        message("mail.amazon.co.uk", "support@mail.amazon.co.uk", "Password reset request"),
        amazon);
    // 18 subdomain + 10 known + 12 security = 40 → MEDIUM floor.
    assertEquals(40, scored.score());
    assertEquals(DetectionEngine.ConfidenceBand.MEDIUM, scored.band());
  }

  @Test
  void unknownDomainGenericMentionStaysLow() {
    DetectionEngine.ScoredEvidence scored = engine.score(
        message("unknown-shop.example", "shop@unknown-shop.example", "Your order update"),
        null);
    assertEquals(10, scored.score());
    assertEquals(DetectionEngine.ConfidenceBand.LOW, scored.band());
  }

  @Test
  void newsletterMentionIsDiscountedToZero() {
    DetectionEngine.ScoredEvidence scored = engine.score(
        new DetectionEngine.NormalizedMessage("random-bulk.example",
            "newsletter@random-bulk.example", "Spotify playlist deals inside",
            System.currentTimeMillis(), true),
        null);
    assertEquals(0, scored.score());
    assertEquals(DetectionEngine.ConfidenceBand.LOW, scored.band());
    assertTrue(scored.matchedRules().contains("NEWSLETTER_DISCOUNT"));
    assertTrue(scored.matchedRules().contains("LIST_HEADER_DISCOUNT"));
  }

  @Test
  void loneSecurityNoticeCannotStandAlone() {
    DetectionEngine.ScoredEvidence scored = engine.score(
        message("unknown-bank.example", "security@unknown-bank.example",
            "Password reset request"),
        null);
    // 12 security − 15 security-only floor → clamped 0.
    assertEquals(0, scored.score());
    assertTrue(scored.matchedRules().contains("SECURITY_ONLY_DISCOUNT"));
  }

  @Test
  void scoringIsDeterministic() {
    DetectionEngine.NormalizedMessage message =
        message("spotify.com", "welcome@spotify.com", "Welcome to Spotify");
    ServiceDirectory hit = spotify();
    DetectionEngine.ScoredEvidence first = engine.score(message, hit);
    for (int i = 0; i < 25; i++) {
      assertEquals(first, engine.score(message, hit));
    }
  }

  @Test
  void confidenceBandsFollowDocumentedThresholds() {
    assertEquals(DetectionEngine.ConfidenceBand.LOW, DetectionEngine.ConfidenceBand.of(0));
    assertEquals(DetectionEngine.ConfidenceBand.LOW, DetectionEngine.ConfidenceBand.of(39));
    assertEquals(DetectionEngine.ConfidenceBand.MEDIUM, DetectionEngine.ConfidenceBand.of(40));
    assertEquals(DetectionEngine.ConfidenceBand.MEDIUM, DetectionEngine.ConfidenceBand.of(69));
    assertEquals(DetectionEngine.ConfidenceBand.HIGH, DetectionEngine.ConfidenceBand.of(70));
    assertEquals(DetectionEngine.ConfidenceBand.HIGH, DetectionEngine.ConfidenceBand.of(100));
  }

  private ServiceDirectory directory(String slug, String name, String[] domains,
      String[] aliases) {
    ServiceDirectory service = new ServiceDirectory(slug, name);
    service.setDomains(domains);
    service.setAliases(aliases);
    return service;
  }
}
