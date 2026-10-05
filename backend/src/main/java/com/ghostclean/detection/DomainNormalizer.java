package com.ghostclean.detection;

import java.net.IDN;
import java.util.Locale;

/**
 * Sender normalization: lowercase, display-name stripping, domain extraction,
 * and registrable-domain approximation (last-two-labels heuristic).
 *
 * <p>The heuristic is documented as approximate — a public-suffix list would
 * be stricter — but directory exact-matches always take priority, so the
 * heuristic only groups unknown senders and can never merge two distinct
 * known services.
 */
public final class DomainNormalizer {

  private DomainNormalizer() {
  }

  /** Extracts the domain from a raw From value ({@code Name <a@b.c>} or bare). */
  public static String extractDomain(String from) {
    if (from == null) {
      return "";
    }
    String address = from.trim();
    int open = address.lastIndexOf('<');
    int close = address.lastIndexOf('>');
    if (open >= 0 && close > open) {
      address = address.substring(open + 1, close).trim();
    }
    int at = address.lastIndexOf('@');
    if (at < 0 || at == address.length() - 1) {
      return "";
    }
    return asciiLower(address.substring(at + 1));
  }

  /** Lowercases + IDN-to-ASCII so unicode domains compare stably. */
  public static String asciiLower(String domain) {
    if (domain == null) {
      return "";
    }
    String lower = domain.trim().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".")) {
      lower = lower.substring(0, lower.length() - 1);
    }
    try {
      return IDN.toASCII(lower);
    } catch (IllegalArgumentException ex) {
      return lower;
    }
  }

  /**
   * Approximates the registrable domain: last two DNS labels
   * ({@code mail.example.com} → {@code example.com}). Single-label and IP
   * inputs pass through unchanged.
   */
  public static String registrableDomain(String domain) {
    String normalized = asciiLower(domain);
    if (normalized.isEmpty()) {
      return "";
    }
    String[] labels = normalized.split("\\.");
    if (labels.length <= 2) {
      return normalized;
    }
    return labels[labels.length - 2] + "." + labels[labels.length - 1];
  }

  /** True when {@code candidate} is the domain itself or a subdomain of it. */
  public static boolean isSameOrSubdomain(String candidate, String domain) {
    String left = asciiLower(candidate);
    String right = asciiLower(domain);
    return !left.isEmpty() && !right.isEmpty()
        && (left.equals(right) || left.endsWith("." + right));
  }

  /** Display form for unknown senders: the registrable domain, or empty. */
  public static String displayName(String domain) {
    String registrable = registrableDomain(domain);
    if (registrable.isEmpty()) {
      return "";
    }
    String first = registrable.contains(".")
        ? registrable.substring(0, registrable.indexOf('.')) : registrable;
    if (first.isEmpty()) {
      return registrable;
    }
    return Character.toUpperCase(first.charAt(0)) + first.substring(1);
  }
}
