package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.services.AutomationStatus;
import com.ghostclean.services.JurisdictionSupport;
import com.ghostclean.services.RequestMethod;
import com.ghostclean.services.RouteAvailability;
import com.ghostclean.services.RouteConfidence;
import com.ghostclean.services.RouteSourceType;
import com.ghostclean.services.RouteType;
import com.ghostclean.services.RouteVerificationStatus;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryRepository;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceDetailDto;
import com.ghostclean.services.ServiceRegistry;
import com.ghostclean.services.ServiceRegistry.Enrichment;
import com.ghostclean.services.ServiceRegistry.MatchKind;
import com.ghostclean.services.ServiceRegistry.ServiceMatch;
import com.ghostclean.services.ServiceUrls;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 registry unit tests (no Spring, no network, no real services).
 * All fixtures use {@code .example.test} names (RFC 2606 test-only) so no
 * real deletion URL, portal, or ownership is ever implied.
 */
class ServiceRegistryTests {

  private InMemoryDirectory repository;
  private ServiceRegistry registry;

  /**
   * Dependency-free in-memory stub: only the two registry read paths are
   * implemented; every other repository operation is unsupported by design.
   */
  static final class InMemoryDirectory implements InvocationHandler {
    List<ServiceDirectory> active = List.of();

    ServiceDirectoryRepository proxy() {
      return (ServiceDirectoryRepository) Proxy.newProxyInstance(
          ServiceDirectoryRepository.class.getClassLoader(),
          new Class<?>[]{ServiceDirectoryRepository.class}, this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      return switch (method.getName()) {
        case "findByStatus" -> active;
        case "findFirstBySlugIgnoreCase" -> active.stream()
            .filter(entry -> entry.getSlug() != null
                && entry.getSlug().equalsIgnoreCase(String.valueOf(args[0])))
            .findFirst();
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
  }

  @BeforeEach
  void setUp() {
    repository = new InMemoryDirectory();
    registry = new ServiceRegistry(repository.proxy());
  }

  private static ServiceDirectory verifiedPortal() {
    ServiceDirectory entry = new ServiceDirectory("acme-portal", "Acme Portal");
    entry.setDomains(new String[]{"acmeportal.test"});
    entry.setAliases(new String[]{"no-reply@acmeportal.test"});
    entry.setCategory("shopping");
    entry.setRouteType("PRIVACY_PORTAL");
    entry.setRequestMethod("PRIVACY_PORTAL");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://privacy.acmeportal.test/request");
    entry.setSourceType("OFFICIAL");
    entry.setVerificationStatus("VERIFIED");
    entry.setJurisdiction(new String[]{"GDPR_RELEVANT", "CCPA_CPRA_RELEVANT"});
    entry.setManualReviewRequired(false);
    entry.setPrivacyUrl("https://privacy.acmeportal.test/policy");
    entry.setStatus("active");
    return entry;
  }

  private static ServiceDirectory unverifiedWithUrl() {
    ServiceDirectory entry = new ServiceDirectory("shady-claim", "Shady Claim");
    entry.setDomains(new String[]{"shady.test"});
    entry.setCategory("other");
    entry.setRouteType("PRIVACY_PORTAL");
    entry.setRequestMethod("WEB_FORM");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://privacy.shady.test/go");
    entry.setSourceType("THIRD_PARTY");
    entry.setVerificationStatus("UNVERIFIED");
    entry.setStatus("active");
    return entry;
  }

  // -- service definition -------------------------------------------------------

  @Test
  void serviceDefinitionLoadsCorrectly() {
    repository.active = List.of(verifiedPortal());

    ServiceDetailDto detail = registry.describe(verifiedPortal());

    assertEquals("acme-portal", detail.serviceId());
    assertEquals("Acme Portal", detail.displayName());
    assertEquals("shopping", detail.category());
    assertEquals(List.of("acmeportal.test"), detail.domains());
    assertEquals("PRIVACY_PORTAL", detail.routeType());
    assertEquals("PRIVACY_PORTAL", detail.requestMethod());
    assertEquals("MANUAL_ONLY", detail.automationStatus());
    assertEquals("HIGH", detail.routeConfidence());
    assertEquals("KNOWN", detail.routeAvailability());
    assertEquals("https://privacy.acmeportal.test/request",
        detail.source().url());
    assertEquals("OFFICIAL", detail.source().type());
    assertEquals("VERIFIED", detail.source().verificationStatus());
    assertEquals(List.of("GDPR_RELEVANT", "CCPA_CPRA_RELEVANT"), detail.jurisdiction());
  }

  @Test
  void enumParsingNeverThrowsAndDefaultsSafe() {
    assertEquals(RouteType.UNKNOWN, RouteType.parse("not-a-route"));
    assertEquals(RouteType.UNKNOWN, RouteType.parse(null));
    assertEquals(RequestMethod.UNKNOWN, RequestMethod.parse(""));
    assertEquals(AutomationStatus.UNKNOWN, AutomationStatus.parse("someday"));
    assertEquals(RouteConfidence.UNKNOWN, RouteConfidence.parse(null));
    assertEquals(RouteAvailability.UNKNOWN, RouteAvailability.parse("maybe"));
    assertEquals(RouteSourceType.UNKNOWN, RouteSourceType.parse(null));
    assertEquals(RouteVerificationStatus.UNVERIFIED, RouteVerificationStatus.parse("bogus"));
    assertEquals(JurisdictionSupport.UNKNOWN, JurisdictionSupport.parse("atlantis"));
  }

  // -- matching ------------------------------------------------------------------

  @Test
  void serviceDomainNormalizationWorks() {
    ServiceDirectory entry = verifiedPortal();
    repository.active = List.of(entry);

    ServiceMatch match = registry.match("MAIL.AcmePortal.TEST");

    assertEquals(MatchKind.KNOWN, match.kind());
    assertEquals("acme-portal", match.service().orElseThrow().getSlug());
  }

  @Test
  void serviceAliasMatchingWorks() {
    ServiceDirectory entry = verifiedPortal();
    repository.active = List.of(entry);

    ServiceMatch match = registry.match("acmeportal.test");

    assertEquals(MatchKind.KNOWN, match.kind());
  }

  @Test
  void knownDomainMapsToCorrectService() {
    ServiceDirectory first = verifiedPortal();
    ServiceDirectory second = unverifiedWithUrl();
    repository.active = List.of(first, second);

    ServiceMatch match = registry.match("user@acmeportal.test");

    assertEquals(MatchKind.KNOWN, match.kind());
    assertEquals("acme-portal", match.service().orElseThrow().getSlug());
  }

  @Test
  void unknownDomainRemainsUnknown() {
    repository.active = List.of(verifiedPortal());

    ServiceMatch match = registry.match("shop@no-such-service.test");

    assertEquals(MatchKind.UNKNOWN, match.kind());
    assertTrue(match.service().isEmpty());
    assertTrue(match.candidates().isEmpty());
  }

  @Test
  void ambiguousServiceRemainsAmbiguous() {
    ServiceDirectory first = new ServiceDirectory("twin-one", "Twin One");
    first.setDomains(new String[]{"shared.test"});
    first.setStatus("active");
    ServiceDirectory second = new ServiceDirectory("twin-two", "Twin Two");
    second.setDomains(new String[]{"shared.test"});
    second.setStatus("active");
    repository.active = List.of(first, second);

    ServiceMatch match = registry.match("user@shared.test");

    assertEquals(MatchKind.AMBIGUOUS, match.kind());
    assertTrue(match.service().isEmpty());
    assertEquals(2, match.candidates().size());
  }

  @Test
  void inactiveServiceNeverMatches() {
    ServiceDirectory entry = verifiedPortal();
    entry.setStatus("deprecated");
    // The real query filters by status; the stub list simulates the filtered
    // result, then the unfiltered row to prove the service-level guard.
    repository.active = List.of();
    assertEquals(MatchKind.UNKNOWN, registry.match("user@acmeportal.test").kind());
    repository.active = List.of(entry);
    assertTrue(registry.findActiveBySlug("acme-portal").isEmpty());
  }

  // -- enrichment -----------------------------------------------------------------

  @Test
  void strongEvidenceMapsToKnownEnrichment() {
    repository.active = List.of(verifiedPortal());

    Enrichment enrichment = registry.enrichmentFor(verifiedPortal(), "acmeportal.test");

    assertEquals("KNOWN", enrichment.privacyRoute().availability());
    assertEquals("PRIVACY_PORTAL", enrichment.privacyRoute().type());
    assertEquals("HIGH", enrichment.privacyRoute().confidence());
    assertEquals("MANUAL_ONLY", enrichment.automation().status());
    assertTrue(enrichment.privacyRoute().note().contains("Potential privacy request route"));
  }

  @Test
  void weakEvidenceIsNeverUpgradedOrConfirmed() {
    repository.active = List.of(verifiedPortal());

    // Unknown sender stays UNKNOWN even though the catalog is non-empty:
    // registry presence must not upgrade weak evidence.
    Enrichment enrichment =
        registry.enrichmentFor(null, "bulk@no-such-service.test");

    assertEquals("UNKNOWN", enrichment.privacyRoute().availability());
    String serialized = enrichment.privacyRoute().toString() + enrichment.automation();
    assertFalse(serialized.contains("CONFIRMED"));
    assertFalse(serialized.contains("ACTIVE"));
    assertFalse(serialized.contains("DELETED"));
  }

  @Test
  void ambiguousFallbackSurfacesAmbiguity() {
    ServiceDirectory first = new ServiceDirectory("twin-one", "Twin One");
    first.setDomains(new String[]{"shared.test"});
    first.setStatus("active");
    ServiceDirectory second = new ServiceDirectory("twin-two", "Twin Two");
    second.setDomains(new String[]{"shared.test"});
    second.setStatus("active");
    repository.active = List.of(first, second);

    Enrichment enrichment = registry.enrichmentFor(null, "user@shared.test");

    assertEquals("AMBIGUOUS", enrichment.privacyRoute().availability());
    assertTrue(enrichment.privacyRoute().note().contains("Manual review required"));
  }

  // -- routes ----------------------------------------------------------------------

  @Test
  void serviceCategoryReturnedSafely() {
    ServiceDetailDto detail = registry.describe(verifiedPortal());
    assertEquals("shopping", detail.category());
  }

  @Test
  void knownPrivacyRouteReturned() {
    ServiceDetailDto detail = registry.describe(verifiedPortal());
    assertEquals("KNOWN", detail.routeAvailability());
    assertEquals("https://privacy.acmeportal.test/policy", detail.privacyUrl());
    assertFalse(detail.manualReviewRequired());
  }

  @Test
  void unknownPrivacyRouteHandledSafely() {
    ServiceDirectory entry = new ServiceDirectory("no-route", "No Route");
    entry.setDomains(new String[]{"noroute.test"});
    entry.setStatus("active");

    ServiceDetailDto detail = registry.describe(entry);

    assertEquals("UNKNOWN", detail.routeAvailability());
    assertEquals("UNKNOWN", detail.routeType());
    assertNull(detail.deletionUrl());
    assertNull(detail.privacyUrl());
    assertNull(detail.source().url());
    assertTrue(detail.manualReviewRequired());
    assertTrue(detail.routeNote().contains("Manual review required"));
  }

  @Test
  void routeConfidenceReturned() {
    assertEquals("HIGH", registry.describe(verifiedPortal()).routeConfidence());
    ServiceDirectory entry = verifiedPortal();
    entry.setRouteConfidence("LOW");
    assertEquals("LOW", registry.describe(entry).routeConfidence());
  }

  @Test
  void unverifiedRouteCannotBecomeTrusted() {
    ServiceDetailDto detail = registry.describe(unverifiedWithUrl());

    // Stored values are visible as description, but never as trusted:
    // availability is forced UNKNOWN and links are suppressed.
    assertEquals("UNKNOWN", detail.routeAvailability());
    assertEquals("UNKNOWN", detail.routeConfidence());
    assertNull(detail.source().url());
    assertNull(detail.privacyUrl());
    assertTrue(detail.manualReviewRequired());
  }

  @Test
  void rejectedRowSuppressedFromEnrichment() {
    ServiceDirectory entry = verifiedPortal();
    entry.setVerificationStatus("REJECTED");
    repository.active = List.of(entry);

    Enrichment enrichment = registry.enrichmentFor(entry, "acmeportal.test");

    assertEquals("UNKNOWN", enrichment.privacyRoute().availability());
  }

  @Test
  void routeTypeMappingWorks() {
    assertEquals(RouteType.PRIVACY_PORTAL, RouteType.parse("privacy_portal"));
    assertEquals(RouteType.SELF_SERVICE_ACCOUNT_DELETE,
        RouteType.parse("SELF_SERVICE_ACCOUNT_DELETE"));
    assertEquals(RouteType.EMAIL_REQUEST, RouteType.parse("email_request"));
    assertEquals(RouteType.ACCOUNT_SETTINGS, RouteType.parse("account_settings"));
    assertEquals(RouteType.HELP_CENTER, RouteType.parse("help_center"));
    assertEquals(RouteType.PRIVACY_REQUEST_FORM, RouteType.parse("privacy_request_form"));
  }

  @Test
  void requestMethodMappingWorks() {
    assertEquals(RequestMethod.WEB_FORM, RequestMethod.parse("web_form"));
    assertEquals(RequestMethod.PRIVACY_PORTAL, RequestMethod.parse("privacy_portal"));
    assertEquals(RequestMethod.EMAIL, RequestMethod.parse("email"));
    assertEquals(RequestMethod.ACCOUNT_SETTINGS, RequestMethod.parse("account_settings"));
    assertEquals(RequestMethod.MANUAL, RequestMethod.parse("manual"));
  }

  @Test
  void automationStatusMappingWorks() {
    assertEquals("MANUAL_ONLY",
        registry.describe(verifiedPortal()).automationStatus());
    ServiceDirectory entry = verifiedPortal();
    for (AutomationStatus status : AutomationStatus.values()) {
      entry.setAutomationStatus(status.name());
      assertEquals(status.name(), registry.describe(entry).automationStatus());
      // Every automation note must deny current execution.
      assertTrue(registry.describe(entry).automationNote().contains("not perform")
          || registry.describe(entry).automationNote().contains("Not performed"),
          "automation note must deny execution for " + status);
    }
  }

  // -- jurisdiction ------------------------------------------------------------------

  @Test
  void gdprMetadataRepresentedSafely() {
    Enrichment enrichment = registry.enrichmentFor(verifiedPortal(), "acmeportal.test");
    assertTrue(enrichment.jurisdiction().supports().contains("GDPR_RELEVANT"));
    assertTrue(enrichment.jurisdiction().note().contains("not legal advice"));
  }

  @Test
  void ccpaMetadataRepresentedSafely() {
    Enrichment enrichment = registry.enrichmentFor(verifiedPortal(), "acmeportal.test");
    assertTrue(enrichment.jurisdiction().supports().contains("CCPA_CPRA_RELEVANT"));
  }

  @Test
  void legalMetadataIsNotLegalCertainty() {
    ServiceDetailDto detail = registry.describe(verifiedPortal());
    String all = detail.routeNote() + "|" + detail.automationNote() + "|"
        + detail.jurisdictionNote() + "|" + detail.jurisdiction();
    assertFalse(all.contains("definitely"));
    assertFalse(all.contains("guarantee"));
    assertFalse(all.contains("You have"));
    assertFalse(all.contains("legal right"));
    assertFalse(all.contains("Confirmed"));
    assertTrue(all.contains("not legal advice"));
  }

  // -- URL safety ----------------------------------------------------------------------

  @Test
  void unsafeUrlsAreRejected() {
    assertFalse(ServiceUrls.isSafe(null));
    assertFalse(ServiceUrls.isSafe(""));
    assertFalse(ServiceUrls.isSafe("javascript:alert(1)"));
    assertFalse(ServiceUrls.isSafe("data:text/html,<h1>x</h1>"));
    assertFalse(ServiceUrls.isSafe("file:///etc/passwd"));
    assertFalse(ServiceUrls.isSafe("http://localhost:8080/delete"));
    assertFalse(ServiceUrls.isSafe("https://localhost/account"));
    assertFalse(ServiceUrls.isSafe("http://127.0.0.1/account"));
    assertFalse(ServiceUrls.isSafe("https://10.0.0.5/privacy"));
    assertFalse(ServiceUrls.isSafe("https://192.168.1.10/privacy"));
    assertFalse(ServiceUrls.isSafe("https://172.16.9.9/privacy"));
    assertFalse(ServiceUrls.isSafe("https://user:pass@privacy.acmeportal.test/"));
    assertFalse(ServiceUrls.isSafe("ftp://privacy.acmeportal.test/file"));
    assertFalse(ServiceUrls.isSafe("not a url"));
  }

  @Test
  void safePublicUrlsPass() {
    assertTrue(ServiceUrls.isSafe("https://privacy.acmeportal.test/request"));
    assertTrue(ServiceUrls.isSafe("http://privacy.acmeportal.test/request"));
  }

  @Test
  void unsafeStoredUrlSuppressedInDetail() {
    ServiceDirectory entry = verifiedPortal();
    entry.setPrivacyUrl("javascript:steal()");
    entry.setSourceUrl("http://10.9.9.9/internal");

    ServiceDetailDto detail = registry.describe(entry);

    assertNull(detail.privacyUrl());
    assertNull(detail.source().url());
    // Trust in the verified description itself is unaffected; only links drop.
    assertEquals("KNOWN", detail.routeAvailability());
  }

  // -- misc ------------------------------------------------------------------------------

  @Test
  void findActiveBySlugIsCaseInsensitive() {
    repository.active = List.of(verifiedPortal());

    assertEquals("acme-portal",
        registry.findActiveBySlug("ACME-PORTAL").orElseThrow().getSlug());
  }

  @Test
  void blankInputsStaySafe() {
    repository.active = List.of(verifiedPortal());
    assertEquals(MatchKind.UNKNOWN, registry.match(null).kind());
    assertEquals(MatchKind.UNKNOWN, registry.match("").kind());
    assertTrue(registry.findActiveBySlug(null).isEmpty());
    assertTrue(registry.findActiveBySlug("  ").isEmpty());
  }
}
