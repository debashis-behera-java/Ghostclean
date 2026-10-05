package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.mailbox.GraphApiException;
import com.ghostclean.oauth.OAuthConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import com.ghostclean.audit.AuditLogRepository;

/**
 * Microsoft scan lifecycle + detection integration against the scripted
 * Graph double: gating, bounds, pagination, throttling, refresh, isolation,
 * audit, and hygiene. Mirrors {@code GmailScanFlowTests}; synthetic fixtures
 * only — no real mailbox, no bodies, no Microsoft account.
 */
class MicrosoftScanFlowTests extends MicrosoftScanTestBase {

  @Autowired
  private AuditLogRepository audits;

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  private void spotifyWelcome(String id, long at) {
    fakeGraph.addMessage(id, "welcome@spotify.com", "Welcome to Spotify", at, false);
  }

  // -- gating ----------------------------------------------------------------

  @Test
  void unauthenticatedScanRejected() throws Exception {
    mockMvc.perform(postJson("/api/v1/scans", Map.of("connectionId", UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unverifiedUserCannotScan() throws Exception {
    String email = "ms-scan-unver-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    mockMvc.perform(postJson("/api/v1/scans",
        Map.of("connectionId", UUID.randomUUID()), nextIp())
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void scanWithExpiredConnectionRequiresReauth() throws Exception {
    ScanFixture fix = connectedUser("expconn");
    OAuthConnection connection = oauthConnections.findById(fix.connectionId()).orElseThrow();
    connection.setStatus(OAuthConnection.STATUS_EXPIRED);
    oauthConnections.save(connection);

    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString()
        .contains("MICROSOFT_REAUTH_REQUIRED"));
  }

  @Test
  void scanWithoutMailboxScopeIsRejected() throws Exception {
    ScanFixture fix = connectedUser("noscope", "ref-ok-ms-noscope",
        List.of("openid", "profile", "offline_access", "User.Read"),
        OAuthConnection.STATUS_ACTIVE);
    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(403, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("SCOPE_INSUFFICIENT"));
  }

  @Test
  void crossUserScanSeesNothing() throws Exception {
    ScanFixture victim = connectedUser("msvictim");
    ScanFixture attacker = connectedUser("msintruder");

    MvcResult result = postScan(attacker.accessToken(), victim.connectionId(), nextIp());
    assertEquals(400, result.getResponse().getStatus());
    assertEquals(0, discovered.findByUser_IdOrderByConfidenceDesc(attacker.userId()).size());
    assertEquals("ACTIVE",
        oauthConnections.findById(victim.connectionId()).orElseThrow().getStatus());
  }

  // -- happy path + detection integration ------------------------------------

  @Test
  void fullScanFindsDetectedAccountAsMicrosoftEvidence() throws Exception {
    ScanFixture fix = connectedUser("happy");
    fakeGraph.scriptPages(List.of("m1", "m1b"));
    spotifyWelcome("m1", NOW - 2 * DAY);
    fakeGraph.addMessage("m1b", "no-reply@spotify.com",
        "Welcome! Please verify your email", NOW - DAY, false);

    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(200, result.getResponse().getStatus());
    JsonNode scan = json(result);
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(2, scan.get("messagesScanned").asInt());
    assertEquals(1, scan.get("candidatesDetected").asInt());

    // Detected-account evidence, attributed to the Microsoft provider —
    // candidate evidence, never proof the account still exists.
    List<String> providers = jdbc.queryForList(
        "select distinct provider from service_evidence where user_id = ?",
        String.class, fix.userId());
    assertEquals(List.of("microsoft"), providers);

    MvcResult list = mockMvc.perform(authedGet("/api/v1/discovered", fix.accessToken()))
        .andExpect(status().isOk()).andReturn();
    JsonNode accounts = json(list);
    assertEquals(1, accounts.size());
    assertEquals("Spotify", accounts.get(0).get("service").asText());
    assertEquals("HIGH", accounts.get(0).get("confidence").asText());
    assertEquals("DETECTED", accounts.get(0).get("status").asText());
  }

  @Test
  void paginationWalksEveryPage() throws Exception {
    ScanFixture fix = connectedUser("pages");
    fakeGraph.scriptPages(List.of("p1", "p2"), List.of("p3"));
    long at = NOW - DAY;
    spotifyWelcome("p1", at);
    fakeGraph.addMessage("p2", "billing@github.com", "Your receipt", at, false);
    fakeGraph.addMessage("p3", "info@netflix.com", "Welcome to Netflix", at, false);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(3, scan.get("messagesScanned").asInt());
    assertTrue(fakeGraph.listCalls.get() >= 2);
  }

  @Test
  void messageLimitIsEnforced() throws Exception {
    graph.setMaxMessagesPerScan(100);
    try {
      ScanFixture fix = connectedUser("limit");
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < 180; i++) {
        String id = "lim-" + i;
        ids.add(id);
        spotifyWelcome(id, NOW - DAY);
      }
      fakeGraph.scriptPages(ids.subList(0, 100), ids.subList(100, 180));

      JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
      assertEquals("COMPLETED", scan.get("status").asText());
      assertEquals(100, scan.get("messagesScanned").asInt());
    } finally {
      graph.setMaxMessagesPerScan(500);
    }
  }

  @Test
  void lookbackFilterIsSentToProvider() throws Exception {
    ScanFixture fix = connectedUser("query");
    fakeGraph.scriptPages(List.of());

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertNotNull(fakeGraph.capturedQuery);
    assertTrue(fakeGraph.capturedQuery.contains("receivedDateTime ge "));
    assertTrue(fakeGraph.capturedQuery.contains("contains(subject,"));
    assertTrue(fakeGraph.capturedTop > 0);
  }

  @Test
  void deletedBetweenListAndGetIsSkipped() throws Exception {
    ScanFixture fix = connectedUser("gone");
    fakeGraph.scriptPages(List.of("g1", "ghost"));
    spotifyWelcome("g1", NOW - DAY);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(1, scan.get("messagesScanned").asInt());
    assertEquals(1, scan.get("candidatesDetected").asInt());
  }

  @Test
  void malformedMessageWithoutSenderIsSkipped() throws Exception {
    ScanFixture fix = connectedUser("malformed");
    fakeGraph.scriptPages(List.of("bad1", "good1"));
    fakeGraph.addMessage("bad1", "   ", "No sender here", NOW - DAY, false);
    spotifyWelcome("good1", NOW - DAY);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(2, scan.get("messagesScanned").asInt());
    assertEquals(1, scan.get("candidatesDetected").asInt());
  }

  @Test
  void repeatedScansDoNotDuplicateEvidence() throws Exception {
    ScanFixture fix = connectedUser("redupe");
    fakeGraph.scriptPages(List.of("r1", "r2"));
    spotifyWelcome("r1", NOW - 3 * DAY);
    spotifyWelcome("r2", NOW - DAY);

    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    long evidenceAfterFirst = evidenceCount(fix.userId());
    postScan(fix.accessToken(), fix.connectionId(), nextIp());

    assertEquals(evidenceAfterFirst, evidenceCount(fix.userId()));
    assertEquals(1, discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).size());
  }

  // -- throttling / provider failures ------------------------------------------

  @Test
  void rateLimitsAreRetriedThenSucceed() throws Exception {
    ScanFixture fix = connectedUser("ratelimit");
    fakeGraph.scriptPages(List.of("l1"));
    spotifyWelcome("l1", NOW - DAY);
    fakeGraph.enqueueListFailure(GraphApiException.rateLimited(0));
    fakeGraph.enqueueListFailure(GraphApiException.rateLimited(0));

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(1, scan.get("messagesScanned").asInt());
    assertTrue(fakeGraph.listCalls.get() >= 3);
  }

  @Test
  void exhaustedRetriesFailBounded() throws Exception {
    ScanFixture fix = connectedUser("exhausted");
    fakeGraph.scriptPages(List.of("e1"));
    spotifyWelcome("e1", NOW - DAY);
    for (int i = 0; i < 10; i++) {
      fakeGraph.enqueueListFailure(GraphApiException.rateLimited(0));
    }

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("PROVIDER_ERROR", scan.get("errorCode").asText());
    // Bounded: one attempt budget per call, never an infinite loop.
    assertTrue(fakeGraph.listCalls.get() <= 5);
  }

  @Test
  void insufficientScopeFailsWithStableCode() throws Exception {
    ScanFixture fix = connectedUser("forbidden");
    fakeGraph.enqueueListFailure(GraphApiException.insufficientScope("ErrorAccessDenied"));

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("SCOPE_INSUFFICIENT", scan.get("errorCode").asText());
  }

  @Test
  void revokedRefreshFailsSafeToReauth() throws Exception {
    ScanFixture fix = connectedUser("badrefresh", "ref-rejected-ms-xyz", FULL_SCOPES,
        OAuthConnection.STATUS_ACTIVE);
    OAuthConnection connection = oauthConnections.findById(fix.connectionId()).orElseThrow();
    connection.setExpiresAt(Instant.now().minusSeconds(60));
    oauthConnections.save(connection);
    fakeGraph.scriptPages(List.of("b1"));
    spotifyWelcome("b1", NOW - DAY);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("MICROSOFT_REAUTH_REQUIRED", scan.get("errorCode").asText());
    // Permanently invalid grants are marked EXPIRED, never retried forever.
    assertEquals(OAuthConnection.STATUS_EXPIRED,
        oauthConnections.findById(fix.connectionId()).orElseThrow().getStatus());
    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.MICROSOFT_REAUTH_REQUIRED));
  }

  // -- audit + hygiene -----------------------------------------------------------

  @Test
  void auditEventsAreRecordedWithoutSensitiveContent() throws Exception {
    ScanFixture fix = connectedUser("auditev");
    fakeGraph.scriptPages(List.of("t1"));
    spotifyWelcome("t1", NOW - DAY);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());

    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.SCAN_STARTED));
    assertTrue(events.contains(AuditEvents.SCAN_COMPLETED));
    assertTrue(events.contains(AuditEvents.ACCOUNT_EVIDENCE_DETECTED));

    String serialized = objectMapper.writeValueAsString(
        audits.findByUserIdOrderByIdAsc(fix.userId()));
    assertTrue(serialized.contains("\"provider\":\"microsoft\"")
        || serialized.contains("microsoft"));
    assertFalse(serialized.contains("Welcome to Spotify"));
    assertFalse(serialized.contains("acc-"));
    assertFalse(serialized.contains("ref-"));
  }

  // -- helpers -------------------------------------------------------------------

  private long evidenceCount(UUID userId) {
    Integer count = jdbc.queryForObject(
        "select count(*) from service_evidence where user_id = ?", Integer.class, userId);
    return count == null ? 0 : count;
  }
}
