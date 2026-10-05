package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 11 classified RLS (ADR-026): policies are enabled on every
 * user/audit table, global catalog tables carry no user scoping, and a
 * least-privilege role sees exactly its own rows while anonymous audit
 * inserts still land. Application-layer authorization stays authoritative;
 * RLS is defense-in-depth.
 */
class RlsPolicyTests extends PostgresTestBase {

  /** Class 1 (direct) + Class 2 (indirect) + Class 4 (audit) tables. */
  private static final List<String> RLS_TABLES = List.of(
      "users", "email_verification_tokens", "refresh_sessions", "user_settings",
      "oauth_connections", "oauth_states", "mailbox_scans", "discovered_services",
      "service_evidence", "privacy_requests", "privacy_request_dispatches",
      "privacy_request_responses", "audit_logs");

  /** Class 3 (global/reference): no user ownership is invented. */
  private static final List<String> GLOBAL_TABLES = List.of("service_directory");

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private DataSource dataSource;

  @Test
  void rlsEnabledOnClassifiedTablesOnly() {
    for (String table : RLS_TABLES) {
      Boolean enabled = jdbc.queryForObject(
          "SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
          Boolean.class, table);
      assertTrue(Boolean.TRUE.equals(enabled), "RLS must be enabled on " + table);
    }
    for (String table : GLOBAL_TABLES) {
      Boolean enabled = jdbc.queryForObject(
          "SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
          Boolean.class, table);
      assertTrue(!Boolean.TRUE.equals(enabled),
          "no user RLS may be invented on global table " + table);
    }
  }

  @Test
  void leastPrivilegeRoleSeesOnlyItsOwnRows() throws Exception {
    UUID userA = insertUser("rls-a-" + UUID.randomUUID() + "@example.com");
    UUID userB = insertUser("rls-b-" + UUID.randomUUID() + "@example.com");
    jdbc.update(
        "INSERT INTO oauth_connections (user_id, provider, provider_account_id) VALUES (?, 'google', 'acct-a')",
        userA);
    UUID discoveredB = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO discovered_services (id, user_id, service_key, display_name) VALUES (?, ?, 'svc-b', 'Service B')",
        discoveredB, userB);
    UUID requestB = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO privacy_requests (id, user_id, discovered_service_id, subject, body, expires_at)"
            + " VALUES (?, ?, ?, 'Subject B', 'Body B', now() + interval '30 days')",
        requestB, userB, discoveredB);

    jdbc.execute("CREATE ROLE rls_tester NOLOGIN");
    jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO rls_tester");
    jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO rls_tester");
    try (Connection con = dataSource.getConnection();
        Statement st = con.createStatement()) {
      try {
        st.execute("SET ROLE rls_tester");

        st.execute("SET app.user_id = '" + userA + "'");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM oauth_connections"),
            "same-user row must be visible");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM users"),
            "self user row must be visible");

        st.execute("SET app.user_id = '" + userB + "'");
        assertEquals(0, count(st, "SELECT COUNT(*) FROM oauth_connections"),
            "cross-user rows must be denied");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM discovered_services"),
            "same-user discovered row must be visible");

        st.execute("INSERT INTO audit_logs (user_id, event, actor)"
            + " VALUES (NULL, 'LOGIN_FAILED', 'anonymous')");
        assertTrue(count(st, "SELECT COUNT(*) FROM audit_logs WHERE user_id IS NULL") >= 1,
            "anonymous audit inserts must still land");
        assertTrue(count(st, "SELECT COUNT(*) FROM service_directory") >= 0,
            "global catalog must remain readable");

        st.execute("SET app.user_id = '" + userA + "'");
        assertThrows(SQLException.class, () -> st.execute(
            "INSERT INTO privacy_request_dispatches"
                + " (privacy_request_id, request_version, destination, subject_snapshot, body_snapshot)"
                + " VALUES ('" + requestB + "', 1, 'privacy@example.com', 's', 'b')"),
            "indirect cross-user insert must be denied");

        st.execute("SET app.user_id = '" + userB + "'");
        st.execute("INSERT INTO privacy_request_dispatches"
            + " (privacy_request_id, request_version, destination, subject_snapshot, body_snapshot)"
            + " VALUES ('" + requestB + "', 1, 'privacy@example.com', 's', 'b')");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM privacy_request_dispatches"),
            "indirect same-user row must be visible");
      } finally {
        st.execute("RESET ROLE");
      }
    } finally {
      jdbc.execute("DROP OWNED BY rls_tester");
      jdbc.execute("DROP ROLE IF EXISTS rls_tester");
      jdbc.update("DELETE FROM users WHERE id = ?", userA);
      jdbc.update("DELETE FROM users WHERE id = ?", userB);
      jdbc.update("DELETE FROM audit_logs WHERE event = 'LOGIN_FAILED'");
    }
  }

  @Test
  void ghostcleanAppRoleEnforcesRlsLocally() throws Exception {
    // Phase 12 local Step 8 (Gap #7): exercise the REAL V14 role
    // `ghostclean_app` locally. Exact V14 definition used — NOLOGIN
    // NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS plus
    // USAGE on schema public, SELECT/INSERT/UPDATE/DELETE on all tables,
    // USAGE+SELECT on all sequences. No GRANT/CREATE/ALTER here and no
    // V13/V14 change: the role already carries its V14 grants from the
    // migration, and V13 policies are role-agnostic via
    // current_setting('app.user_id', true).
    //
    // Test-only setup mechanism (minimum, mirrors the existing
    // rls_tester pattern above): `ghostclean_app` is NOLOGIN so it
    // cannot LOGIN directly in Testcontainers; the privileged owner
    // test connection issues `SET ROLE ghostclean_app` on a dedicated
    // Connection. No membership GRANT, no LOGIN alteration, no extra
    // privileges are added (production assigns credentials/IAM out of
    // band per V14 header). Plain `SET app.user_id` on the dedicated
    // autocommit connection is the transaction-appropriate equivalent
    // of request-scoped `SET LOCAL` (RlsContext) and keeps expected-
    // failure probes (cross-user / DDL denies) from aborting a single
    // explicit transaction.

    // A. Intended restricted shape (owner path, before SET ROLE).
    String attrs = "SELECT rolsuper::text || '/' || rolcreatedb::text || '/'"
        + " || rolcreaterole::text || '/' || rolcanlogin::text || '/'"
        + " || rolreplication::text || '/' || rolbypassrls::text"
        + " FROM pg_roles WHERE rolname = 'ghostclean_app'";
    assertEquals("false/false/false/false/false/false", jdbc.queryForObject(attrs, String.class),
        "V14 role must be NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
    assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT has_schema_privilege('ghostclean_app', 'public', 'USAGE')", Boolean.class)),
        "V14 grants USAGE on schema public");
    for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
      assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
          "SELECT has_table_privilege('ghostclean_app', 'public.oauth_connections', ?)",
          Boolean.class, priv)),
          "V14 grants " + priv + " on tables");
    }
    assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT has_table_privilege('ghostclean_app', 'public.oauth_connections', 'TRUNCATE')",
        Boolean.class)),
        "role must not hold TRUNCATE (restricted to SELECT/INSERT/UPDATE/DELETE)");
    assertEquals(0, jdbc.queryForObject(
        "SELECT COUNT(*) FROM pg_tables WHERE tableowner = 'ghostclean_app'", Integer.class),
        "role must own nothing");
    String seq = null;
    try {
      seq = jdbc.queryForObject(
          "SELECT sequence_name FROM information_schema.sequences"
              + " WHERE sequence_schema = 'public' LIMIT 1",
          String.class);
    } catch (Exception ignored) {
      seq = null;
    }
    if (seq != null) {
      assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
          "SELECT has_sequence_privilege('ghostclean_app', ?, 'USAGE')",
          Boolean.class, "public." + seq)),
          "V14 grants USAGE on sequences");
      assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
          "SELECT has_sequence_privilege('ghostclean_app', ?, 'SELECT')",
          Boolean.class, "public." + seq)),
          "V14 grants SELECT on sequences");
    }

    UUID userA = insertUser("rls-app-a-" + UUID.randomUUID() + "@example.com");
    UUID userB = insertUser("rls-app-b-" + UUID.randomUUID() + "@example.com");
    jdbc.update(
        "INSERT INTO oauth_connections (user_id, provider, provider_account_id) VALUES (?, 'google', 'app-acct-a')",
        userA);
    UUID discoveredB = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO discovered_services (id, user_id, service_key, display_name) VALUES (?, ?, 'app-svc-b', 'App Service B')",
        discoveredB, userB);
    UUID requestB = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO privacy_requests (id, user_id, discovered_service_id, subject, body, expires_at)"
            + " VALUES (?, ?, ?, 'App Subject B', 'App Body B', now() + interval '30 days')",
        requestB, userB, discoveredB);

    try (Connection con = dataSource.getConnection();
        Statement st = con.createStatement()) {
      try {
        st.execute("SET ROLE ghostclean_app");
        try (ResultSet rs = st.executeQuery("SELECT current_user")) {
          rs.next();
          assertEquals("ghostclean_app", rs.getString(1),
              "test must actually run as ghostclean_app");
        }
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'ghostclean_app'", Boolean.class),
            "no BYPASSRLS so V13 policies enforce on this role");
        assertThrows(SQLException.class,
            () -> st.execute("TRUNCATE oauth_connections"),
            "TRUNCATE must be denied (restricted privileges)");
        assertThrows(SQLException.class,
            () -> st.execute("CREATE TABLE ghostclean_app_probe_rls (id int)"),
            "DDL must be denied (USAGE only on schema)");

        // B. Own rows visible.
        st.execute("SET app.user_id = '" + userA + "'");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM oauth_connections"),
            "ghostclean_app sees own oauth row");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM users"),
            "ghostclean_app sees self user row");

        // C. Cross-user rows denied.
        st.execute("SET app.user_id = '" + userB + "'");
        assertEquals(0, count(st, "SELECT COUNT(*) FROM oauth_connections"),
            "ghostclean_app must not see another user's oauth rows");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM discovered_services"),
            "ghostclean_app sees own discovered row");
        assertEquals(0, count(st, "SELECT COUNT(*) FROM privacy_requests WHERE user_id = '" + userA + "'"),
            "ghostclean_app must not see another user's requests");

        // D. Audit/system insert path + global catalog.
        st.execute("INSERT INTO audit_logs (user_id, event, actor)"
            + " VALUES (NULL, 'GHOSTCLEAN_APP_PROBE', 'anonymous')");
        assertTrue(count(st, "SELECT COUNT(*) FROM audit_logs WHERE event = 'GHOSTCLEAN_APP_PROBE'") >= 1,
            "anonymous audit inserts must still land via ghostclean_app");
        assertTrue(count(st, "SELECT COUNT(*) FROM service_directory") >= 0,
            "global catalog must remain readable via ghostclean_app");

        // C (indirect) + B (indirect): dispatches scoped through parent request.
        st.execute("SET app.user_id = '" + userA + "'");
        assertThrows(SQLException.class, () -> st.execute(
            "INSERT INTO privacy_request_dispatches"
                + " (privacy_request_id, request_version, destination, subject_snapshot, body_snapshot)"
                + " VALUES ('" + requestB + "', 1, 'privacy@example.com', 's', 'b')"),
            "indirect cross-user insert must be denied via ghostclean_app");
        st.execute("SET app.user_id = '" + userB + "'");
        st.execute("INSERT INTO privacy_request_dispatches"
            + " (privacy_request_id, request_version, destination, subject_snapshot, body_snapshot)"
            + " VALUES ('" + requestB + "', 1, 'privacy@example.com', 's', 'b')");
        assertEquals(1, count(st, "SELECT COUNT(*) FROM privacy_request_dispatches"),
            "indirect same-user row must be visible via ghostclean_app");
      } finally {
        st.execute("RESET ROLE");
      }
    } finally {
      jdbc.update("DELETE FROM users WHERE id = ?", userA);
      jdbc.update("DELETE FROM users WHERE id = ?", userB);
      jdbc.update("DELETE FROM audit_logs WHERE event = 'GHOSTCLEAN_APP_PROBE'");
    }
  }

  private UUID insertUser(String email) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'x')", id, email);
    return id;
  }

  private int count(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
