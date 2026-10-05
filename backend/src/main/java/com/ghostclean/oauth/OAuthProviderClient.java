package com.ghostclean.oauth;

import java.time.Duration;
import java.util.List;

/**
 * Provider boundary (Phase 3: Google; Phase 10: + Microsoft).
 *
 * <p>All provider HTTP lives behind this interface so core connection logic
 * never branches on provider specifics. No implementation may log codes,
 * tokens, secrets, or raw provider bodies.
 */
public interface OAuthProviderClient {

  /** Stable provider key, e.g. {@code "google"}. */
  String providerId();

  /**
   * Whether this provider is fully configured (kill-switch + credentials +
   * redirect). Core connection logic consults this instead of reaching into
   * provider-specific properties.
   */
  default boolean isConfigured() {
    return true;
  }

  /** Authorization-state lifetime for this provider. */
  default Duration stateTtl() {
    return Duration.ofMinutes(10);
  }

  /** Scopes actually requested at authorize time for this provider. */
  default List<String> requestedScopes() {
    return List.of();
  }

  /** Authorization redirect URL for the given state + PKCE challenge. */
  String buildAuthorizationUrl(String state, String codeChallenge);

  /** Exchanges an authorization code for tokens (single use, server-side). */
  TokenResult exchangeCode(String code, String codeVerifier);

  /** Exchanges a refresh token for a fresh access token. */
  TokenResult refreshAccessToken(String refreshToken);

  /** Resolves the stable provider account identity for an access token. */
  ProviderAccount fetchAccount(String accessToken);

  /** Best-effort revocation; implementations swallow transport failures. */
  void revoke(String token);

  /** Token endpoint result. Refresh may be absent when the provider withholds it. */
  record TokenResult(
      String accessToken,
      String refreshToken,
      long expiresInSeconds,
      String scope) {
  }

  /** Minimum stable identity: opaque account id plus contact email. */
  record ProviderAccount(String providerAccountId, String email) {
  }
}
