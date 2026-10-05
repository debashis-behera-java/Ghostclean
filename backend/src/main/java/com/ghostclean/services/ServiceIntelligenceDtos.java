package com.ghostclean.services;

import java.time.Instant;
import java.util.List;

/**
 * Phase 5 read-only service-intelligence shapes. Shared by the service
 * registry endpoints and detected-account enrichment so both surfaces use
 * identical conservative language. No credentials, no mailbox content —
 * registry metadata only.
 */
public final class ServiceIntelligenceDtos {

  private ServiceIntelligenceDtos() {
  }

  public record PrivacyRouteDto(
      String type,
      String availability,
      String confidence,
      boolean manualReviewRequired,
      String note) {
  }

  public record AutomationDto(
      String status,
      String note) {
  }

  public record JurisdictionDto(
      List<String> supports,
      String note) {
  }

  public record RouteSourceDto(
      String url,
      String type,
      String verificationStatus,
      Instant lastVerifiedAt) {
  }

  public record ServiceSummaryDto(
      String serviceId,
      String displayName,
      String category,
      String routeAvailability,
      String automationStatus) {
  }

  public record ServiceDetailDto(
      String serviceId,
      String displayName,
      String category,
      List<String> domains,
      List<String> senderAliases,
      String accountManagementUrl,
      String privacyUrl,
      String deletionUrl,
      String privacyEmail,
      String routeType,
      String requestMethod,
      String routeConfidence,
      String routeAvailability,
      boolean manualReviewRequired,
      String routeNote,
      String automationStatus,
      String automationNote,
      List<String> jurisdiction,
      String jurisdictionNote,
      RouteSourceDto source,
      Instant lastVerifiedAt) {
  }
}
