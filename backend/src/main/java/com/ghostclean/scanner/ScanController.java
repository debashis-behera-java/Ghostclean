package com.ghostclean.scanner;

import com.ghostclean.common.AuthException;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scan endpoints (Phase 4, exact paths per {@code docs/API_SPECIFICATION.md}
 * §4). All routes require authentication; ownership derives from the
 * principal. Scans execute synchronously and bounded in-request, so POST
 * returns the finished record (200) rather than a queued stub.
 */
@RestController
@RequestMapping("/api/v1/scans")
public class ScanController {

  private final ScanService scans;

  public ScanController(ScanService scans) {
    this.scans = scans;
  }

  /** Starts a bounded scan of an owned Google connection. */
  @PostMapping
  public ResponseEntity<ScanDto> start(
      @RequestBody(required = false) Map<String, String> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    UUID connectionId = parseConnectionId(body);
    MailScan scan = scans.startScan(userId, connectionId, idempotencyKey, request.getRemoteAddr());
    return ResponseEntity.ok(toDto(scan));
  }

  /** Own scan history, newest first. */
  @GetMapping
  public ResponseEntity<List<ScanDto>> list(Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(
        scans.listScans(userId).stream().map(ScanController::toDto).toList());
  }

  /** One owned scan with progress. */
  @GetMapping("/{id}")
  public ResponseEntity<ScanDto> get(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(toDto(scans.ownedScan(userId, id)));
  }

  /** Cooperative cancellation; terminal scans answer 409. */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<ScanDto> cancel(
      @PathVariable("id") UUID id, Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(toDto(scans.cancelScan(userId, id, request.getRemoteAddr())));
  }

  /** Deletes a finished run record (evidence untouched). */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    scans.deleteScan(userId, id);
    return ResponseEntity.noContent().build();
  }

  private static UUID parseConnectionId(Map<String, String> body) {
    if (body == null || body.get("connectionId") == null) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "connectionId is required");
    }
    try {
      return UUID.fromString(body.get("connectionId"));
    } catch (IllegalArgumentException ex) {
      throw new AuthException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION_ERROR,
          "connectionId is required");
    }
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }

  static ScanDto toDto(MailScan scan) {
    return new ScanDto(
        scan.getId(),
        scan.getType(),
        scan.getStatus(),
        scan.getProgressTotal(),
        scan.getProgressDone(),
        scan.getMessagesScanned(),
        scan.getCandidatesDetected(),
        scan.getErrorsCount(),
        scan.getErrorCode(),
        scan.getStartedAt(),
        scan.getEndedAt());
  }

  public record ScanDto(
      UUID id,
      String type,
      String status,
      Integer progressTotal,
      int progressDone,
      int messagesScanned,
      int candidatesDetected,
      int errorsCount,
      String errorCode,
      Instant startedAt,
      Instant endedAt) {
  }
}
