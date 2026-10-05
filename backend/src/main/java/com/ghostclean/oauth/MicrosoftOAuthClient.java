package com.ghostclean.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghostclean.common.OAuthException;
import java.time.Duration;
import java.util.List;
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
 * Microsoft (Entra ID) OAuth HTTP adapter: delegated authorization-code flow
 * (+PKCE) with identity resolution via Microsoft Graph {@code /me}.
 *
 * <p>Requests delegated READ-only scopes only (identity plus the explicitly
 * configured {@code Mail.Read}). The client secret travels server-side only.
 * Logs statuses and shapes — never codes, tokens, secrets, or raw bodies.
 * There is no provider-side revocation API for delegated permissions, so
 * {@link #revoke(String)} is a documented no-op: the local credential wipe
 * performed by the connection lifecycle is the guarantee, and users can
 * revoke at their Microsoft account page at any time.
 */
@Component
@Profile("!oauth-fake")
public class MicrosoftOAuthClient implements OAuthProviderClient {

  private static final Logger log = LoggerFactory.getLogger(MicrosoftOAuthClient.class);

  private final MicrosoftOAuthProperties properties;
  private final RestClient http;

  public MicrosoftOAuthClient(MicrosoftOAuthProperties properties) {
    this.properties = properties;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(10));
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public String providerId() {
    return "microsoft";
  }

  @Override
  public boolean isConfigured() {
    return properties.isConfigured();
  }

  @Override
  public Duration stateTtl() {
    return properties.getStateTtl();
  }

  @Override
  public List<String> requestedScopes() {
    return properties.requestedScopes();
  }

  @Override
  public String buildAuthorizationUrl(String state, String codeChallenge) {
    String scope = properties.requestedScopes().stream().collect(Collectors.joining(" "));
    return UriComponentsBuilder.fromUriString(properties.authorizationEndpoint())
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", scope)
        .queryParam("state", state)
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
        "code_verifier", codeVerifier,
        "scope", properties.requestedScopes().stream().collect(Collectors.joining(" ")));
    return postTokenForm(form, "exchange");
  }

  @Override
  public TokenResult refreshAccessToken(String refreshToken) {
    Map<String, String> form = Map.of(
        "client_id", properties.getClientId(),
        "client_secret", properties.getClientSecret(),
        "refresh_token", refreshToken,
        "grant_type", "refresh_token",
        "scope", properties.requestedScopes().stream().collect(Collectors.joining(" ")));
    return postTokenForm(form, "refresh");
  }

  @Override
  public ProviderAccount fetchAccount(String accessToken) {
    String uri = UriComponentsBuilder
        .fromUriString(properties.getGraphBaseUrl().strip() + "/v1.0/me")
        .queryParam("$select", "id,mail,userPrincipalName")
        .encode()
        .build()
        .toUriString();
    try {
      JsonNode body = http.get()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(JsonNode.class);
      String id = body != null && body.hasNonNull("id") ? body.get("id").asText() : "";
      String mail = body != null && body.hasNonNull("mail") ? body.get("mail").asText() : "";
      if (mail.isBlank() && body != null && body.hasNonNull("userPrincipalName")) {
        mail = body.get("userPrincipalName").asText();
      }
      if (id.isBlank() || mail.isBlank()) {
        log.warn("Graph /me response missing stable identity fields");
        throw OAuthException.providerError();
      }
      return new ProviderAccount(id, mail);
    } catch (OAuthException ex) {
      throw ex;
    } catch (RestClientException ex) {
      log.warn("Graph /me request failed: {}", ex.getClass().getSimpleName());
      throw OAuthException.providerError();
    }
  }

  @Override
  public void revoke(String token) {
    // No provider-side revocation API exists for delegated permissions; the
    // connection lifecycle always wipes local credentials regardless, and
    // users can revoke consent at their Microsoft account page. Deliberate
    // no-op (never claim provider-side revocation without confirmation).
    log.debug("Microsoft delegated consent has no revocation endpoint; local wipe applies");
  }

  private TokenResult postTokenForm(Map<String, String> form, String operation) {
    String body = form.entrySet().stream()
        .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
        .collect(Collectors.joining("&"));
    try {
      JsonNode json = http.post()
          .uri(properties.tokenEndpoint())
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
