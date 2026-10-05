package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ghostclean.common.AuthPrincipal;
import com.ghostclean.common.RlsContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 RLS request-context plumbing (ADR-032): the helper sets
 * {@code SET LOCAL app.user_id} inside an authenticated transaction and
 * is a strict no-op otherwise (no transaction, anonymous caller, or
 * non-user principal) — so anonymous/system paths can never inherit a
 * stale ownership context.
 */
class RlsContextTests extends PostgresTestBase {

  @Autowired
  private RlsContext rls;

  @Autowired
  @PersistenceContext
  private EntityManager entities;

  @Autowired
  private org.springframework.transaction.PlatformTransactionManager transactions;

  private org.springframework.transaction.support.TransactionTemplate tx() {
    return new org.springframework.transaction.support.TransactionTemplate(transactions);
  }

  @Test
  void noContextLeaksAcrossTransactions() {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId), null, List.of()));
    try {
      tx().execute(status -> {
        rls.apply();
        return null;
      });
    } finally {
      SecurityContextHolder.clearContext();
    }
    tx().execute(status -> {
      rls.apply();
      String current = (String) entities
          .createNativeQuery("SELECT current_setting('app.user_id', true)")
          .getSingleResult();
      assertNoContext(current);
      return null;
    });
  }

  @Test
  @Transactional
  void setsLocalContextVisibleInTransaction() {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId), null, List.of()));
    try {
      rls.apply();
      String current = (String) entities
          .createNativeQuery("SELECT current_setting('app.user_id', true)")
          .getSingleResult();
      assertEquals(userId.toString(), current);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  @Transactional
  void skipsAnonymousCallers() {
    SecurityContextHolder.clearContext();
    rls.apply();
    String current = (String) entities
        .createNativeQuery("SELECT current_setting('app.user_id', true)")
        .getSingleResult();
    assertNoContext(current);
  }

  @Test
  void skipsOutsideTransactions() {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(userId), null, List.of()));
    try {
      rls.apply();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void assertNoContext(String current) {
    // An undefined custom setting reads back as NULL (or empty on some
    // paths); neither can equal a user UUID, so both fail safe under RLS
    // equality. Anything else would be a leaked ownership context.
    org.junit.jupiter.api.Assertions.assertTrue(current == null || current.isEmpty(),
        "no ownership context must be present");
  }
}
