package com.ghostclean.privacy;

import com.ghostclean.account.DiscoveredService;
import com.ghostclean.account.DiscoveredServiceRepository;
import com.ghostclean.audit.AuditEvents;
import com.ghostclean.audit.AuditService;
import com.ghostclean.auth.AuthRateLimiter;
import com.ghostclean.common.RlsContext;
import com.ghostclean.common.ApiError;
import com.ghostclean.common.AuthException;
import com.ghostclean.privacy.RequestTemplates.RenderedTemplate;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceDetailDto;
import com.ghostclean.services.ServiceRegistry;
import com.ghostclean.user.User;
import com.ghostclean.user.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletion-request engine (Phase 8). Resolves the Phase 5 service record
 * to a deletion method, renders the versioned template, and funnels
 * creation through the Phase 6 lifecycle (no duplicate request entity,
 * no second approval system). Preview never writes; confirmation and
 * sending are the Phase 6 approval and Phase 7 dispatch endpoints,
 * documented on the DTOs. Non-email methods route to manual handling
 * with verified-metadata-only instructions — nothing is fabricated.
 */
@Service
public class DeletionRequestService {

  private final PrivacyRequestService requests;
  private final PrivacyRequestRepository store;
  private final DiscoveredServiceRepository accounts;
  private final UserRepository users;
  private final ServiceRegistry registry;
  private final AuditService audit;
  private final AuthRateLimiter rateLimiter;
  private final RlsContext rls;

  public DeletionRequestService(
      PrivacyRequestService requests,
      PrivacyRequestRepository store,
      DiscoveredServiceRepository accounts,
      UserRepository users,
      ServiceRegistry registry,
      AuditService audit,
      AuthRateLimiter rateLimiter,
      RlsContext rls) {
    this.requests = requests;
    this.store = store;
    this.accounts = accounts;
    this.users = users;
    this.registry = registry;
    this.audit = audit;
    this.rateLimiter = rateLimiter;
    this.rls = rls;
  }

  /** Side-effect-free preview: resolves, renders, warns. Emits a preview audit. */
  @Transactional
  public DeletionRequestController.DeletionPreviewDto preview(
      UUID userId, UUID accountId, String typeRaw, String jurisdictionRaw) {
    rls.apply();
    rateLimiter.check("deletion:preview:user:" + userId, 30, Duration.ofMinutes(1));
    PrivacyRequestService.verifiedUser(users, userId);
    DiscoveredService account =
        PrivacyRequestService.ownedAccount(accounts, userId, accountId);
    PrivacyRequestType requested = PrivacyRequestService.parseType(typeRaw);
    PrivacyJurisdiction jurisdiction = PrivacyRequestService.parseJurisdiction(jurisdictionRaw);

    ServiceDirectory entry = account.getServiceDirectory();
    boolean trusted = ServiceRegistry.isTrusted(entry);
    PrivacyRequestType resolved = PrivacyRequestGenerator.resolveType(requested, trusted);
    DeletionMethod method = DeletionMethodResolver.resolve(entry);
    RenderedTemplate rendered = RequestTemplates.render(
        resolved, jurisdiction, account.getDisplayName(), account.getEvidenceCount());
    audit.emit(AuditEvents.DELETION_REQUEST_PREVIEWED, userId, "user",
        "discovered_service", accountId.toString(),
        Map.of("method", method.name(), "type", resolved.name()));
    return toPreview(account, entry, resolved, jurisdiction, method, rendered);
  }

  /**
   * Creates a Phase 6 request with template provenance (or returns the
   * existing duplicate). Lifecycle, approval, and sending stay on the
   * Phase 6/7 endpoints referenced in the response contract.
   */
  @Transactional
  public DeletionRequestController.DeletionRequestDto create(
      UUID userId, UUID accountId, String typeRaw, String jurisdictionRaw,
      String idempotencyKey) {
    rls.apply();
    rateLimiter.check("privacy:create:user:" + userId, 10, Duration.ofHours(1));
    User user = PrivacyRequestService.verifiedUser(users, userId);
    DiscoveredService account =
        PrivacyRequestService.ownedAccount(accounts, userId, accountId);
    PrivacyRequestType requested = PrivacyRequestService.parseType(typeRaw);
    PrivacyJurisdiction jurisdiction = PrivacyRequestService.parseJurisdiction(jurisdictionRaw);

    String key = idempotencyKey == null || idempotencyKey.isBlank()
        ? null : idempotencyKey.trim();
    if (key != null) {
      var existing = store.findByUser_IdAndIdempotencyKey(userId, key);
      if (existing.isPresent()) {
        return toDto(existing.get());
      }
    }
    ServiceDirectory entry = account.getServiceDirectory();
    boolean trusted = ServiceRegistry.isTrusted(entry);
    PrivacyRequestType resolved = PrivacyRequestGenerator.resolveType(requested, trusted);
    var duplicate = store.findActiveForAccount(userId, account.getId(), resolved.name());
    if (duplicate.isPresent()) {
      return toDto(duplicate.get());
    }
    DeletionMethod method = DeletionMethodResolver.resolve(entry);
    RenderedTemplate rendered = RequestTemplates.render(
        resolved, jurisdiction, account.getDisplayName(), account.getEvidenceCount());
    requests.createWithContent(user, account, resolved, jurisdiction,
        rendered.subject(), rendered.body(),
        RequestTemplates.CURRENT_TEMPLATE_ID, RequestTemplates.CURRENT_TEMPLATE_VERSION,
        method.name(), DeletionMethodResolver.requiresManualHandling(method), key);
    PrivacyRequest row = store.findActiveForAccount(
        userId, account.getId(), resolved.name()).orElseThrow();
    audit.emit(AuditEvents.DELETION_REQUEST_GENERATED, userId, "user",
        "privacy_request", row.getId().toString(),
        Map.of("method", method.name(), "type", resolved.name()));
    if (DeletionMethodResolver.requiresManualHandling(method)) {
      audit.emit(AuditEvents.DELETION_REQUEST_MANUAL_REVIEW_REQUIRED, userId, "system",
          "privacy_request", row.getId().toString(),
          Map.of("method", method.name(), "type", resolved.name()));
    }
    return toDto(row);
  }

  /** One owned deletion request with fresh method/warnings; stored content untouched. */
  @Transactional
  public DeletionRequestController.DeletionRequestDto get(UUID userId, UUID id) {
    rls.apply();
    PrivacyRequestService.verifiedUser(users, userId);
    PrivacyRequest row = store.findByIdAndUser_Id(id, userId)
        .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND,
            "Deletion request not found"));
    PrivacyRequestService.applyExpiry(store, audit, row);
    return toDto(row);
  }

  // -- internals ------------------------------------------------------------------

  private DeletionRequestController.DeletionPreviewDto toPreview(
      DiscoveredService account, ServiceDirectory entry, PrivacyRequestType resolved,
      PrivacyJurisdiction jurisdiction, DeletionMethod method, RenderedTemplate rendered) {
    boolean manual = DeletionMethodResolver.requiresManualHandling(method);
    return new DeletionRequestController.DeletionPreviewDto(
        account.getId(),
        account.getDisplayName(),
        account.getServiceKey(),
        account.getCategory(),
        resolved.name(),
        jurisdiction.name(),
        method.name(),
        manual,
        supportedTypes(entry),
        emailDestination(entry, method),
        rendered.subject(),
        rendered.body(),
        RequestTemplates.CURRENT_TEMPLATE_ID,
        RequestTemplates.CURRENT_TEMPLATE_VERSION,
        warnings(method),
        manualInstructions(entry, method));
  }

  private DeletionRequestController.DeletionRequestDto toDto(PrivacyRequest row) {
    DiscoveredService account = accounts.findByIdAndUser_Id(
        row.getDiscoveredServiceId(), row.getUser().getId()).orElse(null);
    String service = account != null ? account.getDisplayName() : "";
    String domain = account != null ? account.getServiceKey() : "";
    String category = account != null ? account.getCategory() : "";
    ServiceDirectory entry = account != null ? account.getServiceDirectory() : null;
    DeletionMethod method = DeletionMethod.parse(row.getDeletionMethod());
    if (method == DeletionMethod.UNKNOWN && entry != null) {
      // Legacy Phase 6 rows predate method recording: resolve live without
      // touching stored content.
      method = DeletionMethodResolver.resolve(entry);
    }
    boolean manual = Boolean.TRUE.equals(row.getManualReviewRequired())
        || DeletionMethodResolver.requiresManualHandling(method);
    if (entry != null && DeletionMethodResolver.resolve(entry) != method
        && method != DeletionMethod.UNKNOWN) {
      // The route materially changed after generation: stored content and
      // method stand, but review is required again before anything proceeds.
      manual = true;
    }
    return new DeletionRequestController.DeletionRequestDto(
        row.getId(),
        row.getDiscoveredServiceId(),
        service,
        domain,
        category,
        row.getRequestType(),
        row.getStatus(),
        row.getSubject(),
        row.getBody(),
        row.getJurisdiction(),
        row.getVersion(),
        row.getApprovedVersion(),
        row.getApprovedAt(),
        row.getExpiresAt(),
        row.getTemplateId(),
        row.getTemplateVersion(),
        method.name(),
        manual,
        supportedTypes(entry),
        emailDestination(entry, method),
        warnings(method),
        manualInstructions(entry, method),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  /** Request types exposed for a service: DELETE_ACCOUNT only when trusted. */
  public static List<String> supportedTypes(ServiceDirectory entry) {
    if (ServiceRegistry.isTrusted(entry)) {
      return List.of(
          PrivacyRequestType.DELETE_PERSONAL_DATA.name(),
          PrivacyRequestType.DELETE_ACCOUNT.name(),
          PrivacyRequestType.ACCESS_PERSONAL_DATA.name(),
          PrivacyRequestType.GENERAL_PRIVACY_REQUEST.name());
    }
    return List.of(
        PrivacyRequestType.DELETE_PERSONAL_DATA.name(),
        PrivacyRequestType.ACCESS_PERSONAL_DATA.name(),
        PrivacyRequestType.GENERAL_PRIVACY_REQUEST.name());
  }

  static String emailDestination(ServiceDirectory entry, DeletionMethod method) {
    if (method != DeletionMethod.EMAIL || entry == null) {
      return null;
    }
    String destination = entry.getPrivacyEmail();
    return DispatchService.isUsableDestination(destination) ? destination.trim() : null;
  }

  /** Deterministic warning set: boilerplate plus method-specific cautions. */
  public static List<String> warnings(DeletionMethod method) {
    List<String> warnings = new ArrayList<>(List.of(
        "GhostClean does not guarantee deletion.",
        "External service policies may affect processing."));
    if (method == DeletionMethod.UNKNOWN) {
      warnings.add("Service deletion method is unknown.");
    }
    if (DeletionMethodResolver.requiresManualHandling(method)) {
      warnings.add("Manual review is required.");
    }
    if (method != DeletionMethod.EMAIL) {
      warnings.add("The service may require additional identity verification.");
    }
    return List.copyOf(warnings);
  }

  private List<String> manualInstructions(ServiceDirectory entry, DeletionMethod method) {
    if (method == DeletionMethod.EMAIL) {
      return List.of(
          "Send the approved request using the dispatch action. "
              + "GhostClean sends the request; the service decides how to process it.");
    }
    List<String> instructions = new ArrayList<>();
    switch (method) {
      case PRIVACY_PORTAL -> instructions.add("Use the service's official privacy portal.");
      case ACCOUNT_SETTINGS -> instructions.add("Use the service's account settings page.");
      case WEB_FORM -> instructions.add(
          "Use the service's official web form. Manual review is required.");
      default -> instructions.add("Manual review is required. No verified route is known.");
    }
    if (entry != null && method != DeletionMethod.UNKNOWN) {
      ServiceDetailDto detail = registry.describe(entry);
      List<String> references = new ArrayList<>();
      if (detail.privacyUrl() != null) {
        references.add(detail.privacyUrl());
      }
      if (detail.deletionUrl() != null) {
        references.add(detail.deletionUrl());
      }
      if (detail.accountManagementUrl() != null) {
        references.add(detail.accountManagementUrl());
      }
      for (String reference : references) {
        instructions.add("Verified service reference: " + reference);
      }
    }
    return List.copyOf(instructions);
  }
}
