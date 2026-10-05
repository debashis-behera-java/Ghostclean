-- ============================================================================
-- GhostClean V13 — classified Row-Level Security (Phase 11, ADR-026)
-- ============================================================================
-- Defense-in-depth ONLY. Application authorization (requireOwner, JWT,
-- service-layer checks, `where user_id = :uid`, 404-masking) remains
-- required and authoritative; these policies are a database-layer
-- backstop for a least-privilege role. The current single-role
-- deployment connects as the table owner (which bypasses RLS by
-- design), so existing behavior is unchanged; production (Phase 12)
-- moves the app to a least-privilege role where these policies enforce.
--
-- Classes (see docs/PHASE_11.md §6):
--   1. Direct user-owned (user_id NOT NULL): users self-row + 9 tables.
--   2. Indirectly owned (parent FK, no user_id): privacy_request_dispatches,
--      privacy_request_responses — scoped through privacy_requests.
--   3. Global/reference: service_directory — intentionally NO user RLS.
--   4. System/audit: audit_logs — nullable user_id + REQUIRES_NEW behavior
--      respected; anonymous inserts permitted; no UPDATE/DELETE policy
--      (append-only preserved).
--
-- Context key: app.user_id (UUID text), set per transaction after
-- authentication. current_setting(..., true) yields NULL when unset, so
-- a missing context fails safe (deny) rather than exposing rows.
--
-- ROLLBACK: run one line per table below —
--   ALTER TABLE <table> DISABLE ROW LEVEL SECURITY;
--   DROP POLICY IF EXISTS <policy> ON <table>;
-- Application code needs no change either way.
-- ============================================================================

-- --------------------------------------------------------------------------
-- Class 1a: users self-row
-- --------------------------------------------------------------------------
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS users_self_isolation ON users;
CREATE POLICY users_self_isolation ON users
  USING (id::text = current_setting('app.user_id', true))
  WITH CHECK (id::text = current_setting('app.user_id', true));

-- --------------------------------------------------------------------------
-- Class 1b: direct user-owned tables (user_id NOT NULL)
-- --------------------------------------------------------------------------
ALTER TABLE email_verification_tokens ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS email_verification_tokens_owner_isolation ON email_verification_tokens;
CREATE POLICY email_verification_tokens_owner_isolation ON email_verification_tokens
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE refresh_sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS refresh_sessions_owner_isolation ON refresh_sessions;
CREATE POLICY refresh_sessions_owner_isolation ON refresh_sessions
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE user_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS user_settings_owner_isolation ON user_settings;
CREATE POLICY user_settings_owner_isolation ON user_settings
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE oauth_connections ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS oauth_connections_owner_isolation ON oauth_connections;
CREATE POLICY oauth_connections_owner_isolation ON oauth_connections
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE oauth_states ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS oauth_states_owner_isolation ON oauth_states;
CREATE POLICY oauth_states_owner_isolation ON oauth_states
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE mailbox_scans ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS mailbox_scans_owner_isolation ON mailbox_scans;
CREATE POLICY mailbox_scans_owner_isolation ON mailbox_scans
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE discovered_services ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS discovered_services_owner_isolation ON discovered_services;
CREATE POLICY discovered_services_owner_isolation ON discovered_services
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE service_evidence ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS service_evidence_owner_isolation ON service_evidence;
CREATE POLICY service_evidence_owner_isolation ON service_evidence
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

ALTER TABLE privacy_requests ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS privacy_requests_owner_isolation ON privacy_requests;
CREATE POLICY privacy_requests_owner_isolation ON privacy_requests
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));

-- --------------------------------------------------------------------------
-- Class 2: indirectly owned tables (scoped through parent privacy_requests)
-- --------------------------------------------------------------------------
ALTER TABLE privacy_request_dispatches ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS privacy_request_dispatches_owner_isolation ON privacy_request_dispatches;
CREATE POLICY privacy_request_dispatches_owner_isolation ON privacy_request_dispatches
  USING (EXISTS (
    SELECT 1 FROM privacy_requests pr
    WHERE pr.id = privacy_request_dispatches.privacy_request_id
      AND pr.user_id::text = current_setting('app.user_id', true)))
  WITH CHECK (EXISTS (
    SELECT 1 FROM privacy_requests pr
    WHERE pr.id = privacy_request_dispatches.privacy_request_id
      AND pr.user_id::text = current_setting('app.user_id', true)));

ALTER TABLE privacy_request_responses ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS privacy_request_responses_owner_isolation ON privacy_request_responses;
CREATE POLICY privacy_request_responses_owner_isolation ON privacy_request_responses
  USING (EXISTS (
    SELECT 1 FROM privacy_requests pr
    WHERE pr.id = privacy_request_responses.privacy_request_id
      AND pr.user_id::text = current_setting('app.user_id', true)))
  WITH CHECK (EXISTS (
    SELECT 1 FROM privacy_requests pr
    WHERE pr.id = privacy_request_responses.privacy_request_id
      AND pr.user_id::text = current_setting('app.user_id', true)));

-- --------------------------------------------------------------------------
-- Class 3: service_directory — deliberately NO user RLS (global catalog).
-- --------------------------------------------------------------------------

-- --------------------------------------------------------------------------
-- Class 4: audit_logs — nullable user_id respected; anonymous inserts land;
-- no UPDATE/DELETE policy (append-only; owner path unchanged).
-- --------------------------------------------------------------------------
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS audit_logs_owner_read ON audit_logs;
CREATE POLICY audit_logs_owner_read ON audit_logs
  FOR SELECT
  USING (user_id IS NULL
    OR user_id::text = current_setting('app.user_id', true));
DROP POLICY IF EXISTS audit_logs_append ON audit_logs;
CREATE POLICY audit_logs_append ON audit_logs
  FOR INSERT
  WITH CHECK (true);
