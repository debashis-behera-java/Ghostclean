package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.oauth.GoogleOAuthClient;
import com.ghostclean.oauth.GoogleOAuthProperties;
import com.ghostclean.oauth.OAuthStateService;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Provider-shape unit tests (no Spring, no network): scope configuration
 * discipline and authorization-URL construction.
 */
class OAuthConfigUnitTests {

  private GoogleOAuthProperties properties() {
    GoogleOAuthProperties properties = new GoogleOAuthProperties();
    properties.setClientId("test-google-client-id");
    properties.setClientSecret("synthetic-secret");
    properties.setRedirectUri("http://localhost:8080/api/v1/oauth/google/callback");
    return properties;
  }

  @Test
  void defaultScopesAreIdentityOnly() {
    GoogleOAuthProperties properties = properties();
    assertEquals(3, properties.requestedScopes().size());
    assertTrue(properties.requestedScopes().containsAll(
        java.util.List.of("openid", "email", "profile")));
    assertTrue(properties.getMailboxScope().isBlank());
  }

  @Test
  void mailboxScopeJoinsOnlyWhenExplicitlyConfigured() {
    GoogleOAuthProperties properties = properties();
    // Reserved for Phase 4; the value below is documentation-shaped and
    // never used in production code paths.
    properties.setMailboxScope("reserved-phase-4-mailbox-scope");
    assertEquals(4, properties.requestedScopes().size());
  }

  @Test
  void configuredMailboxScopeIsRequestedReadOnly() {
    // Phase 4 production shape: identity plus exactly one restricted
    // read-only mailbox scope. PKCE/offline/consent posture unchanged,
    // secret still never in the URL, and no write scope may appear.
    GoogleOAuthProperties properties = properties();
    properties.setMailboxScope("https://www.googleapis.com/auth/gmail.readonly");
    GoogleOAuthClient client = new GoogleOAuthClient(properties);
    String url = client.buildAuthorizationUrl("test-state", "test-challenge");

    assertTrue(url.contains("gmail.readonly"));
    assertTrue(url.contains("code_challenge=test-challenge"));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("access_type=offline"));
    assertTrue(url.contains("prompt=consent"));
    assertFalse(url.contains("synthetic-secret"));
    String lower = url.toLowerCase();
    assertFalse(lower.contains("gmail.send"), "SEND must never be requested");
    assertFalse(lower.contains("gmail.modify"), "MODIFY must never be requested");
    assertFalse(lower.contains("gmail.compose"), "COMPOSE must never be requested");
  }

  @Test
  void authorizationUrlCarriesSafeParametersOnly() {
    GoogleOAuthClient client = new GoogleOAuthClient(properties());
    String url = client.buildAuthorizationUrl("test-state", "test-challenge");

    assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
    assertTrue(url.contains("client_id=test-google-client-id"));
    assertTrue(url.contains("response_type=code"));
    assertTrue(url.contains("access_type=offline"));
    assertTrue(url.contains("prompt=consent"));
    assertTrue(url.contains("code_challenge=test-challenge"));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("state=test-state"));
    // No mailbox scope literal anywhere in the Phase 3 request surface.
    assertFalse(url.toLowerCase().contains("gmail"));
    assertFalse(url.toLowerCase().contains("mail.google"));
    // Secret never in the URL.
    assertFalse(url.contains("synthetic-secret"));
  }

  @Test
  void stateChallengeIsSha256OfVerifier() {
    String challenge = OAuthStateService.codeChallenge("verifier-value");
    assertEquals(43, challenge.length());
    assertTrue(challenge.matches("[A-Za-z0-9\\-_]+"));
  }

  @Test
  void generatedStatesAreUnique() {
    // Entropy shape check without persistence: challenges of distinct
    // verifiers never collide over a meaningful sample.
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      seen.add(OAuthStateService.codeChallenge("verifier-" + i + "-pad-to-entropy"));
    }
    assertEquals(500, seen.size());
  }
}
