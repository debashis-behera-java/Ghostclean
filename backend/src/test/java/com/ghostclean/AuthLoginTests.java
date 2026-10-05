package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Login: verified success, documented unverified behavior, identical
 * failures (no oracle), lockout after repeated failures, leak-free bodies.
 */
class AuthLoginTests extends AuthTestBase {

  @Test
  void loginSuccessForVerifiedUser() throws Exception {
    String email = "login-ok-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");

    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());
    assertNotNull(tokens.accessToken());
    assertEquals(3, tokens.accessToken().split("\\.").length);
    assertEquals("Bearer", tokens.body().get("tokenType").asText());
    assertTrue(tokens.body().get("expiresIn").asLong() > 0);
    assertTrue(tokens.body().get("emailVerified").asBoolean());
    assertNotNull(tokens.refreshCookie());
    assertTrue(tokens.setCookieHeader().contains("HttpOnly"));
  }

  @Test
  void unverifiedLoginSucceedsButFlagged() throws Exception {
    String email = "login-unver-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());

    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, tokens.status());
    assertNotNull(tokens.accessToken());
    // Documented behavior: tokens are issued so the user can reach the
    // verification state; sensitive features gate on verification later.
    assertEquals(false, tokens.body().get("emailVerified").asBoolean());
  }

  @Test
  void wrongPasswordAndUnknownEmailAreIdentical() throws Exception {
    String email = "login-oracle-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");

    String wrongPw = mockMvc.perform(postJson("/api/v1/auth/login",
        Map.of("email", email, "password", "Wrong-Password-000")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        .andReturn().getResponse().getContentAsString();

    String unknown = mockMvc.perform(postJson("/api/v1/auth/login",
        Map.of("email", "ghost-" + UUID.randomUUID() + "@example.com",
            "password", "Wrong-Password-000")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        .andReturn().getResponse().getContentAsString();

    // Same shape: only requestId/timestamp differ.
    assertEquals(
        wrongPw.replaceAll("\"requestId\":\"[^\"]+\"", "")
            .replaceAll("\"timestamp\":\"[^\"]+\"", ""),
        unknown.replaceAll("\"requestId\":\"[^\"]+\"", "")
            .replaceAll("\"timestamp\":\"[^\"]+\"", ""));
  }

  @Test
  void repeatedFailuresLockTheAccount() throws Exception {
    String email = "login-lock-" + UUID.randomUUID() + "@example.com";
    String password = "Str0ng-Enough-Password";
    verifiedUser(email, password);

    // Distributed-style failures: distinct IPs so only the per-account
    // counter (5 fails -> 15m lock) engages, not the per-IP budget.
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(postJson("/api/v1/auth/login",
          Map.of("email", email, "password", "Wrong-Password-000")))
          .andExpect(status().isUnauthorized());
    }
    // Even the right password is now refused with 423.
    mockMvc.perform(postJson("/api/v1/auth/login",
        Map.of("email", email, "password", password)))
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
  }

  @Test
  void loginBodyCarriesExactlyTheDocumentedKeys() throws Exception {
    String email = "login-keys-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    Set<String> keys = new java.util.HashSet<>();
    tokens.body().fieldNames().forEachRemaining(keys::add);
    assertEquals(Set.of("accessToken", "tokenType", "expiresIn", "emailVerified"), keys);
  }
}
