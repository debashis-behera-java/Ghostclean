package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.privacy.DeletionMethod;
import com.ghostclean.privacy.DeletionMethodResolver;
import com.ghostclean.privacy.DeletionRequestService;
import com.ghostclean.privacy.PrivacyJurisdiction;
import com.ghostclean.privacy.PrivacyRequestType;
import com.ghostclean.privacy.RequestTemplates;
import com.ghostclean.privacy.RequestTemplates.RenderedTemplate;
import com.ghostclean.services.ServiceDirectory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 engine unit tests (no Spring, no network). Resolver rules,
 * template determinism/conservatism, warnings, and supported-type gating
 * on synthetic {@code .test} fixtures — no real service data implied.
 */
class DeletionRequestUnitTests {

  private static ServiceDirectory entry(String routeType, String verification) {
    ServiceDirectory row = new ServiceDirectory("unit-" + routeType.toLowerCase(), "Unit");
    row.setDomains(new String[]{"unit.test"});
    row.setRouteType(routeType);
    row.setRequestMethod("EMAIL");
    row.setAutomationStatus("MANUAL_ONLY");
    row.setRouteConfidence("HIGH");
    row.setRouteAvailability("KNOWN");
    row.setVerificationStatus(verification);
    row.setPrivacyEmail("privacy@unit.test");
    row.setStatus("active");
    return row;
  }

  // -- resolver ------------------------------------------------------------------------

  @Test
  void emailRouteResolvesToEmail() {
    assertEquals(DeletionMethod.EMAIL,
        DeletionMethodResolver.resolve(entry("EMAIL_REQUEST", "VERIFIED")));
  }

  @Test
  void privacyPortalRemainsPortal() {
    assertEquals(DeletionMethod.PRIVACY_PORTAL,
        DeletionMethodResolver.resolve(entry("PRIVACY_PORTAL", "VERIFIED")));
  }

  @Test
  void webFormRemainsWebForm() {
    assertEquals(DeletionMethod.WEB_FORM,
        DeletionMethodResolver.resolve(entry("PRIVACY_REQUEST_FORM", "VERIFIED")));
  }

  @Test
  void accountSettingsRemainsAccountSettings() {
    assertEquals(DeletionMethod.ACCOUNT_SETTINGS,
        DeletionMethodResolver.resolve(entry("ACCOUNT_SETTINGS", "VERIFIED")));
  }

  @Test
  void unsupportedMethodRemainsManual() {
    assertEquals(DeletionMethod.UNKNOWN,
        DeletionMethodResolver.resolve(entry("SELF_SERVICE_ACCOUNT_DELETE", "VERIFIED")));
    assertEquals(DeletionMethod.UNKNOWN,
        DeletionMethodResolver.resolve(entry("HELP_CENTER", "VERIFIED")));
    assertEquals(DeletionMethod.UNKNOWN,
        DeletionMethodResolver.resolve(entry("UNKNOWN", "VERIFIED")));
  }

  @Test
  void unknownServiceRemainsUnknown() {
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethodResolver.resolve(null));
    ServiceDirectory inactive = entry("EMAIL_REQUEST", "VERIFIED");
    inactive.setStatus("deprecated");
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethodResolver.resolve(inactive));
    assertEquals(DeletionMethod.UNKNOWN,
        DeletionMethodResolver.resolve(entry("EMAIL_REQUEST", "UNVERIFIED")));
    ServiceDirectory rejected = entry("EMAIL_REQUEST", "REJECTED");
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethodResolver.resolve(rejected));
    ServiceDirectory unlisted = entry("EMAIL_REQUEST", "VERIFIED");
    unlisted.setRouteAvailability("UNKNOWN");
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethodResolver.resolve(unlisted));
  }

  @Test
  void onlyEmailAvoidsManualHandling() {
    assertFalse(DeletionMethodResolver.requiresManualHandling(DeletionMethod.EMAIL));
    for (DeletionMethod method : new DeletionMethod[]{DeletionMethod.PRIVACY_PORTAL,
        DeletionMethod.ACCOUNT_SETTINGS, DeletionMethod.WEB_FORM,
        DeletionMethod.MANUAL, DeletionMethod.UNKNOWN}) {
      assertTrue(DeletionMethodResolver.requiresManualHandling(method));
    }
  }

  @Test
  void deletionMethodParsingIsLenient() {
    assertEquals(DeletionMethod.EMAIL, DeletionMethod.parse("email"));
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethod.parse("telepathy"));
    assertEquals(DeletionMethod.UNKNOWN, DeletionMethod.parse(null));
  }

  // -- supported types --------------------------------------------------------------------

  @Test
  void deleteAccountOnlyWhenSupported() {
    List<String> trusted = DeletionRequestService.supportedTypes(
        entry("EMAIL_REQUEST", "VERIFIED"));
    assertTrue(trusted.contains(PrivacyRequestType.DELETE_ACCOUNT.name()));
    List<String> untrusted = DeletionRequestService.supportedTypes(
        entry("EMAIL_REQUEST", "UNVERIFIED"));
    assertFalse(untrusted.contains(PrivacyRequestType.DELETE_ACCOUNT.name()));
    assertTrue(untrusted.contains(PrivacyRequestType.DELETE_PERSONAL_DATA.name()));
    assertTrue(untrusted.contains(PrivacyRequestType.ACCESS_PERSONAL_DATA.name()));
    assertTrue(untrusted.contains(PrivacyRequestType.GENERAL_PRIVACY_REQUEST.name()));
    List<String> unknown = DeletionRequestService.supportedTypes(null);
    assertFalse(unknown.contains(PrivacyRequestType.DELETE_ACCOUNT.name()));
  }

  // -- templates -------------------------------------------------------------------------------

  @Test
  void templateSelectionDeterministic() {
    RenderedTemplate first = RequestTemplates.render(
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GDPR, "Spotify", 3);
    RenderedTemplate second = RequestTemplates.render(
        PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GDPR, "Spotify", 3);
    assertEquals(first.subject(), second.subject());
    assertEquals(first.body(), second.body());
  }

  @Test
  void templateVersionRecorded() {
    assertEquals("privacy-request-v1", RequestTemplates.CURRENT_TEMPLATE_ID);
    assertEquals(1, RequestTemplates.CURRENT_TEMPLATE_VERSION);
  }

  @Test
  void allTypesAndJurisdictionsRenderSafely() {
    for (PrivacyRequestType type : PrivacyRequestType.values()) {
      for (PrivacyJurisdiction jurisdiction : PrivacyJurisdiction.values()) {
        RenderedTemplate rendered = RequestTemplates.render(type, jurisdiction, "Spotify", 2);
        assertFalse(rendered.subject().isBlank());
        assertFalse(rendered.body().isBlank());
        assertTrue(rendered.subject().length() <= 200);
        assertTrue(rendered.body().length() <= 8000);
        assertFalse(rendered.body().contains("{{"), "unresolved variable");
        assertFalse(rendered.body().contains("legally required"));
        assertFalse(rendered.body().contains("guarantee"));
        assertFalse(rendered.body().contains("deadline"));
        assertFalse(rendered.body().contains("immediately"));
      }
    }
  }

  @Test
  void noIndividualizedLegalConclusion() {
    RenderedTemplate rendered = RequestTemplates.render(
        PrivacyRequestType.DELETE_ACCOUNT, PrivacyJurisdiction.GDPR, "Spotify", 1);
    String all = rendered.subject() + "|" + rendered.body();
    assertFalse(all.contains("You definitely"));
    assertFalse(all.contains("legal right"));
    assertFalse(all.contains("must comply"));
    assertTrue(all.contains("applicable"));
  }

  @Test
  void variablesResolveAsPlainText() {
    assertEquals("Hi Spotify!",
        RequestTemplates.substitute("Hi {{service_name}}!",
            Map.of("service_name", "Spotify")));
    // Unlisted variables survive verbatim — no expression engine exists.
    assertEquals("Hi {{system.exit(1)}}!",
        RequestTemplates.substitute("Hi {{system.exit(1)}}!", Map.of()));
    assertEquals("Hi !",
        RequestTemplates.substitute("Hi {{display_name}}!", Map.of("display_name", "")));
  }

  @Test
  void unknownTemplateRejected() {
    try {
      RequestTemplates.render("other-template", 9,
          PrivacyRequestType.DELETE_PERSONAL_DATA, PrivacyJurisdiction.GENERAL, "S", 0);
      assertTrue(false, "unknown template must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Unknown template"));
    }
  }

  // -- warnings -------------------------------------------------------------------------------------

  @Test
  void emailHasNoManualWarnings() {
    List<String> warnings = DeletionRequestService.warnings(DeletionMethod.EMAIL);
    assertEquals(2, warnings.size());
    assertTrue(warnings.toString().contains("does not guarantee deletion"));
  }

  @Test
  void unknownMethodWarnsLoudly() {
    List<String> warnings = DeletionRequestService.warnings(DeletionMethod.UNKNOWN);
    assertEquals(5, warnings.size());
    assertTrue(warnings.toString().contains("method is unknown"));
    assertTrue(warnings.toString().contains("Manual review is required"));
  }
}
