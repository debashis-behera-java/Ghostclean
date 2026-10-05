package com.ghostclean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Local backup/restore script safety (file-content only, ADR-036): the
 * freshness check creates a pg_dump backup and verifies the file, the
 * disposable drill refuses to run without its required inputs, never
 * touches production identifiers, replays restore-verify.sql, verifies
 * health, and cleans up the disposable database. Never executes the
 * scripts, never touches a network beyond local PostgreSQL here.
 */
class BackupScriptPropertiesTests {

  private static Path backupFile(String name) throws Exception {
    Path file = Paths.get("..", "infrastructure", "backup", name);
    if (!Files.exists(file)) {
      file = Paths.get("infrastructure", "backup", name);
    }
    assertTrue(Files.exists(file), "infrastructure/backup/" + name + " must exist");
    return file;
  }

  private static String readBackupFile(String name) throws Exception {
    return Files.readString(backupFile(name));
  }

  @Test
  void backupCheckRequiresInputAndVerifiesLocalDump() throws Exception {
    String script = readBackupFile("backup-check.sh");
    assertTrue(script.contains("${BACKUP_FILE:?"),
        "backup-check must refuse without BACKUP_FILE");
    assertTrue(script.contains("command -v pg_dump"),
        "backup-check must fail closed when pg_dump is missing");
    assertTrue(script.contains("pg_dump"),
        "backup-check must create the backup with pg_dump");
    assertTrue(script.contains("test -f") && script.contains("test -s"),
        "backup-check must verify the backup file exists and is non-empty");
    assertTrue(script.contains("FAIL"),
        "backup-check must fail clearly on errors");
    assertFalse(script.contains("aws rds"),
        "backup-check must not depend on AWS RDS");
    assertFalse(script.contains("--password"),
        "backup-check must not expose passwords in command arguments");
  }

  @Test
  void restoreDrillRequiresInputsAndRefusesProduction() throws Exception {
    String script = readBackupFile("restore-drill.sh");
    assertTrue(script.contains("${BACKUP_FILE:?"),
        "restore drill must refuse without BACKUP_FILE");
    assertTrue(script.contains("${SCRATCH_DB:?"),
        "restore drill must refuse without SCRATCH_DB");
    assertTrue(script.contains("case \"$SCRATCH_DB\" in"),
        "restore drill must guard the scratch identifier");
    assertTrue(script.contains("*prod*") && script.contains("*live*"),
        "scratch guard must refuse production-like identifiers");
    assertTrue(script.contains("REFUSING"),
        "production scratch use must be refused, not warned");
    assertTrue(script.contains("command -v pg_restore"),
        "restore drill must fail closed when pg_restore is missing");
    assertTrue(script.contains("command -v psql"),
        "restore drill must fail closed when psql is missing");
    assertFalse(script.contains("aws rds"),
        "restore drill must not depend on AWS RDS");
  }

  @Test
  void restoreDrillVerifiesRestoreAndHealth() throws Exception {
    String script = readBackupFile("restore-drill.sh");
    assertTrue(script.contains("pg_restore"),
        "restore drill must restore with pg_restore");
    assertTrue(script.contains("restore-verify.sql"),
        "restore drill must run restore-verify.sql");
    assertTrue(script.contains("ON_ERROR_STOP=1"),
        "restore verification must fail fast on SQL errors");
    assertTrue(script.contains("pg_policies"),
        "restore drill must spot-check ownership isolation");
    assertTrue(script.contains("/api/v1/health") && script.contains("\"status\":\"UP\""),
        "restore drill must verify application health UP");
    assertTrue(script.contains("DROP DATABASE"),
        "restore drill must destroy the disposable database");
  }

  @Test
  void restoreVerifySqlMatchesProductionGate() throws Exception {
    String sql = readBackupFile("restore-verify.sql");
    assertTrue(sql.contains("flyway_schema_history"),
        "restore SQL must check migration completeness");
    assertTrue(sql.contains("expected: 17"),
        "restore SQL must expect migrations through V17");
    for (String table : new String[]{"users", "oauth_connections", "mailbox_scans",
        "discovered_services", "service_evidence", "privacy_requests",
        "privacy_request_dispatches", "privacy_request_responses", "audit_logs",
        "service_directory"}) {
      assertTrue(sql.contains("'" + table + "'"),
          "restore SQL must assert table present: " + table);
    }
    assertTrue(sql.contains("ghostclean_app"),
        "restore SQL must assert the least-privilege role shape");
    assertTrue(sql.contains("rolsuper") && sql.contains("rolcreatedb"),
        "restore SQL must check role privilege flags");
    assertTrue(sql.contains("rowsecurity"),
        "restore SQL must assert RLS is enabled");
    assertTrue(sql.contains("access_token_enc") && sql.contains("refresh_token_enc")
        && sql.contains("key_version"),
        "restore SQL must assert encrypted token columns + version");
    assertTrue(sql.contains("'access_token'") && sql.contains("'refresh_token'"),
        "restore SQL must assert no plaintext credential columns");
    assertTrue(sql.toLowerCase().contains("select"),
        "restore SQL must be read-only verification queries");
    assertTrue(sql.toLowerCase().contains("read-only"),
        "restore SQL must document its read-only contract");
  }
}
