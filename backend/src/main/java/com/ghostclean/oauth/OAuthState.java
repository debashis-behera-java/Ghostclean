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
import jakarta.persistence.Table;
import com.ghostclean.user.User;
import java.time.Instant;
import java.util.UUID;

/**
 * Single-use authorization state. Only the SHA-256 hash of the state value
 * is stored; the raw state travels the browser redirect and dies at the
 * callback. Rows are consumed (or expired) exactly once, binding the
 * callback to the initiating GhostClean user without trusting the browser.
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "state_hash", nullable = false, unique = true)
  private String stateHash;

  /** PKCE verifier: short-lived, server-side only, never logged/returned. */
  @Column(name = "code_verifier", nullable = false)
  private String codeVerifier;

  @Column(name = "return_url", length = 500)
  private String returnUrl;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected OAuthState() {
  }

  public OAuthState(User user, String provider, String stateHash,
      String codeVerifier, String returnUrl, Instant expiresAt) {
    this.user = user;
    this.provider = provider;
    this.stateHash = stateHash;
    this.codeVerifier = codeVerifier;
    this.returnUrl = returnUrl;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
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

  public String getStateHash() {
    return stateHash;
  }

  public String getCodeVerifier() {
    return codeVerifier;
  }

  public String getReturnUrl() {
    return returnUrl;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public void setUsedAt(Instant usedAt) {
    this.usedAt = usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
