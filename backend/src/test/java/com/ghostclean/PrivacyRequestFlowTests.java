package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.privacy.PrivacyRequest;
import com.ghostclean.privacy.PrivacyRequestRepository;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 6 privacy-request flow tests on synthetic data only. Gate 1
 * (detection review) is Phase 4's endpoint; these tests own gate 2 —
 * draft, review, approval, cancellation, expiry, dedup — and prove nothing
 * is ever sent anywhere.
 */
class PrivacyRequestFlowTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

  @Autowired
  private AuditLogRepository audits;

  @Autowired
  private ServiceDirectoryService directoryService;

  private ScanFixture scannedSpotify(String tag) throws Exception {
    ScanFixture fix = connectedUser(tag);
    fakeGmail.scriptPages(List.of(tag + "-m1"));
    fakeGmail.addMessage(tag + "-m1", "welcome@spotify.com",
        "Welcome to Spotify", NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    return fix;
  }

  private UUID accountIdOf(ScanFixture fix) {
    return discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).get(0).getId();
  }

  private MvcResult postRequest(String token, UUID accountId, Map<String, String> extra,
      String ip) throws Exception {
    var body = new java.util.HashMap<String, String>();
    body.put("discoveredServiceId", accountId.toString());
    if (extra != null) {
      body.putAll(extra);
    }
    return mockMvc.perform(postJson("/api/v1/privacy-requests", body, ip)
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private MvcResult patchRequest(String token, UUID id, Map<String, String> body)
      throws Exception {
    return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/privacy-requests/" + id)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body == null ? Map.of() : body))
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  // -- creation -----------------------------------------------------------------------

  @Test
  void createPrivacyRequest() throws Exception {
    ScanFixture fix = scannedSpotify("prcreate");
    MvcResult result = postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp());

    assertEquals(200, result.getResponse().getStatus());
    JsonNode dto = json(result);
    assertEquals("DRAFT", dto.get("status").asText());
    assertEquals(1, dto.get("version").asInt());
    assertEquals("GENERAL_PRIVACY_REQUEST", dto.get("requestType").asText());
    assertFalse(dto.get("subject").asText().isBlank());
    assertFalse(dto.get("body").asText().isBlank());
    assertNotNull(dto.get("expiresAt"));
  }

  @Test
  void authenticatedUserRequired() throws Exception {
    mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(authedGet("/api/v1/privacy-requests", "not-a-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unverifiedUserCannotCreate() throws Exception {
    String email = "pr-unver-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    MvcResult result = postRequest(tokens.accessToken(), UUID.randomUUID(), null, nextIp());
    assertEquals(403, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void foreignAccountRejected() throws Exception {
    ScanFixture owner = scannedSpotify("prowner");
    ScanFixture attacker = connectedUser("prattacker");
    MvcResult result = postRequest(
        attacker.accessToken(), accountIdOf(owner), null, nextIp());
    assertEquals(404, result.getResponse().getStatus());
  }

  @Test
  void missingAccountRejected() throws Exception {
    ScanFixture fix = connectedUser("prmissing");
    MvcResult result = postRequest(fix.accessToken(), UUID.randomUUID(), null, nextIp());
    assertEquals(404, result.getResponse().getStatus());
  }

  @Test
  void serviceResolvesCorrectly() throws Exception {
    ScanFixture fix = scannedSpotify("prservice");
    JsonNode dto = json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()));
    assertEquals("Spotify", dto.get("service").asText());
    assertEquals("spotify", dto.get("domain").asText());
    assertEquals("media", dto.get("category").asText());
  }

  @Test
  void requestNeverAutomaticallyApproved() throws Exception {
    ScanFixture fix = scannedSpotify("prauto");
    for (int i = 0; i < 3; i++) {
      JsonNode dto = json(postRequest(fix.accessToken(), accountIdOf(fix),
          Map.of("requestType", "DELETE_PERSONAL_DATA"), nextIp()));
      assertEquals("DRAFT", dto.get("status").asText());
      assertTrue(dto.get("approvedAt").isNull());
    }
  }

  @Test
  void generatedContentCarriesNoSecretsOrBodies() throws Exception {
    ScanFixture fix = scannedSpotify("prclean");
    String body = postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp())
        .getResponse().getContentAsString();
    assertFalse(body.contains("Welcome to Spotify"), "mailbox content leak");
    assertFalse(body.contains("acc-"), "token leak");
    assertFalse(body.contains("ref-"), "token leak");
    assertFalse(body.contains("Bearer"), "auth leak");
    assertFalse(body.contains("legally required"), "absolute legal claim");
    assertFalse(body.contains("guarantee"), "absolute legal claim");
    assertFalse(body.contains("immediately"), "invented deadline");
    assertTrue(body.contains("applicable privacy"));
  }

  @Test
  void invalidTypeAndJurisdictionRejected() throws Exception {
    ScanFixture fix = scannedSpotify("prinvalid");
    UUID accountId = accountIdOf(fix);
    assertEquals(400, postRequest(fix.accessToken(), accountId,
        Map.of("requestType", "ERASE_EVERYTHING"), nextIp()).getResponse().getStatus());
    assertEquals(400, postRequest(fix.accessToken(), accountId,
        Map.of("jurisdiction", "MARS"), nextIp()).getResponse().getStatus());
  }

  @Test
  void missingAccountIdRejected() throws Exception {
    ScanFixture fix = connectedUser("prnoid");
    MvcResult result = mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("requestType", "DELETE_PERSONAL_DATA"), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())).andReturn();
    assertEquals(400, result.getResponse().getStatus());
  }

  // -- review / edit ---------------------------------------------------------------------

  @Test
  void ownerCanViewAndList() throws Exception {
    ScanFixture fix = scannedSpotify("prview");
    UUID created = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    mockMvc.perform(authedGet("/api/v1/privacy-requests/" + created, fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));
    MvcResult list = mockMvc.perform(
        authedGet("/api/v1/privacy-requests", fix.accessToken()))
        .andExpect(status().isOk()).andReturn();
    assertEquals(1, json(list).size());
  }

  @Test
  void nonOwnerCannotViewEditApproveOrCancel() throws Exception {
    ScanFixture owner = scannedSpotify("prvictim");
    ScanFixture attacker = connectedUser("printruder");
    UUID id = UUID.fromString(
        json(postRequest(owner.accessToken(), accountIdOf(owner), null, nextIp()))
            .get("id").asText());

    mockMvc.perform(authedGet("/api/v1/privacy-requests/" + id, attacker.accessToken()))
        .andExpect(status().isNotFound());
    assertEquals(404, patchRequest(attacker.accessToken(), id,
        Map.of("subject", "x")).getResponse().getStatus());
    assertEquals(404, mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", attacker.accessToken()))
        .andReturn().getResponse().getStatus());
    assertEquals(404, mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/cancel", attacker.accessToken()))
        .andReturn().getResponse().getStatus());
  }

  @Test
  void ownerCanEditDraftIntoReview() throws Exception {
    ScanFixture fix = scannedSpotify("predit");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    JsonNode edited = json(patchRequest(fix.accessToken(), id,
        Map.of("subject", "Privacy Request - updated subject",
            "body", "Hello, please delete my data. Thank you.")));
    assertEquals("READY_FOR_REVIEW", edited.get("status").asText());
    assertEquals(2, edited.get("version").asInt());
    assertEquals("Privacy Request - updated subject", edited.get("subject").asText());
  }

  @Test
  void emptyPatchMarksReviewed() throws Exception {
    ScanFixture fix = scannedSpotify("prempty");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    JsonNode marked = json(patchRequest(fix.accessToken(), id, Map.of()));
    assertEquals("READY_FOR_REVIEW", marked.get("status").asText());
    assertEquals(2, marked.get("version").asInt());
  }

  @Test
  void unsafeAndOversizeContentRejected() throws Exception {
    ScanFixture fix = scannedSpotify("prxss");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    assertEquals(400, patchRequest(fix.accessToken(), id,
        Map.of("body", "<script>alert(1)</script>")).getResponse().getStatus());
    assertEquals(400, patchRequest(fix.accessToken(), id,
        Map.of("body", "see javascript:alert(1)")).getResponse().getStatus());
    assertEquals(400, patchRequest(fix.accessToken(), id,
        Map.of("body", "x".repeat(9000))).getResponse().getStatus());
    assertEquals(400, patchRequest(fix.accessToken(), id,
        Map.of("subject", "   ")).getResponse().getStatus());
  }

  // -- approval ------------------------------------------------------------------------------

  @Test
  void approvalRequiresReadyForReview() throws Exception {
    ScanFixture fix = scannedSpotify("prapprove404");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    MvcResult result = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
        .andReturn();
    assertEquals(409, result.getResponse().getStatus());
  }

  @Test
  void approvalHappyPathRecordsTimestamp() throws Exception {
    ScanFixture fix = scannedSpotify("prok");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    int version = json(patchRequest(fix.accessToken(), id, Map.of()))
        .get("version").asInt();

    MvcResult approved = mockMvc.perform(postJson(
        "/api/v1/privacy-requests/" + id + "/approve",
        Map.of("version", String.valueOf(version)), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())).andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    JsonNode dto = json(approved);
    assertEquals("APPROVED", dto.get("status").asText());
    assertEquals(version, dto.get("approvedVersion").asInt());
    assertFalse(dto.get("approvedAt").isNull());
  }

  @Test
  void approvalVersionMismatchRejected() throws Exception {
    ScanFixture fix = scannedSpotify("prstale");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    patchRequest(fix.accessToken(), id, Map.of());

    MvcResult result = mockMvc.perform(postJson(
        "/api/v1/privacy-requests/" + id + "/approve",
        Map.of("version", "1"), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())).andReturn();
    assertEquals(409, result.getResponse().getStatus());
  }

  @Test
  void editAfterApprovalRequiresReapproval() throws Exception {
    ScanFixture fix = scannedSpotify("prreapprove");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    patchRequest(fix.accessToken(), id, Map.of());
    int approvedVersion = json(mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
        .andReturn()).get("version").asInt();

    JsonNode edited = json(patchRequest(fix.accessToken(), id,
        Map.of("body", "Hello, updated request text. Thank you.")));
    assertEquals("READY_FOR_REVIEW", edited.get("status").asText());
    assertEquals(approvedVersion + 1, edited.get("version").asInt());
    assertTrue(edited.get("approvedAt").isNull());

    MvcResult reapproved = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
        .andReturn();
    assertEquals(200, reapproved.getResponse().getStatus());
    assertEquals("APPROVED", json(reapproved).get("status").asText());
    assertEquals(approvedVersion + 1, json(reapproved).get("approvedVersion").asInt());
  }

  // -- cancellation / expiry --------------------------------------------------------------------

  @Test
  void cancellationWorksAndIsIdempotent() throws Exception {
    ScanFixture fix = scannedSpotify("prcancel");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());

    MvcResult first = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/cancel", fix.accessToken()))
        .andReturn();
    assertEquals(200, first.getResponse().getStatus());
    assertEquals("CANCELLED", json(first).get("status").asText());

    MvcResult second = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/cancel", fix.accessToken()))
        .andReturn();
    assertEquals(200, second.getResponse().getStatus());
    assertEquals("CANCELLED", json(second).get("status").asText());
  }

  @Test
  void approvedCanBeCancelled() throws Exception {
    ScanFixture fix = scannedSpotify("prcancelappr");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    patchRequest(fix.accessToken(), id, Map.of());
    mockMvc.perform(authedPost(
        "/api/v1/privacy-requests/" + id + "/approve", fix.accessToken())).andReturn();

    MvcResult cancelled = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/cancel", fix.accessToken()))
        .andReturn();
    assertEquals(200, cancelled.getResponse().getStatus());
    assertEquals("CANCELLED", json(cancelled).get("status").asText());
  }

  @Test
  void expiredRequestCannotBeApproved() throws Exception {
    ScanFixture fix = scannedSpotify("prexpire");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    patchRequest(fix.accessToken(), id, Map.of());

    PrivacyRequest row = privacyRequests.findById(id).orElseThrow();
    row.setExpiresAt(Instant.now().minusSeconds(60));
    privacyRequests.saveAndFlush(row);

    MvcResult result = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + id + "/approve", fix.accessToken()))
        .andReturn();
    assertEquals(410, result.getResponse().getStatus());
    assertEquals("EXPIRED", json(mockMvc.perform(
        authedGet("/api/v1/privacy-requests/" + id, fix.accessToken()))
        .andReturn()).get("status").asText());
  }

  // -- duplicates / idempotency / rate limits ---------------------------------------------------------

  @Test
  void duplicateRequestsReturnExisting() throws Exception {
    ScanFixture fix = scannedSpotify("prdupe");
    UUID accountId = accountIdOf(fix);

    JsonNode first = json(postRequest(fix.accessToken(), accountId,
        Map.of("requestType", "DELETE_PERSONAL_DATA"), nextIp()));
    JsonNode second = json(postRequest(fix.accessToken(), accountId,
        Map.of("requestType", "DELETE_PERSONAL_DATA"), nextIp()));
    assertEquals(first.get("id").asText(), second.get("id").asText());
    assertEquals(1, privacyRequests.findAll().stream()
        .filter(row -> row.getUser().getId().equals(fix.userId())
            && row.getDiscoveredServiceId().equals(accountId))
        .count());
  }

  @Test
  void idempotencyKeyReturnsExisting() throws Exception {
    ScanFixture fix = scannedSpotify("pridem");
    UUID accountId = accountIdOf(fix);
    String key = "pr-key-" + UUID.randomUUID();
    String ip = nextIp();

    MvcResult first = mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", accountId.toString()), ip)
        .header("Authorization", "Bearer " + fix.accessToken())
        .header("Idempotency-Key", key)).andReturn();
    MvcResult second = mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", accountId.toString()), ip)
        .header("Authorization", "Bearer " + fix.accessToken())
        .header("Idempotency-Key", key)).andReturn();
    assertEquals(json(first).get("id").asText(), json(second).get("id").asText());
  }

  @Test
  void requestCreationRateLimited() throws Exception {
    ScanFixture fix = scannedSpotify("prspam");
    UUID accountId = accountIdOf(fix);
    for (int i = 0; i < 10; i++) {
      assertEquals(200,
          postRequest(fix.accessToken(), accountId, null, nextIp())
              .getResponse().getStatus());
    }
    MvcResult limited = postRequest(fix.accessToken(), accountId, null, nextIp());
    assertEquals(429, limited.getResponse().getStatus());
    assertTrue(limited.getResponse().getContentAsString().contains("RATE_LIMITED"));
  }

  // -- route-aware types -----------------------------------------------------------------------------------

  @Test
  void deleteAccountCoercedWithoutTrustedRoute() throws Exception {
    ScanFixture fix = scannedSpotify("prcoerce");
    JsonNode dto = json(postRequest(fix.accessToken(), accountIdOf(fix),
        Map.of("requestType", "DELETE_ACCOUNT"), nextIp()));
    // Seed routes are UNKNOWN → no account-deletion claim is stored.
    assertEquals("GENERAL_PRIVACY_REQUEST", dto.get("requestType").asText());
  }

  @Test
  void deleteAccountKeptWithTrustedRoute() throws Exception {
    String slug = "dac-" + UUID.randomUUID().toString().substring(0, 8);
    String domain = slug + ".test";
    ServiceDirectory entry = new ServiceDirectory(slug, "Synthetic " + slug);
    entry.setDomains(new String[]{domain});
    entry.setAliases(new String[]{"no-reply@" + domain});
    entry.setCategory("shopping");
    entry.setRouteType("SELF_SERVICE_ACCOUNT_DELETE");
    entry.setRequestMethod("ACCOUNT_SETTINGS");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://" + domain + "/delete");
    entry.setSourceType("OFFICIAL");
    entry.setVerificationStatus("VERIFIED");
    entry.setStatus("active");
    directory.save(entry);
    directoryService.refresh();
    try {
      ScanFixture fix = connectedUser("prtrusted");
      fakeGmail.scriptPages(List.of("t9"));
      fakeGmail.addMessage("t9", "no-reply@" + domain,
          "Welcome! Please verify your account", NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());
      UUID accountId = accountIdOf(fix);

      JsonNode dto = json(postRequest(fix.accessToken(), accountId,
          Map.of("requestType", "DELETE_ACCOUNT"), nextIp()));
      assertEquals("DELETE_ACCOUNT", dto.get("requestType").asText());
    } finally {
      try {
        directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
      } catch (RuntimeException ignored) {
        // Best-effort cleanup.
      }
      directoryService.refresh();
    }
  }

  // -- audit ------------------------------------------------------------------------------------------------------

  @Test
  void auditEventsCreatedWithoutSensitiveContent() throws Exception {
    ScanFixture fix = scannedSpotify("praudit");
    UUID id = UUID.fromString(
        json(postRequest(fix.accessToken(), accountIdOf(fix), null, nextIp()))
            .get("id").asText());
    patchRequest(fix.accessToken(), id, Map.of());
    mockMvc.perform(authedPost(
        "/api/v1/privacy-requests/" + id + "/approve", fix.accessToken())).andReturn();

    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_CREATED));
    assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_EDITED));
    assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_APPROVED));

    String serialized = objectMapper.writeValueAsString(
        audits.findByUserIdOrderByIdAsc(fix.userId()));
    assertFalse(serialized.contains("Welcome to Spotify"));
    assertFalse(serialized.contains("acc-"));
    assertFalse(serialized.contains("ref-"));
    assertFalse(serialized.contains("delete my data"));
  }
}
