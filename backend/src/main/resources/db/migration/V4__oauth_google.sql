-- ============================================================================
-- GhostClean V4 — Google OAuth connections + authorization states (Phase 3)
-- ============================================================================
-- oauth_connections: one row per (user, provider, provider account), holding
-- ONLY AES-GCM ciphertext plus identity minimums and lifecycle state.
-- Encrypted columns are TEXT because the TokenCryptoProvider seam exchanges
-- Base64 strings (deliberate deviation from the BYTEA sketch in
-- DATABASE_DESIGN.md; security properties are identical).
-- oauth_states: single-use authorization state hashes + PKCE verifiers.
-- No mailbox, evidence, or deletion tables — those belong to later phases.
-- ============================================================================

CREATE TABLE oauth_connections (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  provider              VARCHAR(32) NOT NULL,
  provider_account_id   TEXT NOT NULL,
  provider_email        CITEXT NULL,
  scopes                TEXT[] NOT NULL DEFAULT '{}',
  access_token_enc      TEXT NULL,
  refresh_token_enc     TEXT NULL,
  key_version           INTEGER NOT NULL DEFAULT 1,
  expires_at            TIMESTAMPTZ NULL,
  status                VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  last_error            VARCHAR(300) NULL,
  connected_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_refreshed_at     TIMESTAMPTZ NULL,
  revoked_at            TIMESTAMPTZ NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_oauth_user_provider_account
    UNIQUE (user_id, provider, provider_account_id),
  CONSTRAINT ck_oauth_provider CHECK (provider IN ('google', 'microsoft')),
  CONSTRAINT ck_oauth_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'ERROR'))
);

CREATE INDEX ix_oauth_user ON oauth_connections (user_id);
CREATE INDEX ix_oauth_expiry ON oauth_connections (expires_at)
  WHERE status = 'ACTIVE';

CREATE TRIGGER trg_oauth_connections_touch_updated_at
  BEFORE UPDATE ON oauth_connections
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();

-- --------------------------------------------------------------------------
-- oauth_states: hashed single-use states (raw values never persisted)
-- --------------------------------------------------------------------------
CREATE TABLE oauth_states (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  provider        VARCHAR(32) NOT NULL,
  state_hash      TEXT NOT NULL,
  code_verifier   TEXT NOT NULL,
  return_url      VARCHAR(500) NULL,
  expires_at      TIMESTAMPTZ NOT NULL,
  used_at         TIMESTAMPTZ NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_oauth_state_hash UNIQUE (state_hash),
  CONSTRAINT ck_oauth_state_provider CHECK (provider IN ('google', 'microsoft'))
);

CREATE INDEX ix_oauth_state_user ON oauth_states (user_id);
CREATE INDEX ix_oauth_state_expiry ON oauth_states (expires_at);
