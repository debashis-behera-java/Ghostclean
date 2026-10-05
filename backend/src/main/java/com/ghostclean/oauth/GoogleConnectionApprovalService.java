package com.ghostclean.oauth;

import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.auth.VerificationEmailSender;
import com.ghostclean.auth.VerificationMailException;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.RlsContext;
import com.ghostclean.user.AdminAccess;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GhostClean-level approval gate before Google OAuth may start.
 *
 * <p>Business control only: it never replaces Google's consent screen, never
 * touches provider tokens, and never authorizes any mailbox by itself. The
 * OAuth authorize path re-checks eligibility at call time; approval rows
 * are descriptive records, not capabilities.
 *
 * <p>One PENDING row per user at most (service check plus partial unique
 * index). Rejected users may retry only after {@link #RETRY_COOLDOWN}.
 * Admin notification email is best-effort: its failure is logged by
 * category and never fails the user-facing request.
 */
@Service
public class GoogleConnectionApprovalService {

  private static final Logger log = LoggerFactory.getLogger(GoogleConnectionApprovalService.class);

  static final Duration RETRY_COOLDOWN = Duration.ofHours(24);

  private final GoogleConnectionRequestRepository requests;
  private final OAuthConnectionRepository connections;
  private final UserRepository users;
  private final AdminAccess admins;
  private final VerificationEmailSender mailer;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;

  public GoogleConnectionApprovalService(
      GoogleConnectionRequestRepository requests,
      OAuthConnectionRepository connections,
      UserRepository users,
      AdminAccess admins,
      VerificationEmailSender mailer,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls) {
    this.requests = requests;
    this.connections = connections;
    this.users = users;
    this.admins = admins;
    this.mailer = mailer;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
  }

  /** Owner's current approval state (plus CONNECTED when already connected). */
  @Transactional(readOnly = true)
  public ApprovalDto status(UUID userId) {
    rls.apply();
    User user = verifiedUser(userId);
    if (hasActiveGoogleConnection(userId)) {
      return new ApprovalDto(null, userId, null, "CONNECTED", null, null, null, null);
    }
    return requests.findByUser_IdOrderByRequestedAtDesc(userId).stream()
        .findFirst()
        .map(this::toDto)
        .orElseGet(() -> new ApprovalDto(null, userId, null, "NONE", null, null, null, null));
  }

  /**
   * Idempotent request creation: CONNECTED stays connected, PENDING returns
   * the existing row, APPROVED stays approved, recent REJECTED is held to
   * the cooldown, otherwise a new PENDING row is created and the admin is
   * notified (best-effort).
   */
  @Transactional
  public ApprovalDto request(UUID userId) {
    rls.apply();
    rateLimiter.check("google-approval:request:user:" + userId, 10, Duration.ofHours(1));
    User user = verifiedUser(userId);
    if (hasActiveGoogleConnection(userId)) {
      return new ApprovalDto(null, userId, null, "CONNECTED", null, null, null, null);
    }
    var pending = requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
        userId, GoogleConnectionRequest.STATUS_PENDING);
    if (pending.isPresent()) {
      return toDto(pending.get());
    }
    var approved = requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
        userId, GoogleConnectionRequest.STATUS_APPROVED);
    if (approved.isPresent()) {
      return toDto(approved.get());
    }
    var rejected = requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
        userId, GoogleConnectionRequest.STATUS_REJECTED);
    if (rejected.isPresent() && rejected.get().getReviewedAt() != null
        && Instant.now().isBefore(rejected.get().getReviewedAt().plus(RETRY_COOLDOWN))) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "A recent request was rejected; please wait before requesting again");
    }
    GoogleConnectionRequest row = new GoogleConnectionRequest(user);
    try {
      requests.saveAndFlush(row);
    } catch (DataIntegrityViolationException ex) {
      // Lost a duplicate-PENDING race: the recorded row wins.
      return toDto(requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
          userId, GoogleConnectionRequest.STATUS_PENDING).orElseThrow());
    }
    audit.emit(AuditEvents.GOOGLE_CONNECTION_REQUESTED, userId, "user",
        "google_connection_request", row.getId().toString(),
        Map.of("status", row.getStatus()));
    notifyAdmin(user, row);
    return toDto(row);
  }

  /** Owner cancels their own PENDING request. */
  @Transactional
  public ApprovalDto cancel(UUID userId) {
    rls.apply();
    verifiedUser(userId);
    GoogleConnectionRequest row = requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
        userId, GoogleConnectionRequest.STATUS_PENDING)
        .orElseThrow(() -> new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
            "No pending request to cancel"));
    row.setStatus(GoogleConnectionRequest.STATUS_CANCELLED);
    requests.save(row);
    audit.emit(AuditEvents.GOOGLE_CONNECTION_CANCELLED, userId, "user",
        "google_connection_request", row.getId().toString(),
        Map.of("status", row.getStatus()));
    return toDto(row);
  }

  /** Admin queue, newest first, optional status filter. */
  @Transactional(readOnly = true)
  public List<AdminApprovalDto> list(UUID adminId, String status) {
    rls.apply();
    admins.requireAdmin(verifiedUser(adminId));
    List<GoogleConnectionRequest> rows =
        status == null || status.isBlank()
            ? requests.findAllByOrderByRequestedAtDesc()
            : requests.findByStatusOrderByRequestedAtDesc(status.trim().toUpperCase());
    return rows.stream()
        .map(row -> new AdminApprovalDto(
            row.getId(),
            row.getUser().getId(),
            row.getUser().getEmail(),
            row.getStatus(),
            row.getRequestedAt(),
            row.getReviewedAt(),
            row.getReviewedBy() == null ? null : row.getReviewedBy().getId(),
            row.getRejectionReason()))
        .toList();
  }

  /** Admin approval of one PENDING request. */
  @Transactional
  public AdminApprovalDto approve(UUID adminId, UUID requestId) {
    rls.apply();
    User admin = verifiedUser(adminId);
    admins.requireAdmin(admin);
    GoogleConnectionRequest row = requests.findById(requestId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Approval request not found"));
    requirePending(row);
    row.setStatus(GoogleConnectionRequest.STATUS_APPROVED);
    row.setReviewedAt(Instant.now());
    row.setReviewedBy(admin);
    row.setRejectionReason(null);
    requests.save(row);
    audit.emit(AuditEvents.GOOGLE_CONNECTION_APPROVED, adminId, "user",
        "google_connection_request", row.getId().toString(),
        Map.of("status", row.getStatus()));
    return toAdminDto(row);
  }

  /** Admin rejection of one PENDING request with an optional reason. */
  @Transactional
  public AdminApprovalDto reject(UUID adminId, UUID requestId, String reason) {
    rls.apply();
    User admin = verifiedUser(adminId);
    admins.requireAdmin(admin);
    GoogleConnectionRequest row = requests.findById(requestId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Approval request not found"));
    requirePending(row);
    String trimmed = reason == null ? null : reason.trim();
    if (trimmed != null && trimmed.length() > 300) {
      trimmed = trimmed.substring(0, 300);
    }
    row.setStatus(GoogleConnectionRequest.STATUS_REJECTED);
    row.setReviewedAt(Instant.now());
    row.setReviewedBy(admin);
    row.setRejectionReason(trimmed == null || trimmed.isEmpty() ? null : trimmed);
    requests.save(row);
    audit.emit(AuditEvents.GOOGLE_CONNECTION_REJECTED, adminId, "user",
        "google_connection_request", row.getId().toString(),
        Map.of("status", row.getStatus()));
    return toAdminDto(row);
  }

  /**
   * OAuth authorize gate for Google: an ACTIVE connection may reconnect;
   * otherwise only an APPROVED request may proceed. Anything else is a
   * 409 — never an approval-oracle beyond what the status endpoint says.
   */
  @Transactional(readOnly = true)
  public void requireGoogleAuthorizeAllowed(UUID userId) {
    rls.apply();
    verifiedUser(userId);
    if (hasActiveGoogleConnection(userId)) {
      return;
    }
    boolean approved = requests.findFirstByUser_IdAndStatusOrderByRequestedAtDesc(
        userId, GoogleConnectionRequest.STATUS_APPROVED).isPresent();
    if (!approved) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Google connection requires administrator approval");
    }
  }

  // -- internals ------------------------------------------------------------------

  private boolean hasActiveGoogleConnection(UUID userId) {
    return connections.findByUser_Id(userId).stream()
        .anyMatch(row -> "google".equalsIgnoreCase(row.getProvider())
            && OAuthConnection.STATUS_ACTIVE.equals(row.getStatus()));
  }

  private void requirePending(GoogleConnectionRequest row) {
    if (!GoogleConnectionRequest.STATUS_PENDING.equals(row.getStatus())) {
      throw new AuthException(HttpStatus.CONFLICT, ApiError.REQUEST_CONFLICT,
          "Only pending requests can be reviewed");
    }
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

  private ApprovalDto toDto(GoogleConnectionRequest row) {
    return new ApprovalDto(
        row.getId(),
        row.getUser().getId(),
        null,
        row.getStatus(),
        row.getRequestedAt(),
        row.getReviewedAt(),
        row.getRejectionReason(),
        retryAvailableAt(row));
  }

  private AdminApprovalDto toAdminDto(GoogleConnectionRequest row) {
    return new AdminApprovalDto(
        row.getId(),
        row.getUser().getId(),
        row.getUser().getEmail(),
        row.getStatus(),
        row.getRequestedAt(),
        row.getReviewedAt(),
        row.getReviewedBy() == null ? null : row.getReviewedBy().getId(),
        row.getRejectionReason());
  }

  private static Instant retryAvailableAt(GoogleConnectionRequest row) {
    if (GoogleConnectionRequest.STATUS_REJECTED.equals(row.getStatus())
        && row.getReviewedAt() != null) {
      return row.getReviewedAt().plus(RETRY_COOLDOWN);
    }
    return null;
  }

  private void notifyAdmin(User user, GoogleConnectionRequest row) {
    var recipient = admins.notificationRecipient();
    if (recipient.isEmpty()) {
      log.warn("Google connection approval requested with no admin recipient configured");
      return;
    }
    String text = "A new Google connection request needs review.\n\n"
        + "Requester: " + user.getEmail() + "\n"
        + "Request: " + row.getId() + "\n"
        + "Requested: " + row.getRequestedAt() + "\n"
        + "Status: " + row.getStatus() + "\n\n"
        + "Review it in the GhostClean admin dashboard (/admin/google-requests).";
    try {
      mailer.sendAdminNotification(
          recipient.get(), "GhostClean: New Google connection request", text);
    } catch (VerificationMailException ex) {
      log.warn("Admin notification failed reason={}", ex.getCategory());
    } catch (RuntimeException ex) {
      log.warn("Admin notification failed reason=error");
    }
  }

  public record ApprovalDto(
      UUID requestId,
      UUID userId,
      String userEmail,
      String status,
      Instant requestedAt,
      Instant reviewedAt,
      String rejectionReason,
      Instant retryAvailableAt) {
  }

  public record AdminApprovalDto(
      UUID requestId,
      UUID userId,
      String userEmail,
      String status,
      Instant requestedAt,
      Instant reviewedAt,
      UUID reviewedBy,
      String rejectionReason) {
  }
}
