package com.ghostclean.oauth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Microsoft (Entra ID) OAuth configuration (Phase 10: connection + mailbox
 * read).
 *
 * <p>Scope separation mirrors the Google surface:
 * <ul>
 *   <li>{@code identityScopes} (default {@code "openid profile offline_access
 *       User.Read"}) — delegated identity plus the Graph profile read needed
 *       to resolve the stable account id via {@code /me}. No mailbox
 *       access.</li>
 *   <li>{@code mailboxScope} (default {@code
 *       "https://graph.microsoft.com/Mail.Read"}) — delegated mailbox READ.
 *       Only ever requested when this property is non-blank.</li>
 * </ul>
 * No message-send or mailbox-write scope exists anywhere in this
 * configuration surface: send scopes, read-write scopes, and the shared
 * mailbox variants are post-V1 only and must never be configured here
 * (build-enforced by {@code NoSendCapabilityTests}). Nothing is
 * hard-coded: authority, tenant, redirect, and Graph base URL are all
 * settable (environment overrides in {@code application.yml}).
 */
@Component
@ConfigurationProperties(prefix = "oauth.microsoft")
public class MicrosoftOAuthProperties {

  /** Kill-switch; an unwired provider answers 410 via the client registry. */
  private boolean enabled = true;
  private String clientId = "";
  private String clientSecret = "";
  private String redirectUri = "";
  private String authority = "https://login.microsoftonline.com";
  private String tenant = "common";
  private String graphBaseUrl = "https://graph.microsoft.com";
  private String identityScopes = "openid profile offline_access User.Read";
  private String mailboxScope = "https://graph.microsoft.com/Mail.Read";
  private Duration stateTtl = Duration.ofMinutes(10);

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

  /** Composed from authority + tenant so no tenant value is hard-coded. */
  public String authorizationEndpoint() {
    return authority.strip() + "/" + tenant.strip() + "/oauth2/v2.0/authorize";
  }

  /** Composed from authority + tenant so no tenant value is hard-coded. */
  public String tokenEndpoint() {
    return authority.strip() + "/" + tenant.strip() + "/oauth2/v2.0/token";
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

  public String getAuthority() {
    return authority;
  }

  public void setAuthority(String authority) {
    this.authority = authority;
  }

  public String getTenant() {
    return tenant;
  }

  public void setTenant(String tenant) {
    this.tenant = tenant;
  }

  public String getGraphBaseUrl() {
    return graphBaseUrl;
  }

  public void setGraphBaseUrl(String graphBaseUrl) {
    this.graphBaseUrl = graphBaseUrl;
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
}
