package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLogRepository;
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
 * Phase 8 deletion-request flow tests on synthetic data only. The engine
 * is proven end to end — resolve, preview, generate, review, approve via
 * Phase 6, confirm via Phase 7 dispatch — without sending anything real:
 * the sink provider records instead of delivering.
 */
class DeletionRequestFlowTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

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

  private ServiceDirectory routedService(
      String slug, String routeType, String verification) {
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
    entry.setPrivacyEmail("privacy@" + domain);
    entry.setStatus("active");
    return directory.save(entry);
  }

  private void cleanupRouted(String slug, UUID userId) {
    try {
      for (var account : discovered.findByUser_IdOrderByConfidenceDesc(userId)) {
        privacyRequests.findAll().stream()
            .filter(row -> row.getDiscoveredServiceId().equals(account.getId()))
            .forEach(row -> privacyRequests.deleteById(row.getId()));
        discovered.deleteById(account.getId());
      }
      directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
    } catch (RuntimeException ignored) {
      // Best-effort cleanup; assertions already ran.
    }
    directoryService.refresh();
  }

  private ScanFixture scannedDomain(String tag, String sender, String subject)
      throws Exception {
    ScanFixture fix = connectedUser(tag);
    fakeGmail.scriptPages(List.of(tag + "-g1"));
    fakeGmail.addMessage(tag + "-g1", sender, subject, NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    return fix;
  }

  private UUID accountIdOf(ScanFixture fix) {
    return discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).get(0).getId();
  }

  private MvcResult postPreview(String token, UUID accountId, Map<String, String> extra)
      throws Exception {
    var body = new java.util.HashMap<String, String>();
    body.put("discoveredServiceId", accountId.toString());
    if (extra != null) {
      body.putAll(extra);
    }
    return mockMvc.perform(postJson("/api/v1/deletion-requests/preview", body, nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private MvcResult postCreate(String token, UUID accountId, Map<String, String> extra,
      String ip) throws Exception {
    var body = new java.util.HashMap<String, String>();
    body.put("discoveredServiceId", accountId.toString());
    if (extra != null) {
      body.putAll(extra);
    }
    return mockMvc.perform(postJson("/api/v1/deletion-requests", body, ip)
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  // -- preview --------------------------------------------------------------------------------

  @Test
  void previewRequiresAuthentication() throws Exception {
    mockMvc.perform(postJson("/api/v1/deletion-requests/preview",
        Map.of("discoveredServiceId", UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void previewResolvesServiceAndMethod() throws Exception {
    ScanFixture fix = scannedDomain("delprev", "welcome@spotify.com", "Welcome to Spotify");
    JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix), null));

    assertEquals("Spotify", preview.get("service").asText());
    assertEquals("UNKNOWN", preview.get("deletionMethod").asText());
    assertTrue(preview.get("manualReviewRequired").asBoolean());
    assertTrue(preview.get("destination").isNull());
    assertFalse(preview.get("subject").asText().isBlank());
    assertFalse(preview.get("body").asText().isBlank());
    assertEquals("privacy-request-v1", preview.get("templateId").asText());
    assertEquals(1, preview.get("templateVersion").asInt());
    assertTrue(preview.get("warnings").size() >= 2);
  }

  @Test
  void previewDoesNotSendOrPersist() throws Exception {
    ScanFixture fix = scannedDomain("delpure", "welcome@spotify.com", "Welcome to Spotify");
    long before = privacyRequests.count();
    MvcResult result = postPreview(fix.accessToken(), accountIdOf(fix), null);

    assertEquals(200, result.getResponse().getStatus());
    assertEquals(before, privacyRequests.count());
    assertEquals(0, sink.sent().size());
  }

  @Test
  void previewUnknownServiceStaysUnknown() throws Exception {
    ScanFixture fix = scannedDomain("delunk",
        "shop@unknown-shop.example", "Your order update");
    JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix), null));

    assertEquals("UNKNOWN", preview.get("deletionMethod").asText());
    assertTrue(preview.get("manualReviewRequired").asBoolean());
    assertTrue(preview.get("destination").isNull());
    assertTrue(preview.get("warnings").toString().contains("method is unknown"));
    assertFalse(preview.get("supportedTypes").toString().contains("DELETE_ACCOUNT"));
  }

  @Test
  void previewUnknownRouteFabricatesNothing() throws Exception {
    ScanFixture fix = scannedDomain("delfab", "welcome@spotify.com", "Welcome to Spotify");
    String body = postPreview(fix.accessToken(), accountIdOf(fix), null)
        .getResponse().getContentAsString();

    assertFalse(body.contains("privacy@"));
    assertFalse(body.contains("support@"));
    assertFalse(body.contains("dpo@"));
    assertFalse(body.contains("legal@"));
  }

  @Test
  void emailRoutePreviewResolvesDestination() throws Exception {
    String slug = "pv-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delemail", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix),
          Map.of("requestType", "DELETE_PERSONAL_DATA")));
      assertEquals("EMAIL", preview.get("deletionMethod").asText());
      assertFalse(preview.get("manualReviewRequired").asBoolean());
      assertEquals("privacy@" + slug + ".test", preview.get("destination").asText());
      assertTrue(preview.get("supportedTypes").toString().contains("DELETE_ACCOUNT"));
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void portalRouteStaysManual() throws Exception {
    String slug = "pp-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "PRIVACY_PORTAL", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delportal", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix), null));
      assertEquals("PRIVACY_PORTAL", preview.get("deletionMethod").asText());
      assertTrue(preview.get("manualReviewRequired").asBoolean());
      assertTrue(preview.get("destination").isNull());
      assertTrue(preview.get("manualInstructions").size() >= 1);
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void webFormAndAccountSettingsStayManual() throws Exception {
    for (String route : new String[]{"PRIVACY_REQUEST_FORM", "ACCOUNT_SETTINGS"}) {
      String slug = "wf-" + UUID.randomUUID().toString().substring(0, 8);
      routedService(slug, route, "VERIFIED");
      directoryService.refresh();
      // Tags stay lowercase: lookups below rely on exact-case matching.
      String tag = ("delwf" + route.charAt(0)).toLowerCase();
      ScanFixture fix = scannedDomain(tag, "no-reply@" + slug + ".test",
          "Welcome! Please verify");
      try {
        JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix), null));
        assertTrue(preview.get("manualReviewRequired").asBoolean(),
            "route " + route + " must stay manual");
        assertTrue(preview.get("destination").isNull());
      } finally {
        cleanupRouted(slug, fix.userId());
      }
    }
  }

  @Test
  void deleteAccountCoercedWhenUnsupported() throws Exception {
    ScanFixture fix = scannedDomain("delcoerce", "welcome@spotify.com", "Welcome to Spotify");
    JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix),
        Map.of("requestType", "DELETE_ACCOUNT")));
    assertEquals("GENERAL_PRIVACY_REQUEST", preview.get("requestType").asText());
  }

  @Test
  void requestTypesAndJurisdictionsPreview() throws Exception {
    ScanFixture fix = scannedDomain("deltypes", "welcome@spotify.com", "Welcome to Spotify");
    UUID accountId = accountIdOf(fix);
    for (String type : new String[]{"DELETE_PERSONAL_DATA", "GENERAL_PRIVACY_REQUEST",
        "ACCESS_PERSONAL_DATA"}) {
      JsonNode preview = json(
          postPreview(fix.accessToken(), accountId, Map.of("requestType", type)));
      assertEquals(type, preview.get("requestType").asText());
      assertFalse(preview.get("body").asText().isBlank());
    }
    for (String jurisdiction : new String[]{"GENERAL", "GDPR", "CCPA_CPRA"}) {
      JsonNode preview = json(
          postPreview(fix.accessToken(), accountId, Map.of("jurisdiction", jurisdiction)));
      assertEquals(jurisdiction, preview.get("jurisdiction").asText());
    }
  }

  @Test
  void previewDeterministic() throws Exception {
    ScanFixture fix = scannedDomain("deldet", "welcome@spotify.com", "Welcome to Spotify");
    UUID accountId = accountIdOf(fix);
    String first = postPreview(fix.accessToken(), accountId, null)
        .getResponse().getContentAsString();
    String second = postPreview(fix.accessToken(), accountId, null)
        .getResponse().getContentAsString();
    assertEquals(first, second);
  }

  @Test
  void previewLegalLanguageConservative() throws Exception {
    ScanFixture fix = scannedDomain("dellegal", "welcome@spotify.com", "Welcome to Spotify");
    JsonNode preview = json(postPreview(fix.accessToken(), accountIdOf(fix),
        Map.of("jurisdiction", "GDPR")));
    // The honest "does not guarantee deletion" disclaimer lives in warnings;
    // the request text itself must carry no absolute claims.
    String text = preview.get("subject").asText() + "|" + preview.get("body").asText();
    assertFalse(text.contains("legally required"));
    assertFalse(text.contains("guarantee"));
    assertFalse(text.contains("deadline"));
    assertFalse(text.contains("You definitely"));
    assertTrue(text.contains("applicable"));
    assertTrue(preview.get("warnings").toString().contains("does not guarantee deletion"));
  }

  @Test
  void previewCrossUserRejected() throws Exception {
    ScanFixture owner = scannedDomain("delpvictim", "welcome@spotify.com", "Welcome to Spotify");
    ScanFixture attacker = connectedUser("delpattacker");
    MvcResult result = postPreview(attacker.accessToken(), accountIdOf(owner), null);
    assertEquals(404, result.getResponse().getStatus());
  }

  @Test
  void previewContentHasNoSecrets() throws Exception {
    ScanFixture fix = scannedDomain("delsec", "welcome@spotify.com", "Welcome to Spotify");
    String body = postPreview(fix.accessToken(), accountIdOf(fix), null)
        .getResponse().getContentAsString();
    assertFalse(body.contains("Welcome to Spotify"));
    assertFalse(body.contains("acc-"));
    assertFalse(body.contains("ref-"));
    assertFalse(body.contains("Bearer"));
  }

  // -- generation -------------------------------------------------------------------------------

  @Test
  void generationCreatesDraftWithTemplateProvenance() throws Exception {
    ScanFixture fix = scannedDomain("delgen", "welcome@spotify.com", "Welcome to Spotify");
    JsonNode dto = json(postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp()));

    assertEquals("DRAFT", dto.get("status").asText());
    assertEquals(1, dto.get("version").asInt());
    assertEquals("privacy-request-v1", dto.get("templateId").asText());
    assertEquals(1, dto.get("templateVersion").asInt());
    assertEquals("UNKNOWN", dto.get("deletionMethod").asText());
    assertTrue(dto.get("manualReviewRequired").asBoolean());
    assertEquals(0, sink.sent().size());
  }

  @Test
  void generationDoesNotSend() throws Exception {
    String slug = "gs-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delgsend", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void duplicateGenerationReturnsExisting() throws Exception {
    ScanFixture fix = scannedDomain("deldupe", "welcome@spotify.com", "Welcome to Spotify");
    UUID accountId = accountIdOf(fix);
    String ip = nextIp();
    JsonNode first = json(postCreate(fix.accessToken(), accountId, null, ip));
    JsonNode second = json(postCreate(fix.accessToken(), accountId, null, ip));
    assertEquals(first.get("id").asText(), second.get("id").asText());

    String key = "del-key-" + UUID.randomUUID();
    MvcResult keyed1 = mockMvc.perform(postJson("/api/v1/deletion-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())
        .header("Idempotency-Key", key)).andReturn();
    MvcResult keyed2 = mockMvc.perform(postJson("/api/v1/deletion-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())
        .header("Idempotency-Key", key)).andReturn();
    assertEquals(json(keyed1).get("id").asText(), json(keyed2).get("id").asText());
  }

  @Test
  void frontendCannotOverrideDestinationOnCreate() throws Exception {
    String slug = "go-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delgo", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      JsonNode dto = json(mockMvc.perform(postJson("/api/v1/deletion-requests",
          Map.of("discoveredServiceId", accountIdOf(fix).toString(),
              "recipient", "attacker@evil.test",
              "destination", "attacker@evil.test"), nextIp())
          .header("Authorization", "Bearer " + fix.accessToken())).andReturn());
      assertEquals("privacy@" + slug + ".test", dto.get("destination").asText());
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void creationCrossUserRejected() throws Exception {
    ScanFixture owner = scannedDomain("delcvictim", "welcome@spotify.com", "Welcome to Spotify");
    ScanFixture attacker = connectedUser("delcattacker");
    assertEquals(404, postCreate(attacker.accessToken(), accountIdOf(owner), null, nextIp())
        .getResponse().getStatus());
    mockMvc.perform(authedGet("/api/v1/deletion-requests/" + UUID.randomUUID(),
        attacker.accessToken())).andExpect(status().isNotFound());
  }

  // -- lifecycle integration (Phase 6 review/approve, Phase 7 dispatch) --------------------------------

  @Test
  void fullLifecycleThroughPhase6And7() throws Exception {
    String slug = "fl-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delfull", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      UUID accountId = accountIdOf(fix);
      UUID id = UUID.fromString(
          json(postCreate(fix.accessToken(), accountId, null, nextIp())).get("id").asText());

      // Review via Phase 6 PATCH.
      assertEquals(200, patchRequest(fix.accessToken(), id).getResponse().getStatus());
      // Approve via Phase 6 approval.
      mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
          .andExpect(status().isOk());
      assertEquals(0, sink.sent().size(), "approval must not send");

      // Confirm via Phase 7 dispatch.
      MvcResult dispatched = mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + id + "/dispatch", fix.accessToken()))
          .andReturn();
      assertEquals(200, dispatched.getResponse().getStatus());
      assertEquals("ACCEPTED", json(dispatched).get("status").asText());
      assertEquals(1, sink.sent().size());
      assertEquals("privacy@" + slug + ".test", sink.sent().get(0).to());

      // Duplicate confirmation does not duplicate the send.
      MvcResult repeat = mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + id + "/dispatch", fix.accessToken()))
          .andReturn();
      assertEquals(json(dispatched).get("dispatchId").asText(),
          json(repeat).get("dispatchId").asText());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  private MvcResult patchRequest(String token, UUID id) throws Exception {
    return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/privacy-requests/" + id)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content("{}")
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  @Test
  void manualRouteCannotDispatch() throws Exception {
    String slug = "md-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "PRIVACY_PORTAL", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delmanual", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      UUID id = UUID.fromString(json(
          postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp())).get("id").asText());
      patchRequest(fix.accessToken(), id);
      mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
          .andExpect(status().isOk());

      MvcResult result = mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + id + "/dispatch", fix.accessToken()))
          .andReturn();
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString()
          .contains("MANUAL_REVIEW_REQUIRED"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void storedContentImmuneToTemplateAndRouteChanges() throws Exception {
    String slug = "im-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delimmune", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      UUID id = UUID.fromString(json(
          postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp())).get("id").asText());
      String bodyBefore = json(mockMvc.perform(
          authedGet("/api/v1/deletion-requests/" + id, fix.accessToken()))
          .andReturn()).get("body").asText();

      // Material route change after generation.
      ServiceDirectory entry = directory.findFirstBySlugIgnoreCase(slug).orElseThrow();
      entry.setRouteType("PRIVACY_PORTAL");
      directory.saveAndFlush(entry);

      JsonNode after = json(mockMvc.perform(
          authedGet("/api/v1/deletion-requests/" + id, fix.accessToken()))
          .andReturn());
      assertEquals(bodyBefore, after.get("body").asText());
      assertEquals("privacy-request-v1", after.get("templateId").asText());
      assertEquals(1, after.get("templateVersion").asInt());
      // Stored provenance stands, but the live route change forces review.
      assertEquals("EMAIL", after.get("deletionMethod").asText());
      assertTrue(after.get("manualReviewRequired").asBoolean());
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void editInvalidatesApprovalViaPhase6() throws Exception {
    ScanFixture fix = scannedDomain("deledit", "welcome@spotify.com", "Welcome to Spotify");
    UUID id = UUID.fromString(json(
        postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp())).get("id").asText());
    patchRequest(fix.accessToken(), id);
    mockMvc.perform(authedPost(
        "/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
        .andExpect(status().isOk());

    MvcResult edited = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/privacy-requests/" + id)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(Map.of("body", "Edited text. Thank you.")))
        .header("Authorization", "Bearer " + fix.accessToken())).andReturn();
    assertEquals("READY_FOR_REVIEW", json(edited).get("status").asText());

    JsonNode viewed = json(mockMvc.perform(
        authedGet("/api/v1/deletion-requests/" + id, fix.accessToken())).andReturn());
    assertEquals("READY_FOR_REVIEW", viewed.get("status").asText());
    assertEquals(3, viewed.get("version").asInt());
  }

  // -- audit -----------------------------------------------------------------------------------

  @Test
  void auditEventsCreatedWithoutSecrets() throws Exception {
    String slug = "au-" + UUID.randomUUID().toString().substring(0, 8);
    routedService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = scannedDomain("delaudit", "no-reply@" + slug + ".test",
        "Welcome! Please verify");
    try {
      postPreview(fix.accessToken(), accountIdOf(fix), null);
      postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp());

      List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
          .map(com.ghostclean.audit.AuditLog::getEvent).toList();
      assertTrue(events.contains(AuditEvents.DELETION_REQUEST_PREVIEWED));
      assertTrue(events.contains(AuditEvents.DELETION_REQUEST_GENERATED));

      String serialized = objectMapper.writeValueAsString(
          audits.findByUserIdOrderByIdAsc(fix.userId()));
      assertFalse(serialized.contains("acc-"));
      assertFalse(serialized.contains("ref-"));
      assertFalse(serialized.contains("secure channel"));
    } finally {
      cleanupRouted(slug, fix.userId());
    }
  }

  @Test
  void manualReviewAuditEmitted() throws Exception {
    ScanFixture fix = scannedDomain("delman", "welcome@spotify.com", "Welcome to Spotify");
    postCreate(fix.accessToken(), accountIdOf(fix), null, nextIp());
    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.DELETION_REQUEST_MANUAL_REVIEW_REQUIRED));
  }
}
