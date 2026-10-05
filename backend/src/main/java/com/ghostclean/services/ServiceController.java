package com.ghostclean.services;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceDetailDto;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceSummaryDto;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only service-intelligence endpoints (Phase 5). The trusted registry
 * is server-controlled: there are deliberately no mutation endpoints, so
 * ordinary users cannot submit URLs that become trusted routes. Every
 * route requires authentication; unknown or inactive slugs read as 404.
 * Responses carry registry metadata only — never credentials, never
 * mailbox content — and describe routes without executing anything.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

  private final ServiceRegistry registry;

  public ServiceController(ServiceRegistry registry) {
    this.registry = registry;
  }

  /** Active catalog summaries, highest trust first is a client concern. */
  @GetMapping
  public ResponseEntity<List<ServiceSummaryDto>> list(Authentication authentication) {
    currentUserId(authentication);
    return ResponseEntity.ok(
        registry.listActive().stream().map(registry::summarize).toList());
  }

  /** One active service by stable slug; unknown/inactive reads as 404. */
  @GetMapping("/{slug}")
  public ResponseEntity<ServiceDetailDto> get(
      @PathVariable("slug") String slug, Authentication authentication) {
    currentUserId(authentication);
    ServiceDirectory entry = registry.findActiveBySlug(slug)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Service not found"));
    return ResponseEntity.ok(registry.describe(entry));
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }
}
