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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deletion-request endpoints (Phase 8). Thin engine over the Phase 6
 * lifecycle: preview renders without writing; create stores a template-
 * versioned DRAFT; review/approval stay on {@code /privacy-requests}
 * and confirmation/sending stay on the Phase 7 dispatch endpoint (see
 * the response contract). Ownership derives from the principal; foreign
 * ids read as 404. Previewing and generating never send anything.
 */
@RestController
@RequestMapping("/api/v1/deletion-requests")
public class DeletionRequestController {

  private final DeletionRequestService engine;

  public DeletionRequestController(DeletionRequestService engine) {
    this.engine = engine;
  }

  /** Side-effect-free preview of the would-be request. */
  @PostMapping("/preview")
  public ResponseEntity<DeletionPreviewDto> preview(
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    UUID accountId = parseAccountId(body);
    String type = body != null ? body.get("requestType") : null;
    String jurisdiction = body != null ? body.get("jurisdiction") : null;
    return ResponseEntity.ok(engine.preview(userId, accountId, type, jurisdiction));
  }

  /** Creates a template-versioned DRAFT (idempotent). */
  @PostMapping
  public ResponseEntity<DeletionRequestDto> create(
      @RequestBody(required = false) Map<String, String> body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      Authentication authentication,
      HttpServletRequest request) {
    UUID userId = currentUserId(authentication);
    UUID accountId = parseAccountId(body);
    String type = body != null ? body.get("requestType") : null;
    String jurisdiction = body != null ? body.get("jurisdiction") : null;
    return ResponseEntity.ok(
        engine.create(userId, accountId, type, jurisdiction, idempotencyKey));
  }

  /** One owned deletion request with live method/warnings; stored content untouched. */
  @GetMapping("/{id}")
  public ResponseEntity<DeletionRequestDto> get(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(engine.get(userId, id));
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

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }

  public record DeletionPreviewDto(
      UUID discoveredServiceId,
      String service,
      String domain,
      String category,
      String requestType,
      String jurisdiction,
      String deletionMethod,
      boolean manualReviewRequired,
      List<String> supportedTypes,
      String destination,
      String subject,
      String body,
      String templateId,
      int templateVersion,
      List<String> warnings,
      List<String> manualInstructions) {
  }

  public record DeletionRequestDto(
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
      String templateId,
      Integer templateVersion,
      String deletionMethod,
      boolean manualReviewRequired,
      List<String> supportedTypes,
      String destination,
      List<String> warnings,
      List<String> manualInstructions,
      Instant createdAt,
      Instant updatedAt) {
  }
}
