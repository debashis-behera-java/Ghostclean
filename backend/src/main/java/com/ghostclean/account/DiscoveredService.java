package com.ghostclean.account;

import com.ghostclean.services.ServiceDirectory;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One normalized account candidate per (user, service). Grouping key is
 * {@code service_key}: the directory slug for known services, the
 * registrable domain otherwise — enforced unique per user, so rescans
 * update rather than duplicate. Confidence is an evidence rollup, never a
 * certainty; language is "Detected account evidence", never "confirmed".
 */
@Entity
@Table(
    name = "discovered_services",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_discovered_user_service_key",
        columnNames = {"user_id", "service_key"}))
public class DiscoveredService {

  public static final String STATUS_DETECTED = "DETECTED";
  public static final String STATUS_REVIEWED = "REVIEWED";
  public static final String STATUS_DISMISSED = "DISMISSED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "service_directory_id")
  private ServiceDirectory serviceDirectory;

  @Column(name = "service_key", nullable = false, columnDefinition = "citext")
  private String serviceKey;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "category", length = 32)
  private String category = "other";

  @Column(name = "confidence", nullable = false)
  private int confidence;

  @Column(name = "confidence_band", nullable = false, length = 16)
  private String confidenceBand = "LOW";

  @Column(name = "risk_score")
  private Integer riskScore;

  @Column(name = "risk_band", length = 16)
  private String riskBand;

  @Column(name = "evidence_count", nullable = false)
  private int evidenceCount;

  @Column(name = "first_seen_at")
  private Instant firstSeenAt;

  @Column(name = "last_seen_at")
  private Instant lastSeenAt;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_DETECTED;

  @Column(name = "dismissed_reason", length = 300)
  private String dismissedReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DiscoveredService() {
  }

  public DiscoveredService(User user, String serviceKey, String displayName) {
    this.user = user;
    this.serviceKey = serviceKey;
    this.displayName = displayName;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public ServiceDirectory getServiceDirectory() {
    return serviceDirectory;
  }

  public void setServiceDirectory(ServiceDirectory serviceDirectory) {
    this.serviceDirectory = serviceDirectory;
  }

  public String getServiceKey() {
    return serviceKey;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public int getConfidence() {
    return confidence;
  }

  public void setConfidence(int confidence) {
    this.confidence = confidence;
  }

  public String getConfidenceBand() {
    return confidenceBand;
  }

  public void setConfidenceBand(String confidenceBand) {
    this.confidenceBand = confidenceBand;
  }

  public int getEvidenceCount() {
    return evidenceCount;
  }

  public void setEvidenceCount(int evidenceCount) {
    this.evidenceCount = evidenceCount;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public void setFirstSeenAt(Instant firstSeenAt) {
    this.firstSeenAt = firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDismissedReason() {
    return dismissedReason;
  }

  public void setDismissedReason(String dismissedReason) {
    this.dismissedReason = dismissedReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
