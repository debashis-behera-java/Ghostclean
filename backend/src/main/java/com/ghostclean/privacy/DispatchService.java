package com.ghostclean.privacy;

import com.ghostclean.account.DiscoveredService;
import com.ghostclean.account.DiscoveredServiceRepository;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.privacy.OutboundMailProvider.OutboundMailMessage;
import com.ghostclean.privacy.OutboundMailProvider.SendResult;
import com.ghostclean.services.RouteType;
import com.ghostclean.services.RouteVerificationStatus;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled outbound dispatch (Phase 7, gate 2).
 *
 * <p>Dispatch is a separate explicit user action: approval never sends,
 * scans never send, and no scheduler exists anywhere in this codebase.
 * Eligibility re-verifies everything at send time against the trusted
 * service route (server-resolved destination — frontend recipients are
 * ignored), the exact approved version is snapshotted, attempts are
 * bounded, and repeats return the recorded outcome instead of resending.
 * Gmail is never involved; delivery goes through {@link OutboundMailProvider}.
 */
@Service
public class DispatchService {

  private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

  private static final Pattern EMAIL_FORMAT =
      Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Set<String> SAFE_CATEGORIES = Set.of(
      "TEMPORARY_PROVIDER_ERROR", "AUTHENTICATION_ERROR", "INVALID_DESTINATION",
      "MESSAGE_REJECTED", "RATE_LIMITED", "UNKNOWN_PROVIDER_ERROR");

  private final PrivacyDispatchRepository dispatches;
  private final PrivacyRequestRepository requests;
  private final DiscoveredServiceRepository accounts;
  private final UserRepository users;
  private final OutboundMailProvider provider;
  private final OutboundMailProperties mail;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;

  public DispatchService(
      PrivacyDispatchRepository dispatches,
      PrivacyRequestRepository requests,
      DiscoveredServiceRepository accounts,
      UserRepository users,
      OutboundMailProvider provider,
      OutboundMailProperties mail,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls) {
    this.dispatches = dispatches;
    this.requests = requests;
    this.accounts = accounts;
    this.users = users;
    this.provider = provider;
    this.mail = mail;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
  }

  /**
   * Explicit user dispatch (gate 2). Idempotent: a client key match or the
   * one-row-per-request record returns the existing outcome without
   * resending once accepted.
   */
  @Transactional
  public PrivacyRequestController.DispatchDto dispatch(
      UUID userId, UUID requestId, String idempotencyKey, Integer expectedVersion) {
    rls.apply();
    rateLimiter.check("dispatch:send:user:" + userId, 10, Duration.ofHours(1));
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, requestId);
    PrivacyRequestService.applyExpiry(requests, audit, row);
    expireApprovedIfDue(userId, row);

    String key = idempotencyKey == null || idempotencyKey.isBlank()
        ? null : idempotencyKey.trim();
    if (key != null) {
      var byKey = dispatches.findByIdempotencyKey(key);
      if (byKey.isPresent() && owns(userId, byKey.get())) {
        return toDto(byKey.get());
      }
    }
    var existing = dispatches.findByRequest_Id(requestId);
    if (existing.isPresent()) {
      PrivacyDispatch prior = existing.get();
      if (PrivacyDispatch.STATUS_ACCEPTED.equals(prior.getStatus())) {
        return toDto(prior);
      }
      if (PrivacyDispatch.STATUS_CANCELLED.equals(prior.getStatus())) {
        throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
            "Dispatch was cancelled");
      }
      // FAILED (or stale QUEUED/SENDING): fall through to bounded retry
      // against the current approved content, same dispatch row.
      return retry(userId, row, prior);
    }
    EligibleTarget target = requireEligible(userId, row, expectedVersion);
    PrivacyDispatch dispatch = new PrivacyDispatch(row, row.getVersion());
    dispatch.setProvider(provider.providerId());
    dispatch.setStatus(PrivacyDispatch.STATUS_QUEUED);
    dispatch.setDestination(target.destination());
    dispatch.setSubjectSnapshot(row.getSubject());
    dispatch.setBodySnapshot(row.getBody());
    dispatch.setIdempotencyKey(key);
    try {
      dispatches.save(dispatch);
    } catch (DataIntegrityViolationException ex) {
      // Lost an idempotency race; the recorded outcome wins, never a resend.
      return toDto(dispatches.findByRequest_Id(requestId).orElseThrow());
    }
    audit.emit(AuditEvents.PRIVACY_REQUEST_DISPATCH_REQUESTED, userId, "user",
        "privacy_request_dispatch", dispatch.getId().toString(),
        Map.of("status", dispatch.getStatus(), "version", dispatch.getRequestVersion()));
    log.info("Dispatch requested: dispatchId={} requestId={} provider={} status={}",
        dispatch.getId(), requestId, dispatch.getProvider(), dispatch.getStatus());
    return attempt(userId, row, dispatch);
  }

  /** Current dispatch state for one owned request. */
  @Transactional(readOnly = true)
  public PrivacyRequestController.DispatchDto status(UUID userId, UUID requestId) {
    rls.apply();
    verifiedUser(userId);
    ownedRequest(userId, requestId);
    PrivacyDispatch dispatch = dispatches.findByRequest_Id(requestId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "No dispatch recorded"));
    if (!owns(userId, dispatch)) {
      throw new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
          "No dispatch recorded");
    }
    return toDto(dispatch);
  }

  /** Cancels a non-accepted dispatch; accepted sends cannot be unsent. */
  @Transactional
  public PrivacyRequestController.DispatchDto cancel(UUID userId, UUID requestId) {
    rls.apply();
    rateLimiter.check("dispatch:cancel:user:" + userId, 30, Duration.ofMinutes(1));
    verifiedUser(userId);
    ownedRequest(userId, requestId);
    PrivacyDispatch dispatch = dispatches.findByRequest_Id(requestId)
        .filter(row -> owns(userId, row))
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "No dispatch recorded"));
    if (PrivacyDispatch.STATUS_CANCELLED.equals(dispatch.getStatus())) {
      return toDto(dispatch);
    }
    if (PrivacyDispatch.STATUS_ACCEPTED.equals(dispatch.getStatus())) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Accepted dispatch cannot be cancelled");
    }
    dispatch.setStatus(PrivacyDispatch.STATUS_CANCELLED);
    dispatches.save(dispatch);
    audit.emit(AuditEvents.PRIVACY_REQUEST_DISPATCH_CANCELLED, userId, "user",
        "privacy_request_dispatch", dispatch.getId().toString(),
        Map.of("status", dispatch.getStatus(), "version", dispatch.getRequestVersion()));
    return toDto(dispatch);
  }

  // -- internals ------------------------------------------------------------------

  private PrivacyRequestController.DispatchDto retry(
      UUID userId, PrivacyRequest row, PrivacyDispatch prior) {
    EligibleTarget target = requireEligible(userId, row, null);
    if (prior.getAttempts() >= maxAttempts()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Dispatch attempts exhausted");
    }
    // Refresh the snapshot to the exact current approved content.
    prior.setRequestVersion(row.getVersion());
    prior.setDestination(target.destination());
    prior.setSubjectSnapshot(row.getSubject());
    prior.setBodySnapshot(row.getBody());
    prior.setFailureCategory(null);
    audit.emit(AuditEvents.PRIVACY_REQUEST_DISPATCH_REQUESTED, userId, "user",
        "privacy_request_dispatch", prior.getId().toString(),
        Map.of("status", prior.getStatus(), "version", prior.getRequestVersion()));
    return attempt(userId, row, prior);
  }

  private PrivacyRequestController.DispatchDto attempt(
      UUID userId, PrivacyRequest row, PrivacyDispatch dispatch) {
    int max = maxAttempts();
    while (dispatch.getAttempts() < max) {
      dispatch.setStatus(PrivacyDispatch.STATUS_SENDING);
      dispatch.setAttempts(dispatch.getAttempts() + 1);
      dispatches.save(dispatch);
      OutboundMailMessage message = new OutboundMailMessage(
          mail.getFrom(), dispatch.getDestination(),
          dispatch.getSubjectSnapshot(), dispatch.getBodySnapshot());
      long started = System.currentTimeMillis();
      SendResult result = provider.send(message);
      long latencyMs = System.currentTimeMillis() - started;
      if (result.outcome() == SendResult.Outcome.ACCEPTED) {
        dispatch.setStatus(PrivacyDispatch.STATUS_ACCEPTED);
        dispatch.setAcceptedAt(Instant.now());
        dispatch.setProviderReference(result.providerReference());
        dispatch.setFailureCategory(null);
        dispatches.save(dispatch);
        audit.emit(AuditEvents.PRIVACY_REQUEST_DISPATCH_ACCEPTED, userId, "system",
            "privacy_request_dispatch", dispatch.getId().toString(),
            Map.of("status", dispatch.getStatus(), "version", dispatch.getRequestVersion()));
        log.info("Dispatch accepted: dispatchId={} provider={} attempts={} latencyMs={}",
            dispatch.getId(), dispatch.getProvider(), dispatch.getAttempts(), latencyMs);
        return toDto(dispatch);
      }
      String category = sanitizeCategory(result.failureCategory());
      log.info("Dispatch attempt failed: dispatchId={} provider={} attempt={} category={}",
          dispatch.getId(), dispatch.getProvider(), dispatch.getAttempts(), category);
      if (result.outcome() == SendResult.Outcome.PERMANENT_FAILURE) {
        return fail(userId, dispatch, category);
      }
      if (dispatch.getAttempts() >= max) {
        return fail(userId, dispatch, category);
      }
    }
    return fail(userId, dispatch, "TEMPORARY_PROVIDER_ERROR");
  }

  private PrivacyRequestController.DispatchDto fail(
      UUID userId, PrivacyDispatch dispatch, String category) {
    dispatch.setStatus(PrivacyDispatch.STATUS_FAILED);
    dispatch.setFailedAt(Instant.now());
    dispatch.setFailureCategory(category);
    dispatches.save(dispatch);
    audit.emit(AuditEvents.PRIVACY_REQUEST_DISPATCH_FAILED, userId, "system",
        "privacy_request_dispatch", dispatch.getId().toString(),
        Map.of("status", dispatch.getStatus(), "version", dispatch.getRequestVersion()));
    return toDto(dispatch);
  }

  /** Lazy expiry also covers APPROVED rows: stale approvals can never send. */
  private void expireApprovedIfDue(UUID userId, PrivacyRequest row) {
    if (row.isTerminal() || !Instant.now().isAfter(row.getExpiresAt())) {
      return;
    }
    row.setStatus(PrivacyRequest.STATUS_EXPIRED);
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_EXPIRED, userId, "system",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
    throw new AuthException(HttpStatus.GONE, ApiError.REQUEST_EXPIRED,
        "Request has expired");
  }

  /** All 14 eligibility gates; any failure means DO NOT SEND. */
  private EligibleTarget requireEligible(
      UUID userId, PrivacyRequest row, Integer expectedVersion) {
    if (PrivacyRequest.STATUS_EXPIRED.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.GONE, ApiError.REQUEST_EXPIRED,
          "Request has expired");
    }
    if (!PrivacyRequest.STATUS_APPROVED.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Only approved requests can be dispatched");
    }
    if (row.getApprovedVersion() == null || row.getApprovedVersion() != row.getVersion()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Approval does not cover the current version");
    }
    if (expectedVersion != null && expectedVersion != row.getVersion()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Request version changed; please review the current version");
    }
    DiscoveredService account = accounts.findByIdAndUser_Id(
        row.getDiscoveredServiceId(), userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Detected account not found"));
    ServiceDirectory entry = account.getServiceDirectory();
    if (entry == null || !"active".equalsIgnoreCase(entry.getStatus())
        || RouteVerificationStatus.parse(entry.getVerificationStatus())
            == RouteVerificationStatus.REJECTED) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Service route is no longer valid");
    }
    if (RouteVerificationStatus.parse(entry.getVerificationStatus())
        != RouteVerificationStatus.VERIFIED) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.DESTINATION_UNKNOWN,
          "No verified email destination is known");
    }
    if (RouteType.parse(entry.getRouteType()) != RouteType.EMAIL_REQUEST) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.MANUAL_REVIEW_REQUIRED,
          "Only verified email routes can be dispatched; manual handling required");
    }
    String destination = entry.getPrivacyEmail();
    if (!isUsableDestination(destination)) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.DESTINATION_UNKNOWN,
          "No verified email destination is known");
    }
    PrivacyRequestService.validateContent("subject", row.getSubject(),
        PrivacyRequestGenerator.MAX_SUBJECT);
    PrivacyRequestService.validateContent("body", row.getBody(),
        PrivacyRequestGenerator.MAX_BODY);
    return new EligibleTarget(destination.trim());
  }

  /** Server-side destination hygiene: well-formed, public, non-local. */
  static boolean isUsableDestination(String raw) {
    if (raw == null || raw.isBlank() || !EMAIL_FORMAT.matcher(raw.trim()).matches()) {
      return false;
    }
    String domain = raw.trim().substring(raw.trim().lastIndexOf('@') + 1).toLowerCase();
    if (domain.equals("localhost") || domain.endsWith(".localhost")
        || domain.equals("::1") || domain.equals("[::1]")) {
      return false;
    }
    if (domain.matches("127(\\.\\d+){3}")
        || domain.matches("10(\\.\\d+){3}")
        || domain.matches("192\\.168(\\.\\d+){2}")
        || domain.matches("172\\.(1[6-9]|2\\d|3[01])(\\.\\d+){2}")
        || domain.matches("169\\.254(\\.\\d+){2}")) {
      return false;
    }
    return true;
  }

  private int maxAttempts() {
    return Math.max(1, Math.min(10, mail.getMaxAttempts()));
  }

  private static String sanitizeCategory(String raw) {
    if (raw != null && SAFE_CATEGORIES.contains(raw.trim().toUpperCase())) {
      return raw.trim().toUpperCase();
    }
    return "UNKNOWN_PROVIDER_ERROR";
  }

  private boolean owns(UUID userId, PrivacyDispatch dispatch) {
    return dispatch.getRequest() != null
        && dispatch.getRequest().getUser() != null
        && userId.equals(dispatch.getRequest().getUser().getId());
  }

  private PrivacyRequest ownedRequest(UUID userId, UUID requestId) {
    return requests.findByIdAndUser_Id(requestId, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Privacy request not found"));
  }

  private User verifiedUser(UUID userId) {
    User user = users.findById(userId).orElseThrow(
        () -> new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
            "Authentication required"));
    if (!user.isEmailVerified()) {
      throw new AuthException(HttpStatus.FORBIDDEN, ApiError.EMAIL_NOT_VERIFIED,
          "Email verification required");
    }
    return user;
  }

  private PrivacyRequestController.DispatchDto toDto(PrivacyDispatch dispatch) {
    return new PrivacyRequestController.DispatchDto(
        dispatch.getId(),
        dispatch.getRequest().getId(),
        dispatch.getStatus(),
        dispatch.getProvider(),
        dispatch.getDestination(),
        dispatch.getRequestVersion(),
        dispatch.getAttempts(),
        dispatch.getProviderReference(),
        dispatch.getFailureCategory(),
        dispatch.getRequestedAt(),
        dispatch.getAcceptedAt(),
        dispatch.getFailedAt());
  }

  private record EligibleTarget(String destination) {
  }
}
