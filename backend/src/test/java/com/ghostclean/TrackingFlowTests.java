package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.privacy.PrivacyDispatchRepository;
import com.ghostclean.privacy.PrivacyRequestRepository;
import com.ghostclean.privacy.PrivacyRequestResponseRepository;
import com.ghostclean.privacy.SinkOutboundMailProvider;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceDirectoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 9 tracking flow tests on synthetic data only. Deadlines anchor at
 * dispatch {@code accepted_at} (the V1 handoff timestamp — never proof of
 * external delivery); responses are manual user intake; evaluation writes
 * nothing. No scheduler, no sending, no external calls.
 */
class TrackingFlowTests extends ScanTestBase {

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  @Autowired
  private PrivacyRequestRepository privacyRequests;

  @Autowired
  private PrivacyDispatchRepository dispatches;

  @Autowired
  private PrivacyRequestResponseRepository responses;

  @Autowired
  private AuditLogRepository audits;

  @Autowired
  private ServiceDirectoryService directoryService;

  @Autowired
  private SinkOutboundMailProvider sink;

  @Autowired
  private com.ghostclean.user.UserSettingsRepository userSettings;

  @BeforeEach
  void resetSink() {
    sink.reset();
  }

  private record TrackedSetup(ScanFixture fix, UUID accountId, UUID requestId, String slug) {
  }

  private ServiceDirectory emailService(String slug) {
    String domain = slug + ".test";
    ServiceDirectory entry = new ServiceDirectory(slug, "Synthetic " + slug);
    entry.setDomains(new String[]{domain});
    entry.setAliases(new String[]{"no-reply@" + domain});
    entry.setCategory("shopping");
    entry.setRouteType("EMAIL_REQUEST");
    entry.setRequestMethod("EMAIL");
    entry.setAutomationStatus("MANUAL_ONLY");
    entry.setRouteConfidence("HIGH");
    entry.setRouteAvailability("KNOWN");
    entry.setSourceUrl("https://" + domain + "/contact");
    entry.setSourceType("OFFICIAL");
    entry.setVerificationStatus("VERIFIED");
    entry.setManualReviewRequired(false);
    entry.setPrivacyEmail("privacy@" + domain);
    entry.setStatus("active");
    return directory.save(entry);
  }

  private void cleanupSetup(TrackedSetup setup) {
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

  private TrackedSetup dispatchedSetup(String tag) throws Exception {
    String slug = "tr-" + UUID.randomUUID().toString().substring(0, 8);
    emailService(slug);
    directoryService.refresh();
    try {
      String domain = slug + ".test";
      ScanFixture fix = connectedUser(tag);
      fakeGmail.scriptPages(List.of(tag + "-t1"));
      fakeGmail.addMessage(tag + "-t1", "no-reply@" + domain,
          "Welcome! Please verify your account", NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());
      UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
          .get(0).getId();
      UUID requestId = approveRequest(fix.accessToken(), accountId);
      MvcResult dispatched = mockMvc.perform(
          authedPost("/api/v1/privacy-requests/" + requestId + "/dispatch",
              fix.accessToken())).andReturn();
      assertEquals(200, dispatched.getResponse().getStatus());
      return new TrackedSetup(fix, accountId, requestId, slug);
    } catch (RuntimeException | AssertionError ex) {
      directory.findFirstBySlugIgnoreCase(slug).ifPresent(directory::delete);
      directoryService.refresh();
      throw ex;
    }
  }

  private UUID approveRequest(String token, UUID accountId) throws Exception {
    MvcResult created = mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
    UUID requestId = UUID.fromString(json(created).get("id").asText());
    MvcResult patched = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .patch("/api/v1/privacy-requests/" + requestId)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content("{}")
        .header("Authorization", "Bearer " + token)).andReturn();
    assertEquals(200, patched.getResponse().getStatus());
    MvcResult approved = mockMvc.perform(
        authedPost("/api/v1/privacy-requests/" + requestId + "/approve", token)).andReturn();
    assertEquals(200, approved.getResponse().getStatus());
    return requestId;
  }

  private JsonNode tracking(String token, UUID requestId) throws Exception {
    return json(mockMvc.perform(
        authedGet("/api/v1/privacy-requests/" + requestId + "/tracking", token))
        .andExpect(status().isOk()).andReturn());
  }

  private MvcResult postResponse(String token, UUID requestId, Map<String, String> body)
      throws Exception {
    return mockMvc.perform(postJson(
        "/api/v1/privacy-requests/" + requestId + "/responses",
        body == null ? Map.of() : body, nextIp())
        .header("Authorization", "Bearer " + token)).andReturn();
  }

  // -- anchor + schema -----------------------------------------------------------------------------

  @Test
  void acceptedAtIsTheDeadlineAnchor() throws Exception {
    TrackedSetup setup = dispatchedSetup("tranchor");
    try {
      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("AWAITING_RESPONSE", view.get("trackingState").asText());
      assertEquals("accepted_at", view.get("deadlineSource").asText());
      assertTrue(view.get("deadlineNote").asText().contains("verify locally"));

      var dispatch = dispatches.findByRequest_Id(setup.requestId()).orElseThrow();
      Instant expected = dispatch.getAcceptedAt().plus(Duration.ofDays(30));
      assertEquals(expected.toString(), view.get("deadlineAt").asText());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void noFakeSentAtColumnsExist() {
    List<String> privacyColumns = jdbc.queryForList(
        "select column_name from information_schema.columns "
            + "where table_name = 'privacy_requests'", String.class).stream()
                .map(String::toLowerCase).toList();
    List<String> dispatchColumns = jdbc.queryForList(
        "select column_name from information_schema.columns "
            + "where table_name = 'privacy_request_dispatches'", String.class).stream()
                .map(String::toLowerCase).toList();
    List<String> responseColumns = jdbc.queryForList(
        "select column_name from information_schema.columns "
            + "where table_name = 'privacy_request_responses'", String.class).stream()
                .map(String::toLowerCase).toList();
    for (String columns : privacyColumns.stream().toList()) {
      assertFalse(columns.equals("sent_at"), "fabricated sent_at: privacy_requests");
      assertFalse(columns.equals("mark_sent_at"), "fabricated mark-sent: privacy_requests");
    }
    for (String columns : dispatchColumns.stream().toList()) {
      assertFalse(columns.equals("sent_at"), "fabricated sent_at: dispatches");
    }
    for (String columns : responseColumns.stream().toList()) {
      assertFalse(columns.equals("sent_at"), "fabricated sent_at: responses");
    }
    assertTrue(privacyColumns.contains("expires_at"));
    assertTrue(responseColumns.contains("outcome"));
  }

  @Test
  void noMarkSentEndpointExists() throws Exception {
    TrackedSetup setup = dispatchedSetup("trmark");
    try {
      // No mark-sent route exists: unmapped paths surface through the
      // existing generic error envelope (never 2xx — no action occurs).
      MvcResult get = mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/mark-sent",
          setup.fix().accessToken())).andReturn();
      assertTrue(get.getResponse().getStatus() >= 400, "no mark-sent action may succeed");
      MvcResult post = mockMvc.perform(postJson(
          "/api/v1/privacy-requests/" + setup.requestId() + "/mark-sent", Map.of(), nextIp())
          .header("Authorization", "Bearer " + setup.fix().accessToken())).andReturn();
      assertTrue(post.getResponse().getStatus() >= 400, "no mark-sent action may succeed");
      MvcResult deletion = mockMvc.perform(postJson(
          "/api/v1/deletion-requests/" + setup.requestId() + "/mark-sent", Map.of(), nextIp())
          .header("Authorization", "Bearer " + setup.fix().accessToken())).andReturn();
      assertTrue(deletion.getResponse().getStatus() >= 400, "no mark-sent action may succeed");
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- calculation via API ------------------------------------------------------------------------------

  @Test
  void trackingEvaluationIsDeterministicAndWriteFree() throws Exception {
    TrackedSetup setup = dispatchedSetup("trdet");
    try {
      String first = mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/tracking",
          setup.fix().accessToken())).andReturn()
          .getResponse().getContentAsString();
      long responsesBefore = responses.countByRequest_Id(setup.requestId());
      String second = mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/tracking",
          setup.fix().accessToken())).andReturn()
          .getResponse().getContentAsString();
      assertEquals(first, second);
      assertEquals(responsesBefore, responses.countByRequest_Id(setup.requestId()));
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void reminderDueAndNextReminderWork() throws Exception {
    TrackedSetup setup = dispatchedSetup("trdue");
    try {
      var dispatch = dispatches.findByRequest_Id(setup.requestId()).orElseThrow();
      dispatch.setAcceptedAt(Instant.now().minus(Duration.ofDays(31)));
      dispatches.saveAndFlush(dispatch);

      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("OVERDUE", view.get("trackingState").asText());
      assertTrue(view.get("remindersDue").toString().contains("OVERDUE"));
      assertTrue(view.get("nextReminderDue").isNull());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void upcomingReminderReportsNextDate() throws Exception {
    TrackedSetup setup = dispatchedSetup("trnext");
    try {
      var dispatch = dispatches.findByRequest_Id(setup.requestId()).orElseThrow();
      Instant accepted = Instant.now().minus(Duration.ofDays(20))
          .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      dispatch.setAcceptedAt(accepted);
      dispatches.saveAndFlush(dispatch);

      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("AWAITING_RESPONSE", view.get("trackingState").asText());
      assertTrue(view.get("remindersDue").size() == 0);
      Instant expectedNext = accepted.plus(Duration.ofDays(30)).minus(Duration.ofDays(7));
      assertEquals(expectedNext.toString(), view.get("nextReminderDue").asText());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void jurisdictionDeadlinesFollowConfiguredTargets() throws Exception {
    TrackedSetup setup = dispatchedSetup("trjur");
    try {
      // Default creation path uses GENERAL (30d); GDPR/CCPA math is covered
      // by ReminderCalculationTests with the same constants.
      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      var dispatch = dispatches.findByRequest_Id(setup.requestId()).orElseThrow();
      assertEquals(dispatch.getAcceptedAt().plus(Duration.ofDays(30)).toString(),
          view.get("deadlineAt").asText());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void manualRouteHasNoFabricatedDeadline() throws Exception {
    ScanFixture fix = connectedUser("trmanual");
    fakeGmail.scriptPages(List.of("trmanual-m1"));
    fakeGmail.addMessage("trmanual-m1", "welcome@spotify.com",
        "Welcome to Spotify", NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    UUID requestId = approveRequest(fix.accessToken(), accountId);

    JsonNode view = tracking(fix.accessToken(), requestId);
    assertTrue(view.get("manualRoute").asBoolean());
    assertTrue(view.get("deadlineAt").isNull());
    assertTrue(view.get("remindersDue").size() == 0);
    assertTrue(view.get("nextReminderDue").isNull());
    assertEquals("AWAITING_RESPONSE", view.get("trackingState").asText());
    assertTrue(view.get("deadlineNote").asText().contains("without a handoff timestamp"));
  }

  @Test
  void optedOutUserGetsNoReminders() throws Exception {
    TrackedSetup setup = dispatchedSetup("troptout");
    try {
      var settings = userSettings.findById(setup.fix().userId()).orElseThrow();
      settings.setReminderOptOut(true);
      userSettings.saveAndFlush(settings);

      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("OPTED_OUT", view.get("reminderState").asText());
      assertTrue(view.get("remindersDue").size() == 0);
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void trackingLanguageStaysConservative() throws Exception {
    TrackedSetup setup = dispatchedSetup("trwords");
    try {
      String body = mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/tracking",
          setup.fix().accessToken())).andReturn()
          .getResponse().getContentAsString();
      assertFalse(body.contains("must respond by"));
      assertFalse(body.contains("legally required"));
      assertFalse(body.contains("guarantee"));
      assertTrue(body.contains("verify locally"));
    } finally {
      cleanupSetup(setup);
    }
  }

  // -- ownership -------------------------------------------------------------------------------------------------

  @Test
  void trackingRequiresAuthentication() throws Exception {
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .get("/api/v1/privacy-requests/" + UUID.randomUUID() + "/tracking"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(postJson(
        "/api/v1/privacy-requests/" + UUID.randomUUID() + "/responses",
        Map.of("outcome", "completed")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void crossUserTrackingRejected() throws Exception {
    TrackedSetup owner = dispatchedSetup("trvictim");
    try {
      ScanFixture attacker = connectedUser("trintruder");
      mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + owner.requestId() + "/tracking",
          attacker.accessToken())).andExpect(status().isNotFound());
      mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + owner.requestId() + "/responses",
          attacker.accessToken())).andExpect(status().isNotFound());
      assertEquals(404, postResponse(
          attacker.accessToken(), owner.requestId(), Map.of("outcome", "completed"))
          .getResponse().getStatus());
    } finally {
      cleanupSetup(owner);
    }
  }

  // -- response intake -----------------------------------------------------------------------------------------------

  @Test
  void responseIntakeClosesReminders() throws Exception {
    TrackedSetup setup = dispatchedSetup("trresp");
    try {
      MvcResult result = postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "completed", "summary", "Account removed per reply."));
      assertEquals(200, result.getResponse().getStatus());
      JsonNode recorded = json(result);
      assertEquals("completed", recorded.get("outcome").asText());
      assertFalse(recorded.get("recordedAt").isNull());

      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("RESPONDED_COMPLETED", view.get("trackingState").asText());
      assertEquals("CLOSED", view.get("reminderState").asText());
      assertTrue(view.get("remindersDue").size() == 0);
      assertEquals(1, view.get("responseCount").asInt());
      assertEquals("completed", view.get("latestOutcome").asText());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void verificationRequiredReopensFollowUp() throws Exception {
    TrackedSetup setup = dispatchedSetup("trverify");
    try {
      assertEquals(200, postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "partial")).getResponse().getStatus());
      assertEquals("RESPONDED_PARTIAL",
          tracking(setup.fix().accessToken(), setup.requestId())
              .get("trackingState").asText());

      assertEquals(200, postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "verification_required")).getResponse().getStatus());
      JsonNode view = tracking(setup.fix().accessToken(), setup.requestId());
      assertEquals("AWAITING_AFTER_VERIFICATION", view.get("trackingState").asText());
      assertEquals(2, view.get("responseCount").asInt());

      MvcResult history = mockMvc.perform(authedGet(
          "/api/v1/privacy-requests/" + setup.requestId() + "/responses",
          setup.fix().accessToken())).andExpect(status().isOk()).andReturn();
      assertEquals(2, json(history).size());
      assertEquals("verification_required", json(history).get(0).get("outcome").asText());
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void invalidOutcomeAndSummaryRejected() throws Exception {
    TrackedSetup setup = dispatchedSetup("trinvalid");
    try {
      assertEquals(400, postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "deleted_by_ghostclean")).getResponse().getStatus());
      assertEquals(400, postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "completed", "summary", "x".repeat(2001)))
          .getResponse().getStatus());
      assertEquals(400, postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "completed", "summary", "<script>alert(1)</script>"))
          .getResponse().getStatus());
      assertEquals(0, responses.countByRequest_Id(setup.requestId()));
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void nonApprovedIntakeRejected() throws Exception {
    ScanFixture fix = connectedUser("trdraft");
    fakeGmail.scriptPages(List.of("trdraft-m1"));
    fakeGmail.addMessage("trdraft-m1", "welcome@spotify.com",
        "Welcome to Spotify", NOW - DAY, false);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    MvcResult created = mockMvc.perform(postJson("/api/v1/privacy-requests",
        Map.of("discoveredServiceId", accountId.toString()), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken())).andReturn();
    UUID requestId = UUID.fromString(json(created).get("id").asText());

    assertEquals(409, postResponse(fix.accessToken(), requestId,
        Map.of("outcome", "completed")).getResponse().getStatus());
    assertEquals(0, responses.countByRequest_Id(requestId));
  }

  @Test
  void responseRateLimited() throws Exception {
    TrackedSetup setup = dispatchedSetup("trspam");
    try {
      for (int i = 0; i < 30; i++) {
        assertEquals(200, postResponse(setup.fix().accessToken(), setup.requestId(),
            Map.of("outcome", "partial")).getResponse().getStatus());
      }
      MvcResult limited = postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "partial"));
      assertEquals(429, limited.getResponse().getStatus());
      assertTrue(limited.getResponse().getContentAsString().contains("RATE_LIMITED"));
    } finally {
      cleanupSetup(setup);
    }
  }

  @Test
  void auditRecordedWithoutSecrets() throws Exception {
    TrackedSetup setup = dispatchedSetup("traudit");
    try {
      postResponse(setup.fix().accessToken(), setup.requestId(),
          Map.of("outcome", "completed", "summary", "Removed after my call."));

      List<String> events = audits.findByUserIdOrderByIdAsc(setup.fix().userId()).stream()
          .map(com.ghostclean.audit.AuditLog::getEvent).toList();
      assertTrue(events.contains(AuditEvents.PRIVACY_REQUEST_RESPONSE_RECORDED));

      String serialized = objectMapper.writeValueAsString(
          audits.findByUserIdOrderByIdAsc(setup.fix().userId()));
      assertFalse(serialized.contains("acc-"));
      assertFalse(serialized.contains("ref-"));
      assertFalse(serialized.contains("Removed after my call."));
    } finally {
      cleanupSetup(setup);
    }
  }
}
