package com.ghostclean.privacy;

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
 * One dispatch attempt-record per privacy request (Phase 7, gate 2).
 *
 * <p>Lifecycle: QUEUED (created by the explicit dispatch action) →
 * SENDING (provider call in flight) → ACCEPTED (provider confirmed) or
 * FAILED (bounded retries exhausted or permanent rejection). CANCELLED is
 * terminal via explicit cancel of a non-accepted row; READY is reserved
 * for a future async queue. One row per request (unique) makes repeat
 * dispatches return the recorded outcome instead of resending.
 *
 * <p>Stores the approved-version snapshot actually handed to the
 * provider, the trusted destination, and safe outcome metadata. No
 * credentials, tokens, mailbox content, or attachments — the message
 * shape itself has no attachment capability.
 */
@Entity
@Table(
    name = "privacy_request_dispatches",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_dispatch_request",
            columnNames = {"privacy_request_id"}),
        @UniqueConstraint(
            name = "uq_dispatch_idempotency",
            columnNames = {"idempotency_key"})
    })
public class PrivacyDispatch {

  public static final String STATUS_READY = "READY";
  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_SENDING = "SENDING";
  public static final String STATUS_ACCEPTED = "ACCEPTED";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_CANCELLED = "CANCELLED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "privacy_request_id", nullable = false)
  private PrivacyRequest request;

  @Column(name = "request_version", nullable = false)
  private int requestVersion;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider = "sink";

  @Column(name = "status", nullable = false, length = 16)
  private String status = STATUS_QUEUED;

  @Column(name = "destination", nullable = false, columnDefinition = "TEXT")
  private String destination = "";

  @Column(name = "subject_snapshot", nullable = false, columnDefinition = "TEXT")
  private String subjectSnapshot = "";

  @Column(name = "body_snapshot", nullable = false, columnDefinition = "TEXT")
  private String bodySnapshot = "";

  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "provider_reference", columnDefinition = "TEXT")
  private String providerReference;

  @Column(name = "failure_category", length = 32)
  private String failureCategory;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "failed_at")
  private Instant failedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PrivacyDispatch() {
  }

  public PrivacyDispatch(PrivacyRequest request, int requestVersion) {
    this.request = request;
    this.requestVersion = requestVersion;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (requestedAt == null) {
      requestedAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public boolean isFinal() {
    return STATUS_ACCEPTED.equals(status) || STATUS_CANCELLED.equals(status);
  }

  public UUID getId() {
    return id;
  }

  public PrivacyRequest getRequest() {
    return request;
  }

  public int getRequestVersion() {
    return requestVersion;
  }

  public void setRequestVersion(int requestVersion) {
    this.requestVersion = requestVersion;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  public String getSubjectSnapshot() {
    return subjectSnapshot;
  }

  public void setSubjectSnapshot(String subjectSnapshot) {
    this.subjectSnapshot = subjectSnapshot;
  }

  public String getBodySnapshot() {
    return bodySnapshot;
  }

  public void setBodySnapshot(String bodySnapshot) {
    this.bodySnapshot = bodySnapshot;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getProviderReference() {
    return providerReference;
  }

  public void setProviderReference(String providerReference) {
    this.providerReference = providerReference;
  }

  public String getFailureCategory() {
    return failureCategory;
  }

  public void setFailureCategory(String failureCategory) {
    this.failureCategory = failureCategory;
  }

  public int getAttempts() {
    return attempts;
  }

  public void setAttempts(int attempts) {
    this.attempts = attempts;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getAcceptedAt() {
    return acceptedAt;
  }

  public void setAcceptedAt(Instant acceptedAt) {
    this.acceptedAt = acceptedAt;
  }

  public Instant getFailedAt() {
    return failedAt;
  }

  public void setFailedAt(Instant failedAt) {
    this.failedAt = failedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
