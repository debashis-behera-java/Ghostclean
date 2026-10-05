package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 12 restore-drill verification gate (ADR-033): the exact checks
 * the disposable restore drill runs — migrations complete, schema
 * present, seed catalog present, least-privilege role shaped correctly,
 * RLS enabled, and no plaintext token columns. Executable here against
 * Testcontainers; the drill replays the same SQL file
 * ({@code infrastructure/backup/restore-verify.sql}) against the
 * restored instance.
 */
class RestoreVerificationTests extends PostgresTestBase {

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void migrationsAreCompleteThroughV17() {
    Integer max = jdbc.queryForObject(
        "SELECT MAX(version::int) FROM (SELECT version FROM flyway_schema_history"
            + " WHERE success) histories",
        Integer.class);
    assertEquals(17, max, "restored schema must include V17");
  }

  @Test
  void coreTablesExist() {
    List<String> tables = List.of("users", "oauth_connections", "mailbox_scans",
        "discovered_services", "service_evidence", "privacy_requests",
        "privacy_request_dispatches", "privacy_request_responses", "audit_logs",
        "service_directory");
    for (String table : tables) {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM information_schema.tables"
              + " WHERE table_schema = 'public' AND table_name = ?",
          Integer.class, table);
      assertEquals(1, count, "restored schema must contain " + table);
    }
  }

  @Test
  void seedCatalogIsPresent() {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM service_directory", Integer.class);
    assertTrue(count != null && count > 0, "service directory seed must be present");
  }

  @Test
  void productionRoleIsLeastPrivilege() {
    Boolean superuser = jdbc.queryForObject(
        "SELECT rolsuper FROM pg_roles WHERE rolname = 'ghostclean_app'", Boolean.class);
    assertEquals(Boolean.FALSE, superuser, "application role must not be superuser");
    Boolean createdb = jdbc.queryForObject(
        "SELECT rolcreatedb FROM pg_roles WHERE rolname = 'ghostclean_app'", Boolean.class);
    assertEquals(Boolean.FALSE, createdb, "application role must not create databases");
    Boolean createrole = jdbc.queryForObject(
        "SELECT rolcreaterole FROM pg_roles WHERE rolname = 'ghostclean_app'", Boolean.class);
    assertEquals(Boolean.FALSE, createrole, "application role must not create roles");
    Boolean canLogin = jdbc.queryForObject(
        "SELECT rolcanlogin FROM pg_roles WHERE rolname = 'ghostclean_app'", Boolean.class);
    assertEquals(Boolean.FALSE, canLogin, "role ships NOLOGIN; production grants access out of band");
  }

  @Test
  void rlsIsEnabledOnUserTables() {
    Integer enabled = jdbc.queryForObject(
        "SELECT COUNT(*) FROM pg_tables WHERE schemaname = 'public' AND rowsecurity"
            + " AND tablename IN ('users','oauth_connections','mailbox_scans',"
            + "'discovered_services','service_evidence','privacy_requests',"
            + "'privacy_request_dispatches','privacy_request_responses','audit_logs')",
        Integer.class);
    assertEquals(9, enabled, "all user/audit tables must carry RLS");
  }

  @Test
  void noPlaintextTokenColumnsExist() {
    List<String> plaintext = jdbc.queryForList(
        "SELECT table_name || '.' || column_name FROM information_schema.columns"
            + " WHERE table_schema = 'public' AND column_name IN"
            + " ('access_token', 'refresh_token', 'password', 'token', 'private_key')",
        String.class);
    assertTrue(plaintext.isEmpty(),
        "no plaintext credential columns may exist: " + plaintext);
    Integer encrypted = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns"
            + " WHERE table_schema = 'public' AND table_name = 'oauth_connections'"
            + " AND column_name IN ('access_token_enc', 'refresh_token_enc', 'key_version')",
        Integer.class);
    assertEquals(3, encrypted, "encrypted token columns + version must be present");
  }
}
