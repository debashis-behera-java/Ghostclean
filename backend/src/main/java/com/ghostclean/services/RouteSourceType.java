package com.ghostclean.services;

/**
 * What kind of source backs a route description (Phase 5). Official
 * service sources are preferred; a route is never called official
 * without evidence.
 */
public enum RouteSourceType {
  OFFICIAL,
  HELP_CENTER,
  THIRD_PARTY,
  UNKNOWN;

  /** Lenient parse: blank or unrecognized values become UNKNOWN, never throw. */
  public static RouteSourceType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    try {
      return RouteSourceType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return UNKNOWN;
    }
  }
}
