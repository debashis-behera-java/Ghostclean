package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostclean.detection.DomainNormalizer;
import org.junit.jupiter.api.Test;

/** Sender/domain normalization matrix (pure functions, no Spring). */
class DomainNormalizerTests {

  @Test
  void extractsDomainFromDisplayNameForms() {
    assertEquals("spotify.com", DomainNormalizer.extractDomain("Spotify <welcome@spotify.com>"));
    assertEquals("spotify.com", DomainNormalizer.extractDomain("welcome@spotify.com"));
    assertEquals("spotify.com", DomainNormalizer.extractDomain("  <NO-REPLY@Spotify.COM>  "));
    assertEquals("", DomainNormalizer.extractDomain("not-an-address"));
    assertEquals("", DomainNormalizer.extractDomain(null));
  }

  @Test
  void registrableDomainStripsSubdomains() {
    assertEquals("spotify.com", DomainNormalizer.registrableDomain("mail.spotify.com"));
    assertEquals("spotify.com", DomainNormalizer.registrableDomain("a.b.spotify.com"));
    assertEquals("spotify.com", DomainNormalizer.registrableDomain("spotify.com"));
    assertEquals("example", DomainNormalizer.registrableDomain("example"));
  }

  @Test
  void subdomainChecksAreSuffixAware() {
    assertTrue(DomainNormalizer.isSameOrSubdomain("mail.spotify.com", "spotify.com"));
    assertTrue(DomainNormalizer.isSameOrSubdomain("spotify.com", "spotify.com"));
    assertFalse(DomainNormalizer.isSameOrSubdomain("notspotify.com", "spotify.com"));
    assertFalse(DomainNormalizer.isSameOrSubdomain("spotify.com.evil.example", "spotify.com"));
  }

  @Test
  void displayNamesAreStable() {
    assertEquals("Spotify", DomainNormalizer.displayName("mail.spotify.com"));
    assertEquals("Unknown-shop", DomainNormalizer.displayName("unknown-shop.example"));
    assertEquals("", DomainNormalizer.displayName(""));
  }
}
