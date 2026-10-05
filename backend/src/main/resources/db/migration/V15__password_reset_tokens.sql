-- ============================================================================
-- GhostClean V15 — password-reset tokens (forgot/reset flow)
-- ============================================================================
-- Dedicated single-use token HASHES only: raw reset secrets are never
-- persisted. Mirrors the email_verification_tokens conventions (V1):
-- UUID PK, user FK with cascading deletes, unique token hash, one active
-- (unused) token per user, expiry index for purge sweeps.
-- ============================================================================

CREATE TABLE password_reset_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);

-- One active (unused) token per user.
CREATE UNIQUE INDEX uq_password_reset_active_per_user
  ON password_reset_tokens (user_id)
  WHERE used_at IS NULL;

CREATE INDEX ix_password_reset_expiry ON password_reset_tokens (expires_at);
