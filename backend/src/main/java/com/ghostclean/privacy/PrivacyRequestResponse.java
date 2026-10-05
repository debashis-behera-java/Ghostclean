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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One user-recorded service outcome (Phase 9, manual intake only).
 *
 * <p>Append-only: rows are never updated, and the latest row per request
 * determines the computed tracking state. Outcomes are the user's own
 * characterization of an external response — never verified external
 * evidence, never a claim that an account was deleted. No DELIVERED or
 * ACCOUNT_DELETED concept exists anywhere in this model.
 */
@Entity
@Table(name = "privacy_request_responses")
public class PrivacyRequestResponse {

  public static final String OUTCOME_COMPLETED = "completed";
  public static final String OUTCOME_REJECTED = "rejected";
  public static final String OUTCOME_PARTIAL = "partial";
  public static final String OUTCOME_VERIFICATION_REQUIRED = "verification_required";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "privacy_request_id", nullable = false)
  private PrivacyRequest request;

  @Column(name = "outcome", nullable = false, length = 32)
  private String outcome = OUTCOME_COMPLETED;

  @Column(name = "summary", columnDefinition = "TEXT")
  private String summary;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PrivacyRequestResponse() {
  }

  public PrivacyRequestResponse(PrivacyRequest request, String outcome) {
    this.request = request;
    this.outcome = outcome;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (recordedAt == null) {
      recordedAt = now;
    }
  }

  /** Terminal outcomes close reminders; verification_required re-opens follow-up. */
  public static boolean isTerminal(String outcome) {
    return OUTCOME_COMPLETED.equals(outcome)
        || OUTCOME_REJECTED.equals(outcome)
        || OUTCOME_PARTIAL.equals(outcome);
  }

  public UUID getId() {
    return id;
  }

  public PrivacyRequest getRequest() {
    return request;
  }

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
