package com.ghostclean.privacy;

import com.ghostclean.account.DiscoveredService;
import com.ghostclean.account.DiscoveredServiceRepository;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceRegistry;
import com.ghostclean.services.RouteVerificationStatus;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Privacy-request lifecycle (Phase 6, two-gate foundation).
 *
 * <p>Gate 1 (detection review) lives in the account package; this service
 * owns gate 2: DRAFT → READY_FOR_REVIEW (via PATCH) → APPROVED (explicit,
 * exact-version). Editing an APPROVED request returns it to
 * READY_FOR_REVIEW with cleared approval — approval never silently covers
 * new content. Expiry is lazy (applied on access); nothing here sends,
 * submits, or deletes anything externally.
 */
@Service
public class PrivacyRequestService {

  private static final Pattern UNSAFE_CONTENT =
      Pattern.compile("(?i)<script|javascript:|on\\w+\\s*=");

  private final PrivacyRequestRepository requests;
  private final DiscoveredServiceRepository accounts;
  private final UserRepository users;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;
  private final int ttlDays;

  public PrivacyRequestService(
      PrivacyRequestRepository requests,
      DiscoveredServiceRepository accounts,
      UserRepository users,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls,
      @Value("${privacy.request-ttl-days:7}") int ttlDays) {
    this.requests = requests;
    this.accounts = accounts;
    this.users = users;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
    this.ttlDays = Math.max(1, ttlDays);
  }

  /** Creates a DRAFT (or returns the existing duplicate). Never APPROVED. */
  @Transactional
  public PrivacyRequestController.PrivacyRequestDto create(
      UUID userId, UUID accountId, String typeRaw, String jurisdictionRaw,
      String idempotencyKey, String ip) {
    rls.apply();
    rateLimiter.check("privacy:create:user:" + userId, 10, Duration.ofHours(1));
    User user = verifiedUser(userId);
    DiscoveredService account = ownedAccount(userId, accountId);
    PrivacyRequestType requested = parseType(typeRaw);
    PrivacyJurisdiction jurisdiction = parseJurisdiction(jurisdictionRaw);

    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var existing = requests.findByUser_IdAndIdempotencyKey(userId, idempotencyKey.trim());
      if (existing.isPresent()) {
        return describe(existing.get());
      }
    }
    ServiceDirectory entry = account.getServiceDirectory();
    boolean trusted = ServiceRegistry.isTrusted(entry);
    PrivacyRequestType resolved = PrivacyRequestGenerator.resolveType(requested, trusted);
    var duplicate = requests.findActiveForAccount(userId, account.getId(), resolved.name());
    if (duplicate.isPresent()) {
      return describe(duplicate.get());
    }
    PrivacyRequestGenerator.GeneratedContent content = PrivacyRequestGenerator.generate(
        account.getDisplayName(), account.getCategory(), account.getEvidenceCount(),
        resolved, jurisdiction);
    return createWithContent(user, account, resolved, jurisdiction,
        content.subject(), content.body(), null, null, null, null, idempotencyKey);
  }

  /**
   * Creation core with caller-rendered content (Phase 8 templates reuse
   * this so lifecycle, coercion outcomes, and auditing stay identical).
   * Package-visible for the deletion-request service.
   */
  @Transactional
  PrivacyRequestController.PrivacyRequestDto createWithContent(
      User user, DiscoveredService account, PrivacyRequestType resolved,
      PrivacyJurisdiction jurisdiction, String subject, String body,
      String templateId, Integer templateVersion, String deletionMethod,
      Boolean manualReviewRequired, String idempotencyKey) {
    UUID userId = user.getId();
    ServiceDirectory entry = account.getServiceDirectory();
    PrivacyRequest row = new PrivacyRequest(user, account.getId());
    if (entry != null) {
      row.setServiceDirectoryId(entry.getId());
    }
    row.setRequestType(resolved.name());
    row.setStatus(PrivacyRequest.STATUS_DRAFT);
    row.setSubject(validateContent("subject", subject, PrivacyRequestGenerator.MAX_SUBJECT));
    row.setBody(validateContent("body", body, PrivacyRequestGenerator.MAX_BODY));
    row.setJurisdiction(jurisdiction.name());
    row.setVersion(1);
    row.setTemplateId(templateId);
    row.setTemplateVersion(templateVersion);
    row.setDeletionMethod(deletionMethod);
    row.setManualReviewRequired(manualReviewRequired);
    row.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)));
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      row.setIdempotencyKey(idempotencyKey.trim());
    }
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_CREATED, userId, "user",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
    return describe(row);
  }

  /** Owner-scoped list (applies lazy expiry). */
  @Transactional
  public List<PrivacyRequestController.PrivacyRequestDto> list(UUID userId, String status) {
    rls.apply();
    verifiedUser(userId);
    List<PrivacyRequest> rows;
    if (status == null || status.isBlank()) {
      rows = requests.findByUser_IdOrderByCreatedAtDesc(userId);
    } else {
      String normalized = status.trim().toUpperCase();
      if (!isKnownStatus(normalized)) {
        throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
            "Unknown status filter");
      }
      rows = requests.findByUser_IdAndStatusOrderByCreatedAtDesc(userId, normalized);
    }
    rows.forEach(this::expireIfDue);
    return rows.stream().map(this::describe).toList();
  }

  /** One owned request (applies lazy expiry). */
  @Transactional
  public PrivacyRequestController.PrivacyRequestDto get(UUID userId, UUID id) {
    rls.apply();
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, id);
    expireIfDue(row);
    return describe(row);
  }

  /**
   * Edits content and marks the draft reviewed: any PATCH moves DRAFT →
   * READY_FOR_REVIEW (even an empty one — review without changes).
   * PATCH on APPROVED returns it to READY_FOR_REVIEW with cleared approval
   * and a bumped version, forcing re-approval of the exact new content.
   */
  @Transactional
  public PrivacyRequestController.PrivacyRequestDto patch(
      UUID userId, UUID id, String subject, String body) {
    rls.apply();
    rateLimiter.check("privacy:patch:user:" + userId, 30, Duration.ofMinutes(1));
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, id);
    expireIfDue(row);
    if (row.isTerminal()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Finished requests cannot be edited");
    }
    boolean wasApproved = PrivacyRequest.STATUS_APPROVED.equals(row.getStatus());
    if (subject != null) {
      row.setSubject(validateContent("subject", subject,
          PrivacyRequestGenerator.MAX_SUBJECT));
    }
    if (body != null) {
      row.setBody(validateContent("body", body, PrivacyRequestGenerator.MAX_BODY));
    }
    row.setVersion(row.getVersion() + 1);
    row.setStatus(PrivacyRequest.STATUS_READY_FOR_REVIEW);
    if (wasApproved) {
      row.setApprovedVersion(null);
      row.setApprovedAt(null);
    }
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_EDITED, userId, "user",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
    return describe(row);
  }

  /**
   * Explicit approval: READY_FOR_REVIEW → APPROVED for the exact current
   * version. Records approved_at. Sends nothing.
   */
  @Transactional
  public PrivacyRequestController.PrivacyRequestDto approve(
      UUID userId, UUID id, Integer expectedVersion) {
    rls.apply();
    rateLimiter.check("privacy:approve:user:" + userId, 30, Duration.ofMinutes(1));
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, id);
    expireIfDue(row);
    if (PrivacyRequest.STATUS_EXPIRED.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.GONE, ApiError.REQUEST_EXPIRED,
          "Request has expired and cannot be approved");
    }
    if (!PrivacyRequest.STATUS_READY_FOR_REVIEW.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Only requests ready for review can be approved");
    }
    if (expectedVersion != null && expectedVersion != row.getVersion()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Request version changed; please review the current version");
    }
    DiscoveredService account = ownedAccount(userId, row.getDiscoveredServiceId());
    ServiceDirectory entry = account.getServiceDirectory();
    if (entry != null && (!"active".equalsIgnoreCase(entry.getStatus())
        || RouteVerificationStatus.parse(entry.getVerificationStatus())
            == RouteVerificationStatus.REJECTED)) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Service route is no longer valid");
    }
    if (PrivacyRequestType.parseLenient(row.getRequestType())
            == PrivacyRequestType.DELETE_ACCOUNT
        && !ServiceRegistry.isTrusted(entry)) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Service route is no longer valid for account deletion");
    }
    if (row.getSubject().isBlank() || row.getBody().isBlank()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Request content is empty");
    }
    row.setStatus(PrivacyRequest.STATUS_APPROVED);
    row.setApprovedVersion(row.getVersion());
    row.setApprovedAt(Instant.now());
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_APPROVED, userId, "user",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
    return describe(row);
  }

  /** Cancellation is idempotent; audit history is never deleted. */
  @Transactional
  public PrivacyRequestController.PrivacyRequestDto cancel(UUID userId, UUID id) {
    rls.apply();
    rateLimiter.check("privacy:cancel:user:" + userId, 30, Duration.ofMinutes(1));
    verifiedUser(userId);
    PrivacyRequest row = ownedRequest(userId, id);
    expireIfDue(row);
    if (PrivacyRequest.STATUS_CANCELLED.equals(row.getStatus())) {
      return describe(row);
    }
    if (row.isTerminal()) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Finished requests cannot be cancelled");
    }
    row.setStatus(PrivacyRequest.STATUS_CANCELLED);
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_CANCELLED, userId, "user",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
    return describe(row);
  }

  // -- internals ------------------------------------------------------------------

  private PrivacyRequest ownedRequest(UUID userId, UUID id) {
    return requests.findByIdAndUser_Id(id, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Privacy request not found"));
  }

  static DiscoveredService ownedAccount(
      DiscoveredServiceRepository accounts, UUID userId, UUID accountId) {
    return accounts.findByIdAndUser_Id(accountId, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Detected account not found"));
  }

  private DiscoveredService ownedAccount(UUID userId, UUID accountId) {
    return ownedAccount(accounts, userId, accountId);
  }

  static User verifiedUser(UserRepository users, UUID userId) {
    User user = users.findById(userId).orElseThrow(
        () -> new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
            "Authentication required"));
    if (!user.isEmailVerified()) {
      throw new AuthException(HttpStatus.FORBIDDEN, ApiError.EMAIL_NOT_VERIFIED,
          "Email verification required");
    }
    return user;
  }

  private User verifiedUser(UUID userId) {
    return verifiedUser(users, userId);
  }

  static PrivacyRequestType parseType(String raw) {
    return PrivacyRequestType.parseStrict(raw).orElseThrow(
        () -> new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
            "Unknown request type"));
  }

  static PrivacyJurisdiction parseJurisdiction(String raw) {
    return PrivacyJurisdiction.parseStrict(raw).orElseThrow(
        () -> new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
            "Unknown jurisdiction"));
  }

  private static boolean isKnownStatus(String status) {
    return PrivacyRequest.STATUS_DRAFT.equals(status)
        || PrivacyRequest.STATUS_READY_FOR_REVIEW.equals(status)
        || PrivacyRequest.STATUS_APPROVED.equals(status)
        || PrivacyRequest.STATUS_CANCELLED.equals(status)
        || PrivacyRequest.STATUS_EXPIRED.equals(status);
  }

  /** Lazy expiry: due active rows become EXPIRED exactly once, with audit. */
  private void expireIfDue(PrivacyRequest row) {
    applyExpiry(requests, audit, row);
  }

  /**
   * Shared expiry transition (also used by dispatch eligibility so stale
   * approvals can never send). Package-visible for the dispatch service.
   */
  static void applyExpiry(
      PrivacyRequestRepository requests, AuditService audit, PrivacyRequest row) {
    if (!row.isActive() || !Instant.now().isAfter(row.getExpiresAt())) {
      return;
    }
    row.setStatus(PrivacyRequest.STATUS_EXPIRED);
    requests.save(row);
    audit.emit(AuditEvents.PRIVACY_REQUEST_EXPIRED, row.getUser().getId(), "system",
        "privacy_request", row.getId().toString(),
        Map.of("status", row.getStatus(), "version", row.getVersion()));
  }

  /** Plain-text user content: bounded, no markup/script affordances. */
  static String validateContent(String field, String value, int max) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty() || trimmed.length() > max) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Invalid " + field);
    }
    if (UNSAFE_CONTENT.matcher(trimmed).find()) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Invalid " + field);
    }
    return trimmed;
  }

  private PrivacyRequestController.PrivacyRequestDto describe(PrivacyRequest row) {
    DiscoveredService account = accounts.findByIdAndUser_Id(
        row.getDiscoveredServiceId(), row.getUser().getId()).orElse(null);
    return new PrivacyRequestController.PrivacyRequestDto(
        row.getId(),
        row.getDiscoveredServiceId(),
        account != null ? account.getDisplayName() : "",
        account != null ? account.getServiceKey() : "",
        account != null ? account.getCategory() : "",
        row.getRequestType(),
        row.getStatus(),
        row.getSubject(),
        row.getBody(),
        row.getJurisdiction(),
        row.getVersion(),
        row.getApprovedVersion(),
        row.getApprovedAt(),
        row.getExpiresAt(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }
}
