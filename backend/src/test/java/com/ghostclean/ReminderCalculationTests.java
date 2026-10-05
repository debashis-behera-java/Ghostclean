package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.privacy.PrivacyJurisdiction;
import com.ghostclean.privacy.ReminderCalculation;
import com.ghostclean.privacy.ReminderCalculation.Evaluation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic reminder-calculation tests (no Spring, fixed clock).
 * accepted_at is the V1 anchor: a handoff timestamp, never proof of
 * external delivery. Deadlines are configured estimates only.
 */
class ReminderCalculationTests {

  private static final Instant ACCEPTED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void deadlineUsesConfiguredMetadataOnly() {
    assertEquals(ACCEPTED.plus(Duration.ofDays(30)),
        ReminderCalculation.evaluate(
            ACCEPTED, PrivacyJurisdiction.GENERAL, ACCEPTED, false, null).deadlineAt());
    assertEquals(ACCEPTED.plus(Duration.ofDays(30)),
        ReminderCalculation.evaluate(
            ACCEPTED, PrivacyJurisdiction.GDPR, ACCEPTED, false, null).deadlineAt());
    assertEquals(ACCEPTED.plus(Duration.ofDays(45)),
        ReminderCalculation.evaluate(
            ACCEPTED, PrivacyJurisdiction.CCPA_CPRA, ACCEPTED, false, null).deadlineAt());
  }

  @Test
  void evaluationIsDeterministicAndSideEffectFree() {
    Instant now = ACCEPTED.plus(Duration.ofDays(10));
    Evaluation first =
        ReminderCalculation.evaluate(ACCEPTED, PrivacyJurisdiction.GENERAL, now, false, null);
    Evaluation second =
        ReminderCalculation.evaluate(ACCEPTED, PrivacyJurisdiction.GENERAL, now, false, null);
    assertEquals(first, second);
  }

  @Test
  void noRemindersBeforeFirstThreshold() {
    Evaluation evaluation = ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL, ACCEPTED.plus(Duration.ofDays(1)), false, null);
    assertTrue(evaluation.remindersDue().isEmpty());
    assertEquals(ACCEPTED.plus(Duration.ofDays(23)), evaluation.nextReminderDue());
  }

  @Test
  void reminderDueStatesProgress() {
    Instant deadline = ACCEPTED.plus(Duration.ofDays(30));
    Evaluation early = ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL,
        deadline.minus(Duration.ofDays(7)), false, null);
    assertEquals(List.of(ReminderCalculation.REMINDER_DUE_7D), early.remindersDue());
    assertEquals(deadline.minus(Duration.ofDays(1)), early.nextReminderDue());

    Evaluation late = ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL,
        deadline.minus(Duration.ofHours(12)), false, null);
    assertEquals(List.of(
        ReminderCalculation.REMINDER_DUE_7D, ReminderCalculation.REMINDER_DUE_1D),
        late.remindersDue());
    assertEquals(deadline, late.nextReminderDue());

    Evaluation overdue = ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL,
        deadline.plus(Duration.ofDays(2)), false, null);
    assertEquals(List.of(
        ReminderCalculation.REMINDER_DUE_7D,
        ReminderCalculation.REMINDER_DUE_1D,
        ReminderCalculation.REMINDER_OVERDUE), overdue.remindersDue());
    assertNull(overdue.nextReminderDue());
  }

  @Test
  void optedOutUserGetsNoEvaluation() {
    assertNull(ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL,
        ACCEPTED.plus(Duration.ofDays(40)), true, null));
  }

  @Test
  void terminalOutcomeClosesReminders() {
    Instant farFuture = ACCEPTED.plus(Duration.ofDays(100));
    for (String outcome : new String[]{"completed", "rejected", "partial"}) {
      assertNull(ReminderCalculation.evaluate(
          ACCEPTED, PrivacyJurisdiction.GENERAL, farFuture, false, outcome));
    }
  }

  @Test
  void verificationRequiredResumesFollowUp() {
    Evaluation evaluation = ReminderCalculation.evaluate(
        ACCEPTED, PrivacyJurisdiction.GENERAL,
        ACCEPTED.plus(Duration.ofDays(40)), false, "verification_required");
    assertTrue(evaluation.remindersDue().contains(ReminderCalculation.REMINDER_OVERDUE));
  }

  @Test
  void unknownAnchorYieldsNoDeadline() {
    assertNull(ReminderCalculation.evaluate(
        null, PrivacyJurisdiction.GENERAL, ACCEPTED, false, null));
    assertNull(ReminderCalculation.evaluate(
        ACCEPTED, null, ACCEPTED, false, null));
  }
}
