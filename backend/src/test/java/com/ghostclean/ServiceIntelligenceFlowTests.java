package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import com.ghostclean.services.ServiceRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 5 service-intelligence flow tests on synthetic data only.
 *
 * <p>Catalog fixtures use {@code .example.test} names (RFC 2606 test-only):
 * no real deletion URL, portal, or ownership is implied anywhere. Seed rows
 * from V7 are never mutated — synthetic rows are cleaned up after use.
 */
class ServiceIntelligenceFlowTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private ServiceRegistry registry;

  @Autowired
  private ServiceDirectoryService directoryService;

  private String verifiedToken(String tag) throws Exception {
    String email = "svc-" + tag + "-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    return login(email, "Str0ng-Enough-Password", nextIp()).accessToken();
  }

  /** Synthetic VERIFIED service; caller must delete it when done. */
  private ServiceDirectory verifiedSynthetic(String slug, String domain) {
    ServiceDirectory entry = new ServiceDirectory(slug, "Synthetic " + slug);
    entry.setDomains(new String[]{domain});
    entry.setAliases(new String[]{"no-reply@" + domain});
    entry.setCategory("shopping");
    entry.setRouteType("PRIVACY_PORTAL");
    entry.setRequestMethod("PRIVACY_PORTAL");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://" + domain + "/privacy-request");
    entry.setSourceType("OFFICIAL");
    entry.setVerificationStatus("VERIFIED");
    entry.setJurisdiction(new String[]{"GDPR_RELEVANT", "CCPA_CPRA_RELEVANT"});
    entry.setManualReviewRequired(false);
    entry.setPrivacyUrl("https://" + domain + "/policy");
    entry.setStatus("active");
    return directory.save(entry);
  }

  private void deleteQuietly(String slug) {
    try {
      directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
    } catch (RuntimeException ignored) {
      // Best-effort cleanup; the test assertions already ran.
    }
  }

  // -- access control ------------------------------------------------------------

  @Test
  void servicesRequireAuthentication() throws Exception {
    mockMvc.perform(authedGet("/api/v1/services", "not-a-token"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(postJson("/api/v1/scans", Map.of("connectionId", UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
  }

  // -- catalog reads ---------------------------------------------------------------

  @Test
  void serviceListReturnsSeededCatalogSafely() throws Exception {
    String token = verifiedToken("list");

    MvcResult result = mockMvc.perform(authedGet("/api/v1/services", token))
        .andExpect(status().isOk()).andReturn();
    JsonNode services = json(result);
    assertTrue(services.size() >= 8, "V7 seed catalog must be listed");

    boolean spotify = false;
    for (JsonNode service : services) {
      assertNotNull(service.get("serviceId"));
      assertNotNull(service.get("displayName"));
      assertNotNull(service.get("category"));
      assertNotNull(service.get("routeAvailability"));
      assertNotNull(service.get("automationStatus"));
      if ("spotify".equals(service.get("serviceId").asText())) {
        spotify = true;
      }
    }
    assertTrue(spotify, "seeded spotify must be listed");

    String body = result.getResponse().getContentAsString();
    assertFalse(body.contains("acc-"), "token leak");
    assertFalse(body.contains("ref-"), "token leak");
    assertFalse(body.contains("Bearer"), "auth leak");
  }

  @Test
  void seededRoutesStayUnknown() throws Exception {
    String token = verifiedToken("seedroute");

    JsonNode detail = json(mockMvc.perform(authedGet("/api/v1/services/spotify", token))
        .andExpect(status().isOk()).andReturn());

    assertEquals("spotify", detail.get("serviceId").asText());
    assertEquals("UNKNOWN", detail.get("routeAvailability").asText());
    assertEquals("UNKNOWN", detail.get("routeConfidence").asText());
    assertTrue(detail.get("manualReviewRequired").asBoolean());
    assertTrue(detail.get("deletionUrl").isNull());
    assertTrue(detail.get("privacyUrl").isNull());
    assertTrue(detail.get("source").get("url").isNull());
    assertTrue(detail.get("routeNote").asText().contains("Manual review required"));
  }

  @Test
  void unknownSlugReturns404() throws Exception {
    String token = verifiedToken("unknownslug");
    mockMvc.perform(authedGet("/api/v1/services/no-such-service", token))
        .andExpect(status().isNotFound());
  }

  @Test
  void inactiveServiceReturns404() throws Exception {
    String slug = "retired-" + UUID.randomUUID().toString().substring(0, 8);
    ServiceDirectory entry = new ServiceDirectory(slug, "Retired Synthetic");
    entry.setDomains(new String[]{slug + ".test"});
    entry.setStatus("deprecated");
    directory.save(entry);
    try {
      String token = verifiedToken("inactive");
      mockMvc.perform(authedGet("/api/v1/services/" + slug, token))
          .andExpect(status().isNotFound());
    } finally {
      deleteQuietly(slug);
    }
  }

  // -- enrichment ---------------------------------------------------------------------

  @Test
  void enrichedDetectedAccountAfterScan() throws Exception {
    ScanFixture fix = connectedUser("svcenrich");
    fakeGmail.scriptPages(List.of("e9"));
    fakeGmail.addMessage("e9", "welcome@spotify.com", "Welcome to Spotify",
        NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());

    JsonNode accounts = json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
    assertEquals(1, accounts.size());
    JsonNode account = accounts.get(0);
    assertEquals("Spotify", account.get("service").asText());

    JsonNode route = account.get("privacyRoute");
    assertNotNull(route, "enrichment block must be present");
    assertEquals("UNKNOWN", route.get("availability").asText());
    assertTrue(route.get("manualReviewRequired").asBoolean());
    assertEquals("MANUAL_ONLY", account.get("automation").get("status").asText());
    assertTrue(account.get("jurisdiction").get("note").asText()
        .contains("not legal advice"));

    String body = accounts.toString();
    assertFalse(body.contains("CONFIRMED"));
    assertFalse(body.contains("ACTIVE"));
    assertFalse(body.contains("DELETED"));
  }

  @Test
  void knownEnrichmentForVerifiedService() throws Exception {
    String slug = "acme-" + UUID.randomUUID().toString().substring(0, 8);
    String domain = slug + ".test";
    verifiedSynthetic(slug, domain);
    directoryService.refresh();
    try {
      ScanFixture fix = connectedUser("svcknown");
      fakeGmail.scriptPages(List.of("k9"));
      fakeGmail.addMessage("k9", "no-reply@" + domain, "Welcome! Please verify",
          NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());

      JsonNode accounts = json(mockMvc.perform(
          authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
      assertEquals(1, accounts.size());
      JsonNode route = accounts.get(0).get("privacyRoute");
      assertEquals("KNOWN", route.get("availability").asText());
      assertEquals("PRIVACY_PORTAL", route.get("type").asText());
      assertEquals("HIGH", route.get("confidence").asText());
      assertTrue(route.get("note").asText().contains("Potential privacy request route"));
      assertEquals("MANUAL_ONLY",
          accounts.get(0).get("automation").get("status").asText());
      assertTrue(accounts.get(0).get("automation").get("note").asText()
          .contains("not perform"));
      assertTrue(accounts.get(0).get("jurisdiction").get("supports").toString()
          .contains("GDPR_RELEVANT"));
    } finally {
      deleteQuietly(slug);
      directoryService.refresh();
    }
  }

  @Test
  void crossUserDetectedAccountAccessRejected() throws Exception {
    ScanFixture owner = connectedUser("svcvictim");
    ScanFixture attacker = connectedUser("svcintruder");
    fakeGmail.scriptPages(List.of("x9"));
    fakeGmail.addMessage("x9", "welcome@spotify.com", "Welcome to Spotify",
        NOW - DAY, false);
    postScan(owner.accessToken(), owner.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(owner.userId())
        .get(0).getId();

    mockMvc.perform(authedGet("/api/v1/discovered/" + accountId, attacker.accessToken()))
        .andExpect(status().isNotFound());
    mockMvc.perform(authedGet(
        "/api/v1/discovered/" + accountId + "/evidence", attacker.accessToken()))
        .andExpect(status().isNotFound());
  }

  // -- immutability ----------------------------------------------------------------------

  @Test
  void registryImmutableForOrdinaryUsers() throws Exception {
    String token = verifiedToken("immutable");
    int before = json(mockMvc.perform(authedGet("/api/v1/services", token))
        .andReturn()).size();

    // Any non-successful status proves rejection; the catalog-unchanged
    // assertion below is the real guarantee. (Note: unmapped methods
    // surface through the existing generic error envelope rather than a
    // framework 405 — shared Phase 1/2 behavior, left untouched.)
    java.util.List<String> probeStatuses = new java.util.ArrayList<>();
    for (var request : List.of(
        postJson("/api/v1/services", Map.of("serviceId", "evil"), nextIp())
            .header("Authorization", "Bearer " + token),
        put("/api/v1/services/spotify")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{}")
            .header("Authorization", "Bearer " + token),
        patch("/api/v1/services/spotify")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{}").header("Authorization", "Bearer " + token),
        delete("/api/v1/services/spotify").header("Authorization", "Bearer " + token),
        postJson("/api/v1/services/spotify", Map.of("x", "y"), nextIp())
            .header("Authorization", "Bearer " + token))) {
      int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
      probeStatuses.add(String.valueOf(status));
      assertTrue(status >= 400,
          "registry mutation must be rejected, probes=" + probeStatuses);
    }

    int after = json(mockMvc.perform(authedGet("/api/v1/services", token))
        .andReturn()).size();
    assertEquals(before, after, "catalog must be unchanged");
    assertEquals("UNKNOWN", json(mockMvc.perform(
        authedGet("/api/v1/services/spotify", token)).andReturn())
            .get("routeAvailability").asText());
  }

  // -- persistence --------------------------------------------------------------------------

  @Test
  void duplicateServiceSlugPrevented() {
    String slug = "dupe-" + UUID.randomUUID().toString().substring(0, 8);
    ServiceDirectory first = new ServiceDirectory(slug, "Dupe One");
    first.setDomains(new String[]{slug + ".example.test"});
    directory.save(first);
    try {
      ServiceDirectory second = new ServiceDirectory(slug, "Dupe Two");
      second.setDomains(new String[]{"other.test"});
      try {
        directory.saveAndFlush(second);
        assertTrue(directory.findAll().stream()
            .filter(row -> slug.equalsIgnoreCase(row.getSlug())).count() <= 1,
            "slug uniqueness must hold");
      } catch (RuntimeException expected) {
        assertTrue(expected.getMessage() != null);
      }
    } finally {
      deleteQuietly(slug);
    }
  }

  @Test
  void singleRoutePerServiceByDesign() {
    String slug = "route-" + UUID.randomUUID().toString().substring(0, 8);
    ServiceDirectory entry = new ServiceDirectory(slug, "Route Synthetic");
    entry.setDomains(new String[]{slug + ".test"});
    directory.save(entry);
    try {
      entry.setRouteType("PRIVACY_PORTAL");
      entry.setRequestMethod("WEB_FORM");
      entry.setVerificationStatus("VERIFIED");
      entry.setRouteAvailability("KNOWN");
      directory.save(entry);
      entry.setRouteType("ACCOUNT_SETTINGS");
      directory.save(entry);

      List<ServiceDirectory> rows = directory.findAll().stream()
          .filter(row -> slug.equalsIgnoreCase(row.getSlug())).toList();
      assertEquals(1, rows.size(), "one route description per service by schema design");
      assertEquals("ACCOUNT_SETTINGS", rows.get(0).getRouteType());
    } finally {
      deleteQuietly(slug);
    }
  }

  @Test
  void routeSourceMetadataPersisted() throws Exception {
    String slug = "src-" + UUID.randomUUID().toString().substring(0, 8);
    String domain = slug + ".test";
    verifiedSynthetic(slug, domain);
    try {
      String token = verifiedToken("source");
      JsonNode detail = json(mockMvc.perform(
          authedGet("/api/v1/services/" + slug, token)).andReturn());
      assertEquals("https://" + domain + "/privacy-request",
          detail.get("source").get("url").asText());
      assertEquals("OFFICIAL", detail.get("source").get("type").asText());
      assertEquals("VERIFIED", detail.get("source").get("verificationStatus").asText());
      assertEquals("https://" + domain + "/policy", detail.get("privacyUrl").asText());
    } finally {
      deleteQuietly(slug);
    }
  }

  @Test
  void intelligenceColumnsExist() {
    List<String> columns = jdbc.queryForList(
        "select column_name from information_schema.columns "
            + "where table_name = 'service_directory'", String.class).stream()
                .map(String::toLowerCase).toList();
    for (String required : new String[]{"route_type", "request_method",
        "automation_status", "route_confidence", "route_availability", "source_url",
        "source_type", "verification_status", "jurisdiction", "manual_review_required"}) {
      assertTrue(columns.contains(required), "missing column: " + required);
    }
  }

  @Test
  void auditVocabularyForRoutesExists() {
    assertEquals("SERVICE_METADATA_UPDATED", AuditEvents.SERVICE_METADATA_UPDATED);
    assertEquals("SERVICE_ROUTE_VERIFIED", AuditEvents.SERVICE_ROUTE_VERIFIED);
    assertEquals("SERVICE_ROUTE_MARKED_UNKNOWN", AuditEvents.SERVICE_ROUTE_MARKED_UNKNOWN);
  }

  // -- leak safety ------------------------------------------------------------------------------

  @Test
  void serviceResponsesCarryNoCredentialsOrBodies() throws Exception {
    String token = verifiedToken("leak");
    String listBody = mockMvc.perform(authedGet("/api/v1/services", token))
        .andReturn().getResponse().getContentAsString();
    String detailBody = mockMvc.perform(authedGet("/api/v1/services/spotify", token))
        .andReturn().getResponse().getContentAsString();

    for (String body : new String[]{listBody, detailBody}) {
      assertFalse(body.contains("acc-"), "token leak");
      assertFalse(body.contains("ref-"), "token leak");
      assertFalse(body.contains("Bearer"), "auth leak");
      assertFalse(body.contains("You have"), "legal-certainty leak");
      assertFalse(body.contains("definitely"), "legal-certainty leak");
      assertFalse(body.contains("guarantee"), "legal-certainty leak");
      assertFalse(body.contains("Confirmed account"), "overclaim leak");
    }
    assertFalse(detailBody.contains("\"submit\""), "no action affordances");
    assertFalse(detailBody.contains("\"execute\""), "no action affordances");
  }
}
