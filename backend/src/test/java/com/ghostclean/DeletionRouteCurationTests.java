package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.privacy.PrivacyDispatchRepository;
import com.ghostclean.privacy.PrivacyRequestRepository;
import com.ghostclean.privacy.SinkOutboundMailProvider;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Deletion-route curation tests (V16 migration).
 *
 * <p>Proves the curated {@code service_directory} rows behave exactly as
 * researched — verified EMAIL routes (outlier, amazon-jobs) are dispatch
 * eligible through the existing gates, every other curated route stays
 * manual and blocked, uncertain seeds stay UNKNOWN/UNVERIFIED, and the
 * client can never override the registry destination.
 *
 * <p>All sends go to the sink provider; no external email is ever sent.
 * Catalog seed rows are never deleted by cleanup — only synthetic
 * accounts/requests/dispatches created by each test are removed.
 */
class DeletionRouteCurationTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

  @Autowired
  private PrivacyDispatchRepository dispatchRows;

  @Autowired
  private ServiceDirectoryService directoryService;

  @Autowired
  private SinkOutboundMailProvider sink;

  @BeforeEach
  void resetSink() {
    sink.reset();
  }

  private ServiceDirectory curated(String slug) {
    return directory.findFirstBySlugIgnoreCase(slug).orElseThrow(
        () -> new IllegalStateException("curated row missing: " + slug));
  }

  private void cleanupAccounts(UUID userId) {
    try {
      for (var account : discovered.findByUser_IdOrderByConfidenceDesc(userId)) {
        privacyRequests.findAll().stream()
            .filter(row -> row.getDiscoveredServiceId().equals(account.getId()))
            .forEach(row -> {
              dispatchRows.findByRequest_Id(row.getId()).ifPresent(dispatchRows::delete);
              privacyRequests.deleteById(row.getId());
            });
        discovered.deleteById(account.getId());
      }
    } catch (RuntimeException ignored) {
      // Best-effort cleanup; assertions already ran.
    }
    directoryService.refresh();
  }

  private ScanFixture scanned(String tag, String sender) throws Exception {
    ScanFixture fix = connectedUser(tag);
    fakeGmail.scriptPages(List.of(tag + "-c1"));
    fakeGmail.addMessage(tag + "-c1", sender, "Welcome! Please verify", NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    return fix;
  }

  private UUID accountIdOf(ScanFixture fix) {
    return discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).get(0).getId();
  }

  private MvcResult postPreview(String token, UUID accountId) throws Exception {
    return mockMvc.perform(postJson("/api/v1/deletion-requests/preview",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private MvcResult postCreate(String token, UUID accountId) throws Exception {
    return mockMvc.perform(postJson("/api/v1/deletion-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private UUID approve(String token, UUID accountId) throws Exception {
    UUID id = UUID.fromString(json(postCreate(token, accountId)).get("id").asText());
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/privacy-requests/" + id)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content("{}")
        .header("Authorization", "Bearer " + token)).andReturn();
    MvcResult approved = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", token)).andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    return id;
  }

  private MvcResult postDispatch(String token, UUID requestId, Map<String, String> body)
      throws Exception {
    return mockMvc.perform(postJson("/api/v1/privacy-requests/" + requestId + "/dispatch",
        body == null ? Map.of() : body, nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  // -- migration content ------------------------------------------------------------

  @Test
  void curatedRowsCarryOnlyEvidenceBackedFields() {
    ServiceDirectory microsoft = curated("microsoft");
    assertEquals("ACCOUNT_SETTINGS", microsoft.getRouteType());
    assertEquals("VERIFIED", microsoft.getVerificationStatus());
    assertEquals("KNOWN", microsoft.getRouteAvailability());
    assertEquals("HELP_CENTER", microsoft.getSourceType());
    assertTrue(microsoft.isManualReviewRequired());
    assertNull(microsoft.getPrivacyEmail(), "no email evidenced for microsoft");

    ServiceDirectory outlier = curated("outlier");
    assertEquals("EMAIL_REQUEST", outlier.getRouteType());
    assertEquals("privacy@outlier.ai", outlier.getPrivacyEmail());
    assertEquals("VERIFIED", outlier.getVerificationStatus());
    assertEquals("KNOWN", outlier.getRouteAvailability());
    assertEquals("OFFICIAL", outlier.getSourceType());
    assertFalse(outlier.isManualReviewRequired());

    ServiceDirectory jobs = curated("amazon-jobs");
    assertEquals("EMAIL_REQUEST", jobs.getRouteType());
    assertEquals("hr-dsr-privacy@amazon.com", jobs.getPrivacyEmail());
    assertEquals("VERIFIED", jobs.getVerificationStatus());
    assertEquals("KNOWN", jobs.getRouteAvailability());

    for (String slug : List.of("paypal", "resend", "wellfound")) {
      ServiceDirectory row = curated(slug);
      assertEquals("ACCOUNT_SETTINGS", row.getRouteType(), slug);
      assertEquals("VERIFIED", row.getVerificationStatus(), slug);
      assertNull(row.getPrivacyEmail(), "no email evidenced for " + slug);
      assertTrue(row.isManualReviewRequired(), slug);
    }
    for (String slug : List.of("qualcomm", "capgemini")) {
      ServiceDirectory row = curated(slug);
      assertEquals("PRIVACY_REQUEST_FORM", row.getRouteType(), slug);
      assertEquals("VERIFIED", row.getVerificationStatus(), slug);
      assertNull(row.getPrivacyEmail(), "web-form route must not carry an email: " + slug);
      assertTrue(row.isManualReviewRequired(), slug);
    }
  }

  @Test
  void uncertainSeedsStayUnknownUnverified() {
    for (String slug : List.of(
        "spotify", "netflix", "github", "amazon", "google", "apple", "dropbox")) {
      ServiceDirectory row = curated(slug);
      assertEquals("UNVERIFIED", row.getVerificationStatus(), slug);
      assertEquals("UNKNOWN", row.getRouteAvailability(), slug);
      assertNull(row.getPrivacyEmail(), slug);
    }
  }

  // -- curated manual routes stay blocked --------------------------------------------

  @Test
  void curatedMicrosoftRouteIsManualAndBlocked() throws Exception {
    ScanFixture fix = scanned("cms",
        "account-security-noreply@accountprotection.microsoft.com");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("ACCOUNT_SETTINGS", preview.get("deletionMethod").asText());
      assertTrue(preview.get("manualReviewRequired").asBoolean());
      assertTrue(preview.get("destination").isNull());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      MvcResult result = postDispatch(fix.accessToken(), id, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("MANUAL_REVIEW_REQUIRED"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }

  @Test
  void curatedManualRoutesStayBlocked() throws Exception {
    Map<String, String> cases = Map.of(
        "cpaypal", "no-reply@paypal.com",
        "cresend", "no-reply@resend.com",
        "cwell", "no-reply@wellfound.com",
        "cqual", "no-reply@qualcomm.com",
        "ccap", "no-reply@capgemini.com");
    for (Map.Entry<String, String> entry : cases.entrySet()) {
      ScanFixture fix = scanned(entry.getKey(), entry.getValue());
      try {
        JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
        assertTrue(preview.get("manualReviewRequired").asBoolean(), entry.getKey());
        assertTrue(preview.get("destination").isNull(), entry.getKey());

        UUID id = approve(fix.accessToken(), accountIdOf(fix));
        MvcResult result = postDispatch(fix.accessToken(), id, null);
        assertEquals(409, result.getResponse().getStatus(), entry.getKey());
        assertEquals(0, sink.sent().size(), entry.getKey());
      } finally {
        cleanupAccounts(fix.userId());
      }
    }
  }

  // -- curated EMAIL routes are eligible (still explicit, still sink) ------------------

  @Test
  void curatedOutlierEmailRouteIsDispatchEligible() throws Exception {
    ScanFixture fix = scanned("cout", "no-reply@outlier.ai");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("EMAIL", preview.get("deletionMethod").asText());
      assertFalse(preview.get("manualReviewRequired").asBoolean());
      assertEquals("privacy@outlier.ai", preview.get("destination").asText());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      assertEquals(0, sink.sent().size(), "approval alone must not send");
      JsonNode dispatched = json(postDispatch(fix.accessToken(), id, null));
      assertEquals("ACCEPTED", dispatched.get("status").asText());
      assertEquals("privacy@outlier.ai", dispatched.get("destination").asText());
      assertEquals("privacy@outlier.ai", sink.sent().get(0).to());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }

  @Test
  void curatedAmazonJobsEmailRouteIsDispatchEligible() throws Exception {
    ScanFixture fix = scanned("cajobs", "no-reply@amazon.jobs");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("EMAIL", preview.get("deletionMethod").asText());
      assertEquals("hr-dsr-privacy@amazon.com", preview.get("destination").asText());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      JsonNode dispatched = json(postDispatch(fix.accessToken(), id, null));
      assertEquals("ACCEPTED", dispatched.get("status").asText());
      assertEquals("hr-dsr-privacy@amazon.com", sink.sent().get(0).to());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }

  // -- gates hold on curated data ------------------------------------------------------

  @Test
  void unverifiedSeedRouteRemainsBlocked() throws Exception {
    ScanFixture fix = scanned("cunver", "welcome@spotify.com");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("UNKNOWN", preview.get("deletionMethod").asText());
      assertTrue(preview.get("destination").isNull());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      MvcResult result = postDispatch(fix.accessToken(), id, null);
      assertEquals(409, result.getResponse().getStatus());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }

  @Test
  void userCannotOverrideCuratedDestination() throws Exception {
    ScanFixture fix = scanned("cover", "no-reply@outlier.ai");
    try {
      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      JsonNode dispatched = json(postDispatch(fix.accessToken(), id,
          Map.of("recipient", "attacker@evil.test", "destination", "attacker@evil.test",
              "to", "attacker@evil.test")));
      assertEquals("privacy@outlier.ai", dispatched.get("destination").asText());
      assertEquals("privacy@outlier.ai", sink.sent().get(0).to());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }

  @Test
  void noAutomaticDispatchForCuratedEmailRoute() throws Exception {
    ScanFixture fix = scanned("cnoauto", "no-reply@outlier.ai");
    try {
      UUID accountId = accountIdOf(fix);
      long before = dispatchRows.count();
      postPreview(fix.accessToken(), accountId);
      assertEquals(before, dispatchRows.count());
      assertEquals(0, sink.sent().size());

      UUID id = approve(fix.accessToken(), accountId);
      assertEquals(0, sink.sent().size());
      mockMvc.perform(authedGet("/api/v1/privacy-requests/" + id + "/dispatch",
          fix.accessToken()))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
              .status().isNotFound());
    } finally {
      cleanupAccounts(fix.userId());
    }
  }
}
