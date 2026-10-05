package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.privacy.PrivacyRequestRepository;
import com.ghostclean.privacy.PrivacyDispatchRepository;
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
 * Deletion Route Registry audit tests (registry phase).
 *
 * <p>Proves the dispatch gate over the curated {@code service_directory}
 * catalog on synthetic {@code .test} fixtures only — no real service route
 * is implied and nothing external is contacted:
 *
 * <ul>
 *   <li>Only VERIFIED + KNOWN + EMAIL_REQUEST + usable destination is eligible.</li>
 *   <li>UNKNOWN / UNVERIFIED / MANUAL / missing destination always stay manual
 *       and can never dispatch.</li>
 *   <li>The destination is always server-resolved; client fields never win.</li>
 *   <li>Preview/dispatch snapshots are deterministic; route edits after
 *       approval force re-review and block dispatch.</li>
 *   <li>Approval never sends; Gmail is never written; responses carry no secrets.</li>
 * </ul>
 */
class DeletionRouteRegistryTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

  @Autowired
  private PrivacyDispatchRepository dispatchRows;

  @Autowired
  private AuditLogRepository audits;

  @Autowired
  private ServiceDirectoryService directoryService;

  @Autowired
  private SinkOutboundMailProvider sink;

  @BeforeEach
  void resetSink() {
    sink.reset();
  }

  private ServiceDirectory seedRoute(
      String slug, String routeType, String verification, String privacyEmail) {
    String domain = slug + ".test";
    ServiceDirectory entry = new ServiceDirectory(slug, "Synthetic " + slug);
    entry.setDomains(new String[]{domain});
    entry.setAliases(new String[]{"no-reply@" + domain});
    entry.setCategory("shopping");
    entry.setRouteType(routeType);
    entry.setRequestMethod("EMAIL");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://" + domain + "/contact");
    entry.setSourceType("OFFICIAL");
    entry.setVerificationStatus(verification);
    entry.setManualReviewRequired(false);
    entry.setPrivacyEmail(privacyEmail);
    entry.setStatus("active");
    ServiceDirectory saved = directory.save(entry);
    directoryService.refresh();
    return saved;
  }

  private void cleanupRoute(String slug, UUID userId) {
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
      directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
    } catch (RuntimeException ignored) {
      // Best-effort cleanup; assertions already ran.
    }
    directoryService.refresh();
  }

  private ScanFixture scanned(String tag, String sender) throws Exception {
    ScanFixture fix = connectedUser(tag);
    fakeGmail.scriptPages(List.of(tag + "-r1"));
    fakeGmail.addMessage(tag + "-r1", sender, "Welcome! Please verify", NOW - DAY, false);
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

  private MvcResult postCreate(String token, UUID accountId, Map<String, String> extra)
      throws Exception {
    var body = new java.util.HashMap<String, String>();
    body.put("discoveredServiceId", accountId.toString());
    if (extra != null) {
      body.putAll(extra);
    }
    return mockMvc.perform(postJson("/api/v1/deletion-requests", body, nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private UUID approve(String token, UUID accountId) throws Exception {
    UUID id = UUID.fromString(json(postCreate(token, accountId, null)).get("id").asText());
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

  // -- 1. verified route -> eligible ------------------------------------------------

  @Test
  void verifiedEmailRouteIsEligible() throws Exception {
    String slug = "rr-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrhappy", "no-reply@" + slug + ".test");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("EMAIL", preview.get("deletionMethod").asText());
      assertFalse(preview.get("manualReviewRequired").asBoolean());
      assertEquals("privacy@" + slug + ".test", preview.get("destination").asText());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      MvcResult dispatched = postDispatch(fix.accessToken(), id, null);
      assertEquals(200, dispatched.getResponse().getStatus());
      assertEquals("ACCEPTED", json(dispatched).get("status").asText());
      assertEquals("privacy@" + slug + ".test", json(dispatched).get("destination").asText());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 2. unknown route -> manual review --------------------------------------------

  @Test
  void unknownRouteStaysManualReview() throws Exception {
    ScanFixture fix = scanned("rrunk", "welcome@spotify.com");
    JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
    assertEquals("UNKNOWN", preview.get("deletionMethod").asText());
    assertTrue(preview.get("manualReviewRequired").asBoolean());
    assertTrue(preview.get("destination").isNull());

    // Approving the UNKNOWN-route request still cannot dispatch.
    UUID id = approve(fix.accessToken(), accountIdOf(fix));
    MvcResult result = postDispatch(fix.accessToken(), id, null);
    assertEquals(409, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("DESTINATION_UNKNOWN"));
    assertEquals(0, sink.sent().size());
  }

  // -- 3. unverified route -> blocked ------------------------------------------------

  @Test
  void unverifiedRouteIsBlocked() throws Exception {
    String slug = "ru-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "UNVERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrunver", "no-reply@" + slug + ".test");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("UNKNOWN", preview.get("deletionMethod").asText());
      assertTrue(preview.get("manualReviewRequired").asBoolean());
      assertTrue(preview.get("destination").isNull());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      MvcResult result = postDispatch(fix.accessToken(), id, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("DESTINATION_UNKNOWN"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 4. manual route -> blocked ----------------------------------------------------

  @Test
  void manualRouteIsBlocked() throws Exception {
    String slug = "rm-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "PRIVACY_PORTAL", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrman", "no-reply@" + slug + ".test");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
      assertEquals("PRIVACY_PORTAL", preview.get("deletionMethod").asText());
      assertTrue(preview.get("manualReviewRequired").asBoolean());
      assertTrue(preview.get("destination").isNull());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      MvcResult result = postDispatch(fix.accessToken(), id, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("MANUAL_REVIEW_REQUIRED"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 5. unknown destination -> blocked ---------------------------------------------

  @Test
  void unknownDestinationIsBlocked() throws Exception {
    for (String bad : new String[]{null, "", "not-an-email", "admin@localhost",
        "privacy@127.0.0.1"}) {
      String slug = "rd-" + UUID.randomUUID().toString().substring(0, 8);
      seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", bad);
      ScanFixture fix = scanned("rrbad" + slug.replace("-", "").substring(0, 4),
          "no-reply@" + slug + ".test");
      try {
        JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix)));
        assertTrue(preview.get("destination").isNull(),
            "destination must be null for <" + bad + ">");

        UUID id = approve(fix.accessToken(), accountIdOf(fix));
        MvcResult result = postDispatch(fix.accessToken(), id, null);
        assertEquals(409, result.getResponse().getStatus(),
            "dispatch must fail for <" + bad + ">");
        assertTrue(result.getResponse().getContentAsString().contains("DESTINATION_UNKNOWN"));
        assertEquals(0, sink.sent().size());
      } finally {
        cleanupRoute(slug, fix.userId());
      }
    }
  }

  // -- 6. user cannot override destination -------------------------------------------

  @Test
  void userCannotOverrideDestination() throws Exception {
    String slug = "ro-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrover", "no-reply@" + slug + ".test");
    try {
      JsonNode created = json(postCreate(fix.accessToken(), accountIdOf(fix),
          Map.of("recipient", "attacker@evil.test", "destination", "attacker@evil.test",
              "to", "attacker@evil.test")));
      assertEquals("privacy@" + slug + ".test", created.get("destination").asText());

      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      JsonNode dispatched = json(postDispatch(fix.accessToken(), id,
          Map.of("recipient", "attacker@evil.test", "destination", "attacker@evil.test",
              "to", "attacker@evil.test")));
      assertEquals("privacy@" + slug + ".test", dispatched.get("destination").asText());
      assertEquals("privacy@" + slug + ".test", sink.sent().get(0).to());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 7. destination snapshot is deterministic --------------------------------------

  @Test
  void destinationSnapshotIsDeterministic() throws Exception {
    String slug = "rs-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "  privacy@" + slug + ".test  ");
    ScanFixture fix = scanned("rrsnap", "no-reply@" + slug + ".test");
    try {
      UUID accountId = accountIdOf(fix);
      String first = postPreview(fix.accessToken(), accountId)
          .getResponse().getContentAsString();
      String second = postPreview(fix.accessToken(), accountId)
          .getResponse().getContentAsString();
      assertEquals(first, second);

      // Whitespace-padded registry value resolves to the same trimmed destination.
      assertEquals("privacy@" + slug + ".test", json(postPreview(
          fix.accessToken(), accountId)).get("destination").asText());

      UUID id = approve(fix.accessToken(), accountId);
      JsonNode dispatched = json(postDispatch(fix.accessToken(), id, null));
      assertEquals("privacy@" + slug + ".test", dispatched.get("destination").asText());
      assertEquals("privacy@" + slug + ".test", sink.sent().get(0).to());

      // Repeat returns the recorded outcome without resending.
      JsonNode repeat = json(postDispatch(fix.accessToken(), id, null));
      assertEquals(dispatched.get("dispatchId").asText(), repeat.get("dispatchId").asText());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 8. route changes invalidate approval ------------------------------------------

  @Test
  void routeChangeInvalidatesApproval() throws Exception {
    String slug = "rc-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrchg", "no-reply@" + slug + ".test");
    try {
      UUID accountId = accountIdOf(fix);
      UUID id = approve(fix.accessToken(), accountId);
      String bodyBefore = json(mockMvc.perform(
          authedGet("/api/v1/deletion-requests/" + id, fix.accessToken()))
          .andReturn()).get("body").asText();

      // Material route change after approval: EMAIL -> PORTAL.
      ServiceDirectory entry = directory.findFirstBySlugIgnoreCase(slug).orElseThrow();
      entry.setRouteType("PRIVACY_PORTAL");
      directory.saveAndFlush(entry);

      JsonNode after = json(mockMvc.perform(
          authedGet("/api/v1/deletion-requests/" + id, fix.accessToken()))
          .andReturn());
      assertEquals(bodyBefore, after.get("body").asText());
      assertTrue(after.get("manualReviewRequired").asBoolean());

      // Dispatch re-validates the live route and refuses to send.
      MvcResult result = postDispatch(fix.accessToken(), id, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("MANUAL_REVIEW_REQUIRED"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 9. no automatic dispatch -------------------------------------------------------

  @Test
  void noAutomaticDispatch() throws Exception {
    String slug = "rn-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrnoauto", "no-reply@" + slug + ".test");
    try {
      UUID accountId = accountIdOf(fix);
      long dispatchesBefore = dispatchRows.count();
      postPreview(fix.accessToken(), accountId);
      assertEquals(0, sink.sent().size());
      assertEquals(dispatchesBefore, dispatchRows.count());

      UUID id = approve(fix.accessToken(), accountId);
      assertEquals(0, sink.sent().size());
      mockMvc.perform(authedGet("/api/v1/privacy-requests/" + id + "/dispatch",
          fix.accessToken()))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
              .status().isNotFound());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 10. no Gmail writes ------------------------------------------------------------

  @Test
  void noGmailWrites() throws Exception {
    String slug = "rg-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrnogmail", "no-reply@" + slug + ".test");
    try {
      // The fake Gmail surface used by scans is read-only scripting; dispatch
      // goes only through the sink provider, never through Gmail.
      UUID id = approve(fix.accessToken(), accountIdOf(fix));
      postDispatch(fix.accessToken(), id, null);
      assertEquals(1, sink.sent().size());
      assertEquals("privacy@" + slug + ".test", sink.sent().get(0).to());
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }

  // -- 11. no secret leakage -----------------------------------------------------------

  @Test
  void noSecretLeakage() throws Exception {
    String slug = "rk-" + UUID.randomUUID().toString().substring(0, 8);
    seedRoute(slug, "EMAIL_REQUEST", "VERIFIED", "privacy@" + slug + ".test");
    ScanFixture fix = scanned("rrnosec", "no-reply@" + slug + ".test");
    try {
      UUID accountId = accountIdOf(fix);
      String preview = postPreview(fix.accessToken(), accountId)
          .getResponse().getContentAsString();
      String created = postCreate(fix.accessToken(), accountId, null)
          .getResponse().getContentAsString();
      UUID id = approve(fix.accessToken(), accountId);
      String fetched = mockMvc.perform(
          authedGet("/api/v1/deletion-requests/" + id, fix.accessToken()))
          .andReturn().getResponse().getContentAsString();
      String dispatched = postDispatch(fix.accessToken(), id, null)
          .getResponse().getContentAsString();
      String auditDump = objectMapper.writeValueAsString(
          audits.findByUserIdOrderByIdAsc(fix.userId()));

      for (String surface : new String[]{preview, created, fetched, dispatched, auditDump}) {
        assertFalse(surface.contains("acc-"), "token leak");
        assertFalse(surface.contains("ref-"), "token leak");
        assertFalse(surface.contains("Bearer"), "auth leak");
      }
      assertFalse(dispatched.contains("secure channel"), "content leak");
    } finally {
      cleanupRoute(slug, fix.userId());
    }
  }
}
