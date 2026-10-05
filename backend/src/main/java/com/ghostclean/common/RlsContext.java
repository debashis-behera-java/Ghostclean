package com.ghostclean.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase 12 RLS request-context plumbing (ADR-032).
 *
 * <p>Sets {@code SET LOCAL app.user_id} for the current transaction when it
 * serves an authenticated user, giving the V13 RLS policies their ownership
 * context under the production least-privilege role. Strict no-op otherwise
 * (no active transaction, anonymous caller, non-user principal), so
 * anonymous/system paths can never inherit a stale ownership context and
 * owner-path deployments are unaffected.
 *
 * <p>Uses {@code SET LOCAL} (transaction-scoped) deliberately: session-level
 * settings would leak across pooled connections. The value comes from the
 * typed {@link AuthPrincipal#userId()} only. If the write itself fails, the
 * helper logs a key-agnostic warning and continues — the database then
 * fails safe (deny) wherever RLS enforces.
 */
@Component
public class RlsContext {

  private static final Logger log = LoggerFactory.getLogger(RlsContext.class);

  @PersistenceContext
  private EntityManager entities;

  /** Applies the RLS ownership context when applicable; otherwise no-op. */
  public void apply() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
        || principal.userId() == null) {
      return;
    }
    try {
      entities.createNativeQuery("SET LOCAL app.user_id = '" + principal.userId() + "'")
          .executeUpdate();
    } catch (RuntimeException ex) {
      log.warn("RLS context unavailable; continuing without database ownership context");
    }
  }
}
