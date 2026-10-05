package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditLog;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.oauth.OAuthConnection;
import com.ghostclean.oauth.OAuthState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end Microsoft OAuth connection lifecycle against the scripted
 * provider: authorize gating, state discipline, callback outcomes, encrypted
 * storage, duplicate handling, disconnect, isolation, audit, and log hygiene.
 * Mirrors {@code GoogleOAuthFlowTests}; synthetic data only, no network, no
 * Microsoft account, no secrets.
 */
class MicrosoftOAuthFlowTests extends MicrosoftOAuthTestBase {

  @Autowired
  private TokenCryptoProvider crypto;

  // -- authorize gating ------------------------------------------------------

  @Test
  void unauthenticatedAuthorizeIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/oauth/microsoft/authorize"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unverifiedUserCannotAuthorize() throws Exception {
    String email = freshEmail("unverified");
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());

    mockMvc.perform(authedGet("/api/v1/oauth/microsoft/authorize", tokens.accessToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void unknownProviderIsGone() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("nope"), "Str0ng-Enough-Password");
    mockMvc.perform(authedGet("/api/v1/oauth/yahoo/authorize", tokens.accessToken()))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("OAUTH_PROVIDER_DISABLED"));
  }

  @Test
  void authorizeReturnsSafeUrlWithReadOnlyScopes() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("authz"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());
    assertEquals(200, data.status());
    assertNotNull(data.authorizationUrl());
    assertNotNull(data.state());

    String url = data.authorizationUrl();
    assertTrue(url.startsWith(
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"));
    assertTrue(url.contains("client_id=test-ms-client-id"));
    assertTrue(url.contains("response_type=code"));
    assertTrue(url.contains("prompt=consent"));
    assertTrue(url.contains("code_challenge="));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("state=" + data.state()));
    // Delegated READ-only scopes: identity + Graph profile + mailbox read…
    assertTrue(url.contains("openid"));
    assertTrue(url.contains("User.Read"));
    assertTrue(url.contains("Mail.Read"));
    // …and no SEND/WRITE scope literal may ever appear.
    assertFalse(url.contains("Mail.Send"));
    assertFalse(url.contains("Mail.ReadWrite"));
    // Client secret never travels to the browser.
    assertFalse(url.contains("test-ms-client-secret-synth"));
    assertFalse(data.body().contains("test-ms-client-secret-synth"));
  }

  @Test
  void evilReturnUrlIsRejected() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("evil"), "Str0ng-Enough-Password");
    mockMvc.perform(authedGet(
        "/api/v1/oauth/microsoft/authorize?returnUrl=https://evil.example/cb",
        tokens.accessToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  // -- state discipline ------------------------------------------------------

  @Test
  void callbackRejectsMissingAndUnknownState() throws Exception {
    MvcResult missing = callback("microsoft", "ok-ms-anything", null, null);
    assertEquals(400, missing.getResponse().getStatus());
    assertTrue(missing.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
    assertNull(missing.getResponse().getHeader("Location"));

    MvcResult unknown = callback("microsoft", "ok-ms-anything", "no-such-state", null);
    assertEquals(400, unknown.getResponse().getStatus());
    assertTrue(unknown.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
    assertNull(unknown.getResponse().getHeader("Location"));
  }

  @Test
  void callbackRejectsExpiredState() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("exp"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());
    OAuthState row = states.findByStateHash(shaOf(data.state())).orElseThrow();
    expireRow(row);

    MvcResult result = callback("microsoft", "ok-ms-expired", data.state(), null);
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void stateIsSingleUse() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("replay"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());

    MvcResult first = callback("microsoft", "ok-ms-replay", data.state(), null);
    assertEquals(302, first.getResponse().getStatus());

    MvcResult second = callback("microsoft", "ok-ms-replay", data.state(), null);
    assertEquals(400, second.getResponse().getStatus());
    assertTrue(second.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
  }

  @Test
  void connectionBelongsToStateOwner() throws Exception {
    LoginTokens owner = verifiedLogin(freshEmail("owner"), "Str0ng-Enough-Password");
    LoginTokens other = verifiedLogin(freshEmail("other"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(owner.accessToken());

    // The callback carries no credentials; ownership resolves from the state.
    MvcResult result = callback("microsoft", "ok-ms-bound", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    assertEquals(1, connections.findByUser_Id(userIdOf(owner)).size());
    assertEquals(0, connections.findByUser_Id(userIdOf(other)).size());
  }

  // -- callback outcomes -----------------------------------------------------

  @Test
  void denialRedirectsWithSafeErrorAndCreatesNothing() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("deny"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());

    MvcResult result = callback("microsoft", null, data.state(), "access_denied");
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
    AuthorizeData data = authorize(tokens.accessToken());

    MvcResult result = callback("microsoft", "fail-exchange", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    assertTrue(locationOf(result).contains("PROVIDER_ERROR"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void accountLookupFailureRedirectsSafely() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("acctfail"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());

    MvcResult result = callback("microsoft", "ok-ms-fail-account", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    assertTrue(locationOf(result).contains("PROVIDER_ERROR"));
    assertEquals(0, connections.findByUser_Id(userIdOf(tokens)).size());
  }

  @Test
  void successPersistsEncryptedConnectionAndRedirects() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("happy"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());

    MvcResult result = callback("microsoft", "ok-ms-happy", data.state(), null);
    assertEquals(302, result.getResponse().getStatus());
    String location = locationOf(result);
    assertTrue(location.contains("status=connected"));
    assertTrue(location.contains("provider=microsoft"));
    assertFalse(location.contains("acc-ok-ms-happy"));

    List<OAuthConnection> rows = connections.findByUser_Id(userIdOf(tokens));
    assertEquals(1, rows.size());
    OAuthConnection row = rows.get(0);
    assertEquals("microsoft", row.getProvider());
    assertEquals("ACTIVE", row.getStatus());
    assertEquals("ms-id-acc-ok-ms-happy", row.getProviderAccountId());
    assertNotNull(row.getExpiresAt());
    // Ciphertext at rest, decryptable by the server seam only.
    assertFalse(row.getAccessTokenEnc().contains("acc-ok-ms-happy"));
    assertFalse(row.getRefreshTokenEnc().contains("ref-ok-ms-happy"));
    assertEquals("acc-ok-ms-happy", crypto.decrypt(row.getAccessTokenEnc()));
    assertEquals("ref-ok-ms-happy", crypto.decrypt(row.getRefreshTokenEnc()));
    assertEquals(1, row.getKeyVersion());
  }

  @Test
  void reconnectUpdatesSameRow() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("dupe"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData first = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-same-id", first.state(), null);
    AuthorizeData second = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-same-id-again", second.state(), null);

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    assertEquals("ACTIVE", rows.get(0).getStatus());
    assertNull(rows.get(0).getRevokedAt());
    assertEquals("acc-ok-ms-same-id-again",
        crypto.decrypt(rows.get(0).getAccessTokenEnc()));
  }

  @Test
  void reconnectWithoutFreshRefreshKeepsStoredOne() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("norefresh"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);

    AuthorizeData first = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-same-id", first.state(), null);
    assertEquals("ref-ok-ms-same-id",
        crypto.decrypt(connections.findByUser_Id(userId).get(0).getRefreshTokenEnc()));

    // Same provider account, but the grant withholds a fresh refresh token:
    // the previously stored one must survive, and there is still one row.
    AuthorizeData second = authorize(tokens.accessToken());
    MvcResult result = callback("microsoft", "ok-ms-same-id-no-refresh", second.state(), null);
    assertEquals(302, result.getResponse().getStatus());

    List<OAuthConnection> rows = connections.findByUser_Id(userId);
    assertEquals(1, rows.size());
    assertEquals("ref-ok-ms-same-id", crypto.decrypt(rows.get(0).getRefreshTokenEnc()));
    assertEquals("acc-ok-ms-same-id-no-refresh",
        crypto.decrypt(rows.get(0).getAccessTokenEnc()));
  }

  // -- connections list / disconnect / refresh -------------------------------

  @Test
  void connectionsListExposesNoSecrets() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("list"), "Str0ng-Enough-Password");
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-list", data.state(), null);

    MvcResult result = mockMvc.perform(
        authedGet("/api/v1/oauth/connections", tokens.accessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"provider\":\"microsoft\""));
    assertTrue(body.contains("\"status\":\"ACTIVE\""));
    // Masked, not raw.
    assertTrue(body.contains("o***@example.com"));
    assertFalse(body.contains("owner-ms-id-acc-ok-ms-list@example.com"));
    for (String forbidden : new String[]{
        "acc-ok-ms-list", "ref-ok-ms-list", "test-ms-client-secret-synth",
        "accessTokenEnc", "refreshTokenEnc", "code_verifier", "codeVerifier"}) {
      assertFalse(body.contains(forbidden), "leak: " + forbidden);
    }
    JsonNode json = objectMapper.readTree(body);
    assertTrue(json.isArray() && json.size() == 1);
  }

  @Test
  void disconnectWipesAndAuditsWithoutClaimingProviderRevocation() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("bye"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-bye", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();

    mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isNoContent());

    OAuthConnection row = connections.findById(id).orElseThrow();
    assertEquals("REVOKED", row.getStatus());
    assertNull(row.getAccessTokenEnc());
    assertNull(row.getRefreshTokenEnc());
    assertNotNull(row.getRevokedAt());

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
    AuthorizeData data = authorize(owner.accessToken());
    callback("microsoft", "ok-ms-victim", data.state(), null);
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
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-rot", data.state(), null);
    UUID id = connections.findByUser_Id(userId).get(0).getId();

    MvcResult result = mockMvc.perform(post("/api/v1/oauth/connections/" + id + "/refresh")
        .header("Authorization", "Bearer " + tokens.accessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"status\":\"ACTIVE\""));
    assertFalse(body.contains("acc-refreshed-"));
    assertEquals("acc-refreshed-ref-ok-ms-rot",
        crypto.decrypt(connections.findById(id).orElseThrow().getAccessTokenEnc()));
  }

  @Test
  void invalidRefreshRequiresReauthorization() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("badref"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-badref", data.state(), null);
    OAuthConnection row = connections.findByUser_Id(userId).get(0);
    // Corrupt the stored refresh token: the provider rejects it, so the
    // connection must be marked EXPIRED — never silently retried forever.
    row.setRefreshTokenEnc(crypto.encrypt("ref-invalid-ms-token"));
    connections.saveAndFlush(row);

    MvcResult result = mockMvc.perform(post(
        "/api/v1/oauth/connections/" + row.getId() + "/refresh")
        .header("Authorization", "Bearer " + tokens.accessToken())).andReturn();
    assertEquals(400, result.getResponse().getStatus());
    assertEquals("EXPIRED",
        connections.findById(row.getId()).orElseThrow().getStatus());
  }

  @Test
  void refreshRateLimited() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("rl"), "Str0ng-Enough-Password");
    UUID userId = userIdOf(tokens);
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-rl", data.state(), null);
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
    AuthorizeData data = authorize(tokens.accessToken());
    callback("microsoft", "ok-ms-audit", data.state(), null);
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
    // appear inside the provider account id ("ms-id-acc-…"), which is
    // audited as the connection's entity reference — never as token
    // material.
    assertFalse(serialized.contains("ref-ok-ms-audit"), "audit leak: refresh token");
    String scrubbed = serialized.replace("ms-id-acc-ok-ms-audit", "");
    for (String forbidden : new String[]{
        "acc-ok-ms-audit", "ok-ms-audit", "test-ms-client-secret-synth"}) {
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
      AuthorizeData data = authorize(tokens.accessToken());
      callback("microsoft", "ok-ms-sentinel-xyz", data.state(), null);
      UUID id = connections.findByUser_Id(userIdOf(tokens)).get(0).getId();
      mockMvc.perform(delete("/api/v1/oauth/connections/" + id)
          .header("Authorization", "Bearer " + tokens.accessToken()));

      StringBuilder all = new StringBuilder();
      appender.list.forEach(event -> all.append(event.getFormattedMessage()).append('\n'));
      String logs = all.toString();
      for (String forbidden : new String[]{
          "acc-ok-ms-sentinel-xyz", "ref-ok-ms-sentinel-xyz", "ok-ms-sentinel-xyz",
          "test-ms-client-secret-synth", data.state()}) {
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
