package com.ghostclean.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit record foundation (Phase 1: schema only).
 *
 * <p>Metadata is an allow-listed, redacted JSON document — never passwords,
 * tokens, keys, bodies, or mailbox contents. The application role must only
 * ever INSERT and SELECT (enforced operationally; no UPDATE/DELETE in code).
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "event", nullable = false, length = 64)
  private String event;

  @Column(name = "actor", nullable = false, length = 16)
  private String actor;

  @Column(name = "ip_hash", length = 128)
  private String ipHash;

  @Column(name = "user_agent_hash", length = 128)
  private String userAgentHash;

  @Column(name = "entity_type", length = 64)
  private String entityType;

  @Column(name = "entity_id", length = 128)
  private String entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  private Map<String, Object> metadata;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {
  }

  public AuditLog(String event, String actor) {
    this.event = event;
    this.actor = actor;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public void setUserId(UUID userId) {
    this.userId = userId;
  }

  public String getEvent() {
    return event;
  }

  public String getActor() {
    return actor;
  }

  public String getIpHash() {
    return ipHash;
  }

  public void setIpHash(String ipHash) {
    this.ipHash = ipHash;
  }

  public String getUserAgentHash() {
    return userAgentHash;
  }

  public void setUserAgentHash(String userAgentHash) {
    this.userAgentHash = userAgentHash;
  }

  public String getEntityType() {
    return entityType;
  }

  public void setEntityType(String entityType) {
    this.entityType = entityType;
  }

  public String getEntityId() {
    return entityId;
  }

  public void setEntityId(String entityId) {
    this.entityId = entityId;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
