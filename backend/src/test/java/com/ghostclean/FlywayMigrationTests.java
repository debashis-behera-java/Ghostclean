package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway migration coverage: foundation tables exist with the approved
 * shape, OAuth tables exist (V2/V4), Phase 4 scan/detection tables exist
 * (V5–V7), and the current V1–V15 schema shape holds (later-phase objects
 * present; forbidden blueprint tables absent).
 */
class FlywayMigrationTests extends PostgresTestBase {

  @Autowired
  private Flyway flyway;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void migrationsAppliedSuccessfully() {
    assertTrue(flyway.info().applied().length >= 1,
        "at least the V1 baseline migration must be applied");
  }

  @Test
  void foundationTablesExist() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    for (String expected : new String[]{
        "users", "email_verification_tokens", "user_settings",
        "refresh_sessions", "audit_logs"}) {
      assertTrue(tables.contains(expected), "missing foundation table: " + expected);
    }
  }

  @Test
  void phaseFourTablesExist() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    // Phase 4 scope (V5–V7): directory, evidence, scans, discovered accounts.
    for (String required : new String[]{
        "service_directory", "service_evidence", "mailbox_scans", "discovered_services"}) {
      assertTrue(tables.contains(required), "missing Phase 4 table: " + required);
    }
  }

  @Test
  void currentSchemaIncludesLaterPhaseObjects() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    // V9/V10/V12 (Phases 6/7/9): privacy request lifecycle tables.
    for (String required : new String[]{
        "privacy_requests", "privacy_request_dispatches", "privacy_request_responses"}) {
      assertTrue(tables.contains(required), "missing later-phase table: " + required);
    }
    // V8 (Phase 5): service-directory route intelligence columns.
    Integer routeColumns = jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_name = 'service_directory' and column_name in "
            + "('route_type', 'verification_status')",
        Integer.class);
    assertTrue(routeColumns != null && routeColumns == 2,
        "service_directory must carry V8 route columns");
    // V11 (Phase 8): deletion-request metadata columns on privacy_requests.
    Integer templateColumns = jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_name = 'privacy_requests' and column_name in "
            + "('template_id', 'deletion_method')",
        Integer.class);
    assertTrue(templateColumns != null && templateColumns == 2,
        "privacy_requests must carry V11 template columns");
    // V13 (Phase 11): RLS enabled on classified user/audit tables.
    Integer rlsTables = jdbc.queryForObject(
        "select count(*) from pg_tables where schemaname = 'public' and rowsecurity "
            + "and tablename in ('users','oauth_connections','mailbox_scans',"
            + "'discovered_services','service_evidence','privacy_requests',"
            + "'privacy_request_dispatches','privacy_request_responses','audit_logs')",
        Integer.class);
    assertTrue(rlsTables != null && rlsTables == 9,
        "V13 RLS must be enabled on classified tables");
    // V14 (Phase 12): least-privilege application role exists without login/superuser.
    Integer appRole = jdbc.queryForObject(
        "select count(*) from pg_roles where rolname = 'ghostclean_app' "
            + "and rolsuper = false and rolcanlogin = false",
        Integer.class);
    assertTrue(appRole != null && appRole == 1,
        "V14 ghostclean_app role must exist without superuser/login");
  }

  @Test
  void forbiddenBlueprintTablesRemainAbsent() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    // Phase 0 blueprint names deliberately never created (V12 avoids them;
    // deletion engine extends privacy_requests instead).
    for (String forbidden : new String[]{
        "deletion_requests", "deletion_events", "notifications", "legal_templates"}) {
      assertFalse(tables.contains(forbidden),
          "Forbidden blueprint table must not exist: " + forbidden);
    }
  }

  @Test
  void oauthTablesExistInPhase3() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    assertTrue(tables.contains("oauth_connections"), "missing oauth_connections");
    assertTrue(tables.contains("oauth_states"), "missing oauth_states");
  }

  @Test
  void usersCarryVerificationColumns() {
    Integer unverifiedDefault = jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_name = 'users' and column_name = 'email_verified'",
        Integer.class);
    assertTrue(unverifiedDefault != null && unverifiedDefault == 1);
  }

  @Test
  void usersCarryLockoutColumns() {
    Integer lockedUntil = jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_name = 'users' and column_name in "
            + "('locked_until', 'failed_login_attempts', 'last_login_at')",
        Integer.class);
    assertTrue(lockedUntil != null && lockedUntil == 3);
  }

  @Test
  void passwordResetTokensTableExists() {
    List<String> tables = jdbc.queryForList(
        "select tablename from pg_tables where schemaname = 'public'", String.class);
    assertTrue(tables.contains("password_reset_tokens"), "missing password_reset_tokens");
    // V15 shape mirrors email_verification_tokens: hash uniqueness, user FK
    // with cascading deletes, expiry index, single-active partial index.
    Integer columns = jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_name = 'password_reset_tokens' and column_name in "
            + "('id', 'user_id', 'token_hash', 'expires_at', 'used_at', 'created_at')",
        Integer.class);
    assertTrue(columns != null && columns == 6,
        "password_reset_tokens must carry the six token columns");
    Integer uniqueHash = jdbc.queryForObject(
        "select count(*) from information_schema.table_constraints "
            + "where table_name = 'password_reset_tokens' "
            + "and constraint_type = 'UNIQUE'",
        Integer.class);
    assertTrue(uniqueHash != null && uniqueHash >= 1,
        "password_reset_tokens.token_hash must be unique");
  }

  @Test
  void auditTrailHasNoUserForeignKey() {
    // Deliberate (V3): audit must commit independently of audited rows.
    Integer fks = jdbc.queryForObject(
        "select count(*) from information_schema.table_constraints "
            + "where table_name = 'audit_logs' and constraint_type = 'FOREIGN KEY'",
        Integer.class);
    assertTrue(fks != null && fks == 0);
  }
}
