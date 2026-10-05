package com.ghostclean.oauth;

import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.OAuthException;
import com.ghostclean.common.crypto.TokenCryptoProvider;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Google OAuth connection lifecycle (Phase 3: connect + disconnect only).
 *
 * <p>Ownership rule: every operation resolves the GhostClean user from the
 * security context (authorize) or from the consumed state row (callback).
 * A connection id that is not owned reads as 404 — never 403 with an
 * existence leak. Provider tokens live server-side only: encrypted at rest,
 * decrypted transiently for exchange/refresh/revoke, never returned,
 * never logged, never audited.
 */
@Service
public class OAuthService {

  private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

  private final UserRepository users;
  private final OAuthConnectionRepository connections;
  private final OAuthStateService stateService;
  private final Map<String, OAuthProviderClient> clients;
  private final TokenCryptoProvider crypto;
  private final OAuthTokenRotation rotation;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;
  private final String appBaseUrl;
  private final GoogleConnectionApprovalService googleApprovals;

  public OAuthService(
      UserRepository users,
      OAuthConnectionRepository connections,
      OAuthStateService stateService,
      List<OAuthProviderClient> providerClients,
      TokenCryptoProvider crypto,
      OAuthTokenRotation rotation,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls,
      @Value("${app.base-url:http://localhost:8080}") String appBaseUrl,
      GoogleConnectionApprovalService googleApprovals) {
    this.users = users;
    this.connections = connections;
    this.stateService = stateService;
    this.clients = providerClients.stream()
        .collect(java.util.stream.Collectors.toUnmodifiableMap(
            OAuthProviderClient::providerId, client -> client));
    this.crypto = crypto;
    this.rotation = rotation;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
    this.appBaseUrl = appBaseUrl;
    this.googleApprovals = googleApprovals;
  }

  /** Starts a connection: verified user, fresh state, provider URL. */
  @Transactional
  public AuthorizeResult authorize(UUID userId, String providerId, String returnUrl, String ip) {
    rls.apply();
    OAuthProviderClient client = requireClient(providerId);
    User user = users.findById(userId).orElseThrow(
        () -> new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
            "Authentication required"));
    requireVerified(user);
    rateLimiter.check("oauth:authorize:user:" + userId, 10, Duration.ofMinutes(1));
    requireClientConfigured(client);
    String safeReturnUrl = validateReturnUrl(returnUrl);
    if ("google".equals(client.providerId())) {
      // GhostClean business gate: an approved request (or an already-active
      // connection) is required before Google's own consent flow may start.
      // Google's consent screen still applies in full afterward.
      googleApprovals.requireGoogleAuthorizeAllowed(userId);
    }
    OAuthStateService.CreatedState created =
        stateService.create(userId, client.providerId(), safeReturnUrl, client.stateTtl());
    String authorizationUrl = client.buildAuthorizationUrl(created.state(), created.codeChallenge());
    audit.emit(AuditEvents.OAUTH_CONNECT_STARTED, userId, "user",
        "oauth_state", null, Map.of("provider", client.providerId()));
    log.info("OAuth connect started for user {}", userId);
    return new AuthorizeResult(authorizationUrl, created.state(), created.expiresAt());
  }

  /**
   * Finishes a connection. Returns the SPA redirect target. Throws
   * {@link OAuthException} (rendered as JSON) only when no trusted redirect
   * exists — i.e. the state itself is missing, expired, or replayed.
   */
  public String handleCallback(String providerId, String code, String state,
      String error, String ip) {
    OAuthProviderClient client = requireClient(providerId);
    rateLimiter.check("oauth:callback:ip:" + ip, 30, Duration.ofMinutes(1));
    OAuthStateService.ConsumedState consumed;
    try {
      consumed = stateService.consume(state, client.providerId());
    } catch (OAuthException ex) {
      // Invalid/expired/replayed/missing state: audit the outcome category
      // only (never the presented state) so blind callbacks stay diagnosable.
      audit.emitAnonymous(AuditEvents.OAUTH_CONNECT_FAILED,
          Map.of("provider", client.providerId(), "reason", "state-mismatch"));
      throw ex;
    }
    UUID userId = consumed.userId();

    if (error != null && !error.isBlank()) {
      audit.emit(AuditEvents.OAUTH_CONNECT_FAILED, userId, "anonymous",
          "oauth_state", null, Map.of("provider", client.providerId(), "reason", "denied"));
      return redirect(consumed.returnUrl(), client.providerId(), "error",
          OAuthException.denied().getCode());
    }
    if (code == null || code.isBlank()) {
      audit.emit(AuditEvents.OAUTH_CONNECT_FAILED, userId, "anonymous",
          "oauth_state", null, Map.of("provider", client.providerId(), "reason", "missing-code"));
      return redirect(consumed.returnUrl(), client.providerId(), "error",
          OAuthException.providerError().getCode());
    }
    try {
      OAuthProviderClient.TokenResult tokens =
          client.exchangeCode(code, consumed.codeVerifier());
      OAuthProviderClient.ProviderAccount account = client.fetchAccount(tokens.accessToken());
      upsertConnection(userId, client.providerId(), account, tokens);
      audit.emit(AuditEvents.OAUTH_CONNECT_SUCCEEDED, userId, "user",
          "oauth_connection", account.providerAccountId(),
          Map.of("provider", client.providerId()));
      log.info("OAuth connect succeeded for user {}", userId);
      return redirect(consumed.returnUrl(), client.providerId(), "connected", null);
    } catch (OAuthException ex) {
      audit.emit(AuditEvents.OAUTH_CONNECT_FAILED, userId, "anonymous",
          "oauth_state", null, Map.of("provider", client.providerId(), "reason", "provider"));
      return redirect(consumed.returnUrl(), client.providerId(), "error", ex.getCode());
    }
  }

  /** Safe status list for the owner. No token material, ever. */
  @Transactional(readOnly = true)
  public List<ConnectionDto> connections(UUID userId) {
    rls.apply();
    return connections.findByUser_Id(userId).stream().map(this::toDto).toList();
  }

  /** Disconnects: best-effort provider revocation, then local wipe. Idempotent. */
  @Transactional
  public void disconnect(UUID userId, UUID connectionId, String ip) {
    rls.apply();
    rateLimiter.check("oauth:disconnect:user:" + userId, 10, Duration.ofMinutes(1));
    OAuthConnection connection = ownedConnection(userId, connectionId);
    requireVerified(connection.getUser());
    OAuthProviderClient client = requireClient(connection.getProvider());
    String refresh = rotation.decryptRefresh(connection);
    String access = rotation.decryptAccess(connection);
    if (refresh != null) {
      client.revoke(refresh);
    } else if (access != null) {
      client.revoke(access);
    }
    connection.wipeCredentials();
    connections.save(connection);
    audit.emit(AuditEvents.OAUTH_DISCONNECTED, userId, "user",
        "oauth_connection", connection.getId().toString(),
        Map.of("provider", connection.getProvider()));
    log.info("OAuth connection revoked for user {}", userId);
  }

  /**
   * Force-refreshes stored credentials (429-guarded by the rate limiter).
   * noRollbackFor: a rejected refresh must still persist the EXPIRED marker
   * before the reconnectRequired throw, otherwise callers (e.g. scans)
   * would observe a stale ACTIVE connection.
   */
  @Transactional(noRollbackFor = OAuthException.class)
  public ConnectionDto refreshConnection(UUID userId, UUID connectionId, String ip) {
    rls.apply();
    rateLimiter.check("oauth:connection-refresh:user:" + userId, 5, Duration.ofMinutes(1));
    OAuthConnection connection = ownedConnection(userId, connectionId);
    requireVerified(connection.getUser());
    OAuthProviderClient client = requireClient(connection.getProvider());
    String refresh = rotation.decryptRefresh(connection);
    if (refresh == null) {
      connection.setStatus(OAuthConnection.STATUS_EXPIRED);
      connection.setLastError("Refresh token unavailable");
      connections.save(connection);
      throw OAuthException.reconnectRequired();
    }
    try {
      OAuthProviderClient.TokenResult tokens = client.refreshAccessToken(refresh);
      connection.setAccessTokenEnc(crypto.encrypt(tokens.accessToken()));
      if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
        connection.setRefreshTokenEnc(crypto.encrypt(tokens.refreshToken()));
      }
      connection.setKeyVersion(crypto.keyVersion());
      if (tokens.expiresInSeconds() > 0) {
        connection.setExpiresAt(Instant.now().plusSeconds(tokens.expiresInSeconds()));
      }
      connection.setStatus(OAuthConnection.STATUS_ACTIVE);
      connection.setLastError(null);
      connection.setLastRefreshedAt(Instant.now());
      connections.save(connection);
      return toDto(connection);
    } catch (OAuthException ex) {
      connection.setStatus(OAuthConnection.STATUS_EXPIRED);
      connection.setLastError("Provider rejected the refresh");
      connections.save(connection);
      throw OAuthException.reconnectRequired();
    }
  }

  // -- internals ---------------------------------------------------------------

  private OAuthProviderClient requireClient(String providerId) {
    OAuthProviderClient client = clients.get(normalizeProvider(providerId));
    if (client == null) {
      throw OAuthException.providerDisabled();
    }
    return client;
  }

  private static String normalizeProvider(String providerId) {
    return providerId == null ? "" : providerId.trim().toLowerCase();
  }

  private void requireClientConfigured(OAuthProviderClient client) {
    if (!client.isConfigured()) {
      throw OAuthException.providerError();
    }
  }

  private void requireVerified(User user) {
    if (!user.isEmailVerified()) {
      throw new AuthException(HttpStatus.FORBIDDEN, ApiError.EMAIL_NOT_VERIFIED,
          "Email verification required");
    }
  }

  private OAuthConnection ownedConnection(UUID userId, UUID connectionId) {
    return connections.findByIdAndUser_Id(connectionId, userId)
        .orElseThrow(() -> new OAuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "OAuth connection not found"));
  }

  private void upsertConnection(UUID userId, String providerId,
      OAuthProviderClient.ProviderAccount account, OAuthProviderClient.TokenResult tokens) {
    User user = users.findById(userId).orElseThrow(
        () -> new IllegalStateException("OAuth owner does not exist"));
    OAuthConnection connection = connections
        .findByUser_IdAndProviderAndProviderAccountId(
            userId, providerId, account.providerAccountId())
        .orElseGet(() -> new OAuthConnection(user, providerId, account.providerAccountId()));
    connection.setProviderEmail(account.email());
    OAuthProviderClient client = requireClient(providerId);
    String granted = tokens.scope() != null && !tokens.scope().isBlank()
        ? tokens.scope()
        : String.join(" ", client.requestedScopes());
    connection.setScopes(granted.split("\\s+"));
    connection.setAccessTokenEnc(crypto.encrypt(tokens.accessToken()));
    // Providers may withhold a refresh token on reconsent-free grants; the
    // previously stored one (if any) stays valid, so only overwrite when
    // a fresh one is actually issued.
    if (tokens.refreshToken() != null && !tokens.refreshToken().isBlank()) {
      connection.setRefreshTokenEnc(crypto.encrypt(tokens.refreshToken()));
    }
    connection.setKeyVersion(crypto.keyVersion());
    if (tokens.expiresInSeconds() > 0) {
      connection.setExpiresAt(Instant.now().plusSeconds(tokens.expiresInSeconds()));
    }
    connection.setStatus(OAuthConnection.STATUS_ACTIVE);
    connection.setLastError(null);
    connection.setRevokedAt(null);
    connection.setLastRefreshedAt(Instant.now());
    connections.save(connection);
  }

  /** Same-origin return URL or the default SPA callback. Never trusts input blindly. */
  String validateReturnUrl(String returnUrl) {
    if (returnUrl == null || returnUrl.isBlank()) {
      return defaultReturnUrl();
    }
    try {
      URI base = URI.create(appBaseUrl);
      URI candidate = URI.create(returnUrl.trim());
      String scheme = candidate.getScheme();
      if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        throw OAuthException.providerError();
      }
      if (!candidate.getHost().equalsIgnoreCase(base.getHost())
          || effectivePort(candidate) != effectivePort(base)) {
        throw new OAuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
            "Invalid return URL");
      }
      return candidate.toString();
    } catch (OAuthException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      throw new OAuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Invalid return URL");
    }
  }

  private String redirect(String returnUrl, String provider, String status, String errorCode) {
    String base;
    try {
      base = validateReturnUrl(returnUrl);
    } catch (OAuthException ex) {
      base = defaultReturnUrl();
    }
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base)
        .queryParam("provider", provider)
        .queryParam("status", status);
    if (errorCode != null) {
      builder.queryParam("error", errorCode);
    }
    return builder.build(true).toUriString();
  }

  private String defaultReturnUrl() {
    return appBaseUrl + "/oauth/callback";
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  static String maskEmail(String email) {
    if (email == null || !email.contains("@")) {
      return "";
    }
    int at = email.indexOf('@');
    String local = email.substring(0, at);
    String domain = email.substring(at + 1);
    String shown = local.isEmpty() ? "***" : local.charAt(0) + "***";
    return shown + "@" + domain;
  }

  private ConnectionDto toDto(OAuthConnection connection) {
    return new ConnectionDto(
        connection.getId(),
        connection.getProvider(),
        connection.getStatus(),
        maskEmail(connection.getProviderEmail()),
        List.of(connection.getScopes() == null ? new String[0] : connection.getScopes()),
        connection.getConnectedAt(),
        connection.getExpiresAt());
  }

  public record AuthorizeResult(String authorizationUrl, String state, Instant expiresAt) {
  }

  public record ConnectionDto(
      UUID id,
      String provider,
      String status,
      String providerEmailMasked,
      List<String> scopes,
      Instant connectedAt,
      Instant expiresAt) {
  }
}
