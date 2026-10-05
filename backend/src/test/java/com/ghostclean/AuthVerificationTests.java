package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ghostclean.user.EmailVerificationToken;
import com.ghostclean.user.EmailVerificationTokenRepository;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verification lifecycle: success, invalid/expired/used rejection, replay
 * prevention, resend semantics, and resend rate limiting.
 */
class AuthVerificationTests extends AuthTestBase {

  @Autowired
  private EmailVerificationTokenRepository tokenRepository;

  @Test
  void verifySuccessFlipsUserToVerified() throws Exception {
    String email = "ver-ok-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token)))
        .andExpect(status().isNoContent());

    User user = userRepository.findByEmail(email).orElseThrow();
    assertTrue(user.isEmailVerified());
    assertEquals("active", user.getStatus());
  }

  @Test
  void invalidTokenRejected() throws Exception {
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", "no-such-token")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  void expiredTokenRejected() throws Exception {
    String email = "ver-exp-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");
    User user = userRepository.findByEmail(email).orElseThrow();

    List<EmailVerificationToken> tokens =
        tokenRepository.findByUser_IdAndUsedAtIsNull(user.getId());
    assertFalse(tokens.isEmpty());
    EmailVerificationToken entity = tokens.get(0);
    entity.setExpiresAt(Instant.now().minusSeconds(60));
    tokenRepository.save(entity);

    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token)))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"));
    assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
  }

  @Test
  void usedTokenCannotBeReplayed() throws Exception {
    String email = "ver-replay-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token)))
        .andExpect(status().isNoContent());
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", token)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  void resendIssuesNewTokenAndKillsTheOld() throws Exception {
    String email = "ver-resend-" + UUID.randomUUID() + "@example.com";
    String first = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    mockMvc.perform(postJson("/api/v1/auth/verification/resend", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String second = mailCapture.latestToken();
    assertNotEquals(first, second);

    // Old token is dead.
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", first)))
        .andExpect(status().isBadRequest());
    // New token works.
    mockMvc.perform(postJson("/api/v1/auth/verify", Map.of("token", second)))
        .andExpect(status().isNoContent());
  }

  @Test
  void resendIsNoOpForVerifiedAndUnknownAddresses() throws Exception {
    String email = "ver-noop-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    int mails = mailCapture.count();

    mockMvc.perform(postJson("/api/v1/auth/verification/resend", Map.of("email", email)))
        .andExpect(status().isAccepted());
    mockMvc.perform(postJson("/api/v1/auth/verification/resend",
        Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com")))
        .andExpect(status().isAccepted());
    assertEquals(mails, mailCapture.count());
  }

  @Test
  void resendRateLimitedPerEmail() throws Exception {
    String email = "ver-rl-" + UUID.randomUUID() + "@example.com";
    register(email, "Str0ng-Enough-Password", nextIp());
    String ip = nextIp();
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(postJson("/api/v1/auth/verification/resend",
          Map.of("email", email), ip))
          .andExpect(status().isAccepted());
    }
    mockMvc.perform(postJson("/api/v1/auth/verification/resend",
        Map.of("email", email), ip))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }
}
