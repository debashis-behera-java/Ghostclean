package com.ghostclean.privacy;

import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.privacy.ReminderCalculation.Evaluation;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import com.ghostclean.user.UserSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Request tracking: deadlines, reminders, manual response intake
 * (Phase 9). Read paths are pure calculation over the Phase 6/7 rows —
 * no scheduler, no threads, no writes on evaluation. Response intake is
 * an explicit, owner-scoped, rate-limited user action appending to an
 * append-only history; the latest row drives computed state. Language
 * throughout is estimate/follow-up wording, never legal certainty.
 */
@Service
public class TrackingService {

  static final String DEADLINE_NOTE =
      "privacy-request deadline estimate — verify locally";
  static final String NO_DEADLINE_NOTE =
      "No deadline is computed without a handoff timestamp.";
  static final String DEADLINE_SOURCE = "accepted_at";

  private final PrivacyRequestRepository requests;
  private final PrivacyDispatchRepository dispatches;
  private final PrivacyRequestResponseRepository responses;
  private final UserRepository users;
  private final UserSettingsRepository settings;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;

  public TrackingService(
      PrivacyRequestRepository requests,
      PrivacyDispatchRepository dispatches,
      PrivacyRequestResponseRepository responses,
      UserRepository users,
      UserSettingsRepository settings,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls) {
    this.requests = requests;
    this.dispatches = dispatches;
    this.responses = responses;
    this.users = users;
    this.settings = settings;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
  }

  /** Computed tracking view; evaluation writes nothing. */
  @Transactional(readOnly = true)
  public PrivacyRequestController.TrackingDto tracking(
      UUID userId, UUID requestId, Instant now) {
    rls.apply();
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, requestId);
    Instant acceptedAt = dispatches.findByRequest_Id(requestId)
        .filter(dispatch -> PrivacyDispatch.STATUS_ACCEPTED.equals(dispatch.getStatus()))
        .map(PrivacyDispatch::getAcceptedAt)
        .orElse(null);
    List<PrivacyRequestResponse> history =
        responses.findByRequest_IdOrderByRecordedAtDesc(requestId);
    String latestOutcome = history.isEmpty() ? null : history.get(0).getOutcome();
    boolean optedOut = settings.findById(userId)
        .map(com.ghostclean.user.UserSettings::isReminderOptOut)
        .orElse(false);

    Evaluation evaluation = null;
    if (acceptedAt != null) {
      evaluation = ReminderCalculation.evaluate(
          acceptedAt, jurisdictionOf(row), now, optedOut, latestOutcome);
    }
    return toDto(row, acceptedAt, history, latestOutcome, optedOut, evaluation);
  }

  /** Records one user-reported service outcome (append-only). */
  @Transactional
  public PrivacyRequestController.ResponseDto recordResponse(
      UUID userId, UUID requestId, String outcomeRaw, String summary) {
    rls.apply();
    rateLimiter.check("tracking:respond:user:" + userId, 30, Duration.ofMinutes(1));
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, requestId);
    PrivacyRequestService.applyExpiry(requests, audit, row);
    if (!PrivacyRequest.STATUS_APPROVED.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Responses can only be recorded for approved requests");
    }
    String outcome = parseOutcome(outcomeRaw);
    PrivacyRequestResponse response = new PrivacyRequestResponse(row, outcome);
    if (summary != null && !summary.isBlank()) {
      response.setSummary(
          PrivacyRequestService.validateContent("summary", summary, 2000));
    }
    responses.save(response);
    audit.emit(AuditEvents.PRIVACY_REQUEST_RESPONSE_RECORDED, userId, "user",
        "privacy_request_response", response.getId().toString(),
        Map.of("outcome", outcome));
    return toResponseDto(response);
  }

  /** Owner-scoped response history, newest first. */
  @Transactional(readOnly = true)
  public List<PrivacyRequestController.ResponseDto> history(UUID userId, UUID requestId) {
    rls.apply();
    verifiedUser(userId);
    ownedRequest(userId, requestId);
    return responses.findByRequest_IdOrderByRecordedAtDesc(requestId).stream()
        .map(this::toResponseDto).toList();
  }

  // -- internals ------------------------------------------------------------------

  private PrivacyRequest ownedRequest(UUID userId, UUID requestId) {
    return requests.findByIdAndUser_Id(requestId, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Privacy request not found"));
  }

  private User verifiedUser(UUID userId) {
    return PrivacyRequestService.verifiedUser(users, userId);
  }

  private String parseOutcome(String raw) {
    if (raw == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Unknown response outcome");
    }
    String normalized = raw.trim().toLowerCase();
    if (PrivacyRequestResponse.OUTCOME_COMPLETED.equals(normalized)
        || PrivacyRequestResponse.OUTCOME_REJECTED.equals(normalized)
        || PrivacyRequestResponse.OUTCOME_PARTIAL.equals(normalized)
        || PrivacyRequestResponse.OUTCOME_VERIFICATION_REQUIRED.equals(normalized)) {
      return normalized;
    }
    throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
        "Unknown response outcome");
  }

  private PrivacyJurisdiction jurisdictionOf(PrivacyRequest row) {
    try {
      return PrivacyJurisdiction.valueOf(row.getJurisdiction());
    } catch (IllegalArgumentException | NullPointerException ex) {
      return PrivacyJurisdiction.GENERAL;
    }
  }

  private PrivacyRequestController.TrackingDto toDto(
      PrivacyRequest row, Instant acceptedAt,
      List<PrivacyRequestResponse> history, String latestOutcome,
      boolean optedOut, Evaluation evaluation) {
    String trackingState;
    String reminderState;
    if (!PrivacyRequest.STATUS_APPROVED.equals(row.getStatus())) {
      trackingState = "NOT_APPLICABLE";
      reminderState = "NONE";
    } else if (latestOutcome != null
        && PrivacyRequestResponse.isTerminal(latestOutcome)) {
      trackingState = "RESPONDED_" + latestOutcome.toUpperCase();
      reminderState = "CLOSED";
    } else if (PrivacyRequestResponse.OUTCOME_VERIFICATION_REQUIRED.equals(latestOutcome)) {
      trackingState = "AWAITING_AFTER_VERIFICATION";
      reminderState = evaluation == null ? "NONE" : optedOut ? "OPTED_OUT" : "ACTIVE";
    } else if (evaluation == null) {
      trackingState = "AWAITING_RESPONSE";
      reminderState = acceptedAt == null ? "NONE" : optedOut ? "OPTED_OUT" : "NONE";
    } else if (evaluation.remindersDue().contains(ReminderCalculation.REMINDER_OVERDUE)) {
      trackingState = "OVERDUE";
      reminderState = "ACTIVE";
    } else {
      trackingState = "AWAITING_RESPONSE";
      reminderState = "ACTIVE";
    }
    boolean manualRoute = acceptedAt == null;
    PrivacyRequestResponse latest =
        history.isEmpty() ? null : history.get(0);
    return new PrivacyRequestController.TrackingDto(
        row.getId(),
        row.getStatus(),
        trackingState,
        manualRoute,
        acceptedAt,
        evaluation == null ? null : evaluation.deadlineAt(),
        evaluation == null ? NO_DEADLINE_NOTE : DEADLINE_NOTE,
        DEADLINE_SOURCE,
        evaluation == null ? List.of() : evaluation.remindersDue(),
        evaluation == null ? null : evaluation.nextReminderDue(),
        reminderState,
        latestOutcome,
        latest == null ? null : latest.getSummary(),
        latest == null ? null : latest.getRecordedAt(),
        history.size());
  }

  private PrivacyRequestController.ResponseDto toResponseDto(PrivacyRequestResponse response) {
    return new PrivacyRequestController.ResponseDto(
        response.getId(),
        response.getRequest().getId(),
        response.getOutcome(),
        response.getSummary(),
        response.getRecordedAt());
  }
}
