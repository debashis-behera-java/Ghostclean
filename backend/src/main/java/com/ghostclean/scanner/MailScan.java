package com.ghostclean.scanner;

import com.ghostclean.oauth.OAuthConnection;
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
 * One bounded mailbox scan run. Created RUNNING and executed synchronously
 * in-request (no background-job infrastructure in Phase 4); progress commits
 * per page so polling and cooperative cancellation observe live state.
 * Exactly one non-terminal scan per user is enforced by partial unique
 * index; repeat submissions with the same idempotency key return the
 * original row instead of scanning twice.
 */
@Entity
@Table(
    name = "mailbox_scans",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_scan_user_idempotency",
        columnNames = {"user_id", "idempotency_key"}))
public class MailScan {

  public static final String STATUS_QUEUED = "QUEUED";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_CANCELLED = "CANCELLED";

  public static final String TYPE_FULL = "FULL";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "oauth_connection_id", nullable = false)
  private OAuthConnection connection;

  @Column(name = "type", nullable = false, length = 16)
  private String type = TYPE_FULL;

  @Column(name = "status", nullable = false, length = 16)
  private String status = STATUS_RUNNING;

  @Column(name = "progress_total")
  private Integer progressTotal;

  @Column(name = "progress_done", nullable = false)
  private int progressDone;

  @Column(name = "messages_scanned", nullable = false)
  private int messagesScanned;

  @Column(name = "candidates_detected", nullable = false)
  private int candidatesDetected;

  @Column(name = "errors_count", nullable = false)
  private int errorsCount;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "cancel_requested", nullable = false)
  private boolean cancelRequested;

  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  @Column(name = "query_used", length = 500)
  private String queryUsed;

  /**
   * Incremental-scan foundation: newest observed message instant (ISO) at
   * completion. Phase 5 delta scans build on this watermark.
   */
  @Column(name = "checkpoint")
  private String checkpoint;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MailScan() {
  }

  public MailScan(User user, OAuthConnection connection) {
    this.user = user;
    this.connection = connection;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (startedAt == null) {
      startedAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public boolean isTerminal() {
    return STATUS_COMPLETED.equals(status)
        || STATUS_FAILED.equals(status)
        || STATUS_CANCELLED.equals(status);
  }

  public UUID getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public OAuthConnection getConnection() {
    return connection;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public Integer getProgressTotal() {
    return progressTotal;
  }

  public void setProgressTotal(Integer progressTotal) {
    this.progressTotal = progressTotal;
  }

  public int getProgressDone() {
    return progressDone;
  }

  public void setProgressDone(int progressDone) {
    this.progressDone = progressDone;
  }

  public int getMessagesScanned() {
    return messagesScanned;
  }

  public void setMessagesScanned(int messagesScanned) {
    this.messagesScanned = messagesScanned;
  }

  public int getCandidatesDetected() {
    return candidatesDetected;
  }

  public void setCandidatesDetected(int candidatesDetected) {
    this.candidatesDetected = candidatesDetected;
  }

  public int getErrorsCount() {
    return errorsCount;
  }

  public void setErrorsCount(int errorsCount) {
    this.errorsCount = errorsCount;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public boolean isCancelRequested() {
    return cancelRequested;
  }

  public void setCancelRequested(boolean cancelRequested) {
    this.cancelRequested = cancelRequested;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getQueryUsed() {
    return queryUsed;
  }

  public void setQueryUsed(String queryUsed) {
    this.queryUsed = queryUsed;
  }

  public String getCheckpoint() {
    return checkpoint;
  }

  public void setCheckpoint(String checkpoint) {
    this.checkpoint = checkpoint;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
