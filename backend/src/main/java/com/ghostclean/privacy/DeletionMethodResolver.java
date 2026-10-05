package com.ghostclean.privacy;

import com.ghostclean.services.RouteType;
import com.ghostclean.services.ServiceDirectory;
import com.ghostclean.services.ServiceRegistry;

/**
 * Deterministic deletion-method resolver (Phase 8). Pure function of
 * trusted service metadata — no network, no guessing, no AI. Only
 * VERIFIED + KNOWN-facing + active rows resolve to a concrete method;
 * everything else (including verified-but-unlisted route types) stays
 * UNKNOWN with manual review. A resolved method describes a path; it
 * never executes one.
 */
public final class DeletionMethodResolver {

  private DeletionMethodResolver() {
  }

  public static DeletionMethod resolve(ServiceDirectory entry) {
    if (!ServiceRegistry.isTrusted(entry)) {
      return DeletionMethod.UNKNOWN;
    }
    return switch (RouteType.parse(entry.getRouteType())) {
      case EMAIL_REQUEST -> DeletionMethod.EMAIL;
      case PRIVACY_PORTAL -> DeletionMethod.PRIVACY_PORTAL;
      case ACCOUNT_SETTINGS -> DeletionMethod.ACCOUNT_SETTINGS;
      case PRIVACY_REQUEST_FORM -> DeletionMethod.WEB_FORM;
      default -> DeletionMethod.UNKNOWN;
    };
  }

  /** True only for methods a user can pursue without GhostClean automation. */
  public static boolean requiresManualHandling(DeletionMethod method) {
    return method != DeletionMethod.EMAIL;
  }
}
