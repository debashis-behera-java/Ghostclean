package com.ghostclean.detection;

import com.ghostclean.user.User;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One scored observation of a registration signal. Stores normalized,
 * truncated, sanitized metadata only: sender domain, sender-address hash,
 * subject head, timestamp, matched rules, and score contribution. No bodies,
 * no attachments, no full addresses. OAuth rows dedupe on the opaque
 * provider message id (a sensitive reference: retained for dedupe, never
 * logged, never returned raw).
 */
@Entity
@Table(
    name = "service_evidence",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_evidence_user_provider_message",
        columnNames = {"user_id", "provider", "provider_message_id"}))
public class ServiceEvidence {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /**
   * Owning account id as a plain column (not a JPA association) so the
   * detection package never depends on the account package — the
   * dependency runs account → detection only (ArchUnit-enforced).
   */
  @Column(name = "discovered_service_id", nullable = false)
  private UUID discoveredServiceId;

  /**
   * Originating scan id, plain column for the same layering reason;
   * null for manually provided evidence.
   */
  @Column(name = "mailbox_scan_id")
  private UUID scanId;

  @Column(name = "source", nullable = false, length = 16)
  private String source = "oauth";

  @Column(name = "provider", nullable = false, length = 32)
  private String provider = "google";

  @Column(name = "provider_message_id", nullable = false)
  private String providerMessageId;

  @Column(name = "sender_domain", columnDefinition = "citext")
  private String senderDomain;

  @Column(name = "sender_address_hash")
  private String senderAddressHash;

  @Column(name = "subject_truncated", length = 300)
  private String subjectTruncated;

  @Column(name = "received_at")
  private Instant receivedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "matched_rules", columnDefinition = "jsonb")
  private Map<String, Object> matchedRules;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "signal_snapshot", columnDefinition = "jsonb")
  private Map<String, Object> signalSnapshot;

  @Column(name = "confidence_contrib")
  private int confidenceContrib;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ServiceEvidence() {
  }

  /** Cross-package construction point for the scanner; rows are user-scoped. */
  public static ServiceEvidence create() {
    return new ServiceEvidence();
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

  public void setUser(User user) {
    this.user = user;
  }

  public UUID getDiscoveredServiceId() {
    return discoveredServiceId;
  }

  public void setDiscoveredServiceId(UUID discoveredServiceId) {
    this.discoveredServiceId = discoveredServiceId;
  }

  public UUID getScanId() {
    return scanId;
  }

  public void setScanId(UUID scanId) {
    this.scanId = scanId;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getProviderMessageId() {
    return providerMessageId;
  }

  public void setProviderMessageId(String providerMessageId) {
    this.providerMessageId = providerMessageId;
  }

  public String getSenderDomain() {
    return senderDomain;
  }

  public void setSenderDomain(String senderDomain) {
    this.senderDomain = senderDomain;
  }

  public String getSenderAddressHash() {
    return senderAddressHash;
  }

  public void setSenderAddressHash(String senderAddressHash) {
    this.senderAddressHash = senderAddressHash;
  }

  public String getSubjectTruncated() {
    return subjectTruncated;
  }

  public void setSubjectTruncated(String subjectTruncated) {
    this.subjectTruncated = subjectTruncated;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(Instant receivedAt) {
    this.receivedAt = receivedAt;
  }

  public Map<String, Object> getMatchedRules() {
    return matchedRules;
  }

  public void setMatchedRules(Map<String, Object> matchedRules) {
    this.matchedRules = matchedRules;
  }

  public Map<String, Object> getSignalSnapshot() {
    return signalSnapshot;
  }

  public void setSignalSnapshot(Map<String, Object> signalSnapshot) {
    this.signalSnapshot = signalSnapshot;
  }

  public int getConfidenceContrib() {
    return confidenceContrib;
  }

  public void setConfidenceContrib(int confidenceContrib) {
    this.confidenceContrib = confidenceContrib;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
