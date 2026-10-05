package com.ghostclean;

import com.ghostclean.common.OAuthException;
import com.ghostclean.oauth.GoogleOAuthClient;
import com.ghostclean.oauth.GoogleOAuthProperties;
import com.ghostclean.oauth.OAuthProviderClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Scripted Google double: no network, no Google account, no secrets.
 * Codes drive behavior — {@code ok-*} succeeds, {@code ok-no-refresh}
 * withholds the refresh token, {@code fail-exchange}/{@code fail-account}
 * detonate at the scripted step. URL building delegates to the real client
 * so authorize-URL assertions exercise production code.
 */
@TestConfiguration
public class TestGoogleOAuth {

  @Bean
  @Primary
  public FakeGoogleOAuthClient fakeGoogleOAuthClient(GoogleOAuthProperties properties) {
    return new FakeGoogleOAuthClient(properties);
  }

  public static class FakeGoogleOAuthClient implements OAuthProviderClient {

    private final GoogleOAuthClient urlDelegate;
    public final List<String> revokedTokens = new CopyOnWriteArrayList<>();
    /** Code verifiers received at the token endpoint, in call order. */
    public final List<String> receivedVerifiers = new CopyOnWriteArrayList<>();

    public FakeGoogleOAuthClient(GoogleOAuthProperties properties) {
      this.urlDelegate = new GoogleOAuthClient(properties);
    }

    @Override
    public String providerId() {
      return "google";
    }

    @Override
    public boolean isConfigured() {
      return urlDelegate.isConfigured();
    }

    @Override
    public java.time.Duration stateTtl() {
      return urlDelegate.stateTtl();
    }

    @Override
    public java.util.List<String> requestedScopes() {
      return urlDelegate.requestedScopes();
    }

    @Override
    public String buildAuthorizationUrl(String state, String codeChallenge) {
      return urlDelegate.buildAuthorizationUrl(state, codeChallenge);
    }

    @Override
    public TokenResult exchangeCode(String code, String codeVerifier) {
      if (code == null || !code.startsWith("ok-")) {
        throw OAuthException.providerError();
      }
      receivedVerifiers.add(codeVerifier);
      String refresh = code.contains("no-refresh") ? null : "ref-" + code;
      // The "with-gmail" variant simulates Google granting the restricted
      // mailbox scope alongside identity; every other success code grants
      // identity only (the partial-grant shape seen in real consent flows).
      String scope = code.contains("with-gmail")
          ? "openid email profile https://www.googleapis.com/auth/gmail.readonly"
          : "openid email profile";
      return new TokenResult("acc-" + code, refresh, 3600, scope);
    }

    @Override
    public TokenResult refreshAccessToken(String refreshToken) {
      if (refreshToken != null && refreshToken.startsWith("ref-ok")) {
        return new TokenResult("acc-refreshed-" + refreshToken, null, 3600, null);
      }
      throw OAuthException.providerError();
    }

    @Override
    public ProviderAccount fetchAccount(String accessToken) {
      if (accessToken != null && accessToken.contains("fail-account")) {
        throw OAuthException.providerError();
      }
      String sub = accessToken != null && accessToken.contains("same-sub")
          ? "sub-duplicate"
          : "sub-" + accessToken;
      return new ProviderAccount(sub, "owner-" + sub + "@example.com");
    }

    @Override
    public void revoke(String token) {
      revokedTokens.add(token);
    }
  }
}
