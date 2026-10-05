-- GhostClean restore-drill verification (Phase 12, ADR-033).
--
-- Read-only checks replayed by RestoreVerificationTests (JUnit) and by
-- restore-drill.sh against the disposable restored instance. Every
-- query must return its expected value; any deviation fails the drill.
-- Never run write statements from this file against a live database.

-- 1. Migrations complete through V17.
SELECT MAX(version::int) AS max_version
  FROM (SELECT version FROM flyway_schema_history WHERE success) histories;
-- expected: 17

-- 2. Core tables present.
SELECT COUNT(*) AS core_tables FROM information_schema.tables
 WHERE table_schema = 'public' AND table_name IN (
  'users','oauth_connections','mailbox_scans','discovered_services',
  'service_evidence','privacy_requests','privacy_request_dispatches',
  'privacy_request_responses','audit_logs','service_directory');
-- expected: 10

-- 3. Seed catalog present.
SELECT COUNT(*) AS directory_rows FROM service_directory;
-- expected: > 0

-- 4. Least-privilege application role shape.
SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolcanlogin
  FROM pg_roles WHERE rolname = 'ghostclean_app';
-- expected: single row, all f/f/f/f (access granted out of band)

-- 5. RLS enabled on user/audit tables.
SELECT COUNT(*) AS rls_tables FROM pg_tables
 WHERE schemaname = 'public' AND rowsecurity AND tablename IN (
  'users','oauth_connections','mailbox_scans','discovered_services',
  'service_evidence','privacy_requests','privacy_request_dispatches',
  'privacy_request_responses','audit_logs');
-- expected: 9

-- 6. No plaintext credential columns; encrypted columns + version present.
SELECT table_name || '.' || column_name AS bad_column
  FROM information_schema.columns
 WHERE table_schema = 'public' AND column_name IN (
  'access_token','refresh_token','password','token','private_key');
-- expected: 0 rows
SELECT COUNT(*) AS enc_columns FROM information_schema.columns
 WHERE table_schema = 'public' AND table_name = 'oauth_connections'
   AND column_name IN ('access_token_enc','refresh_token_enc','key_version');
-- expected: 3
