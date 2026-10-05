package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.privacy.PrivacyRequest;
import com.ghostclean.privacy.PrivacyRequestRepository;
import com.ghostclean.privacy.SinkOutboundMailProvider;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 7 dispatch flow tests on synthetic data only. The sink provider
 * records instead of sending, so the full gate-2 workflow — eligibility,
 * idempotency, retry, audit — is proven without contacting any external
 * service. No real email is ever sent by these tests.
 */
class PrivacyDispatchFlowTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private SinkOutboundMailProvider sink;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

  @Autowired
  private com.ghostclean.privacy.PrivacyDispatchRepository dispatches;

  @Autowired
  private AuditLogRepository audits;

  @Autowired
  private ServiceDirectoryService directoryService;

  @BeforeEach
  void resetSink() {
    sink.reset();
  }

  private record ApprovedSetup(ScanFixture fix, UUID accountId, UUID requestId, String slug) {
  }

  /** Synthetic EMAIL_REQUEST service with a verified destination; caller cleans up. */
  private ServiceDirectory emailService(String slug, String routeType, String verification) {
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
    entry.setJurisdiction(new String[]{"GDPR_RELEVANT"});
    entry.setManualReviewRequired(false);
    entry.setPrivacyEmail("privacy@" + domain);
    entry.setStatus("active");
    return directory.save(entry);
  }

  private void cleanupSetup(ApprovedSetup setup) {
    try {
      dispatches.findByRequest_Id(setup.requestId()).ifPresent(dispatches::delete);
      privacyRequests.deleteById(setup.requestId());
      discovered.deleteById(setup.accountId());
      directory.findFirstBySlugIgnoreCase(setup.slug()).ifPresent(directory::delete);
    } catch (RuntimeException ignored) {
      // Best-effort cleanup; assertions already ran.
    }
    directoryService.refresh();
  }

  private void cleanupSetup(String slug, UUID userId) {
    try {
      for (var account : discovered.findByUser_IdOrderByConfidenceDesc(userId)) {
        privacyRequests.findAll().stream()
            .filter(row -> row.getDiscoveredServiceId().equals(account.getId()))
            .forEach(row -> {
              dispatches.findByRequest_Id(row.getId()).ifPresent(dispatches::delete);
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

  private ApprovedSetup approvedEmailSetup(String tag) throws Exception {
    String slug = "em-" + UUID.randomUUID().toString().substring(0, 8);
    emailService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    try {
      String domain = slug + ".test";
      ScanFixture fix = connectedUser(tag);
      fakeGmail.scriptPages(List.of(tag + "-w1"));
      fakeGmail.addMessage(tag + "-w1", "no-reply@" + domain,
          "Welcome! Please verify your account", NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());
      UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
          .get(0).getId();
      UUID requestId = approveRequest(fix.accessToken(), accountId);
      return new ApprovedSetup(fix, accountId, requestId, slug);
    } catch (RuntimeException | AssertionError ex) {
      directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
      directoryService.refresh();
      throw ex;
    }
  }

  private UUID approveRequest(String token, UUID accountId) throws Exception {
    UUID requestId = UUID.fromString(json(postRequest(token, accountId)).get("id").asText());
    MvcResult patched = patchRequest(token, requestId, Map.of());
    assertEquals(200, patched.getResponse().getStatus());
    MvcResult approved = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + requestId + "/approve", token))
        .andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    return requestId;
  }

  private MvcResult postRequest(String token, UUID accountId) throws Exception {
    return mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
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

  private MvcResult postDispatch(String token, UUID requestId, Map<String, String> body)
      throws Exception {
    return mockMvc.perform(postJson("/api/v1/privacy-requests/" + requestId + "/dispatch",
        body == null ? Map.of() : body, nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  private ApprovedSetup approvedSpotifySetup(String tag) throws Exception {
    ScanFixture fix = connectedUser(tag);
    fakeGmail.scriptPages(List.of(tag + "-s1"));
    fakeGmail.addMessage(tag + "-s1", "welcome@spotify.com",
        "Welcome to Spotify", NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    UUID requestId = approveRequest(fix.accessToken(), accountId);
    return new ApprovedSetup(fix, accountId, requestId, "spotify");
  }

  // -- authentication / ownership -----------------------------------------------------------

  @Test
  void unauthenticatedDispatchRejected() throws Exception {
    mockMvc.perform(postJson("/api/v1/privacy-requests/" + UUID.randomUUID() + "/dispatch",
        Map.of())).andExpect(status().isUnauthorized());
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .get("/api/v1/privacy-requests/" + UUID.randomUUID() + "/dispatch"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void crossUserDispatchRejected() throws Exception {
    ApprovedSetup owner = approvedEmailSetup("dxowner");
    try {
      ScanFixture attacker = connectedUser("dxattacker");
      assertEquals(404, postDispatch(
          attacker.accessToken(), owner.requestId(), null).getResponse().getStatus());
      mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + owner.requestId() + "/dispatch",
          attacker.accessToken())).andExpect(status().isNotFound());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(owner);
    }
  }

  // -- state gates -------------------------------------------------------------------------------

  @Test
  void draftCannotDispatch() throws Exception {
    ScanFixture fix = connectedUser("dxdraft");
    fakeGmail.scriptPages(List.of("dd1"));
    fakeGmail.addMessage("dd1", "welcome@spotify.com", "Welcome to Spotify",
        NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    UUID requestId = UUID.fromString(
        json(postRequest(fix.accessToken(), accountId)).get("id").asText());

    assertEquals(409, postDispatch(fix.accessToken(), requestId, null)
        .getResponse().getStatus());
    assertEquals(0, sink.sent().size());
  }

  @Test
  void readyForReviewCannotDispatch() throws Exception {
    ScanFixture fix = connectedUser("dxready");
    fakeGmail.scriptPages(List.of("dr1"));
    fakeGmail.addMessage("dr1", "welcome@spotify.com", "Welcome to Spotify",
        NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    UUID requestId = UUID.fromString(
        json(postRequest(fix.accessToken(), accountId)).get("id").asText());
    patchRequest(fix.accessToken(), requestId, Map.of());

    assertEquals(409, postDispatch(fix.accessToken(), requestId, null)
        .getResponse().getStatus());
    assertEquals(0, sink.sent().size());
  }

  @Test
  void approvedRequestCanDispatch() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxhappy");
    try {
      MvcResult result = postDispatch(setup.fix().accessToken(), setup.requestId(), null);
      assertEquals(200, result.getResponse().getStatus());
      JsonNode dto = json(result);
      assertEquals("ACCEPTED", dto.get("status").asText());
      assertEquals("sink", dto.get("provider").asText());
      assertEquals(1, dto.get("attempts").asInt());
      assertTrue(dto.get("providerReference").asText().startsWith("sink-"));
      assertFalse(dto.get("acceptedAt").isNull());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void cancelledRequestCannotDispatch() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxcancelled");
    try {
      mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + setup.requestId() + "/cancel",
          setup.fix().accessToken())).andExpect(status().isOk());
      assertEquals(409, postDispatch(
          setup.fix().accessToken(), setup.requestId(), null)
          .getResponse().getStatus());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void expiredRequestCannotDispatch() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxexpired");
    try {
      PrivacyRequest row = privacyRequests.findById(setup.requestId()).orElseThrow();
      row.setExpiresAt(Instant.now().minusSeconds(60));
      privacyRequests.saveAndFlush(row);

      MvcResult result = postDispatch(setup.fix().accessToken(), setup.requestId(), null);
      assertEquals(410, result.getResponse().getStatus());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- route gates -----------------------------------------------------------------------------------

  @Test
  void inactiveServiceCannotDispatch() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxinactive");
    String slug = setup.slug();
    try {
      ServiceDirectory entry = directory.findFirstBySlugIgnoreCase(slug).orElseThrow();
      entry.setStatus("deprecated");
      directory.saveAndFlush(entry);

      assertEquals(409, postDispatch(
          setup.fix().accessToken(), setup.requestId(), null)
          .getResponse().getStatus());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void unverifiedRouteCannotDispatch() throws Exception {
    String slug = "uv-" + UUID.randomUUID().toString().substring(0, 8);
    emailService(slug, "EMAIL_REQUEST", "UNVERIFIED");
    directoryService.refresh();
    ScanFixture fix = null;
    try {
      String domain = slug + ".test";
      fix = connectedUser("dxunverified");
      fakeGmail.scriptPages(List.of("du1"));
      fakeGmail.addMessage("du1", "no-reply@" + domain, "Welcome! Verify now",
          NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());
      UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
          .get(0).getId();
      UUID requestId = approveRequest(fix.accessToken(), accountId);

      MvcResult result = postDispatch(fix.accessToken(), requestId, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("DESTINATION_UNKNOWN"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(slug, fix.userId());
    }
  }

  @Test
  void nonEmailRouteCannotDispatch() throws Exception {
    String slug = "ne-" + UUID.randomUUID().toString().substring(0, 8);
    emailService(slug, "PRIVACY_PORTAL", "VERIFIED");
    directoryService.refresh();
    ScanFixture fix = null;
    try {
      String domain = slug + ".test";
      fix = connectedUser("dxnonemail");
      fakeGmail.scriptPages(List.of("dn1"));
      fakeGmail.addMessage("dn1", "no-reply@" + domain, "Welcome! Verify now",
          NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());
      UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
          .get(0).getId();
      UUID requestId = approveRequest(fix.accessToken(), accountId);

      MvcResult result = postDispatch(fix.accessToken(), requestId, null);
      assertEquals(409, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString()
          .contains("MANUAL_REVIEW_REQUIRED"));
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(slug, fix.userId());
    }
  }

  @Test
  void unknownDestinationCannotDispatch() throws Exception {
    ApprovedSetup setup = approvedSpotifySetup("dxunknown");
    MvcResult result = postDispatch(setup.fix().accessToken(), setup.requestId(), null);
    assertEquals(409, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("DESTINATION_UNKNOWN"));
    assertEquals(0, sink.sent().size());
  }

  // -- destination + confirmation ------------------------------------------------------------------------

  @Test
  void trustedDestinationResolvedServerSide() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxdest");
    String slug = setup.slug();
    try {
      String expected = "privacy@" + slug + ".test";
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals(expected, dto.get("destination").asText());
      assertEquals(expected, sink.sent().get(0).to());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void frontendCannotOverrideDestination() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxoverride");
    String slug = setup.slug();
    try {
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(),
          Map.of("recipient", "attacker@evil.test", "to", "attacker@evil.test",
              "destination", "attacker@evil.test")));
      assertEquals("privacy@" + slug + ".test", dto.get("destination").asText());
      assertEquals("privacy@" + slug + ".test", sink.sent().get(0).to());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void finalConfirmationRequired() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxconfirm");
    try {
      // Approval alone sends nothing and records no dispatch.
      assertEquals(0, sink.sent().size());
      mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch",
          setup.fix().accessToken())).andExpect(status().isNotFound());

      MvcResult result = postDispatch(setup.fix().accessToken(), setup.requestId(), null);
      assertEquals(200, result.getResponse().getStatus());
      assertEquals(1, sink.sent().size());
      mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch",
          setup.fix().accessToken()))
          .andExpect(status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
              .jsonPath("$.status").value("ACCEPTED"));
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void dispatchUsesApprovedVersion() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxversion");
    try {
      JsonNode request = json(mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId(), setup.fix().accessToken()))
          .andReturn());
      JsonNode dispatch = json(
          postDispatch(setup.fix().accessToken(), setup.requestId(), null));

      assertEquals(request.get("version").asInt(), dispatch.get("requestVersion").asInt());
      assertEquals(request.get("subject").asText(), sink.sent().get(0).subject());
      assertEquals(request.get("body").asText(), sink.sent().get(0).body());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void changedVersionInvalidatesApproval() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxstale");
    try {
      // Edit after approval returns the request to READY_FOR_REVIEW.
      patchRequest(setup.fix().accessToken(), setup.requestId(),
          Map.of("body", "Hello, updated text. Thank you."));
      assertEquals(409, postDispatch(
          setup.fix().accessToken(), setup.requestId(), null)
          .getResponse().getStatus());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- idempotency ---------------------------------------------------------------------------------------------

  @Test
  void idempotencyKeyPreventsDuplicateSend() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxidem");
    try {
      String key = "dx-key-" + UUID.randomUUID();
      String ip = nextIp();
      MvcResult first = mockMvc.perform(postJson(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch", Map.of(), ip)
          .header("Authorization", "Bearer " + setup.fix().accessToken())
          .header("Idempotency-Key", key)).andReturn();
      MvcResult second = mockMvc.perform(postJson(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch", Map.of(), ip)
          .header("Authorization", "Bearer " + setup.fix().accessToken())
          .header("Idempotency-Key", key)).andReturn();

      assertEquals(200, first.getResponse().getStatus());
      assertEquals(json(first).get("dispatchId").asText(),
          json(second).get("dispatchId").asText());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void repeatedDispatchReturnsRecordedOutcome() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxrepeat");
    try {
      JsonNode first = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      JsonNode second = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals(first.get("dispatchId").asText(), second.get("dispatchId").asText());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- provider outcomes ------------------------------------------------------------------------------------------

  @Test
  void providerAcceptanceRecorded() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxaccept");
    try {
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals("ACCEPTED", dto.get("status").asText());
      assertFalse(dto.get("acceptedAt").isNull());
      assertTrue(dto.get("providerReference").asText().startsWith("sink-"));
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void providerFailureRecorded() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxfail");
    try {
      sink.failNextPermanent("MESSAGE_REJECTED");
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals("FAILED", dto.get("status").asText());
      assertEquals("MESSAGE_REJECTED", dto.get("failureCategory").asText());
      assertFalse(dto.get("failedAt").isNull());
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void transientFailureRetryIsBounded() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxretry");
    try {
      sink.failNextTemporary(2);
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals("ACCEPTED", dto.get("status").asText());
      assertEquals(3, dto.get("attempts").asInt());
      assertEquals(1, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void permanentFailureIsNotRetried() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxperm");
    try {
      sink.failNextPermanent("INVALID_DESTINATION");
      JsonNode dto = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals("FAILED", dto.get("status").asText());
      assertEquals(1, dto.get("attempts").asInt());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void dispatchCancellationWorks() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxcancel");
    try {
      sink.failNextPermanent("TEMPORARY_PROVIDER_ERROR");
      JsonNode failed = json(postDispatch(setup.fix().accessToken(), setup.requestId(), null));
      assertEquals("FAILED", failed.get("status").asText());

      MvcResult first = mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch/cancel",
          setup.fix().accessToken())).andReturn();
      assertEquals(200, first.getResponse().getStatus());
      assertEquals("CANCELLED", json(first).get("status").asText());

      MvcResult second = mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch/cancel",
          setup.fix().accessToken())).andReturn();
      assertEquals(200, second.getResponse().getStatus());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void acceptedDispatchCannotBeCancelled() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxnounsend");
    try {
      postDispatch(setup.fix().accessToken(), setup.requestId(), null);
      assertEquals(409, mockMvc.perform(authedPost(
          "/api/v1/privacy-requests/" + setup.requestId() + "/dispatch/cancel",
          setup.fix().accessToken())).andReturn().getResponse().getStatus());
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- rate limiting ---------------------------------------------------------------------------------------------------

  @Test
  void dispatchRateLimited() throws Exception {
    String slug = "rl-" + UUID.randomUUID().toString().substring(0, 8);
    emailService(slug, "EMAIL_REQUEST", "VERIFIED");
    directoryService.refresh();
    String email = "dx-spam-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens login = login(email, "Str0ng-Enough-Password", nextIp());
    User user = userRepository.findByEmail(email).orElseThrow();
    try {
      ServiceDirectory entry = directory.findFirstBySlugIgnoreCase(slug).orElseThrow();

      for (int i = 0; i < 10; i++) {
        var account = new com.ghostclean.account.DiscoveredService(
            user, "rl-svc-" + i, "Rate Svc " + i);
        account.setCategory("other");
        account.setServiceDirectory(entry);
        discovered.save(account);
        UUID requestId = approveDirect(login.accessToken(), account.getId());
        MvcResult dispatched = postDispatch(login.accessToken(), requestId, null);
        assertEquals(200, dispatched.getResponse().getStatus());
      }
      // The 11th dispatch attempt (a repeat on an accepted row still counts
      // as an attempt) hits the per-user budget.
      var first = discovered.findByUser_IdOrderByConfidenceDesc(user.getId()).get(0);
      UUID firstRequest = privacyRequests.findAll().stream()
          .filter(row -> row.getDiscoveredServiceId().equals(first.getId()))
          .findFirst().orElseThrow().getId();
      MvcResult limited = postDispatch(login.accessToken(), firstRequest, null);
      assertEquals(429, limited.getResponse().getStatus());
      assertTrue(limited.getResponse().getContentAsString().contains("RATE_LIMITED"));
    } finally {
      cleanupSetup(slug, user.getId());
    }
  }

  private UUID approveDirect(String token, UUID accountId) throws Exception {
    UUID requestId = UUID.fromString(json(postRequest(token, accountId)).get("id").asText());
    patchRequest(token, requestId, Map.of());
    MvcResult approved = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + requestId + "/approve", token)).andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    return requestId;
  }

  // -- audit + content safety -----------------------------------------------------------------------------------------------

  @Test
  void auditEventsCreatedWithoutSecrets() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxaudit");
    try {
      postDispatch(setup.fix().accessToken(), setup.requestId(), null);

      List<String> events = audits.findByUserIdOrderByIdAsc(setup.fix().userId()).stream()
          .map(com.ghostclean.audit.AuditLog::getEvent).toList();
      assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_DISPATCH_REQUESTED));
      assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_DISPATCH_ACCEPTED));

      String serialized = objectMapper.writeValueAsString(
          audits.findByUserIdOrderByIdAsc(setup.fix().userId()));
      assertFalse(serialized.contains("acc-"), "token leak");
      assertFalse(serialized.contains("ref-"), "token leak");
      assertFalse(serialized.contains("sink-"), "provider reference leak");
      assertFalse(serialized.contains("secure channel"), "content leak");
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void outboundMessageCarriesNoTokensBodiesOrAttachments() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxcontent");
    try {
      postDispatch(setup.fix().accessToken(), setup.requestId(), null);
      var message = sink.sent().get(0);
      assertFalse(message.body().contains("Welcome"), "mailbox content leak");
      assertFalse(message.body().contains("acc-"), "token leak");
      assertFalse(message.body().contains("ref-"), "token leak");
      assertFalse(message.body().contains("Bearer"), "auth leak");
      assertFalse(message.subject().contains("acc-"), "token leak");

      var components = java.util.Arrays.stream(
          com.ghostclean.privacy.OutboundMailProvider.OutboundMailMessage.class
              .getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
          .sorted().toList();
      assertEquals(List.of("body", "from", "subject", "to"), components);
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void approvalAloneNeverSends() throws Exception {
    ApprovedSetup setup = approvedEmailSetup("dxnosend");
    try {
      assertEquals(0, sink.sent().size());
    } finally {
      cleanupSetup(setup);
    }
  }
}
