package com.ghostclean.user;

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
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-session foundation for Phase 2 rotation (schema only in Phase 1).
 *
 * <p>Stores only the SHA-256 <em>hash</em> of the opaque refresh token —
 * never the token itself and never plaintext secrets.
 */
@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "rotated_from")
  private UUID rotatedFrom;

  @Column(name = "revoked", nullable = false)
  private boolean revoked;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * Raw token for the single response that issues this session. Transient by
   * design: the raw value is never persisted, only its hash.
   */
  @Transient
  private String rawTokenForResponse;

  protected RefreshSession() {
  }

  public RefreshSession(User user, String tokenHash, Instant expiresAt) {
    this.user = user;
    this.tokenHash = tokenHash;
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

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public UUID getRotatedFrom() {
    return rotatedFrom;
  }

  public void setRotatedFrom(UUID rotatedFrom) {
    this.rotatedFrom = rotatedFrom;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public void setRevoked(boolean revoked) {
    this.revoked = revoked;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getRawTokenForResponse() {
    return rawTokenForResponse;
  }

  public void setRawTokenForResponse(String rawTokenForResponse) {
    this.rawTokenForResponse = rawTokenForResponse;
  }
}
