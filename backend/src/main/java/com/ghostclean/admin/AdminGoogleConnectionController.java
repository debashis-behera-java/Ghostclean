package com.ghostclean.admin;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.oauth.GoogleConnectionApprovalService;
import com.ghostclean.oauth.GoogleConnectionApprovalService.AdminApprovalDto;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator review of Google connection approval requests.
 *
 * <p>Every endpoint re-checks {@link AdminAccess}: only verified owners of
 * a configured admin address pass — anyone else receives 403. Approving or
 * rejecting never touches Google OAuth; it only records the business
 * decision that the existing OAuth flow consults.
 */
@RestController
@RequestMapping("/api/v1/admin/google/connection-requests")
public class AdminGoogleConnectionController {

  private final GoogleConnectionApprovalService approvals;

  public AdminGoogleConnectionController(GoogleConnectionApprovalService approvals) {
    this.approvals = approvals;
  }

  /** Admin queue, newest first, optional status filter. */
  @GetMapping
  public ResponseEntity<List<AdminApprovalDto>> list(
      @RequestParam(value = "status", required = false) String status,
      Authentication authentication) {
    return ResponseEntity.ok(approvals.list(currentUserId(authentication), status));
  }

  /** Approves one PENDING request. */
  @PostMapping("/{id}/approve")
  public ResponseEntity<AdminApprovalDto> approve(
      @PathVariable("id") UUID id, Authentication authentication) {
    return ResponseEntity.ok(approvals.approve(currentUserId(authentication), id));
  }

  /** Rejects one PENDING request with an optional reason. */
  @PostMapping("/{id}/reject")
  public ResponseEntity<AdminApprovalDto> reject(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    String reason = body == null ? null : body.get("reason");
    return ResponseEntity.ok(approvals.reject(currentUserId(authentication), id, reason));
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }
}
