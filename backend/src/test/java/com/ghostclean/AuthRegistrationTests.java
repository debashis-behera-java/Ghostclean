package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ghostclean.user.User;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Registration: happy path, duplicate-safety (no oracle), password policy,
 * hashing, and leak-free responses.
 */
class AuthRegistrationTests extends AuthTestBase {

  @Test
  void registerSuccessCreatesUnverifiedUserPlusTokenAndSettings() throws Exception {
    String email = "reg-success-" + UUID.randomUUID() + "@example.com";
    int mailsBefore = mailCapture.count();

    mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", "Str0ng-Enough-Password")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.message").value("Registration successful. Please verify your email."))
        .andExpect(jsonPath("$.verificationRequired").value(true));

    User user = userRepository.findByEmail(email).orElseThrow();
    assertFalse(user.isEmailVerified());
    assertEquals("pending_verification", user.getStatus());
    assertEquals(mailsBefore + 1, mailCapture.count());
  }

  @Test
  void duplicateRegistrationIsIndistinguishable() throws Exception {
    String email = "reg-dupe-" + UUID.randomUUID() + "@example.com";
    String password = "Str0ng-Enough-Password";

    String first = mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", password)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    // Same address, still unverified: identical response.
    String second = mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", password)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    assertEquals(first, second);

    // Verify, then try again: still identical, and no new mail goes out.
    String token = mailCapture.latestToken();
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token)));
    int mails = mailCapture.count();
    String third = mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", password)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    assertEquals(first, third);
    assertEquals(mails, mailCapture.count());
  }

  @Test
  void weakPasswordsRejected() throws Exception {
    String email = "reg-weak-" + UUID.randomUUID() + "@example.com";
    for (String weak : new String[]{"short1A!", "Password123", "   ", "password"}) {
      mockMvc.perform(postJson("/api/v1/auth/register",
          Map.of("email", email, "password", weak)))
          .andExpect(status().isBadRequest());
    }
    assertTrue(userRepository.findByEmail(email).isEmpty());
  }

  @Test
  void invalidEmailRejected() throws Exception {
    mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", "not-an-email", "password", "Str0ng-Enough-Password")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void passwordStoredAsHashNeverPlaintext() throws Exception {
    String email = "reg-hash-" + UUID.randomUUID() + "@example.com";
    String password = "Str0ng-Enough-Password";
    mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", password)))
        .andExpect(status().isCreated());
    User user = userRepository.findByEmail(email).orElseThrow();
    assertTrue(user.getPasswordHash().startsWith("$2a$"));
    assertFalse(user.getPasswordHash().contains(password));
  }

  @Test
  void registrationResponseCarriesNoSecrets() throws Exception {
    String email = "reg-leak-" + UUID.randomUUID() + "@example.com";
    String body = mockMvc.perform(postJson("/api/v1/auth/register",
        Map.of("email", email, "password", "Str0ng-Enough-Password")))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString().toLowerCase();
    for (String forbidden : Set.of("password_hash", "$2a$", "token", "secret")) {
      assertFalse(body.contains(forbidden), "response must not contain: " + forbidden);
    }
  }

  @Test
  void malformedJsonBodyIsClientErrorNotServerError() throws Exception {    // Regression: an unreadable request body (malformed JSON as produced by
    // shells that mangle quoting, or an empty body) must answer 400 through
    // the validation envelope — never 500 INTERNAL.
    for (String raw : new String[]{
        "{email:not-an-email}",
        "{",
        ""}) {
      mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
          .post("/api/v1/auth/register")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content(raw))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
  }

  @Test
  void providerOutageKeepsRegistrationSuccessful() throws Exception {
    // Provider failure (e.g. Resend http-auth) must neither turn
    // registration into a 500 nor roll back the persisted user/token:
    // the caller recovers through the resend endpoint.
    String email = "reg-outage-" + UUID.randomUUID() + "@example.com";
    mailCapture.failNextVerification();
    try {
      mockMvc.perform(postJson("/api/v1/auth/register",
          Map.of("email", email, "password", "Str0ng-Enough-Password")))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.verificationRequired").value(true));
    } finally {
      mailCapture.disarmFailure();
    }
    assertTrue(userRepository.findByEmail(email).isPresent());
  }
}
