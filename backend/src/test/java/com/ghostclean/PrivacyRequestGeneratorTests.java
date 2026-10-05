package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.privacy.PrivacyJurisdiction;
import com.ghostclean.privacy.PrivacyRequestGenerator;
import com.ghostclean.privacy.PrivacyRequestGenerator.GeneratedContent;
import com.ghostclean.privacy.PrivacyRequestType;
import org.junit.jupiter.api.Test;

/**
 * Deterministic generator tests (no Spring, no network). Asserts structure,
 * conservative legal language, minimal evidence, and type coercion.
 */
class PrivacyRequestGeneratorTests {

  @Test
  void generationIsDeterministic() {
    GeneratedContent first = PrivacyRequestGenerator.generate(
        "Spotify", "media", 3,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GENERAL);
    GeneratedContent second = PrivacyRequestGenerator.generate(
        "Spotify", "media", 3,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GENERAL);
    assertEquals(first.subject(), second.subject());
    assertEquals(first.body(), second.body());
  }

  @Test
  void subjectVariesByType() {
    assertTrue(PrivacyRequestGenerator.generate("S", "c", 1,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GENERAL)
        .subject().contains("Deletion of Personal Data"));
    assertTrue(PrivacyRequestGenerator.generate("S", "c", 1,
        PrivacyRequestType.DELETE_ACCOUNT, PrivacyJurisdiction.GENERAL)
        .subject().contains("Account Deletion"));
    assertEquals("Privacy Request", PrivacyRequestGenerator.generate("S", "c", 1,
        PrivacyRequestType.GENERAL_PRIVACY_REQUEST, PrivacyJurisdiction.GENERAL).subject());
  }

  @Test
  void bodyUsesMinimalEvidenceOnly() {
    GeneratedContent content = PrivacyRequestGenerator.generate(
        "Spotify", "media", 2,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GENERAL);
    assertTrue(content.body().contains("Spotify"));
    assertTrue(content.body().contains("2 items"));
    assertFalse(content.body().contains("Welcome"),
        "no email subjects in generated text");
    assertFalse(content.body().contains("acc-"), "no tokens in generated text");
    assertFalse(content.body().contains("ref-"), "no tokens in generated text");
  }

  @Test
  void legalLanguageStaysConservative() {
    for (PrivacyJurisdiction jurisdiction : PrivacyJurisdiction.values()) {
      String body = PrivacyRequestGenerator.generate("S", "c", 1,
          PrivacyRequestType.DELETE_PERSONAL_DATA, jurisdiction).body();
      assertFalse(body.contains("legally required"), "absolute claim: " + jurisdiction);
      assertFalse(body.contains("guarantee"), "absolute claim: " + jurisdiction);
      assertFalse(body.contains("immediately"), "deadline pressure: " + jurisdiction);
      assertFalse(body.contains("deadline"), "invented deadline: " + jurisdiction);
      assertFalse(body.contains("You definitely"), "certainty: " + jurisdiction);
      assertTrue(body.contains("applicable"), "must reference applicability");
    }
    String gdpr = PrivacyRequestGenerator.generate("S", "c", 1,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GDPR).body();
    assertTrue(gdpr.contains("exceptions may apply"));
    assertTrue(gdpr.contains("2016/679"));
  }

  @Test
  void deleteAccountRequiresTrustedRoute() {
    assertEquals(PrivacyRequestType.DELETE_ACCOUNT,
        PrivacyRequestGenerator.resolveType(PrivacyRequestType.DELETE_ACCOUNT, true));
    assertEquals(PrivacyRequestType.GENERAL_PRIVACY_REQUEST,
        PrivacyRequestGenerator.resolveType(PrivacyRequestType.DELETE_ACCOUNT, false));
    assertEquals(PrivacyRequestType.DELETE_PERSONAL_DATA,
        PrivacyRequestGenerator.resolveType(PrivacyRequestType.DELETE_PERSONAL_DATA, false));
    assertEquals(PrivacyRequestType.UNKNOWN,
        PrivacyRequestGenerator.resolveType(PrivacyRequestType.UNKNOWN, true));
  }

  @Test
  void strictParsingRejectsGarbage() {
    assertTrue(PrivacyRequestType.parseStrict("delete_account").isPresent());
    assertTrue(PrivacyRequestType.parseStrict(null).isPresent());
    assertTrue(PrivacyRequestType.parseStrict("erase_everything").isEmpty());
    assertTrue(PrivacyJurisdiction.parseStrict("gdpr").isPresent());
    assertTrue(PrivacyJurisdiction.parseStrict("mars").isEmpty());
    assertEquals(PrivacyRequestType.UNKNOWN, PrivacyRequestType.parseLenient("bogus"));
  }

  @Test
  void contentRespectsBounds() {
    GeneratedContent content = PrivacyRequestGenerator.generate(
        "Spotify", "media", 100,
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.CCPA_CPRA);
    assertTrue(content.subject().length() <= PrivacyRequestGenerator.MAX_SUBJECT);
    assertTrue(content.body().length() <= PrivacyRequestGenerator.MAX_BODY);
  }
}
