package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLog;
import com.ghostclean.audit.AuditLogRepository;
import com.ghostclean.mailbox.GmailApiException;
import com.ghostclean.mailbox.GmailProperties;
import com.ghostclean.oauth.OAuthConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Scan lifecycle + detection integration against the scripted Gmail double:
 * gating, bounds, pagination, retries, refresh, isolation, review, audit,
 * and log hygiene. Synthetic fixtures only — no real mailbox, no bodies.
 */
class GmailScanFlowTests extends ScanTestBase {

  @Autowired
  private AuditLogRepository audits;

  @Autowired
  private GmailProperties gmail;

  private static final long NOW = Instant.now().toEpochMilli();
  private static final long DAY = 24L * 3600 * 1000;

  private void spotifyWelcome(String id, long at) {
    fakeGmail.addMessage(id, "welcome@spotify.com", "Welcome to Spotify", at, false);
  }

  private void spotifyVerify(String id, long at) {
    fakeGmail.addMessage(id, "no-reply@spotify.com", "Please verify your email address", at,
        false);
  }

  // -- gating ----------------------------------------------------------------

  @Test
  void unauthenticatedScanRejected() throws Exception {
    mockMvc.perform(postJson("/api/v1/scans", Map.of("connectionId", UUID.randomUUID())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unverifiedUserCannotScan() throws Exception {
    String email = "scan-unver-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    mockMvc.perform(postJson("/api/v1/scans",
        Map.of("connectionId", UUID.randomUUID()), nextIp())
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void scanWithoutConnectionIsRejectedSafely() throws Exception {
    ScanFixture fix = connectedUser("noconn");
    MvcResult result = postScan(fix.accessToken(), UUID.randomUUID(), nextIp());
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("GOOGLE_CONNECTION_REQUIRED"));
  }

  @Test
  void scanWithExpiredConnectionRequiresReauth() throws Exception {
    ScanFixture fix = connectedUser("expconn");
    OAuthConnection connection = oauthConnections.findById(fix.connectionId()).orElseThrow();
    connection.setStatus(OAuthConnection.STATUS_EXPIRED);
    oauthConnections.save(connection);

    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("GOOGLE_REAUTH_REQUIRED"));
  }

  @Test
  void scanWithoutMailboxScopeIsRejected() throws Exception {
    ScanFixture fix = connectedUser("noscope", "ref-ok-noscope",
        List.of("openid", "email", "profile"), OAuthConnection.STATUS_ACTIVE);
    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(403, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("SCOPE_INSUFFICIENT"));
  }

  // -- happy path + lifecycle -------------------------------------------------

  @Test
  void fullScanFindsHighConfidenceAccount() throws Exception {
    ScanFixture fix = connectedUser("happy");
    fakeGmail.scriptPages(List.of("m1"));
    spotifyWelcome("m1", NOW - 2 * DAY);
    fakeGmail.addMessage("m1b", "no-reply@spotify.com",
        "Welcome! Please verify your email", NOW - DAY, false);
    fakeGmail.scriptPages(List.of("m1", "m1b"));

    MvcResult result = postScan(fix.accessToken(), fix.connectionId(), nextIp());
    assertEquals(200, result.getResponse().getStatus());
    JsonNode scan = json(result);
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(2, scan.get("messagesScanned").asInt());
    assertEquals(1, scan.get("candidatesDetected").asInt());

    MvcResult list = mockMvc.perform(authedGet("/api/v1/discovered", fix.accessToken()))
        .andExpect(status().isOk()).andReturn();
    JsonNode accounts = json(list);
    assertEquals(1, accounts.size());
    assertEquals("Spotify", accounts.get(0).get("service").asText());
    assertEquals("HIGH", accounts.get(0).get("confidence").asText());
    assertEquals("DETECTED", accounts.get(0).get("status").asText());
    assertEquals(2, accounts.get(0).get("evidenceCount").asInt());
  }

  @Test
  void scanHistoryAndDetailAreReadable() throws Exception {
    ScanFixture fix = connectedUser("hist");
    fakeGmail.scriptPages(List.of("h1"));
    spotifyWelcome("h1", NOW - DAY);

    UUID scanId = UUID.fromString(
        json(postScan(fix.accessToken(), fix.connectionId(), nextIp())).get("id").asText());

    MvcResult list = mockMvc.perform(authedGet("/api/v1/scans", fix.accessToken()))
        .andExpect(status().isOk()).andReturn();
    assertEquals(1, json(list).size());

    mockMvc.perform(authedGet("/api/v1/scans/" + scanId, fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.messagesScanned").value(1));
  }

  @Test
  void paginationWalksEveryPage() throws Exception {
    ScanFixture fix = connectedUser("pages");
    fakeGmail.scriptPages(List.of("p1", "p2"), List.of("p3", "p4"), List.of("p5"));
    long at = NOW - DAY;
    spotifyWelcome("p1", at);
    spotifyVerify("p2", at);
    fakeGmail.addMessage("p3", "billing@github.com", "Your receipt", at, false);
    fakeGmail.addMessage("p4", "info@netflix.com", "Welcome to Netflix", at, false);
    fakeGmail.addMessage("p5", "newsletter@random-bulk.example",
        "Weekly deals inside", at, true);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(5, scan.get("messagesScanned").asInt());
    assertTrue(fakeGmail.listCalls.get() >= 3);
    // 4 stored (newsletter dropped below threshold), 3 accounts.
    assertEquals(3, scan.get("candidatesDetected").asInt());
    assertEquals(3, json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn()).size());
  }

  @Test
  void messageLimitIsEnforced() throws Exception {
    gmail.setMaxMessagesPerScan(100);
    try {
      ScanFixture fix = connectedUser("limit");
      List<String> ids = new ArrayList<>();
      for (int i = 0; i < 180; i++) {
        String id = "lim-" + i;
        ids.add(id);
        spotifyWelcome(id, NOW - DAY);
      }
      fakeGmail.scriptPages(ids.subList(0, 100), ids.subList(100, 180));

      JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
      assertEquals("COMPLETED", scan.get("status").asText());
      assertEquals(100, scan.get("messagesScanned").asInt());
    } finally {
      gmail.setMaxMessagesPerScan(500);
    }
  }

  @Test
  void lookbackWindowIsSentToProvider() throws Exception {
    ScanFixture fix = connectedUser("query");
    fakeGmail.scriptPages(List.of());

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertNotNull(fakeGmail.capturedQuery);
    assertTrue(fakeGmail.capturedQuery.contains("newer_than:360d"));
    assertTrue(fakeGmail.capturedQuery.contains("subject:("));
  }

  @Test
  void deletedBetweenListAndGetIsSkipped() throws Exception {
    ScanFixture fix = connectedUser("gone");
    fakeGmail.scriptPages(List.of("g1", "ghost"));
    spotifyWelcome("g1", NOW - DAY);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(1, scan.get("messagesScanned").asInt());
    assertEquals(1, scan.get("candidatesDetected").asInt());
  }

  // -- concurrency / cancel / idempotency --------------------------------------

  @Test
  void secondConcurrentScanIsRejected() throws Exception {
    ScanFixture fix = connectedUser("conc");
    fakeGmail.scriptPages(List.of("c1"));
    spotifyWelcome("c1", NOW - DAY);
    fakeGmail.blockGets = new CountDownLatch(1);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<MvcResult> running = pool.submit(
        () -> postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    try {
      runningScanId(fix);
      MvcResult second = postScan(fix.accessToken(), fix.connectionId(), nextIp());
      assertEquals(409, second.getResponse().getStatus());
      assertTrue(second.getResponse().getContentAsString().contains("SCAN_CONFLICT"));

      mockMvc.perform(authedPost("/api/v1/scans/"
          + runningScanId(fix) + "/cancel", fix.accessToken()))
          .andExpect(status().isOk());
    } finally {
      fakeGmail.blockGets.countDown();
      running.get(30, TimeUnit.SECONDS);
      pool.shutdownNow();
    }
  }

  @Test
  void cancellationStopsTheScan() throws Exception {
    ScanFixture fix = connectedUser("cancel");
    fakeGmail.scriptPages(List.of("x1", "x2"));
    spotifyWelcome("x1", NOW - DAY);
    spotifyWelcome("x2", NOW - DAY);
    fakeGmail.blockGets = new CountDownLatch(1);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<MvcResult> running = pool.submit(
        () -> postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    try {
      UUID scanId = runningScanId(fix);
      MvcResult cancelled = mockMvc.perform(
          authedPost("/api/v1/scans/" + scanId + "/cancel", fix.accessToken()))
          .andExpect(status().isOk()).andReturn();
      assertEquals("CANCELLED", json(cancelled).get("status").asText());
    } finally {
      fakeGmail.blockGets.countDown();
      MvcResult finished = running.get(30, TimeUnit.SECONDS);
      assertEquals("CANCELLED", json(finished).get("status").asText());
      pool.shutdownNow();
    }
    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.SCAN_CANCELLED));
  }

  @Test
  void idempotentResubmissionReturnsOriginalScan() throws Exception {
    ScanFixture fix = connectedUser("idem");
    fakeGmail.scriptPages(List.of("i1"));
    spotifyWelcome("i1", NOW - DAY);
    String key = "idem-key-" + UUID.randomUUID();

    JsonNode first = json(postScan(fix.accessToken(), fix.connectionId(), nextIp(), key));
    JsonNode second = json(postScan(fix.accessToken(), fix.connectionId(), nextIp(), key));
    assertEquals(first.get("id").asText(), second.get("id").asText());
    assertEquals(1, json(mockMvc.perform(
        authedGet("/api/v1/scans", fix.accessToken())).andReturn()).size());
  }

  @Test
  void repeatedScansDoNotDuplicateEvidence() throws Exception {
    ScanFixture fix = connectedUser("redupe");
    fakeGmail.scriptPages(List.of("r1", "r2"));
    spotifyWelcome("r1", NOW - 3 * DAY);
    spotifyVerify("r2", NOW - DAY);

    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    long evidenceAfterFirst = evidenceCount();
    postScan(fix.accessToken(), fix.connectionId(), nextIp());

    assertEquals(evidenceAfterFirst, evidenceCount());
    assertEquals(1, discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).size());
    assertEquals(2, discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getEvidenceCount());
  }

  // -- provider failures ---------------------------------------------------------

  @Test
  void expiredAccessTokenRefreshesOnceAndContinues() throws Exception {
    ScanFixture fix = connectedUser("refresh401");
    fakeGmail.scriptPages(List.of("f1"));
    spotifyWelcome("f1", NOW - DAY);
    fakeGmail.enqueueListFailure(GmailApiException.unauthenticated());

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(1, scan.get("messagesScanned").asInt());
    assertNotNull(oauthConnections.findById(fix.connectionId()).orElseThrow()
        .getLastRefreshedAt());
  }

  @Test
  void revokedRefreshFailsSafeToReauth() throws Exception {
    ScanFixture fix = connectedUser("badrefresh", "ref-rejected-xyz",
        List.of("openid", "email", "profile", READ_SCOPE), OAuthConnection.STATUS_ACTIVE);
    OAuthConnection connection = oauthConnections.findById(fix.connectionId()).orElseThrow();
    connection.setExpiresAt(Instant.now().minusSeconds(60));
    oauthConnections.save(connection);
    fakeGmail.scriptPages(List.of("b1"));
    spotifyWelcome("b1", NOW - DAY);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("GOOGLE_REAUTH_REQUIRED", scan.get("errorCode").asText());
    assertEquals(OAuthConnection.STATUS_EXPIRED,
        oauthConnections.findById(fix.connectionId()).orElseThrow().getStatus());
    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.GOOGLE_REAUTH_REQUIRED));
  }

  @Test
  void insufficientScopeFailsWithStableCode() throws Exception {
    ScanFixture fix = connectedUser("forbidden");
    fakeGmail.enqueueListFailure(GmailApiException.insufficientScope("insufficientPermissions"));

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("SCOPE_INSUFFICIENT", scan.get("errorCode").asText());
  }

  @Test
  void rateLimitsAreRetriedThenSucceed() throws Exception {
    ScanFixture fix = connectedUser("ratelimit");
    fakeGmail.scriptPages(List.of("l1"));
    spotifyWelcome("l1", NOW - DAY);
    fakeGmail.enqueueListFailure(GmailApiException.rateLimited(0));
    fakeGmail.enqueueListFailure(GmailApiException.rateLimited(0));

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertTrue(fakeGmail.listCalls.get() >= 3);
  }

  @Test
  void serverErrorsRetryThenSucceed() throws Exception {
    ScanFixture fix = connectedUser("retry500");
    fakeGmail.scriptPages(List.of("s1"));
    spotifyWelcome("s1", NOW - DAY);
    fakeGmail.enqueueGetFailure(GmailApiException.serverError(500, "backendError"));

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(1, scan.get("messagesScanned").asInt());
  }

  @Test
  void exhaustedRetriesFailBounded() throws Exception {
    ScanFixture fix = connectedUser("exhaust");
    fakeGmail.scriptPages(List.of("e1"));
    spotifyWelcome("e1", NOW - DAY);
    for (int i = 0; i < 10; i++) {
      fakeGmail.enqueueListFailure(GmailApiException.serverError(500, "backendError"));
    }

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("FAILED", scan.get("status").asText());
    assertEquals("PROVIDER_ERROR", scan.get("errorCode").asText());
    assertEquals(4, fakeGmail.listCalls.get());
  }

  // -- detection quality ----------------------------------------------------------

  @Test
  void mediumConfidenceFromCommercialMail() throws Exception {
    ScanFixture fix = connectedUser("medium");
    fakeGmail.scriptPages(List.of("med1"));
    fakeGmail.addMessage("med1", "billing@github.com", "Your receipt", NOW - DAY, false);

    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    JsonNode accounts = json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
    assertEquals(1, accounts.size());
    assertEquals("MEDIUM", accounts.get(0).get("confidence").asText());
    assertEquals("GitHub", accounts.get(0).get("service").asText());
  }

  @Test
  void weakEvidenceStaysLowAndUnconfirmed() throws Exception {
    ScanFixture fix = connectedUser("weak");
    fakeGmail.scriptPages(List.of("w1"));
    fakeGmail.addMessage("w1", "shop@unknown-shop.example", "Your order update", NOW - DAY,
        false);

    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    JsonNode accounts = json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
    assertEquals(1, accounts.size());
    assertEquals("LOW", accounts.get(0).get("confidence").asText());
    assertEquals("DETECTED", accounts.get(0).get("status").asText());
  }

  @Test
  void newsletterMentionIsNotStored() throws Exception {
    ScanFixture fix = connectedUser("news");
    fakeGmail.scriptPages(List.of("n1"));
    fakeGmail.addMessage("n1", "newsletter@random-bulk.example",
        "Spotify playlist deals inside", NOW - DAY, true);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals("COMPLETED", scan.get("status").asText());
    assertEquals(0, scan.get("candidatesDetected").asInt());
    assertEquals(0, json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn()).size());
  }

  @Test
  void knownServicePasswordResetCountsAsMedium() throws Exception {
    ScanFixture fix = connectedUser("pwreset");
    fakeGmail.scriptPages(List.of("s1"));
    // Subdomain sender under a directory domain: subdomain bonus (+18) +
    // known service (+10) + security subject (+12) = MEDIUM, no penalty.
    fakeGmail.addMessage("s1", "support@mail.amazon.co.uk", "Password reset request",
        NOW - DAY, false);

    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    JsonNode accounts = json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
    assertEquals(1, accounts.size());
    assertEquals("MEDIUM", accounts.get(0).get("confidence").asText());
  }

  @Test
  void threeSendersBecomeOneNormalizedAccount() throws Exception {
    ScanFixture fix = connectedUser("norm");
    fakeGmail.scriptPages(List.of("a1", "a2", "a3"));
    fakeGmail.addMessage("a1", "welcome@spotify.com", "Welcome to Spotify", NOW - 9 * DAY,
        false);
    fakeGmail.addMessage("a2", "no-reply@spotify.com", "Thanks for signing up", NOW - 8 * DAY,
        false);
    fakeGmail.addMessage("a3", "account@spotify.com", "Your subscription receipt", NOW - DAY,
        false);

    JsonNode scan = json(postScan(fix.accessToken(), fix.connectionId(), nextIp()));
    assertEquals(3, scan.get("messagesScanned").asInt());
    JsonNode accounts = json(mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn());
    assertEquals(1, accounts.size());
    assertEquals(3, accounts.get(0).get("evidenceCount").asInt());
    assertEquals("HIGH", accounts.get(0).get("confidence").asText());

    UUID accountId = UUID.fromString(accounts.get(0).get("id").asText());
    JsonNode detail = json(mockMvc.perform(
        authedGet("/api/v1/discovered/" + accountId, fix.accessToken())).andReturn());
    assertEquals("Spotify", detail.get("service").asText());
    JsonNode rows = json(mockMvc.perform(
        authedGet("/api/v1/discovered/" + accountId + "/evidence", fix.accessToken()))
        .andReturn());
    assertEquals(3, rows.size());
    assertTrue(rows.get(0).has("matchedRules"));
    assertFalse(rows.toString().contains("acc-"));
  }

  // -- review / purge ---------------------------------------------------------------

  @Test
  void reviewKeepAndDismissFlow() throws Exception {
    ScanFixture fix = connectedUser("review");
    fakeGmail.scriptPages(List.of("v1"));
    spotifyWelcome("v1", NOW - DAY);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();

    mockMvc.perform(postJson("/api/v1/discovered/" + accountId + "/review",
        Map.of("decision", "keep"), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEWED"));

    mockMvc.perform(postJson("/api/v1/discovered/" + accountId + "/review",
        Map.of("decision", "dismiss", "reason", "not mine"), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISMISSED"));

    mockMvc.perform(postJson("/api/v1/discovered/" + accountId + "/review",
        Map.of("decision", "maybe"), nextIp())
        .header("Authorization", "Bearer " + fix.accessToken()))
        .andExpect(status().isBadRequest());

    mockMvc.perform(authedGet("/api/v1/discovered?status=DISMISSED", fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void evidencePurgeRemovesEmptiedAccount() throws Exception {
    ScanFixture fix = connectedUser("purge");
    fakeGmail.scriptPages(List.of("u1"));
    spotifyWelcome("u1", NOW - DAY);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();

    mockMvc.perform(authedDelete(
        "/api/v1/discovered/" + accountId + "/evidence-purge", fix.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountRemoved").value(true));
    assertTrue(discovered.findByUser_IdOrderByConfidenceDesc(fix.userId()).isEmpty());
  }

  // -- isolation ----------------------------------------------------------------------

  @Test
  void crossUserScanAndDetectionAccessIsMasked() throws Exception {
    ScanFixture owner = connectedUser("victim");
    ScanFixture attacker = connectedUser("attacker");
    fakeGmail.scriptPages(List.of("z1"));
    spotifyWelcome("z1", NOW - DAY);
    UUID scanId = UUID.fromString(
        json(postScan(owner.accessToken(), owner.connectionId(), nextIp()))
            .get("id").asText());
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(owner.userId())
        .get(0).getId();

    mockMvc.perform(authedGet("/api/v1/scans/" + scanId, attacker.accessToken()))
        .andExpect(status().isNotFound());
    mockMvc.perform(authedGet("/api/v1/discovered/" + accountId, attacker.accessToken()))
        .andExpect(status().isNotFound());
    mockMvc.perform(authedDelete("/api/v1/scans/" + scanId, attacker.accessToken()))
        .andExpect(status().isNotFound());
    mockMvc.perform(authedPost("/api/v1/scans/" + scanId + "/cancel", attacker.accessToken()))
        .andExpect(status().isNotFound());
    assertTrue(discovered.findByUser_IdOrderByConfidenceDesc(attacker.userId()).isEmpty());
  }

  // -- leak safety ----------------------------------------------------------------------

  @Test
  void apiResponsesCarryNoCredentialsOrBodies() throws Exception {
    ScanFixture fix = connectedUser("leak");
    String longSubject = "Welcome to Spotify " + "x".repeat(500);
    fakeGmail.scriptPages(List.of("k1"));
    fakeGmail.addMessage("k1", "welcome@spotify.com", longSubject, NOW - DAY, false);

    String scanBody = postScan(fix.accessToken(), fix.connectionId(), nextIp())
        .getResponse().getContentAsString();
    String accountsBody = mockMvc.perform(
        authedGet("/api/v1/discovered", fix.accessToken())).andReturn()
        .getResponse().getContentAsString();
    UUID accountId = discovered.findByUser_IdOrderByConfidenceDesc(fix.userId())
        .get(0).getId();
    String evidenceBody = mockMvc.perform(
        authedGet("/api/v1/discovered/" + accountId + "/evidence", fix.accessToken()))
        .andReturn().getResponse().getContentAsString();
    String scansBody = mockMvc.perform(
        authedGet("/api/v1/scans", fix.accessToken())).andReturn()
        .getResponse().getContentAsString();

    for (String body : new String[]{scanBody, accountsBody, evidenceBody, scansBody}) {
      assertFalse(body.contains("acc-"), "token leak");
      assertFalse(body.contains("ref-"), "token leak");
      assertFalse(body.contains("Bearer"), "auth leak");
    }
    JsonNode rows = objectMapper.readTree(evidenceBody);
    assertTrue(rows.get(0).get("subject").asText().length() <= 200);
  }

  @Test
  void credentialsNeverReachLogs() throws Exception {
    ch.qos.logback.classic.LoggerContext context =
        (ch.qos.logback.classic.LoggerContext)
            org.slf4j.LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger root =
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      ScanFixture fix = connectedUser("logs");
      fakeGmail.scriptPages(List.of("q1"));
      fakeGmail.addMessage("q1", "welcome@spotify.com", "Sentinel Zulu Alpha welcome",
          NOW - DAY, false);
      postScan(fix.accessToken(), fix.connectionId(), nextIp());

      StringBuilder all = new StringBuilder();
      appender.list.forEach(event -> all.append(event.getFormattedMessage()).append('\n'));
      String logs = all.toString();
      assertFalse(logs.contains("Sentinel Zulu Alpha"), "subject leak");
      assertFalse(logs.contains("acc-"), "token leak");
      assertFalse(logs.contains("ref-"), "token leak");
      assertFalse(logs.contains("Bearer"), "auth leak");
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  void evidenceTableHoldsNoBodiesOrAttachments() {
    List<Map<String, Object>> columns = jdbc.queryForList(
        "select column_name from information_schema.columns "
            + "where table_name = 'service_evidence'");
    List<String> names = columns.stream()
        .map(row -> String.valueOf(row.get("column_name")).toLowerCase()).toList();
    for (String forbidden : new String[]{"body", "mime", "attachment", "content", "payload"}) {
      assertFalse(names.contains(forbidden), "body-like column: " + forbidden);
    }
    for (String required : new String[]{"sender_domain", "subject_truncated", "matched_rules",
        "confidence_contrib", "provider_message_id", "source"}) {
      assertTrue(names.contains(required), "missing column: " + required);
    }
  }

  @Test
  void scanRateLimitStopsSpam() throws Exception {
    ScanFixture fix = connectedUser("spam");
    fakeGmail.scriptPages(List.of("sp1"));
    spotifyWelcome("sp1", NOW - DAY);
    String ip = nextIp();
    for (int i = 0; i < 5; i++) {
      MvcResult result = postScan(fix.accessToken(), fix.connectionId(), ip);
      assertEquals(200, result.getResponse().getStatus());
    }
    MvcResult limited = postScan(fix.accessToken(), fix.connectionId(), ip);
    assertEquals(429, limited.getResponse().getStatus());
    assertTrue(limited.getResponse().getContentAsString().contains("RATE_LIMITED"));
  }

  @Test
  void auditEventsAreRecordedWithoutSensitiveContent() throws Exception {
    ScanFixture fix = connectedUser("auditev");
    fakeGmail.scriptPages(List.of("t1"));
    spotifyWelcome("t1", NOW - DAY);
    postScan(fix.accessToken(), fix.connectionId(), nextIp());

    List<String> events = audits.findByUserIdOrderByIdAsc(fix.userId()).stream()
        .map(com.ghostclean.audit.AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.SCAN_STARTED));
    assertTrue(events.contains(AuditEvents.SCAN_COMPLETED));
    assertTrue(events.contains(AuditEvents.ACCOUNT_EVIDENCE_DETECTED));

    String serialized = objectMapper.writeValueAsString(
        audits.findByUserIdOrderByIdAsc(fix.userId()));
    assertFalse(serialized.contains("Welcome to Spotify"));
    assertFalse(serialized.contains("acc-"));
    assertFalse(serialized.contains("ref-"));
  }

  // -- helpers --------------------------------------------------------------------------

  private long evidenceCount() {
    Integer count = jdbc.queryForObject("select count(*) from service_evidence", Integer.class);
    return count == null ? 0 : count;
  }

  private UUID runningScanId(ScanFixture fix) throws Exception {
    Instant deadline = Instant.now().plusSeconds(15);
    while (Instant.now().isBefore(deadline)) {
      List<com.ghostclean.scanner.MailScan> running = scans
          .findByUser_IdOrderByCreatedAtDesc(fix.userId()).stream()
          .filter(scan -> "RUNNING".equals(scan.getStatus()))
          .toList();
      if (!running.isEmpty()) {
        return running.get(0).getId();
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("scan never reached RUNNING");
  }
}
