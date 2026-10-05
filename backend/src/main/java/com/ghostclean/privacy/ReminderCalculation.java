package com.ghostclean.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic reminder/deadline calculation (Phase 9). Pure function —
 * no I/O, no clock reads, no threads, no scheduler. The future production
 * deployment may invoke this from an external scheduler; this class is
 * the application-level boundary, not scheduling infrastructure.
 *
 * <p>Semantics (per human architecture decision): {@code acceptedAt} is
 * the V1 internal handoff anchor — the time the request was accepted for
 * the user's external action. It proves nothing about external delivery,
 * receipt, processing, deletion, or legal deadlines. Deadlines below are
 * configured response-target estimates (GDPR ~30d, CCPA ~45d, other 30d)
 * and must always be presented as estimates. Unknown inputs yield no
 * deadline rather than a fabricated one.
 */
public final class ReminderCalculation {

  /** Configured response targets in days — estimates, never legal deadlines. */
  public static final int GDPR_TARGET_DAYS = 30;
  public static final int CCPA_CPRA_TARGET_DAYS = 45;
  public static final int GENERAL_TARGET_DAYS = 30;

  public static final String REMINDER_DUE_7D = "DUE_7D";
  public static final String REMINDER_DUE_1D = "DUE_1D";
  public static final String REMINDER_OVERDUE = "OVERDUE";

  private ReminderCalculation() {
  }

  public record Evaluation(
      Instant deadlineAt,
      List<String> remindersDue,
      Instant nextReminderDue) {
  }

  /** Configured target for a jurisdiction; null only for null input. */
  public static Integer targetDays(PrivacyJurisdiction jurisdiction) {
    if (jurisdiction == null) {
      return null;
    }
    return switch (jurisdiction) {
      case GDPR -> GDPR_TARGET_DAYS;
      case CCPA_CPRA -> CCPA_CPRA_TARGET_DAYS;
      case GENERAL -> GENERAL_TARGET_DAYS;
    };
  }

  /**
   * Evaluates reminder state at {@code now}. Returns null when no reminder
   * applies: unknown anchor, opted-out user, or terminal recorded outcome.
   * A {@code verification_required} latest outcome resumes follow-up.
   * Repeated calls with equal inputs return equal results and write
   * nothing — idempotent by construction.
   */
  public static Evaluation evaluate(
      Instant acceptedAt,
      PrivacyJurisdiction jurisdiction,
      Instant now,
      boolean optedOut,
      String latestOutcome) {
    if (acceptedAt == null || now == null || optedOut) {
      return null;
    }
    if (latestOutcome != null && PrivacyRequestResponse.isTerminal(latestOutcome)) {
      return null;
    }
    Integer targetDays = targetDays(jurisdiction);
    if (targetDays == null) {
      return null;
    }
    Instant deadline = acceptedAt.plus(Duration.ofDays(targetDays));
    Instant due7d = deadline.minus(Duration.ofDays(7));
    Instant due1d = deadline.minus(Duration.ofDays(1));

    List<String> due = new ArrayList<>(3);
    if (!now.isBefore(due7d)) {
      due.add(REMINDER_DUE_7D);
    }
    if (!now.isBefore(due1d)) {
      due.add(REMINDER_DUE_1D);
    }
    if (now.isAfter(deadline)) {
      due.add(REMINDER_OVERDUE);
    }
    Instant next = null;
    for (Instant threshold : List.of(due7d, due1d, deadline)) {
      if (now.isBefore(threshold)) {
        next = threshold;
        break;
      }
    }
    return new Evaluation(deadline, List.copyOf(due), next);
  }
}
