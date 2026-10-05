package com.ghostclean.oauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Google OAuth configuration (Phase 3: identity/connection only).
 *
 * <p>Scope separation is deliberate and explicit:
 * <ul>
 *   <li>{@code identityScopes} (default {@code "openid email profile"}) —
 *       sufficient to establish the Google account connection via the
 *       identity layer. No mailbox access.</li>
 *   <li>{@code mailboxScope} (default empty = NOT requested) — the mailbox
 *       read scope belongs to Phase 4 and is only ever requested when this
 *       property is explicitly configured. Phase 3 never sets it.</li>
 * </ul>
 * No message-send, compose, modify, or domain-wide scopes exist anywhere in
 * this configuration surface.
 */
@Component
@ConfigurationProperties(prefix = "oauth.google")
public class GoogleOAuthProperties {

  /** Kill-switch; Microsoft has no properties bean and always answers 410. */
  private boolean enabled = true;
  private String clientId = "";
  private String clientSecret = "";
  private String redirectUri = "";
  private String identityScopes = "openid email profile";
  private String mailboxScope = "";
  private Duration stateTtl = Duration.ofMinutes(10);
  private String authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth";
  private String tokenEndpoint = "https://oauth2.googleapis.com/token";
  private String userinfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo";
  private String revokeEndpoint = "https://oauth2.googleapis.com/revoke";

  public boolean isConfigured() {
    return enabled
        && !clientId.isBlank()
        && !clientSecret.isBlank()
        && !redirectUri.isBlank();
  }

  /** Scopes actually requested. Empty mailbox scope means identity-only. */
  public List<String> requestedScopes() {
    List<String> scopes = new ArrayList<>(List.of(identityScopes.split("\\s+")));
    if (!mailboxScope.isBlank()) {
      scopes.add(mailboxScope.trim());
    }
    return List.copyOf(scopes);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getRedirectUri() {
    return redirectUri;
  }

  public void setRedirectUri(String redirectUri) {
    this.redirectUri = redirectUri;
  }

  public String getIdentityScopes() {
    return identityScopes;
  }

  public void setIdentityScopes(String identityScopes) {
    this.identityScopes = identityScopes;
  }

  public String getMailboxScope() {
    return mailboxScope;
  }

  public void setMailboxScope(String mailboxScope) {
    this.mailboxScope = mailboxScope;
  }

  public Duration getStateTtl() {
    return stateTtl;
  }

  public void setStateTtl(Duration stateTtl) {
    this.stateTtl = stateTtl;
  }

  public String getAuthorizationEndpoint() {
    return authorizationEndpoint;
  }

  public void setAuthorizationEndpoint(String authorizationEndpoint) {
    this.authorizationEndpoint = authorizationEndpoint;
  }

  public String getTokenEndpoint() {
    return tokenEndpoint;
  }

  public void setTokenEndpoint(String tokenEndpoint) {
    this.tokenEndpoint = tokenEndpoint;
  }

  public String getUserinfoEndpoint() {
    return userinfoEndpoint;
  }

  public void setUserinfoEndpoint(String userinfoEndpoint) {
    this.userinfoEndpoint = userinfoEndpoint;
  }

  public String getRevokeEndpoint() {
    return revokeEndpoint;
  }

  public void setRevokeEndpoint(String revokeEndpoint) {
    this.revokeEndpoint = revokeEndpoint;
  }
}
