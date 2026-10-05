package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLog;
import com.ghostclean.audit.AuditLog;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.OAuthException;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthState;
import com.ghostclean.oauth.OAuthStateService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end OAuth connection lifecycle against the scripted provider:
 * authorize gating, state discipline, callback outcomes, encrypted storage,
 * duplicate handling, disconnect, isolation, audit, and log hygiene.
 */
class GoogleOAuthFlowTests extends OAuthTestBase {

  @Autowired
  private TokenCryptoProvider crypto;

  @Autowired
  private OAuthStateService stateService;

  @Autowired
  private JdbcTemplate jdbc;

  // -- authorize gating ------------------------------------------------------

  @Test
  void unauthenticatedAuthorizeIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/oauth/google/authorize"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unverifiedUserCannotAuthorize() throws Exception {
    String email = freshEmail("unverified");
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());

    mockMvc.perform(authedGet("/api/v1/oauth/google/authorize", tokens.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void browserVerificationUnblocksOAuthAuthorize() throws Exception {
    String email = freshEmail("verify-then-oauth");
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = mailCapture.latestToken();
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());

    // Blocked while unverified.
    mockMvc.perform(authedGet("/api/v1/oauth/google/authorize", tokens.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

    // Real browser flow: emailed GET link, no JSON body.
    mockMvc.perform(get("/api/v1/auth/verify?token=" + token)
            .with(r -> {
              r.setRemoteAddr(nextIp());
              return r;
            }))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/success"));

    // Same credentials now authorize successfully.
    LoginTokens refreshed = login(email, "Str0ng-Enough-Password", nextIp());
    assertTrue(refreshed.body().get("emailVerified").asBoolean());
    AuthorizeData data = authorizeApproved(refreshed.accessToken());
    assertEquals(200, data.status());
    assertNotNull(data.authorizationUrl());
  }

  @Test
  void unwiredProviderIsGone() throws Exception {
    // Phase 10 wires Microsoft; a provider with no client bean still 410s.
    LoginTokens tokens = verifiedLogin(freshEmail("nope"), "Str0ng-Enough-Password");
    mockMvc.perform(authedGet("/api/v1/oauth/yahoo/authorize", tokens.accessToken()))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_DISABLED"));
  }

  @Test
  void authorizeReturnsSafeUrlWithIdentityOnlyScopes() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("authz"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    assertEquals(200, data.status());
    assertNotNull(data.authorizationUrl());
    assertNotNull(data.state());

    String url = data.authorizationUrl();
    assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
    assertTrue(url.contains("client_id=test-google-client-id"));
    assertTrue(url.contains("response_type=code"));
    assertTrue(url.contains("access_type=offline"));
    assertTrue(url.contains("prompt=consent"));
    assertTrue(url.contains("code_challenge="));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("state=" + data.state()));
    // Identity scopes only: openid/email/profile present…
    assertTrue(url.contains("openid"));
    assertTrue(url.contains("email"));
    assertTrue(url.contains("profile"));
    // …plus the Phase 4 mailbox-read scope only when configured (this test
    // context leaves mailbox-scope empty, so no mailbox scope is requested).
    assertFalse(url.toLowerCase().contains("mail.google"));
    // Client secret never travels to the browser.
    assertFalse(url.contains("test-google-client-secret-synth"));
    assertFalse(data.body().contains("test-google-client-secret-synth"));
  }

  // -- state discipline ------------------------------------------------------

  @Test
  void callbackRejectsMissingAndUnknownState() throws Exception {
    MvcResult missing = callback("google", "ok-anything", null, null);
    assertEquals(400, missing.getResponse().getStatus());
    assertTrue(missing.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));

    MvcResult unknown = callback("google", "ok-anything", "no-such-state", null);
    assertEquals(400, unknown.getResponse().getStatus());
    assertNull(unknown.getResponse().getHeader("Location"));
  }

  @Test
  void callbackAcceptsStateExtractedFromAuthorizationUrl() throws Exception {
    // Regression: the state the browser carries inside the authorization URL
    // must be exactly the state the callback consumes. Any divergence
    // (truncated copy, redacted placeholder, stale URL) is a mismatch.
    LoginTokens tokens = verifiedLogin(freshEmail("urlstate"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    String fromUrl = stateFromUrl(data.authorizationUrl());
    assertEquals(data.state(), fromUrl);

    MvcResult result = callback("google", "ok-urlstate", fromUrl, null);
    assertEquals(302, result.getResponse().getStatus());
    assertTrue(locationOf(result).contains("status=connected"));
    assertEquals(1, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void unknownStateCallbackIsAuditedWithoutStateMaterial() throws Exception {
    callback("google", "ok-anything", "no-such-state-" + UUID.randomUUID(), null);

    List<String> events = audits.findAll().stream()
        .map(AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.OAUTH_CONNECT_FAILED));

    String serialized = objectMapper.writeValueAsString(audits.findAll());
    assertFalse(serialized.contains("no-such-state"),
        "presented state must never reach the audit trail");
  }

  @Test
  void callbackRejectsExpiredState() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("exp"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    OAuthState row = states.findByStateHash(shaOf(data.state())).orElseThrow();
    row.setUsedAt(null);
    row.getClass();
    expireRow(row);

    MvcResult result = callback("google", "ok-expired", data.state(), null);
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void stateIsSingleUse() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("replay"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    MvcResult first = callback("google", "ok-replay", data.state(), null);
    assertEquals(302, first.getResponse().getStatus());

    MvcResult second = callback("google", "ok-replay", data.state(), null);
    assertEquals(400, second.getResponse().getStatus());
    assertTrue(second.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
  }

  @Test
  void connectionBelongsToStateOwner() throws Exception {
    LoginTokens owner = verifiedLogin(freshEmail("owner"), "Str0ng-Enough-Password");
    LoginTokens other = verifiedLogin(freshEmail("other"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(owner.accessToken());

    // The callback carries no credentials; ownership resolves from the state.
    MvcResult result = callback("google", "ok-bound", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    assertEquals(1, connections.findByUser_Id(userIdOf(owner)).size());
    assertEquals(0, connections.findByUser_Id(userIdOf(other)).size());
  }

  @Test
  void consumedStateResolvesOwnerAndPkceVerifier() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("whoami"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    OAuthStateService.CreatedState created =
        stateService.create(userId, "google", null, Duration.ofMinutes(10));
    OAuthStateService.ConsumedState consumed = stateService.consume(created.state(), "google");

    assertEquals(userId, consumed.userId());
    assertEquals("google", consumed.provider());
    assertNotNull(consumed.codeVerifier());
    assertFalse(consumed.codeVerifier().isBlank());
  }

  @Test
  void authorizedStateIsCommittedBeforeAuthorizeReturns() throws Exception {
    // Regression: the browser leaves for Google the moment authorizeApproved()
    // responds, so the state row must be durably committed — not merely
    // visible inside the request transaction — before that response.
    LoginTokens tokens = verifiedLogin(freshEmail("commit"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    assertEquals(200, data.status());

    // Fresh connection/transaction: uncommitted rows would be invisible here.
    Integer matching = jdbc.queryForObject(
        "SELECT COUNT(*) FROM oauth_states WHERE state_hash = ?",
        Integer.class, shaOf(data.state()));
    assertEquals(1, matching);

    // And the URL the browser actually opens carries exactly that state.
    assertEquals(data.state(), stateFromUrl(data.authorizationUrl()));
  }

  @Test
  void callbackRemainsUnauthenticated() throws Exception {
    // Google's browser redirect carries no GhostClean credentials by
    // design: no header at all, and even a forged header, must not affect
    // the outcome. Ownership resolves from the consumed state row.
    LoginTokens tokens = verifiedLogin(freshEmail("noauth"), "Str0ng-Enough-Password");

    AuthorizeData bare = authorizeApproved(tokens.accessToken());
    MvcResult bareResult = mockMvc.perform(get(
        "/api/v1/oauth/google/callback?code=ok-noauth&state=" + bare.state())).andReturn();
    assertEquals(302, bareResult.getResponse().getStatus());
    assertTrue(locationOf(bareResult).contains("status=connected"));

    AuthorizeData forged = authorizeApproved(tokens.accessToken());
    MvcResult forgedResult = mockMvc.perform(get(
        "/api/v1/oauth/google/callback?code=ok-noauth&state=" + forged.state())
        .header("Authorization", "Bearer forged-token")).andReturn();
    assertEquals(302, forgedResult.getResponse().getStatus());
    assertTrue(locationOf(forgedResult).contains("status=connected"));

    // Same provider account reconnects onto one row, owned by the state owner.
    assertEquals(1, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void tokenExchangeReceivesPkceVerifierBoundToState() throws Exception {
    // The verifier never travels the browser: the callback must forward
    // exactly the verifier stored alongside the state. A mismatch would be
    // rejected by Google (invalid_grant); the provider double records what
    // it received so the binding is asserted, not assumed.
    LoginTokens tokens = verifiedLogin(freshEmail("pkce"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    String storedVerifier = states.findByStateHash(shaOf(data.state()))
        .orElseThrow().getCodeVerifier();

    int before = fakeGoogle.receivedVerifiers.size();
    MvcResult result = callback("google", "ok-pkce", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    assertEquals(before + 1, fakeGoogle.receivedVerifiers.size());
    assertEquals(storedVerifier, fakeGoogle.receivedVerifiers.get(before));
  }

  @Test
  void grantedGmailScopeIsPersisted() throws Exception {
    // Fidelity proof: whatever Google grants in the token response is what
    // lands in oauth_connections.scopes — the app neither drops granted
    // scopes nor invents ungranted ones. A full grant (identity + mailbox)
    // persists gmail.readonly; an identity-only grant persists exactly the
    // identity scopes (and the scanner rejects those at scan time).
    LoginTokens tokens = verifiedLogin(freshEmail("granted"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData full = authorizeApproved(tokens.accessToken());
    MvcResult fullResult = callback("google", "ok-with-gmail", full.state(), null);
    assertEquals(302, fullResult.getResponse().getStatus());

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    List<String> stored = List.of(rows.get(0).getScopes());
    assertTrue(stored.contains("https://www.googleapis.com/auth/gmail.readonly"));
    assertTrue(stored.contains("openid"));
  }

  @Test
  void identityOnlyGrantPersistsHonestlyWithoutMailboxScope() throws Exception {
    // The partial-grant shape from real consent flows: Google grants
    // identity only. The connection persists exactly that grant — no
    // mailbox scope is fabricated — so ScanService sees the shortfall.
    LoginTokens tokens = verifiedLogin(freshEmail("partial"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData data = authorizeApproved(tokens.accessToken());
    MvcResult result = callback("google", "ok-partial", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    List<String> stored = List.of(rows.get(0).getScopes());
    assertFalse(stored.contains("https://www.googleapis.com/auth/gmail.readonly"));
    assertTrue(stored.contains("openid"));
  }

  @Test
  void consumeRejectsForeignProviderWithoutBurningState() throws Exception {
    // A Google-issued state presented to another provider's callback is a
    // mismatch — and the rejection must not burn the state, so the genuine
    // Google callback can still consume it exactly once afterwards.
    LoginTokens tokens = verifiedLogin(freshEmail("provider"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    OAuthStateService.CreatedState created =
        stateService.create(userId, "google", null, Duration.ofMinutes(10));

    OAuthException foreign = assertThrows(OAuthException.class,
        () -> stateService.consume(created.state(), "microsoft"));
    assertEquals(ApiError.OAUTH_STATE_MISMATCH, foreign.getCode());

    OAuthStateService.ConsumedState ok =
        stateService.consume(created.state(), "google");
    assertEquals(userId, ok.userId());

    assertThrows(OAuthException.class,
        () -> stateService.consume(created.state(), "google"));
  }

  // -- callback outcomes -----------------------------------------------------

  @Test
  void denialRedirectsWithSafeErrorAndCreatesNothing() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("deny"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    MvcResult result = callback("google", null, data.state(), "access_denied");
    assertEquals(302, result.getResponse().getStatus());
    String location = locationOf(result);
    assertTrue(location.contains("status=error"));
    assertTrue(location.contains("OAUTH_DENIED"));
    assertFalse(location.contains("access_denied_details"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void exchangeFailureRedirectsSafely() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("exfail"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    MvcResult result = callback("google", "fail-exchange", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    assertTrue(locationOf(result).contains("PROVIDER_ERROR"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void accountLookupFailureRedirectsSafely() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("acctfail"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    MvcResult result = callback("google", "ok-fail-account", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    assertTrue(locationOf(result).contains("PROVIDER_ERROR"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void successPersistsEncryptedConnectionAndRedirects() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("happy"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());

    MvcResult result = callback("google", "ok-happy", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    String location = locationOf(result);
    assertTrue(location.contains("status=connected"));
    assertTrue(location.contains("provider=google"));
    assertFalse(location.contains("acc-ok-happy"));

    List<OAuthConnection> rows = connections.findByUser_Id(userIdOf(tokens));
    assertEquals(1, rows.size());
    OAuthConnection row = rows.get(0);
    assertEquals("google", row.getProvider());
    assertEquals("ACTIVE", row.getStatus());
    assertEquals("sub-acc-ok-happy", row.getProviderAccountId());
    assertNotNull(row.getExpiresAt());
    // Ciphertext at rest, decryptable by the server seam only.
    assertFalse(row.getAccessTokenEnc().contains("acc-ok-happy"));
    assertFalse(row.getRefreshTokenEnc().contains("ref-ok-happy"));
    assertEquals("acc-ok-happy", crypto.decrypt(row.getAccessTokenEnc()));
    assertEquals("ref-ok-happy", crypto.decrypt(row.getRefreshTokenEnc()));
    assertEquals(1, row.getKeyVersion());
  }

  @Test
  void reconnectUpdatesSameRow() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("dupe"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData first = authorizeApproved(tokens.accessToken());
    callback("google", "ok-same-sub", first.state(), null);
    AuthorizeData second = authorizeApproved(tokens.accessToken());
    callback("google", "ok-same-sub-again", second.state(), null);

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    assertEquals("ACTIVE", rows.get(0).getStatus());
    assertNull(rows.get(0).getRevokedAt());
    assertEquals("acc-ok-same-sub-again", crypto.decrypt(rows.get(0).getAccessTokenEnc()));
  }

  @Test
  void reconnectWithoutFreshRefreshKeepsStoredOne() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("norefresh"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData first = authorizeApproved(tokens.accessToken());
    callback("google", "ok-same-sub", first.state(), null);
    assertEquals("ref-ok-same-sub",
        crypto.decrypt(connections.findByUser_Id(userId).get(0).getRefreshTokenEnc()));

    // Same provider account, but the grant withholds a fresh refresh token:
    // the previously stored one must survive, and there is still one row.
    AuthorizeData second = authorizeApproved(tokens.accessToken());
    MvcResult result = callback("google", "ok-same-sub-no-refresh", second.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    assertEquals("ref-ok-same-sub", crypto.decrypt(rows.get(0).getRefreshTokenEnc()));
    assertEquals("acc-ok-same-sub-no-refresh",
        crypto.decrypt(rows.get(0).getAccessTokenEnc()));
  }

  // -- connections list / disconnect / refresh -------------------------------

  @Test
  void connectionsListExposesNoSecrets() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("list"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    callback("google", "ok-list", data.state(), null);

    MvcResult result = mockMvc.perform(authedGet("/api/v1/oauth/connections", tokens.accessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"provider\":\"google\""));
    assertTrue(body.contains("\"status\":\"ACTIVE\""));
    // Masked, not raw.
    assertTrue(body.contains("o***@example.com"));
    assertFalse(body.contains("owner-sub-acc-ok-list@example.com"));
    for (String forbidden : new String[]{
        "acc-ok-list", "ref-ok-list", "test-google-client-secret-synth",
        "accessTokenEnc", "refreshTokenEnc", "code_verifier", "codeVerifier"}) {
      assertFalse(body.contains(forbidden), "leak: " + forbidden);
    }
    JsonNode json = objectMapper.readTree(body);
    assertTrue(json.isArray() && json.size() == 1);
  }

  @Test
  void disconnectRevokesWipesAndAudits() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("bye"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    callback("google", "ok-bye", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();

    mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isNoContent());

    OAuthConnection row = connections.findById(id).orElseThrow();
    assertEquals("REVOKED", row.getStatus());
    assertNull(row.getAccessTokenEnc());
    assertNull(row.getRefreshTokenEnc());
    assertNotNull(row.getRevokedAt());
    assertTrue(fakeGoogle.revokedTokens.contains("ref-ok-bye"));

    // Idempotent second call.
    mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isNoContent());

    // Revoked connections cannot be refreshed back to life.
    mockMvc.perform(post("/api/v1/oauth/connections/" + id + "/refresh")
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void crossUserDisconnectIsMasked() throws Exception {
    LoginTokens owner = verifiedLogin(freshEmail("victim"), "Str0ng-Enough-Password");
    LoginTokens attacker = verifiedLogin(freshEmail("attacker"), "Str0ng-Enough-Password");
    AuthorizeData data = authorizeApproved(owner.accessToken());
    callback("google", "ok-victim", data.state(), null);
    UUID id = connections.findByUser_Id(userIdOf(owner)).get(0).getId();

    mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
        .header("Authorization", "Bearer " + attacker.accessToken()))
        .andExpect(status().isNotFound());

    assertEquals("ACTIVE", connections.findById(id).orElseThrow().getStatus());
    assertEquals(0, connections.findByUser_Id(userIdOf(attacker)).size());
  }

  @Test
  void refreshEndpointRotatesStoredCredentials() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("rot"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    callback("google", "ok-rot", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();

    MvcResult result = mockMvc.perform(post("/api/v1/oauth/connections/" + id + "/refresh")
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"status\":\"ACTIVE\""));
    assertFalse(body.contains("acc-refreshed-"));
    assertEquals("acc-refreshed-ref-ok-rot",
        crypto.decrypt(connections.findById(id).orElseThrow().getAccessTokenEnc()));
  }

  @Test
  void refreshRateLimited() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("rl"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    callback("google", "ok-rl", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();

    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/api/v1/oauth/connections/" + id + "/refresh")
          .header("Authorization", "Bearer " + tokens.accessToken()))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post("/api/v1/oauth/connections/" + id + "/refresh")
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isTooManyRequests());
  }

  // -- audit + logs ----------------------------------------------------------

  @Test
  void auditTrailRecordsLifecycleWithoutSecrets() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("audit"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorizeApproved(tokens.accessToken());
    callback("google", "ok-audit", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();
    mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isNoContent());

    List<String> events = audits.findByUserIdOrderByIdAsc(userId).stream()
        .map(AuditLog::getEvent).toList();
    assertTrue(events.contains(AuditEvents.OAUTH_CONNECT_STARTED));
    assertTrue(events.contains(AuditEvents.OAUTH_CONNECT_SUCCEEDED));
    assertTrue(events.contains(AuditEvents.OAUTH_DISCONNECTED));

    String serialized = objectMapper.writeValueAsString(
        audits.findByUserIdOrderByIdAsc(userId));
    // Refresh tokens must never appear anywhere. The access token may only
    // appear inside the provider account id ("sub-acc-…"), which is audited
    // as the connection's entity reference — never as token material.
    assertFalse(serialized.contains("ref-ok-audit"), "audit leak: refresh token");
    String scrubbed = serialized.replace("sub-acc-ok-audit", "");
    for (String forbidden : new String[]{
        "acc-ok-audit", "ok-audit", "test-google-client-secret-synth"}) {
      assertFalse(scrubbed.contains(forbidden), "audit leak: " + forbidden);
    }
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
      LoginTokens tokens = verifiedLogin(freshEmail("logs"), "Str0ng-Enough-Password");
      AuthorizeData data = authorizeApproved(tokens.accessToken());
      callback("google", "ok-sentinel-xyz", data.state(), null);
      UUID id = connections.findByUser_Id(userIdOf(tokens)).get(0).getId();
      mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
          .header("Authorization", "Bearer " + tokens.accessToken()));

      StringBuilder all = new StringBuilder();
      appender.list.forEach(event -> all.append(event.getFormattedMessage()).append('\n'));
      String logs = all.toString();
      for (String forbidden : new String[]{
          "acc-ok-sentinel-xyz", "ref-ok-sentinel-xyz", "ok-sentinel-xyz",
          "test-google-client-secret-synth", data.state()}) {
        assertFalse(logs.contains(forbidden), "log leak: " + forbidden);
      }
    } finally {
      root.detachAppender(appender);
    }
  }

  // -- helpers ---------------------------------------------------------------

  private UUID userIdOf(LoginTokens tokens) {
    return jwtService.parse(tokens.accessToken()).userId();
  }

  private void expireRow(OAuthState row) {
    row.setExpiresAt(Instant.now().minusSeconds(60));
    states.save(row);
  }

  private static String shaOf(String raw) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
