package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Session lifecycle: rotation on refresh, reuse detection with chain
 * revocation, logout invalidation, and expired/tampered access rejection.
 */
class AuthSessionTests extends AuthTestBase {

  @Test
  void refreshRotatesAndKillsThePresentedToken() throws Exception {
    String email = "sess-rot-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens first = login(email, "Str0ng-Enough-Password", nextIp());
    assertEquals(200, first.status());

    var second = mockMvc.perform(post("/api/v1/auth/refresh")
        .cookie(new Cookie("gc_refresh", first.refreshCookie())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists())
        .andReturn();
    String rotatedCookie =
        second.getResponse().getCookie("gc_refresh").getValue();
    assertNotNull(rotatedCookie);
    assertNotEquals(first.refreshCookie(), rotatedCookie);

    // The rotated-in cookie works; the consumed one is dead — and because
    // reuse looks like theft, presenting it revokes the whole chain.
    mockMvc.perform(post("/api/v1/auth/refresh")
        .cookie(new Cookie("gc_refresh", first.refreshCookie())))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/auth/refresh")
        .cookie(new Cookie("gc_refresh", rotatedCookie)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshWithoutCookieIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/auth/refresh"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutRevokesSessionAndClearsCookie() throws Exception {
    String email = "sess-out-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());

    var logout = mockMvc.perform(post("/api/v1/auth/logout")
        .cookie(new Cookie("gc_refresh", tokens.refreshCookie())))
        .andExpect(status().isNoContent())
        .andReturn();
    String cleared = logout.getResponse().getHeader("Set-Cookie");
    assertNotNull(cleared);

    mockMvc.perform(post("/api/v1/auth/refresh")
        .cookie(new Cookie("gc_refresh", tokens.refreshCookie())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutWithoutCredentialsIsRejected() throws Exception {
    mockMvc.perform(post("/api/v1/auth/logout"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredAccessTokenIsRejected() throws Exception {
    String email = "sess-exp-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    UUID userId = userRepository.findByEmail(email).orElseThrow().getId();
    String expired = jwtService.createAccessToken(userId, true, Duration.ofSeconds(-60));

    mockMvc.perform(authedGet("/api/v1/users/me", expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void tamperedAccessTokenIsRejected() throws Exception {
    String email = "sess-tamper-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());
    String tampered = tokens.accessToken().substring(0, tokens.accessToken().length() - 2) + "AA";

    mockMvc.perform(authedGet("/api/v1/users/me", tampered))
        .andExpect(status().isUnauthorized());
    // Untouched token still works.
    mockMvc.perform(authedGet("/api/v1/users/me", tokens.accessToken()))
        .andExpect(status().isOk());
  }

  @Test
  void authenticatedProfileReturnsOwnFactsOnly() throws Exception {
    String email = "sess-me-" + UUID.randomUUID() + "@example.com";
    verifiedUser(email, "Str0ng-Enough-Password");
    LoginTokens tokens = login(email, "Str0ng-Enough-Password", nextIp());

    mockMvc.perform(authedGet("/api/v1/users/me", tokens.accessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.emailVerified").value(true));
  }
}
