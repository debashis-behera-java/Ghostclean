package com.ghostclean.oauth;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.oauth.GoogleConnectionApprovalService.ApprovalDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-scoped Google connection approval endpoints.
 *
 * <p>Business gate before Google OAuth may start: requesting approval never
 * authorizes any mailbox — only an APPROVED request lets the existing OAuth
 * flow begin, and Google's consent screen still applies in full.
 */
@RestController
@RequestMapping("/api/v1/google/connection")
public class GoogleConnectionRequestController {

  private final GoogleConnectionApprovalService approvals;

  public GoogleConnectionRequestController(GoogleConnectionApprovalService approvals) {
    this.approvals = approvals;
  }

  /** Idempotent request creation (or the current state when one exists). */
  @PostMapping("/request")
  public ResponseEntity<ApprovalDto> request(Authentication authentication) {
    return ResponseEntity.ok(approvals.request(currentUserId(authentication)));
  }

  /** Owner's current approval state. */
  @GetMapping("/status")
  public ResponseEntity<ApprovalDto> status(Authentication authentication) {
    return ResponseEntity.ok(approvals.status(currentUserId(authentication)));
  }

  /** Owner cancels their own PENDING request. */
  @PostMapping("/request/cancel")
  public ResponseEntity<ApprovalDto> cancel(Authentication authentication) {
    return ResponseEntity.ok(approvals.cancel(currentUserId(authentication)));
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }
}
