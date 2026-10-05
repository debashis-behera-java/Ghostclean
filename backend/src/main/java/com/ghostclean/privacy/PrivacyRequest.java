package com.ghostclean.privacy;

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
 * One user-owned privacy request over a detected account (Phase 6).
 *
 * <p>Lifecycle: DRAFT (generated, unreviewed) → READY_FOR_REVIEW
 * (user-reviewed via PATCH) → APPROVED (explicit, exact version recorded).
 * CANCELLED and EXPIRED are terminal. Editing an APPROVED request returns
 * it to READY_FOR_REVIEW with a bumped version and cleared approval —
 * approval can never silently cover modified content.
 *
 * <p>Stores user-facing request text (required for review) plus lifecycle
 * bookkeeping. Never tokens, mailbox bodies, attachments, or passwords.
 */
@Entity
@Table(
    name = "privacy_requests",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_privacy_user_idempotency",
        columnNames = {"user_id", "idempotency_key"}))
public class PrivacyRequest {

  public static final String STATUS_DRAFT = "DRAFT";
  public static final String STATUS_READY_FOR_REVIEW = "READY_FOR_REVIEW";
  public static final String STATUS_APPROVED = "APPROVED";
  public static final String STATUS_CANCELLED = "CANCELLED";
  public static final String STATUS_EXPIRED = "EXPIRED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "discovered_service_id", nullable = false)
  private UUID discoveredServiceId;

  @Column(name = "service_directory_id")
  private UUID serviceDirectoryId;

  @Column(name = "request_type", nullable = false, length = 32)
  private String requestType = PrivacyRequestType.GENERAL_PRIVACY_REQUEST.name();

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_DRAFT;

  @Column(name = "subject", nullable = false, columnDefinition = "TEXT")
  private String subject = "";

  @Column(name = "body", nullable = false, columnDefinition = "TEXT")
  private String body = "";

  @Column(name = "jurisdiction", nullable = false, length = 16)
  private String jurisdiction = PrivacyJurisdiction.GENERAL.name();

  @Column(name = "version", nullable = false)
  private int version = 1;

  @Column(name = "approved_version")
  private Integer approvedVersion;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "template_id", length = 64)
  private String templateId;

  @Column(name = "template_version")
  private Integer templateVersion;

  @Column(name = "deletion_method", length = 32)
  private String deletionMethod;

  @Column(name = "manual_review_required")
  private Boolean manualReviewRequired;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PrivacyRequest() {
  }

  public PrivacyRequest(User user, UUID discoveredServiceId) {
    this.user = user;
    this.discoveredServiceId = discoveredServiceId;
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

  public boolean isActive() {
    return STATUS_DRAFT.equals(status) || STATUS_READY_FOR_REVIEW.equals(status);
  }

  public boolean isTerminal() {
    return STATUS_CANCELLED.equals(status) || STATUS_EXPIRED.equals(status);
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public UUID getDiscoveredServiceId() {
    return discoveredServiceId;
  }

  public UUID getServiceDirectoryId() {
    return serviceDirectoryId;
  }

  public void setServiceDirectoryId(UUID serviceDirectoryId) {
    this.serviceDirectoryId = serviceDirectoryId;
  }

  public String getRequestType() {
    return requestType;
  }

  public void setRequestType(String requestType) {
    this.requestType = requestType;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public String getJurisdiction() {
    return jurisdiction;
  }

  public void setJurisdiction(String jurisdiction) {
    this.jurisdiction = jurisdiction;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public Integer getApprovedVersion() {
    return approvedVersion;
  }

  public void setApprovedVersion(Integer approvedVersion) {
    this.approvedVersion = approvedVersion;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }

  public void setApprovedAt(Instant approvedAt) {
    this.approvedAt = approvedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getTemplateId() {
    return templateId;
  }

  public void setTemplateId(String templateId) {
    this.templateId = templateId;
  }

  public Integer getTemplateVersion() {
    return templateVersion;
  }

  public void setTemplateVersion(Integer templateVersion) {
    this.templateVersion = templateVersion;
  }

  public String getDeletionMethod() {
    return deletionMethod;
  }

  public void setDeletionMethod(String deletionMethod) {
    this.deletionMethod = deletionMethod;
  }

  public Boolean getManualReviewRequired() {
    return manualReviewRequired;
  }

  public void setManualReviewRequired(Boolean manualReviewRequired) {
    this.manualReviewRequired = manualReviewRequired;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
