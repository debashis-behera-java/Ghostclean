package com.ghostclean.account;

import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.detection.ServiceEvidence;
import com.ghostclean.services.ServiceIntelligenceDtos.AutomationDto;
import com.ghostclean.services.ServiceIntelligenceDtos.JurisdictionDto;
import com.ghostclean.services.ServiceIntelligenceDtos.PrivacyRouteDto;
import com.ghostclean.services.ServiceRegistry;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Detected-account endpoints (Phase 4, exact paths per
 * {@code docs/API_SPECIFICATION.md} §5; Phase 5 adds intelligence
 * enrichment). Ownership derives from the principal; foreign ids read as
 * 404. Responses carry labels, counts, truncated sanitized subjects, and
 * registry route descriptions — never bodies, tokens, or provider refs.
 * Routes are described, never executed.
 */
@RestController
@RequestMapping("/api/v1/discovered")
public class AccountController {

  private final AccountService accounts;
  private final ServiceRegistry registry;

  public AccountController(AccountService accounts, ServiceRegistry registry) {
    this.accounts = accounts;
    this.registry = registry;
  }

  /** Own detected accounts, highest confidence first, optional status filter. */
  @GetMapping
  public ResponseEntity<List<AccountDto>> list(
      @RequestParam(value = "status", required = false) String status,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(
        accounts.list(userId, status).stream().map(this::toDto).toList());
  }

  /** One owned account with its evidence summary. */
  @GetMapping("/{id}")
  public ResponseEntity<AccountDto> get(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(toDto(accounts.ownedAccount(userId, id)));
  }

  /** keep → REVIEWED, dismiss → DISMISSED (+reason). */
  @PostMapping("/{id}/review")
  public ResponseEntity<AccountDto> review(
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, String> body,
      Authentication authentication) {
    UUID userId = currentUserId(authentication);
    String decision = body == null ? null : body.get("decision");
    String reason = body == null ? null : body.get("reason");
    return ResponseEntity.ok(
        toDto(accounts.review(userId, id, decision, reason)));
  }

  /** Sanitized evidence rows for one owned account, newest first. */
  @GetMapping("/{id}/evidence")
  public ResponseEntity<List<EvidenceDto>> evidence(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    return ResponseEntity.ok(
        accounts.evidenceOf(userId, id).stream().map(AccountController::toDto).toList());
  }

  /** Deletes an account's evidence (recomputes or removes the account). */
  @DeleteMapping("/{id}/evidence-purge")
  public ResponseEntity<Map<String, Object>> purgeEvidence(
      @PathVariable("id") UUID id, Authentication authentication) {
    UUID userId = currentUserId(authentication);
    boolean removed = accounts.purgeEvidence(userId, id);
    return ResponseEntity.ok(Map.of("accountRemoved", removed));
  }

  private static UUID currentUserId(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      return principal.userId();
    }
    throw new AuthException(HttpStatus.UNAUTHORIZED, ApiError.UNAUTHORIZED,
        "Authentication required");
  }

  AccountDto toDto(DiscoveredService account) {
    ServiceRegistry.Enrichment enrichment = registry.enrichmentFor(
        account.getServiceDirectory(), account.getServiceKey());
    return new AccountDto(
        account.getId(),
        account.getDisplayName(),
        account.getServiceKey(),
        account.getCategory(),
        account.getStatus(),
        account.getConfidenceBand(),
        account.getConfidence(),
        account.getEvidenceCount(),
        account.getFirstSeenAt(),
        account.getLastSeenAt(),
        enrichment.privacyRoute(),
        enrichment.automation(),
        enrichment.jurisdiction());
  }

  static EvidenceDto toDto(ServiceEvidence row) {
    return new EvidenceDto(
        row.getId(),
        row.getSource(),
        row.getSenderDomain(),
        row.getSubjectTruncated(),
        row.getReceivedAt(),
        row.getConfidenceContrib(),
        row.getMatchedRules() != null && row.getMatchedRules().get("rules") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList() : List.of(),
        row.getCreatedAt());
  }

  public record AccountDto(
      UUID id,
      String service,
      String domain,
      String category,
      String status,
      String confidence,
      int confidenceScore,
      int evidenceCount,
      Instant firstSeenAt,
      Instant lastSeenAt,
      PrivacyRouteDto privacyRoute,
      AutomationDto automation,
      JurisdictionDto jurisdiction) {
  }

  public record EvidenceDto(
      UUID id,
      String source,
      String sourceDomain,
      String subject,
      Instant observedAt,
      int weight,
      List<String> matchedRules,
      Instant createdAt) {
  }
}
