package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ghostclean.user.EmailVerificationToken;
import com.ghostclean.user.EmailVerificationTokenRepository;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Browser verification-link flow: the emailed GET route consumes the token
 * through the same single-use, expiring pipeline as POST /verify, then
 * redirects to token-free result pages.
 */
class VerificationBrowserFlowTests extends AuthTestBase {

  @Autowired
  private EmailVerificationTokenRepository tokenRepository;

  @Test
  void validLinkVerifiesAccountAndRedirectsToSuccess() throws Exception {
    String email = "web-ok-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/success"));

    mockMvc.perform(browserGet("/api/v1/auth/verify/success"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          String body = result.getResponse().getContentAsString();
          assertTrue(body.contains("verified"));
          assertFalse(body.contains(token));
        });

    User user = userRepository.findByEmail(email).orElseThrow();
    assertTrue(user.isEmailVerified());
    assertEquals("active", user.getStatus());
  }

  @Test
  void invalidLinkRedirectsToGenericFailure() throws Exception {
    String bogus = "no-such-token-" + UUID.randomUUID();
    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + bogus))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/failed"));

    mockMvc.perform(browserGet("/api/v1/auth/verify/failed"))
        .andExpect(status().isBadRequest())
        .andExpect(result -> {
          String body = result.getResponse().getContentAsString();
          assertTrue(body.contains("invalid"));
          assertFalse(body.contains(bogus));
        });
  }

  @Test
  void missingTokenRedirectsToFailureWithoutError() throws Exception {
    mockMvc.perform(browserGet("/api/v1/auth/verify"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/failed"));
  }

  @Test
  void expiredLinkRedirectsToFailureAndStaysUnverified() throws Exception {
    String email = "web-exp-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");
    expireActiveToken(email);

    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/failed"));

    assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
  }

  @Test
  void consumedLinkCannotBeReplayed() throws Exception {
    String email = "web-replay-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/success"));
    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/failed"));
  }

  @Test
  void resultPagesCarryNoTokenMaterial() throws Exception {
    String token = registerAndCaptureToken(
        "web-leak-" + UUID.randomUUID() + "@example.com", "Str0ng-Enough-Password");

    mockMvc.perform(browserGet("/api/v1/auth/verify/success"))
        .andExpect(result ->
            assertFalse(result.getResponse().getContentAsString().contains(token)));
    mockMvc.perform(browserGet("/api/v1/auth/verify/failed"))
        .andExpect(result ->
            assertFalse(result.getResponse().getContentAsString().contains(token)));
  }

  @Test
  void redirectLocationIsAbsolutePathAndCarriesNoToken() throws Exception {
    String email = "web-noleak-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    // Valid link: absolute-path redirect, token-free Location.
    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/success"))
        .andExpect(result -> {
          String location = result.getResponse().getHeader("Location");
          assertTrue(location.startsWith("/"),
              "redirect must be an absolute path, got: " + location);
          assertFalse(location.contains(token), "token leaked into Location");
        });

    // Invalid link: same guarantees on the failure redirect.
    String bogus = "no-such-token-" + UUID.randomUUID();
    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + bogus))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/failed"))
        .andExpect(result -> {
          String location = result.getResponse().getHeader("Location");
          assertTrue(location.startsWith("/"),
              "redirect must be an absolute path, got: " + location);
          assertFalse(location.contains(bogus), "token leaked into Location");
        });
  }

  @Test
  void loginReportsVerifiedAfterBrowserFlow() throws Exception {
    String email = "web-login-" + UUID.randomUUID() + "@example.com";
    String token = registerAndCaptureToken(email, "Str0ng-Enough-Password");

    LoginTokens before = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, before.status());
    assertFalse(before.body().get("emailVerified").asBoolean());

    mockMvc.perform(browserGet("/api/v1/auth/verify?token=" + token))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/api/v1/auth/verify/success"));

    LoginTokens after = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, after.status());
    assertTrue(after.body().get("emailVerified").asBoolean());
  }

  private void expireActiveToken(String email) {
    User user = userRepository.findByEmail(email).orElseThrow();
    List<EmailVerificationToken> tokens =
        tokenRepository.findByUser_IdAndUsedAtIsNull(user.getId());
    assertFalse(tokens.isEmpty());
    EmailVerificationToken entity = tokens.get(0);
    entity.setExpiresAt(Instant.now().minusSeconds(60));
    tokenRepository.save(entity);
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder browserGet(
      String url) {
    // Browsers send no JSON body; the token travels in the query string.
    var request = get(url);
    request.with(r -> {
      r.setRemoteAddr("10.30.0.9");
      return r;
    });
    return request;
  }
}
