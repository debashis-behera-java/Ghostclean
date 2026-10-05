package com.ghostclean.services;

import com.ghostclean.detection.DomainNormalizer;
import com.ghostclean.detection.DirectoryHit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Curated service catalog row (Phase 4: detection identity; Phase 5: route
 * intelligence). Seeds ship known domains/sender aliases for matching;
 * route columns default to UNKNOWN/UNVERIFIED/manual-review — a route is
 * never trusted by accident and nothing here executes deletions.
 */
@Entity
@Table(name = "service_directory")
public class ServiceDirectory implements DirectoryHit {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "slug", nullable = false, unique = true, columnDefinition = "citext")
  private String slug;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "domains", columnDefinition = "text[]")
  private String[] domains = new String[0];

  @Column(name = "aliases", columnDefinition = "text[]")
  private String[] aliases = new String[0];

  @Column(name = "category", length = 32)
  private String category = "other";

  @Column(name = "privacy_url")
  private String privacyUrl;

  @Column(name = "deletion_url")
  private String deletionUrl;

  @Column(name = "account_settings_url")
  private String accountSettingsUrl;

  @Column(name = "privacy_email", columnDefinition = "citext")
  private String privacyEmail;

  @Column(name = "deletion_method", nullable = false, length = 32)
  private String deletionMethod = "UNKNOWN";

  @Column(name = "gdpr_note", columnDefinition = "TEXT")
  private String gdprNote;

  @Column(name = "ccpa_note", columnDefinition = "TEXT")
  private String ccpaNote;

  @Column(name = "api_deletion", nullable = false)
  private boolean apiDeletion;

  @Column(name = "manual_steps", columnDefinition = "TEXT")
  private String manualSteps;

  @Column(name = "route_type", nullable = false, length = 32)
  private String routeType = "UNKNOWN";

  @Column(name = "request_method", nullable = false, length = 32)
  private String requestMethod = "UNKNOWN";

  @Column(name = "automation_status", nullable = false, length = 32)
  private String automationStatus = "MANUAL_ONLY";

  @Column(name = "route_confidence", nullable = false, length = 16)
  private String routeConfidence = "UNKNOWN";

  @Column(name = "route_availability", nullable = false, length = 16)
  private String routeAvailability = "UNKNOWN";

  @Column(name = "source_url")
  private String sourceUrl;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType = "UNKNOWN";

  @Column(name = "verification_status", nullable = false, length = 32)
  private String verificationStatus = "UNVERIFIED";

  @Column(name = "jurisdiction", columnDefinition = "text[]")
  private String[] jurisdiction = new String[0];

  @Column(name = "manual_review_required", nullable = false)
  private boolean manualReviewRequired = true;

  @Column(name = "last_verified_at")
  private Instant lastVerifiedAt;

  @Column(name = "version", nullable = false)
  private int version = 1;

  @Column(name = "status", nullable = false, length = 32)
  private String status = "active";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ServiceDirectory() {
  }

  /** Domain-construction point: slug + display name, rest via setters. */
  public ServiceDirectory(String slug, String name) {
    this.slug = slug;
    this.name = name;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  /** Exact registrable-domain match against known domains/aliases' domains. */
  @Override
  public boolean matchesDomain(String senderDomain) {
    String registrable = DomainNormalizer.registrableDomain(senderDomain);
    if (registrable.isEmpty()) {
      return false;
    }
    for (String domain : domains) {
      if (registrable.equals(DomainNormalizer.asciiLower(domain))) {
        return true;
      }
    }
    for (String alias : aliases) {
      String aliasDomain = DomainNormalizer.extractDomain(alias);
      if (!aliasDomain.isEmpty()
          && registrable.equals(DomainNormalizer.registrableDomain(aliasDomain))) {
        return true;
      }
    }
    return false;
  }

  /** Subdomain of a known domain (e.g. mail under a service domain). */
  @Override
  public boolean matchesSubdomain(String senderDomain) {
    String candidate = DomainNormalizer.asciiLower(senderDomain);
    if (candidate.isEmpty()) {
      return false;
    }
    for (String domain : domains) {
      String known = DomainNormalizer.asciiLower(domain);
      if (!known.isEmpty() && !candidate.equals(known) && candidate.endsWith("." + known)) {
        return true;
      }
    }
    return false;
  }

  /** Full sender-address match against known aliases. */
  @Override
  public boolean matchesSender(String senderAddress) {
    if (senderAddress == null) {
      return false;
    }
    String normalized = senderAddress.trim().toLowerCase();
    for (String alias : aliases) {
      if (!alias.isBlank() && normalized.equals(alias.trim().toLowerCase())) {
        return true;
      }
    }
    return false;
  }

  public UUID getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String[] getDomains() {
    return domains;
  }

  public void setDomains(String[] domains) {
    this.domains = domains;
  }

  public String[] getAliases() {
    return aliases;
  }

  public void setAliases(String[] aliases) {
    this.aliases = aliases;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getDeletionMethod() {
    return deletionMethod;
  }

  public void setDeletionMethod(String deletionMethod) {
    this.deletionMethod = deletionMethod;
  }

  public String getPrivacyUrl() {
    return privacyUrl;
  }

  public void setPrivacyUrl(String privacyUrl) {
    this.privacyUrl = privacyUrl;
  }

  public String getDeletionUrl() {
    return deletionUrl;
  }

  public void setDeletionUrl(String deletionUrl) {
    this.deletionUrl = deletionUrl;
  }

  public String getAccountSettingsUrl() {
    return accountSettingsUrl;
  }

  public void setAccountSettingsUrl(String accountSettingsUrl) {
    this.accountSettingsUrl = accountSettingsUrl;
  }

  public String getPrivacyEmail() {
    return privacyEmail;
  }

  public void setPrivacyEmail(String privacyEmail) {
    this.privacyEmail = privacyEmail;
  }

  public Instant getLastVerifiedAt() {
    return lastVerifiedAt;
  }

  public void setLastVerifiedAt(Instant lastVerifiedAt) {
    this.lastVerifiedAt = lastVerifiedAt;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public int getVersion() {
    return version;
  }

  public String getRouteType() {
    return routeType;
  }

  public void setRouteType(String routeType) {
    this.routeType = routeType;
  }

  public String getRequestMethod() {
    return requestMethod;
  }

  public void setRequestMethod(String requestMethod) {
    this.requestMethod = requestMethod;
  }

  public String getAutomationStatus() {
    return automationStatus;
  }

  public void setAutomationStatus(String automationStatus) {
    this.automationStatus = automationStatus;
  }

  public String getRouteConfidence() {
    return routeConfidence;
  }

  public void setRouteConfidence(String routeConfidence) {
    this.routeConfidence = routeConfidence;
  }

  public String getRouteAvailability() {
    return routeAvailability;
  }

  public void setRouteAvailability(String routeAvailability) {
    this.routeAvailability = routeAvailability;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public void setSourceUrl(String sourceUrl) {
    this.sourceUrl = sourceUrl;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getVerificationStatus() {
    return verificationStatus;
  }

  public void setVerificationStatus(String verificationStatus) {
    this.verificationStatus = verificationStatus;
  }

  public String[] getJurisdiction() {
    return jurisdiction;
  }

  public void setJurisdiction(String[] jurisdiction) {
    this.jurisdiction = jurisdiction;
  }

  public boolean isManualReviewRequired() {
    return manualReviewRequired;
  }

  public void setManualReviewRequired(boolean manualReviewRequired) {
    this.manualReviewRequired = manualReviewRequired;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
