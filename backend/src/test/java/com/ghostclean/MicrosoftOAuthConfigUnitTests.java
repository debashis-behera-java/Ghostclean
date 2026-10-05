package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.oauth.MicrosoftOAuthClient;
import com.ghostclean.oauth.MicrosoftOAuthProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Microsoft provider-shape unit tests (no Spring, no network): delegated
 * READ-only scope discipline, authorization-URL construction, and tenant
 * configurability. Mirrors {@code OAuthConfigUnitTests} for Google.
 */
class MicrosoftOAuthConfigUnitTests {

  private MicrosoftOAuthProperties properties() {
    MicrosoftOAuthProperties properties = new MicrosoftOAuthProperties();
    properties.setClientId("test-ms-client-id");
    properties.setClientSecret("synthetic-secret");
    properties.setRedirectUri("http://localhost:8080/api/v1/oauth/microsoft/callback");
    return properties;
  }

  @Test
  void defaultScopesAreDelegatedReadOnly() {
    List<String> scopes = properties().requestedScopes();
    assertTrue(scopes.contains("openid"));
    assertTrue(scopes.contains("profile"));
    assertTrue(scopes.contains("offline_access"));
    assertTrue(scopes.contains("User.Read"));
    assertTrue(scopes.contains("https://graph.microsoft.com/Mail.Read"));
    assertEquals(5, scopes.size());
  }

  @Test
  void noSendOrWriteScopeIsEverRequested() {
    for (String scope : properties().requestedScopes()) {
      String lower = scope.toLowerCase();
      assertFalse(lower.contains("mail.send"), "SEND scope forbidden: " + scope);
      assertFalse(lower.contains("mail.readwrite"), "write scope forbidden: " + scope);
      assertFalse(lower.contains("mail.send.shared"), "shared SEND forbidden: " + scope);
      assertFalse(lower.contains("mail.read.shared"), "shared write forbidden: " + scope);
    }
  }

  @Test
  void mailboxScopeDropsOutWhenBlank() {
    MicrosoftOAuthProperties properties = properties();
    properties.setMailboxScope("  ");
    List<String> scopes = properties.requestedScopes();
    assertEquals(4, scopes.size());
    assertFalse(scopes.toString().contains("Mail.Read"));
  }

  @Test
  void authorizationUrlCarriesSafeParametersOnly() {
    MicrosoftOAuthClient client = new MicrosoftOAuthClient(properties());
    String url = client.buildAuthorizationUrl("test-state", "test-challenge");

    assertTrue(url.startsWith(
        "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"));
    assertTrue(url.contains("client_id=test-ms-client-id"));
    assertTrue(url.contains("response_type=code"));
    assertTrue(url.contains("prompt=consent"));
    assertTrue(url.contains("code_challenge=test-challenge"));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("state=test-state"));
    // Delegated READ-only scopes travel in the request…
    assertTrue(url.contains("User.Read"));
    assertTrue(url.contains("Mail.Read"));
    // …but no SEND/WRITE scope literal may ever appear.
    assertFalse(url.contains("Mail.Send"));
    assertFalse(url.contains("Mail.ReadWrite"));
    // Client secret never travels to the browser.
    assertFalse(url.contains("synthetic-secret"));
  }

  @Test
  void tenantIsConfigurableNotHardCoded() {
    MicrosoftOAuthProperties properties = properties();
    properties.setTenant("contoso-tenant-id");
    String url = new MicrosoftOAuthClient(properties)
        .buildAuthorizationUrl("test-state", "test-challenge");
    assertTrue(url.startsWith(
        "https://login.microsoftonline.com/contoso-tenant-id/oauth2/v2.0/authorize?"));
    assertFalse(url.contains("/common/"));
  }
}
