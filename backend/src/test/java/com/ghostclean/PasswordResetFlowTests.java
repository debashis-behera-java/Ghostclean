package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ghostclean.auth.AuthProperties;
import com.ghostclean.auth.PasswordResetEmailContent;
import com.ghostclean.user.PasswordResetToken;
import com.ghostclean.user.PasswordResetTokenRepository;
import com.ghostclean.user.User;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Forgot/reset password lifecycle: anti-enumeration, CSPRNG single-use
 * expiring tokens (hash at rest only), password change with session
 * revocation, browser GET flow with token-free redirects, rate limiting,
 * log/response hygiene, and unchanged OAuth/verification gating.
 */
class PasswordResetFlowTests extends AuthTestBase {

  @Autowired
  private PasswordResetTokenRepository resetTokens;

  @Autowired
  private AuthProperties authProperties;

  // -- anti-enumeration ------------------------------------------------------

  @Test
  void forgotExistingReturnsGenericAccepted() throws Exception {
    String email = "rst-ok-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    int before = mailCapture.resetCount();

    mockMvc.perform(postJson("/api/v1/auth/password/forgot", Map.of("email", email)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.message").value(
            "If an account exists for this email, a password reset link has been sent."));

    assertEquals(before + 1, mailCapture.resetCount());
    assertNotNull(mailCapture.latestResetToken());
  }

  @Test
  void forgotUnknownReturnsIdenticalGenericAccepted() throws Exception {
    String known = "rst-known-" + UUID.randomUUID() + "@example.com";
    register(known, "Str0ng-Enough-Password", nextIp());
    String unknown = "nobody-" + UUID.randomUUID() + "@example.com";
    int before = mailCapture.resetCount();

    String knownBody = mockMvc.perform(postJson("/api/v1/auth/password/forgot",
            Map.of("email", known)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    String unknownBody = mockMvc.perform(postJson("/api/v1/auth/password/forgot",
            Map.of("email", unknown)))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();

    assertEquals(knownBody, unknownBody);
    // Exactly one mail (the known address); unknown emits nothing.
    assertEquals(before + 1, mailCapture.resetCount());
  }

  @Test
  void forgotRequiresNoAuthentication() throws Exception {
    mockMvc.perform(post("/api/v1/auth/password/forgot")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                Map.of("email", "anon-" + UUID.randomUUID() + "@example.com"))))
        .andExpect(status().isAccepted());
  }

  // -- token properties ------------------------------------------------------

  @Test
  void resetTokensAreRandomAndHashedAtRest() throws Exception {
    String first = requestResetToken("rst-rnd-a-" + UUID.randomUUID() + "@example.com");
    String second = requestResetToken("rst-rnd-b-" + UUID.randomUUID() + "@example.com");
    assertNotEquals(first, second);

    // Raw token never persisted; only its SHA-256 hash is stored.
    List<String> hashes = resetTokens.findAll().stream()
        .map(PasswordResetToken::getTokenHash).toList();
    assertFalse(hashes.contains(first));
    assertFalse(hashes.contains(second));
    assertTrue(hashes.contains(sha256Hex(first)));
    assertTrue(hashes.contains(sha256Hex(second)));
    assertTrue(resetTokens.findByTokenHash(sha256Hex(first)).isPresent());
  }

  @Test
  void resetTokenDefaultsToThirtyMinuteExpiry() throws Exception {
    String email = "rst-ttl-" + UUID.randomUUID() + "@example.com";
    String token = requestResetToken(email);
    User user = userRepository.findByEmail(email).orElseThrow();
    PasswordResetToken entity = resetTokens.findByUser_IdAndUsedAtIsNull(user.getId())
        .stream().filter(t -> t.getTokenHash().equals(sha256Hex(token)))
        .findFirst().orElseThrow();
    Duration lifetime = Duration.between(entity.getCreatedAt(), entity.getExpiresAt());
    // TIMESTAMPTZ microsecond storage truncates sub-millisecond precision.
    assertTrue(lifetime.compareTo(authProperties.getResetTokenTtl()) <= 0
            && lifetime.compareTo(authProperties.getResetTokenTtl().minusSeconds(5)) > 0,
        "reset token lifetime must be ~30 minutes, was " + lifetime);
  }

  @Test
  void secondRequestInvalidatesPreviousToken() throws Exception {
    String email = "rst-sup-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String first = requestResetToken(email);
    String second = requestResetToken(email);
    assertNotEquals(first, second);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", first, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", second, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk());
  }

  // -- consumption -----------------------------------------------------------

  @Test
  void successfulResetChangesPasswordAndKillsOldOne() throws Exception {
    String email = "rst-pw-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Password reset successful."));

    // Old password dead, new password works.
    assertEquals(401, login(email, "Str0ng-Enough-Password", nextIp()).status());
    LoginTokens fresh = login(email, "N3w-Str0ng-Password", nextIp());
    assertEquals(200, fresh.status());

    // Stored hash verifies the new password; nothing plaintext adjacent.
    User user = userRepository.findByEmail(email).orElseThrow();
    assertFalse(user.getPasswordHash().contains("N3w-Str0ng-Password"));
  }

  @Test
  void consumedTokenCannotBeReplayed() throws Exception {
    String email = "rst-replay-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk());
    String replayBody = mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "An0ther-Str0ng-Password")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        .andReturn().getResponse().getContentAsString();

    // Replayed, invalid, and unknown tokens share one indistinguishable code.
    String bogusBody = mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", "no-such-token", "newPassword", "An0ther-Str0ng-Password")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
        .andReturn().getResponse().getContentAsString();
    assertEquals(
        objectMapper.readTree(replayBody).get("code").asText(),
        objectMapper.readTree(bogusBody).get("code").asText());
  }

  @Test
  void expiredTokenRejectedAndConsumed() throws Exception {
    String email = "rst-exp-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);
    expireActiveToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"));

    // Expired token is consumed: a second attempt reads as invalid, and the
    // password is unchanged.
    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isBadRequest());
    assertEquals(200, login(email, "Str0ng-Enough-Password", nextIp()).status());
  }

  @Test
  void weakPasswordRejectedWithoutConsumingToken() throws Exception {
    String email = "rst-weak-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "short")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    // Token survives the weak attempt and still works once.
    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk());
  }

  @Test
  void resetRevokesExistingRefreshSessions() throws Exception {
    String email = "rst-sess-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());
    String token = requestResetToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk());

    // Pre-reset refresh session is dead.
    mockMvc.perform(post("/api/v1/auth/refresh")
            .cookie(new Cookie("gc_refresh", tokens.refreshCookie())))
        .andExpect(status().isUnauthorized());
    // Fresh login issues a working session.
    LoginTokens fresh = login(email, "N3w-Str0ng-Password", nextIp());
    mockMvc.perform(post("/api/v1/auth/refresh")
            .cookie(new Cookie("gc_refresh", fresh.refreshCookie())))
        .andExpect(status().isOk());
  }

  @Test
  void resetPreservesVerificationState() throws Exception {
    String email = "rst-vstate-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
    String token = requestResetToken(email);

    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", token, "newPassword", "N3w-Str0ng-Password")))
        .andExpect(status().isOk());

    // Reset changes credentials only: verification gating is untouched.
    assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
    assertEquals(false,
        login(email, "N3w-Str0ng-Password", nextIp()).body().get("emailVerified").asBoolean());
  }

  // -- browser GET flow --------------------------------------------------------

  @Test
  void browserPageRendersForValidToken() throws Exception {
    String email = "rst-web-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);

    mockMvc.perform(browserGet("/api/v1/auth/password/reset?token=" + token))
        .andExpect(status().isOk())
        .andExpect(result -> {
          String body = result.getResponse().getContentAsString();
          assertTrue(body.contains("password"));
          assertTrue(body.contains("/api/v1/auth/password/reset"));
        });
  }

  @Test
  void browserPageRedirectsToTokenFreeFailure() throws Exception {
    String bogus = "no-such-token-" + UUID.randomUUID();
    mockMvc.perform(browserGet("/api/v1/auth/password/reset?token=" + bogus))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/password/reset/failed"))
        .andExpect(result -> assertFalse(
            result.getResponse().getHeader("Location").contains(bogus)));

    mockMvc.perform(browserGet("/api/v1/auth/password/reset"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/password/reset/failed"));

    mockMvc.perform(browserGet("/api/v1/auth/password/reset/success"))
        .andExpect(status().isOk());
    mockMvc.perform(browserGet("/api/v1/auth/password/reset/failed"))
        .andExpect(status().isBadRequest())
        .andExpect(result -> assertFalse(
            result.getResponse().getContentAsString().contains(bogus)));
  }

  @Test
  void expiredTokenPageRedirectsToFailure() throws Exception {
    String email = "rst-webexp-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String token = requestResetToken(email);
    expireActiveToken(email);

    mockMvc.perform(browserGet("/api/v1/auth/password/reset?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/password/reset/failed"));
  }

  // -- rate limiting -----------------------------------------------------------

  @Test
  void forgotRateLimitedPerIp() throws Exception {
    String ip = nextIp();
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(postJson("/api/v1/auth/password/forgot",
              Map.of("email", "rl-" + UUID.randomUUID() + "@example.com"), ip))
          .andExpect(status().isAccepted());
    }
    mockMvc.perform(postJson("/api/v1/auth/password/forgot",
            Map.of("email", "rl-" + UUID.randomUUID() + "@example.com"), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void forgotRateLimitedPerEmail() throws Exception {
    String email = "rst-rl-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String ip = nextIp();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(postJson("/api/v1/auth/password/forgot",
              Map.of("email", email), ip))
          .andExpect(status().isAccepted());
    }
    mockMvc.perform(postJson("/api/v1/auth/password/forgot",
            Map.of("email", email), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void resetRateLimitedPerIpAgainstBruteForce() throws Exception {
    String ip = nextIp();
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(postJson("/api/v1/auth/password/reset",
              Map.of("token", "guess-" + i, "newPassword", "N3w-Str0ng-Password"), ip))
          .andExpect(status().isBadRequest());
    }
    mockMvc.perform(postJson("/api/v1/auth/password/reset",
            Map.of("token", "guess-final", "newPassword", "N3w-Str0ng-Password"), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  // -- hygiene: logs, responses, content -----------------------------------------

  @Test
  void resetTokenNeverReachesLogs() throws Exception {
    ch.qos.logback.classic.LoggerContext context =
        (ch.qos.logback.classic.LoggerContext)
            org.slf4j.LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger root =
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<
        ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      String email = "rst-log-" + UUID.randomUUID() + "@example.com";
      register(email, "Str0ng-Enough-Password", nextIp());
      String token = requestResetToken(email);
      mockMvc.perform(postJson("/api/v1/auth/password/reset",
          Map.of("token", token, "newPassword", "N3w-Str0ng-Password")));

      StringBuilder all = new StringBuilder();
      appender.list.forEach(event -> all.append(event.getFormattedMessage()).append('\n'));
      String logs = all.toString();
      assertFalse(logs.contains(token), "raw reset token must never be logged");
      assertEquals(-1, logs.indexOf("token="), "reset links must never be logged");
      assertFalse(logs.contains("N3w-Str0ng-Password"), "password must never be logged");
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  void resetEmailCarriesLinkWithoutSecrets() throws Exception {
    String sentinelPassword = "S3ntinel-Pw-" + UUID.randomUUID();
    String link = "http://localhost:8082/api/v1/auth/password/reset?token=abc123";
    PasswordResetEmailContent.EmailBody body = PasswordResetEmailContent.build(link);

    assertTrue(body.subject().contains("GhostClean"));
    assertTrue(body.text().contains(link));
    assertTrue(body.text().contains("30 minutes"));
    assertTrue(body.text().contains("only once"));
    assertTrue(body.text().contains("did not request"));
    assertTrue(body.html().contains("Reset your password"));
    assertFalse(body.text().contains(sentinelPassword));
    assertFalse(body.html().contains(sentinelPassword));
    for (String forbidden : new String[]{
        "apiKey", "api-key", "API_KEY", "client_secret", "clientSecret"}) {
      assertFalse(body.text().contains(forbidden), "leak: " + forbidden);
      assertFalse(body.html().contains(forbidden), "leak: " + forbidden);
    }
  }

  @Test
  void resetLinkUsesConfiguredBaseUrl() throws Exception {
    String email = "rst-url-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    requestResetToken(email);
    String link = mailCapture.latestResetLink();
    assertTrue(link.startsWith("http://localhost:8080/api/v1/auth/password/reset?token="),
        "reset link must use the configured base URL");
    assertFalse(link.contains(" "), "reset link must not contain spaces");
  }

  // -- architecture guards ---------------------------------------------------------

  @Test
  void noBackgroundSchedulerOrAsyncInMainSources() throws Exception {
    Path base = Paths.get("src", "main");
    if (!Files.exists(base)) {
      base = Paths.get("backend", "src", "main");
    }
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(base)) {
      for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        if (!file.toString().endsWith(".java")) {
          continue;
        }
        String content = Files.readString(file);
        for (String needle : new String[]{"@Scheduled", "@Async"}) {
          if (content.contains(needle)) {
            offenders.add(base.relativize(file) + " contains " + needle);
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(), "no scheduler/async allowed: " + offenders);
  }

  // -- helpers -------------------------------------------------------------------

  private String requestResetToken(String email) throws Exception {
    register(email, "Str0ng-Enough-Password", nextIp());
    int before = mailCapture.resetCount();
    mockMvc.perform(postJson("/api/v1/auth/password/forgot", Map.of("email", email)))
        .andExpect(status().isAccepted());
    assertEquals(before + 1, mailCapture.resetCount());
    return mailCapture.latestResetToken();
  }

  private void expireActiveToken(String email) {
    User user = userRepository.findByEmail(email).orElseThrow();
    List<PasswordResetToken> tokens =
        resetTokens.findByUser_IdAndUsedAtIsNull(user.getId());
    assertFalse(tokens.isEmpty());
    PasswordResetToken entity = tokens.get(0);
    entity.setExpiresAt(Instant.now().minusSeconds(60));
    resetTokens.save(entity);
  }

  private static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder browserGet(
      String url) {
    var request = get(url);
    request.with(r -> {
      r.setRemoteAddr("10.40.0.9");
      return r;
    });
    return request;
  }
}
