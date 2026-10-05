package com.ghostclean.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (user, provider, provider account). Holds only AES-GCM
 * ciphertext (never plaintext tokens), identity minimums, and lifecycle
 * state. Disconnect nulls token columns and flips status; the row survives
 * as a revoked stub per retention policy.
 */
@Entity
@Table(
    name = "oauth_connections",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_oauth_user_provider_account",
        columnNames = {"user_id", "provider", "provider_account_id"}))
public class OAuthConnection {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REVOKED = "REVOKED";
  public static final String STATUS_EXPIRED = "EXPIRED";
  public static final String STATUS_ERROR = "ERROR";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "provider_account_id", nullable = false)
  private String providerAccountId;

  @Column(name = "provider_email", columnDefinition = "citext")
  private String providerEmail;

  @Column(name = "scopes", columnDefinition = "text[]")
  private String[] scopes = new String[0];

  @Column(name = "access_token_enc")
  private String accessTokenEnc;

  @Column(name = "refresh_token_enc")
  private String refreshTokenEnc;

  @Column(name = "key_version", nullable = false)
  private int keyVersion = 1;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_ACTIVE;

  @Column(name = "last_error", length = 300)
  private String lastError;

  @Column(name = "connected_at", nullable = false, updatable = false)
  private Instant connectedAt;

  @Column(name = "last_refreshed_at")
  private Instant lastRefreshedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OAuthConnection() {
  }

  public OAuthConnection(User user, String provider, String providerAccountId) {
    this.user = user;
    this.provider = provider;
    this.providerAccountId = providerAccountId;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (connectedAt == null) {
      connectedAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  /** Wipes token material for disconnect; keeps the revoked stub. */
  public void wipeCredentials() {
    this.accessTokenEnc = null;
    this.refreshTokenEnc = null;
    this.status = STATUS_REVOKED;
    this.revokedAt = Instant.now();
    this.lastError = null;
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderAccountId() {
    return providerAccountId;
  }

  public String getProviderEmail() {
    return providerEmail;
  }

  public void setProviderEmail(String providerEmail) {
    this.providerEmail = providerEmail;
  }

  public String[] getScopes() {
    return scopes;
  }

  public void setScopes(String[] scopes) {
    this.scopes = scopes;
  }

  public String getAccessTokenEnc() {
    return accessTokenEnc;
  }

  public void setAccessTokenEnc(String accessTokenEnc) {
    this.accessTokenEnc = accessTokenEnc;
  }

  public String getRefreshTokenEnc() {
    return refreshTokenEnc;
  }

  public void setRefreshTokenEnc(String refreshTokenEnc) {
    this.refreshTokenEnc = refreshTokenEnc;
  }

  public int getKeyVersion() {
    return keyVersion;
  }

  public void setKeyVersion(int keyVersion) {
    this.keyVersion = keyVersion;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public Instant getConnectedAt() {
    return connectedAt;
  }

  public Instant getLastRefreshedAt() {
    return lastRefreshedAt;
  }

  public void setLastRefreshedAt(Instant lastRefreshedAt) {
    this.lastRefreshedAt = lastRefreshedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
