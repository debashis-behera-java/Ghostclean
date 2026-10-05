package com.ghostclean.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.common.OAuthException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OAuth HTTP adapter (authorization-code flow + OIDC userinfo).
 *
 * <p>Requests identity scopes plus the explicitly configured mailbox scope
 * only (empty in Phase 3 → identity-only). Sends the client secret
 * server-side only. Logs statuses and shapes — never codes, tokens,
 * secrets, subjects, or raw bodies.
 */
@Component
@Profile("!oauth-fake")
public class GoogleOAuthClient implements OAuthProviderClient {

  private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

  private final GoogleOAuthProperties properties;
  private final RestClient http;

  public GoogleOAuthClient(GoogleOAuthProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String providerId() {
    return "google";
  }

  @Override
  public boolean isConfigured() {
    return properties.isConfigured();
  }

  @Override
  public java.time.Duration stateTtl() {
    return properties.getStateTtl();
  }

  @Override
  public java.util.List<String> requestedScopes() {
    return properties.requestedScopes();
  }

  @Override
  public String buildAuthorizationUrl(String state, String codeChallenge) {
    String scope = properties.requestedScopes().stream().collect(Collectors.joining(" "));
    return UriComponentsBuilder.fromUriString(properties.getAuthorizationEndpoint())
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", scope)
        .queryParam("state", state)
        .queryParam("access_type", "offline")
        .queryParam("prompt", "consent")
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .encode()
        .build()
        .toUriString();
  }

  @Override
  public TokenResult exchangeCode(String code, String codeVerifier) {
    Map<String, String> form = Map.of(
        "code", code,
        "client_id", properties.getClientId(),
        "client_secret", properties.getClientSecret(),
        "redirect_uri", properties.getRedirectUri(),
        "grant_type", "authorization_code",
        "code_verifier", codeVerifier);
    return postTokenForm(form, "exchange");
  }

  @Override
  public TokenResult refreshAccessToken(String refreshToken) {
    Map<String, String> form = Map.of(
        "client_id", properties.getClientId(),
        "client_secret", properties.getClientSecret(),
        "refresh_token", refreshToken,
        "grant_type", "refresh_token");
    return postTokenForm(form, "refresh");
  }

  @Override
  public ProviderAccount fetchAccount(String accessToken) {
    try {
      JsonNode body = http.get()
          .uri(properties.getUserinfoEndpoint())
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      String subject = body != null && body.hasNonNull("sub") ? body.get("sub").asText() : "";
      String email = body != null && body.hasNonNull("email") ? body.get("email").asText() : "";
      if (subject.isBlank() || email.isBlank()) {
        log.warn("Userinfo response missing stable identity fields");
        throw OAuthException.providerError();
      }
      return new ProviderAccount(subject, email);
    } catch (OAuthException ex) {
      throw ex;
    } catch (RestClientException ex) {
      log.warn("Userinfo request failed: {}", ex.getClass().getSimpleName());
      throw OAuthException.providerError();
    }
  }

  @Override
  public void revoke(String token) {
    try {
      http.post()
          .uri(properties.getRevokeEndpoint())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body("token=" + urlEncode(token))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException ex) {
      // Best-effort: local credentials are wiped regardless.
      log.warn("Provider revocation attempt failed: {}", ex.getClass().getSimpleName());
    }
  }

  private TokenResult postTokenForm(Map<String, String> form, String operation) {
    String body = form.entrySet().stream()
        .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
        .collect(Collectors.joining("&"));
    try {
      JsonNode json = http.post()
          .uri(properties.getTokenEndpoint())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(body)
          .retrieve()
          .body(JsonNode.class);
      String accessToken = textOrNull(json, "access_token");
      if (accessToken == null || accessToken.isBlank()) {
        log.warn("Token {} response missing access token", operation);
        throw OAuthException.providerError();
      }
      String refreshToken = textOrNull(json, "refresh_token");
      long expiresIn = json != null && json.has("expires_in") ? json.get("expires_in").asLong(0) : 0;
      String scope = textOrNull(json, "scope");
      return new TokenResult(accessToken, refreshToken, expiresIn, scope);
    } catch (OAuthException ex) {
      throw ex;
    } catch (RestClientException ex) {
      log.warn("Token {} request failed: {}", operation, ex.getClass().getSimpleName());
      throw OAuthException.providerError();
    }
  }

  private static String textOrNull(JsonNode json, String field) {
    return json != null && json.hasNonNull(field) ? json.get(field).asText() : null;
  }

  private static String urlEncode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
