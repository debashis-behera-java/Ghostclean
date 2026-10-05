package com.ghostclean.privacy;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privacy-request endpoints (Phase 6) plus explicit dispatch confirmation
 * (Phase 7, gate 2). Ownership derives from the principal; foreign ids
 * read as 404. Nothing here sends on approval — dispatch is a separate
 * explicit action and the only path that touches the outbound provider.
 */
@RestController
@RequestMapping("/api/v1/privacy-requests")
public class PrivacyRequestController {

  private final PrivacyRequestService requests;
  private final DispatchService dispatches;
  private final TrackingService trackingService;

  public PrivacyRequestController(PrivacyRequestService requests, DispatchService dispatches,
      TrackingService trackingService) {
    this.requests = requests;
    this.dispatches = dispatches;
    this.trackingService = trackingService;
  }

  /** Creates a DRAFT (idempotent via header or per-account dedup). */
  @PostMapping
  public ResponseEntity<PrivacyRequestDto> create(
      @RequestBody(required = false) Map<String, String> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    UUID accountId = parseAccountId(body);
    String type = body != null ? body.get("requestType") : null;
    String jurisdiction = body != null ? body.get("jurisdiction") : null;
    return ResponseEntity.ok(requests.create(
        userId, accountId, type, jurisdiction, idempotencyKey, request.getRemoteAddr()));
  }

  /** Own requests, newest first, optional status filter. */
  @GetMapping
  public ResponseEntity<List<PrivacyRequestDto>> list(
      @RequestParam(value = "status", required = false) String status,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(requests.list(userId, status));
  }

  /** One owned request. */
  @GetMapping("/{id}")
  public ResponseEntity<PrivacyRequestDto> get(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(requests.get(userId, id));
  }

  /**
   * Edits permitted text; any PATCH marks the draft reviewed
   * (DRAFT → READY_FOR_REVIEW, APPROVED → READY_FOR_REVIEW + re-approval).
   */
  @PatchMapping("/{id}")
  public ResponseEntity<PrivacyRequestDto> patch(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    String subject = body == null ? null : body.get("subject");
    String text = body == null ? null : body.get("body");
    return ResponseEntity.ok(requests.patch(userId, id, subject, text));
  }

  /** Explicit approval of the exact current version (optional version check). */
  @PostMapping("/{id}/approve")
  public ResponseEntity<PrivacyRequestDto> approve(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    Integer expectedVersion = parseVersion(body);
    return ResponseEntity.ok(requests.approve(userId, id, expectedVersion));
  }

  /** Idempotent cancellation; audit history is preserved. */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<PrivacyRequestDto> cancel(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(requests.cancel(userId, id));
  }

  /**
   * Computed tracking view (Phase 9): deadline estimate from the V1
   * {@code accepted_at} anchor, reminder state, and latest recorded
   * outcome. Pure calculation — evaluation writes nothing.
   */
  @GetMapping("/{id}/tracking")
  public ResponseEntity<TrackingDto> tracking(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(trackingService.tracking(userId, id, Instant.now()));
  }

  /** Owner-scoped manual response history, newest first. */
  @GetMapping("/{id}/responses")
  public ResponseEntity<List<ResponseDto>> responses(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(trackingService.history(userId, id));
  }

  /**
   * Manual service-outcome intake (Phase 9): the user records what the
   * external service did. Append-only; the latest row drives tracking
   * state. Never verified external evidence.
   */
  @PostMapping("/{id}/responses")
  public ResponseEntity<ResponseDto> recordResponse(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    String outcome = body == null ? null : body.get("outcome");
    String summary = body == null ? null : body.get("summary");
    return ResponseEntity.ok(trackingService.recordResponse(userId, id, outcome, summary));
  }

  /**
   * Gate 2: explicit user dispatch confirmation (Phase 7). Only approved
   * requests with a verified email route may send; approval alone never
   * sends. Unknown body fields (e.g. any client-supplied recipient) are
   * ignored — the destination always resolves server-side from the
   * trusted registry.
   */
  @PostMapping("/{id}/dispatch")
  public ResponseEntity<DispatchDto> dispatch(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    String key = idempotencyKey;
    if ((key == null || key.isBlank()) && body != null) {
      key = body.get("idempotencyKey");
    }
    Integer expectedVersion = parseVersion(body);
    return ResponseEntity.ok(dispatches.dispatch(userId, id, key, expectedVersion));
  }

  /** Current dispatch state for one owned request. */
  @GetMapping("/{id}/dispatch")
  public ResponseEntity<DispatchDto> dispatchStatus(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(dispatches.status(userId, id));
  }

  /** Cancels a non-accepted dispatch; accepted sends cannot be unsent. */
  @PostMapping("/{id}/dispatch/cancel")
  public ResponseEntity<DispatchDto> cancelDispatch(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(dispatches.cancel(userId, id));
  }

  private static UUID parseAccountId(Map<String, String> body) {
    if (body == null || body.get("discoveredServiceId") == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "discoveredServiceId is required");
    }
    try {
      return UUID.fromString(body.get("discoveredServiceId"));
    } catch (IllegalArgumentException ex) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "discoveredServiceId is required");
    }
  }

  private static Integer parseVersion(Map<String, String> body) {
    if (body == null || body.get("version") == null || body.get("version").isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(body.get("version").trim());
    } catch (NumberFormatException ex) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "Invalid version");
    }
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }

  public record PrivacyRequestDto(
      UUID id,
      UUID discoveredServiceId,
      String service,
      String domain,
      String category,
      String requestType,
      String status,
      String subject,
      String body,
      String jurisdiction,
      int version,
      Integer approvedVersion,
      Instant approvedAt,
      Instant expiresAt,
      Instant createdAt,
      Instant updatedAt) {
  }

  /**
   * Safe dispatch view: ids, lifecycle, and failure category only. No
   * provider secrets, no message content, no raw provider responses.
   */
  public record DispatchDto(
      UUID dispatchId,
      UUID requestId,
      String status,
      String provider,
      String destination,
      int requestVersion,
      int attempts,
      String providerReference,
      String failureCategory,
      Instant requestedAt,
      Instant acceptedAt,
      Instant failedAt) {
  }

  /**
   * Computed tracking view (Phase 9). Deadlines are estimates anchored at
   * {@code accepted_at} — the handoff time, not proof of external
   * delivery, receipt, or deletion. No new lifecycle states.
   */
  public record TrackingDto(
      UUID requestId,
      String requestStatus,
      String trackingState,
      boolean manualRoute,
      Instant acceptedAt,
      Instant deadlineAt,
      String deadlineNote,
      String deadlineSource,
      List<String> remindersDue,
      Instant nextReminderDue,
      String reminderState,
      String latestOutcome,
      String latestSummary,
      Instant latestRecordedAt,
      int responseCount) {
  }

  /** One user-recorded outcome (manual intake, never verified evidence). */
  public record ResponseDto(
      UUID id,
      UUID requestId,
      String outcome,
      String summary,
      Instant recordedAt) {
  }
}
