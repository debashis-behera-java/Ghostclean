package com.ghostclean;

import com.ghostclean.common.OAuthException;
import com.ghostclean.oauth.MicrosoftOAuthClient;
import com.ghostclean.oauth.MicrosoftOAuthProperties;
import com.ghostclean.oauth.OAuthProviderClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Scripted Microsoft double: no network, no Microsoft account, no secrets.
 * Codes drive behavior — {@code ok-ms-*} succeeds, {@code ok-ms-no-refresh}
 * withholds the refresh token, {@code fail-exchange}/{@code fail-account}
 * detonate at the scripted step. URL building delegates to the real client
 * so authorize-URL assertions exercise production code.
 */
@TestConfiguration
public class TestMicrosoftOAuth {

  @Bean
  @Primary
  public FakeMicrosoftOAuthClient fakeMicrosoftOAuthClient(
      MicrosoftOAuthProperties properties) {
    return new FakeMicrosoftOAuthClient(properties);
  }

  public static class FakeMicrosoftOAuthClient implements OAuthProviderClient {

    private final MicrosoftOAuthClient urlDelegate;
    public final List<String> revokedTokens = new CopyOnWriteArrayList<>();

    public FakeMicrosoftOAuthClient(MicrosoftOAuthProperties properties) {
      this.urlDelegate = new MicrosoftOAuthClient(properties);
    }

    @Override
    public String providerId() {
      return "microsoft";
    }

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public Duration stateTtl() {
      return urlDelegate.stateTtl();
    }

    @Override
    public List<String> requestedScopes() {
      return urlDelegate.requestedScopes();
    }

    @Override
    public String buildAuthorizationUrl(String state, String codeChallenge) {
      return urlDelegate.buildAuthorizationUrl(state, codeChallenge);
    }

    @Override
    public TokenResult exchangeCode(String code, String codeVerifier) {
      if (code == null || !code.startsWith("ok-ms-")) {
        throw OAuthException.providerError();
      }
      String refresh = code.contains("no-refresh") ? null : "ref-" + code;
      return new TokenResult("acc-" + code, refresh, 3600,
          "openid profile offline_access User.Read https://graph.microsoft.com/Mail.Read");
    }

    @Override
    public TokenResult refreshAccessToken(String refreshToken) {
      if (refreshToken != null && refreshToken.startsWith("ref-ok-ms-")) {
        return new TokenResult("acc-refreshed-" + refreshToken, null, 3600, null);
      }
      throw OAuthException.providerError();
    }

    @Override
    public ProviderAccount fetchAccount(String accessToken) {
      if (accessToken != null && accessToken.contains("fail-account")) {
        throw OAuthException.providerError();
      }
      String id = accessToken != null && accessToken.contains("same-id")
          ? "ms-id-duplicate"
          : "ms-id-" + accessToken;
      return new ProviderAccount(id, "owner-" + id + "@example.com");
    }

    @Override
    public void revoke(String token) {
      revokedTokens.add(token);
    }
  }
}
