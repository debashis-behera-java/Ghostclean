-- ============================================================================
-- GhostClean V2 — Phase 2 authentication columns (lockout + last login)
-- ============================================================================
-- Extends the V1 foundation only. Adds brute-force lockout state and last
-- login tracking to `users`. No new tables, no Phase 3+ structures.
-- ============================================================================

ALTER TABLE users
  ADD COLUMN locked_until TIMESTAMPTZ NULL,
  ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN last_login_at TIMESTAMPTZ NULL;

-- Lockout sweep / expiry queries (Phase 2 login path, Phase 11 RLS later).
CREATE INDEX ix_users_locked_until ON users (locked_until)
  WHERE locked_until IS NOT NULL;
