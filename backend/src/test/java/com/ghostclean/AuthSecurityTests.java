package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ghostclean.audit.AuditService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Adversarial posture: isolation by construction, per-IP budgets, no
 * credential in responses/logs/audit, and the audit denylist itself.
 */
class AuthSecurityTests extends AuthTestBase {

  @Autowired
  private AuditService auditService;

  @Test
  void usersSeeOnlyTheirOwnProfile() throws Exception {
    String emailA = "sec-a-" + UUID.randomUUID() + "@example.com";
    String emailB = "sec-b-" + UUID.randomUUID() + "@example.com";
    verifiedUser(emailA, "Str0ng-Enough-Password");
    verifiedUser(emailB, "Str0ng-Enough-Password");
    LoginTokens tokensA = login(emailA, "Str0ng-Enough-Password", nextIp());
    LoginTokens tokensB = login(emailB, "Str0ng-Enough-Password", nextIp());

    String bodyA = mockMvc.perform(authedGet("/api/v1/users/me", tokensA.accessToken()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    String bodyB = mockMvc.perform(authedGet("/api/v1/users/me", tokensB.accessToken()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertTrue(bodyA.contains(emailA));
    assertFalse(bodyA.contains(emailB));
    assertTrue(bodyB.contains(emailB));
    assertFalse(bodyB.contains(emailA));
    assertNotEquals(bodyA, bodyB);
  }

  @Test
  void loginIsRateLimitedPerIp() throws Exception {
    String ip = nextIp();
    for (int i = 0; i < 5; i++) {
      // Distinct unknown addresses: exercises the IP budget only, never the
      // per-account lockout.
      mockMvc.perform(postJson("/api/v1/auth/login",
          Map.of("email", "rl-" + UUID.randomUUID() + "@example.com",
              "password", "Wrong-Password-000"), ip))
          .andExpect(status().isUnauthorized());
    }
    mockMvc.perform(postJson("/api/v1/auth/login",
        Map.of("email", "rl-" + UUID.randomUUID() + "@example.com",
            "password", "Wrong-Password-000"), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void credentialsNeverReachLogs() throws Exception {
    ch.qos.logback.classic.LoggerContext context =
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      String sentinel = "S3ntinel-Pw-9x8z-" + UUID.randomUUID();
      String email = "sec-log-" + UUID.randomUUID() + "@example.com";
      register(email, sentinel, nextIp());
      LoginTokens tokens = login(email, sentinel, nextIp());

      StringBuilder all = new StringBuilder();
      for (ILoggingEvent event : appender.list) {
        all.append(event.getFormattedMessage()).append('\n');
      }
      String logs = all.toString();
      assertFalse(logs.contains(sentinel), "password must never be logged");
      assertFalse(logs.contains("Set-Cookie"), "cookie material must never be logged");
      if (tokens.accessToken() != null) {
        assertFalse(logs.contains(tokens.accessToken()), "JWT must never be logged");
      }
      assertEquals(-1, logs.indexOf("token="), "verification links must never be logged");
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  void auditRejectsSecretBearingMetadata() {
    assertThrows(IllegalArgumentException.class, () ->
        auditService.emit("LOGIN_SUCCESS", UUID.randomUUID(), "user",
            "session", "s1", Map.of("refreshToken", "raw-value")));
    assertThrows(IllegalArgumentException.class, () ->
        auditService.emitAnonymous("LOGIN_FAILED", Map.of("password", "x")));
  }

  @Test
  void anonymousProfileAccessIsRejected() throws Exception {
    mockMvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized());
  }
}
