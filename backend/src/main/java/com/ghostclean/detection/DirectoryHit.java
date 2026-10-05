package com.ghostclean.detection;

/**
 * Matching surface the scoring engine needs from a service-catalog hit.
 *
 * <p>Owned by {@code detection} so the engine depends on no catalog module:
 * the catalog (e.g. {@code com.ghostclean.services}) implements this seam and
 * {@link DetectionEngine} scores against it (ArchUnit: modules stay free of
 * dependency cycles).
 */
public interface DirectoryHit {

  /** Exact registrable-domain match against known domains/aliases' domains. */
  boolean matchesDomain(String senderDomain);

  /** Subdomain of a known domain (e.g. mail under a service domain). */
  boolean matchesSubdomain(String senderDomain);

  /** Full sender-address match against known aliases. */
  boolean matchesSender(String senderAddress);
}