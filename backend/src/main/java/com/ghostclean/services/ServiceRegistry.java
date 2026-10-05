package com.ghostclean.services;

import com.ghostclean.detection.DomainNormalizer;
import com.ghostclean.services.ServiceIntelligenceDtos.AutomationDto;
import com.ghostclean.services.ServiceIntelligenceDtos.JurisdictionDto;
import com.ghostclean.services.ServiceIntelligenceDtos.PrivacyRouteDto;
import com.ghostclean.services.ServiceIntelligenceDtos.RouteSourceDto;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceDetailDto;
import com.ghostclean.services.ServiceIntelligenceDtos.ServiceSummaryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service-intelligence registry (Phase 5): deterministic matching and
 * read-only enrichment over the curated {@code service_directory} catalog.
 *
 * <p>Matching is a pure function of normalized domains/aliases — no AI,
 * no network, no guessing. Identity resolution is tri-state: exactly one
 * active claimant → KNOWN; several → AMBIGUOUS (never silently pick one);
 * none → UNKNOWN. Route descriptions are only ever presented as trusted
 * when the row is VERIFIED; unverified or rejected rows stay
 * UNKNOWN-facing and unsafe URLs are suppressed entirely.
 *
 * <p>Reads the active catalog fresh from the database on every call. The
 * catalog is a handful of rows by design, so no cache-staleness window
 * exists for tests or future admin flows. The registry describes routes;
 * it executes nothing.
 */
@Service
public class ServiceRegistry {

  static final String KNOWN_ROUTE_NOTE =
      "Potential privacy request route. The registry describes this route; "
          + "GhostClean does not submit requests.";
  static final String UNKNOWN_ROUTE_NOTE =
      "No verified privacy route known. Manual review required.";
  static final String AMBIGUOUS_ROUTE_NOTE =
      "Multiple services could match this evidence. Manual review required; "
          + "no service was chosen.";
  static final String MANUAL_ONLY_NOTE =
      "Manual action only. GhostClean does not perform this action.";
  static final String NOT_SUPPORTED_NOTE =
      "No supported route. GhostClean does not perform this action.";
  static final String FUTURE_AUTOMATION_NOTE =
      "Not performed by GhostClean. Noted as structurally compatible with "
          + "future automation only.";
  static final String AUTOMATABLE_NOTE =
      "Structurally compatible with future automation. GhostClean does not "
          + "perform this action.";
  static final String JURISDICTION_NOTE =
      "Jurisdictional support information only — not legal advice and not "
          + "a determination of individual rights.";

  private final ServiceDirectoryRepository repository;

  public ServiceRegistry(ServiceDirectoryRepository repository) {
    this.repository = repository;
  }

  public enum MatchKind {
    KNOWN,
    AMBIGUOUS,
    UNKNOWN
  }

  /** Tri-state identity match; never throws on odd input. */
  public record ServiceMatch(MatchKind kind, List<ServiceDirectory> candidates) {
    public Optional<ServiceDirectory> service() {
      if (kind == MatchKind.KNOWN && candidates.size() == 1) {
        return Optional.of(candidates.get(0));
      }
      return Optional.empty();
    }
  }

  /**
   * Deterministic domain match: exact claimants first, then subdomains.
   * Accepts a bare domain or a full sender address (the domain is extracted
   * exactly like the Phase 4 scan pipeline does before lookup).
   */
  @Transactional(readOnly = true)
  public ServiceMatch match(String senderDomain) {
    String normalized = senderDomain != null && senderDomain.contains("@")
        ? DomainNormalizer.extractDomain(senderDomain)
        : DomainNormalizer.asciiLower(senderDomain);
    if (normalized.isEmpty()) {
      return new ServiceMatch(MatchKind.UNKNOWN, List.of());
    }
    List<ServiceDirectory> active = repository.findByStatus("active");
    List<ServiceDirectory> exact = new ArrayList<>();
    for (ServiceDirectory entry : active) {
      if (entry.matchesDomain(normalized)) {
        exact.add(entry);
      }
    }
    if (exact.size() == 1) {
      return new ServiceMatch(MatchKind.KNOWN, List.copyOf(exact));
    }
    if (exact.size() > 1) {
      return new ServiceMatch(MatchKind.AMBIGUOUS, List.copyOf(exact));
    }
    List<ServiceDirectory> sub = new ArrayList<>();
    for (ServiceDirectory entry : active) {
      if (entry.matchesSubdomain(normalized)) {
        sub.add(entry);
      }
    }
    if (sub.size() == 1) {
      return new ServiceMatch(MatchKind.KNOWN, List.copyOf(sub));
    }
    if (sub.size() > 1) {
      return new ServiceMatch(MatchKind.AMBIGUOUS, List.copyOf(sub));
    }
    return new ServiceMatch(MatchKind.UNKNOWN, List.of());
  }

  /** Active entry by stable slug (case-insensitive); inactive never matches. */
  @Transactional(readOnly = true)
  public Optional<ServiceDirectory> findActiveBySlug(String slug) {
    if (slug == null || slug.isBlank()) {
      return Optional.empty();
    }
    return repository.findFirstBySlugIgnoreCase(slug.trim())
        .filter(entry -> "active".equalsIgnoreCase(entry.getStatus()));
  }

  @Transactional(readOnly = true)
  public List<ServiceDirectory> listActive() {
    return repository.findByStatus("active");
  }

  /**
   * Enrichment for a detected account linked to {@code entry} (null when
   * the account grouped under an unlisted domain). Falls back to
   * domain matching on {@code fallbackDomain} so ambiguous/unknown states
   * surface instead of silently resolving.
   */
  @Transactional(readOnly = true)
  public Enrichment enrichmentFor(ServiceDirectory entry, String fallbackDomain) {
    ServiceDirectory usable =
        entry != null && "active".equalsIgnoreCase(entry.getStatus()) ? entry : null;
    if (usable != null && RouteVerificationStatus.parse(usable.getVerificationStatus())
        != RouteVerificationStatus.REJECTED) {
      return new Enrichment(routeFor(usable), automationFor(usable), jurisdictionFor(usable));
    }
    if (usable != null) {
      // REJECTED rows are suppressed from route presentation entirely.
      return unknownEnrichment();
    }
    ServiceMatch match = match(fallbackDomain);
    if (match.kind() == MatchKind.AMBIGUOUS) {
      return new Enrichment(
          new PrivacyRouteDto(RouteType.UNKNOWN.name(), RouteAvailability.AMBIGUOUS.name(),
              RouteConfidence.UNKNOWN.name(), true, AMBIGUOUS_ROUTE_NOTE),
          new AutomationDto(AutomationStatus.MANUAL_ONLY.name(), MANUAL_ONLY_NOTE),
          jurisdictionFor(null));
    }
    if (match.kind() == MatchKind.KNOWN) {
      ServiceDirectory single = match.candidates().get(0);
      return new Enrichment(routeFor(single), automationFor(single), jurisdictionFor(single));
    }
    return unknownEnrichment();
  }

  /** Full safe detail for one active entry; unsafe links are nulled. */
  @Transactional(readOnly = true)
  public ServiceDetailDto describe(ServiceDirectory entry) {
    boolean trusted = isTrusted(entry);
    return new ServiceDetailDto(
        entry.getSlug(),
        entry.getName(),
        entry.getCategory(),
        List.of(entry.getDomains() == null ? new String[0] : entry.getDomains()),
        List.of(entry.getAliases() == null ? new String[0] : entry.getAliases()),
        trusted ? safeOrNull(entry.getAccountSettingsUrl()) : null,
        trusted ? safeOrNull(entry.getPrivacyUrl()) : null,
        trusted ? safeOrNull(entry.getDeletionUrl()) : null,
        trusted && RouteType.parse(entry.getRouteType()) == RouteType.EMAIL_REQUEST
            ? entry.getPrivacyEmail() : null,
        RouteType.parse(entry.getRouteType()).name(),
        RequestMethod.parse(entry.getRequestMethod()).name(),
        trusted ? RouteConfidence.parse(entry.getRouteConfidence()).name()
            : RouteConfidence.UNKNOWN.name(),
        trusted ? RouteAvailability.KNOWN.name() : RouteAvailability.UNKNOWN.name(),
        trusted ? entry.isManualReviewRequired() : true,
        trusted ? KNOWN_ROUTE_NOTE : UNKNOWN_ROUTE_NOTE,
        AutomationStatus.parse(entry.getAutomationStatus()).name(),
        automationNote(AutomationStatus.parse(entry.getAutomationStatus())),
        jurisdictionNames(entry),
        JURISDICTION_NOTE,
        new RouteSourceDto(
            trusted ? safeOrNull(entry.getSourceUrl()) : null,
            RouteSourceType.parse(entry.getSourceType()).name(),
            RouteVerificationStatus.parse(entry.getVerificationStatus()).name(),
            entry.getLastVerifiedAt()),
        entry.getLastVerifiedAt());
  }

  public ServiceSummaryDto summarize(ServiceDirectory entry) {
    boolean trusted = isTrusted(entry);
    return new ServiceSummaryDto(
        entry.getSlug(),
        entry.getName(),
        entry.getCategory(),
        trusted ? RouteAvailability.KNOWN.name() : RouteAvailability.UNKNOWN.name(),
        AutomationStatus.parse(entry.getAutomationStatus()).name());
  }

  /** Trusted only when VERIFIED, KNOWN-facing, and actively listed. */
  public static boolean isTrusted(ServiceDirectory entry) {
    if (entry == null || !"active".equalsIgnoreCase(entry.getStatus())) {
      return false;
    }
    if (RouteVerificationStatus.parse(entry.getVerificationStatus())
        != RouteVerificationStatus.VERIFIED) {
      return false;
    }
    return RouteAvailability.parse(entry.getRouteAvailability()) == RouteAvailability.KNOWN;
  }

  private PrivacyRouteDto routeFor(ServiceDirectory entry) {
    boolean trusted = isTrusted(entry);
    return new PrivacyRouteDto(
        RouteType.parse(entry.getRouteType()).name(),
        trusted ? RouteAvailability.KNOWN.name() : RouteAvailability.UNKNOWN.name(),
        trusted ? RouteConfidence.parse(entry.getRouteConfidence()).name()
            : RouteConfidence.UNKNOWN.name(),
        trusted ? entry.isManualReviewRequired() : true,
        trusted ? KNOWN_ROUTE_NOTE : UNKNOWN_ROUTE_NOTE);
  }

  private AutomationDto automationFor(ServiceDirectory entry) {
    AutomationStatus status = AutomationStatus.parse(entry.getAutomationStatus());
    return new AutomationDto(status.name(), automationNote(status));
  }

  private JurisdictionDto jurisdictionFor(ServiceDirectory entry) {
    return new JurisdictionDto(jurisdictionNames(entry), JURISDICTION_NOTE);
  }

  private List<String> jurisdictionNames(ServiceDirectory entry) {
    List<String> supports = new ArrayList<>();
    if (entry == null || entry.getJurisdiction() == null) {
      return supports;
    }
    for (String raw : entry.getJurisdiction()) {
      JurisdictionSupport parsed = JurisdictionSupport.parse(raw);
      if (parsed != JurisdictionSupport.UNKNOWN) {
        supports.add(parsed.name());
      }
    }
    return List.copyOf(supports);
  }

  private Enrichment unknownEnrichment() {
    return new Enrichment(
        new PrivacyRouteDto(RouteType.UNKNOWN.name(), RouteAvailability.UNKNOWN.name(),
            RouteConfidence.UNKNOWN.name(), true, UNKNOWN_ROUTE_NOTE),
        new AutomationDto(AutomationStatus.MANUAL_ONLY.name(), MANUAL_ONLY_NOTE),
        new JurisdictionDto(List.of(), JURISDICTION_NOTE));
  }

  private static String automationNote(AutomationStatus status) {
    return switch (status) {
      case NOT_SUPPORTED -> NOT_SUPPORTED_NOTE;
      case FUTURE_AUTOMATION -> FUTURE_AUTOMATION_NOTE;
      case AUTOMATABLE -> AUTOMATABLE_NOTE;
      default -> MANUAL_ONLY_NOTE;
    };
  }

  private static String safeOrNull(String url) {
    return ServiceUrls.isSafe(url) ? url : null;
  }

  public record Enrichment(
      PrivacyRouteDto privacyRoute,
      AutomationDto automation,
      JurisdictionDto jurisdiction) {
  }
}
