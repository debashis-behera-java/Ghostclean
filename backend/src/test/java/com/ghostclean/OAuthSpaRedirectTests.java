package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SPA redirect contract for Google OAuth.
 *
 * <p>Regression for the production 401 dead-end: the post-consent redirect
 * must target the SPA callback route (same origin as {@code app.base-url}),
 * and the provider callback itself must never 401 for a missing Bearer
 * token (it is intentionally permitAll — authorization comes from the
 * validated state/code flow, never from a pre-existing JWT).
 */
class OAuthSpaRedirectTests extends OAuthTestBase {

  @Test
  void providerCallbackNever401sWithoutBearer() throws Exception {
    MvcResult result = callback("google", null, "no-such-state-" + UUID.randomUUID(), null);
    assertEquals(400, result.getResponse().getStatus());
    assertTrue(result.getResponse().getContentAsString().contains("OAUTH_STATE_MISMATCH"));
  }

  @Test
  void authorizeAcceptsSpaOriginReturnUrlAndCallbackRedirectsThere() throws Exception {
    LoginTokens tokens = verifiedLogin(freshEmail("sparedirect"), "Str0ng-Enough-Password");
    UUID userId = jwtService.parse(tokens.accessToken()).userId();
    var request = approvals.request(userId);
    approvals.approve(googleAdminId(), request.requestId());

    // The SPA origin matches the test app.base-url default (localhost:8080).
    String spaCallback = "http://localhost:8080/oauth/callback";
    MvcResult authorized = mockMvc.perform(
        authedGet("/api/v1/oauth/google/authorize?returnUrl=" + spaCallback,
            tokens.accessToken())).andReturn();
    assertEquals(200, authorized.getResponse().getStatus());

    String state = json(authorized).get("state").asText();
    MvcResult denied = callback("google", null, state, "access_denied");
    assertEquals(302, denied.getResponse().getStatus());
    String location = locationOf(denied);
    assertTrue(location.startsWith(spaCallback + "?provider=google&status=error"),
        "post-consent redirect must land on the SPA route, got: " + location);
  }

  @Test
  void protectedEndpointsStillRequireAuthentication() throws Exception {
    assertEquals(401, mockMvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/v1/discovered")).andReturn().getResponse().getStatus());
    assertEquals(401, mockMvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/oauth/callback?provider=google&status=connected"))
        .andReturn().getResponse().getStatus());
  }

  private com.fasterxml.jackson.databind.JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
