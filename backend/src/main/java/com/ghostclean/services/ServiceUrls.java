package com.ghostclean.services;

import java.net.URI;
import java.util.Locale;

/**
 * Server-side URL safety for registry route presentation (Phase 5).
 *
 * <p>Registry routes are server-controlled configuration, but defense in
 * depth applies: a URL is only ever presented to users when it uses
 * {@code http(s)}, carries no credentials, and does not target localhost,
 * loopback, or private-network hosts. Anything else is treated as absent.
 * Phase 5 performs no fetching — this gate exists so no unsafe target can
 * become trusted later.
 */
public final class ServiceUrls {

  private ServiceUrls() {
  }

  /** True only for public http(s) URLs without credentials or local targets. */
  public static boolean isSafe(String raw) {
    if (raw == null || raw.isBlank()) {
      return false;
    }
    final URI uri;
    try {
      uri = URI.create(raw.trim());
    } catch (IllegalArgumentException ex) {
      return false;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme))) {
      return false;
    }
    if (uri.getUserInfo() != null) {
      return false;
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      return false;
    }
    String lower = host.toLowerCase(Locale.ROOT);
    if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("::1")
        || lower.equals("[::1]")) {
      return false;
    }
    if (isLoopbackOrPrivateIpv4(lower)) {
      return false;
    }
    if (lower.startsWith("fc") || lower.startsWith("fd")) {
      // IPv6 unique-local (fc00::/7) — never a public route target.
      // Hostnames starting with fc/fd are vanishingly rare; rejecting them
      // here is the conservative choice for a trust gate.
      return false;
    }
    return true;
  }

  private static boolean isLoopbackOrPrivateIpv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      try {
        octets[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException ex) {
        return false;
      }
      if (octets[i] < 0 || octets[i] > 255) {
        return false;
      }
    }
    if (octets[0] == 127 || octets[0] == 10) {
      return true;
    }
    if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
      return true;
    }
    if (octets[0] == 192 && octets[1] == 168) {
      return true;
    }
    // Link-local (self-assigned) addresses are never route targets either.
    return octets[0] == 169 && octets[1] == 254;
  }
}
