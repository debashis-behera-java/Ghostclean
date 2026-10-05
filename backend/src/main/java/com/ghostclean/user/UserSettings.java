package com.ghostclean.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-user preferences foundation (Phase 1: schema only).
 *
 * <p>Shares the user's primary key. Only the retention/reminder/jurisdiction
 * knobs approved in Phase 0 — no speculative settings.
 */
@Entity
@Table(name = "user_settings")
public class UserSettings {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId
  @JoinColumn(name = "user_id")
  private User user;

  @Column(name = "retention_days", nullable = false)
  private int retentionDays = 180;

  @Column(name = "reminder_opt_out", nullable = false)
  private boolean reminderOptOut;

  @Column(name = "jurisdiction_default", length = 32)
  private String jurisdictionDefault;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserSettings() {
  }

  public UserSettings(User user) {
    this.user = user;
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

  public UUID getUserId() {
    return userId;
  }

  public User getUser() {
    return user;
  }

  public int getRetentionDays() {
    return retentionDays;
  }

  public void setRetentionDays(int retentionDays) {
    this.retentionDays = retentionDays;
  }

  public boolean isReminderOptOut() {
    return reminderOptOut;
  }

  public void setReminderOptOut(boolean reminderOptOut) {
    this.reminderOptOut = reminderOptOut;
  }

  public String getJurisdictionDefault() {
    return jurisdictionDefault;
  }

  public void setJurisdictionDefault(String jurisdictionDefault) {
    this.jurisdictionDefault = jurisdictionDefault;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
