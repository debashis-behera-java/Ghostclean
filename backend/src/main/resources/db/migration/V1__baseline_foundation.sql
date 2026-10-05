-- ============================================================================
-- GhostClean V1 — foundation / security schema (Phase 1)
-- ============================================================================
-- Contains ONLY foundation tables: users, email_verification_tokens,
-- user_settings, refresh_sessions, audit_logs.
--
-- Business tables (oauth_connections, mailbox_scans, discovered_services,
-- service_evidence, service_directory, deletion_requests, deletion_events,
-- notifications, legal_templates) belong to later phases and MUST NOT be
-- added here.
--
-- Conventions: UUID PKs, TIMESTAMPTZ (UTC), user-scoped FKs with cascading
-- deletes, explicit index / unique-constraint names.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "citext";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- --------------------------------------------------------------------------
-- users: identity + credential hash + verification state (auth workflow: P2)
-- --------------------------------------------------------------------------
CREATE TABLE users (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email             CITEXT NOT NULL,
  password_hash     TEXT NOT NULL,
  email_verified    BOOLEAN NOT NULL DEFAULT FALSE,
  status            VARCHAR(32) NOT NULL DEFAULT 'pending_verification',
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at        TIMESTAMPTZ NULL,
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT ck_users_status CHECK (status IN ('pending_verification', 'active', 'locked'))
);

CREATE INDEX ix_users_status ON users (status);

-- --------------------------------------------------------------------------
-- email_verification_tokens: single-use token HASHES only (mailer: Phase 2)
-- --------------------------------------------------------------------------
CREATE TABLE email_verification_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash  TEXT NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_verification_token_hash UNIQUE (token_hash)
);

-- One active (unused) token per user.
CREATE UNIQUE INDEX uq_verification_active_per_user
  ON email_verification_tokens (user_id)
  WHERE used_at IS NULL;

CREATE INDEX ix_verification_expiry ON email_verification_tokens (expires_at);

-- --------------------------------------------------------------------------
-- user_settings: approved preference knobs only (no speculative settings)
-- --------------------------------------------------------------------------
CREATE TABLE user_settings (
  user_id               UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
  retention_days        INTEGER NOT NULL DEFAULT 180,
  reminder_opt_out      BOOLEAN NOT NULL DEFAULT FALSE,
  jurisdiction_default  VARCHAR(32) NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_settings_retention CHECK (retention_days BETWEEN 30 AND 365)
);

-- --------------------------------------------------------------------------
-- refresh_sessions: rotation tracking for Phase 2 (token HASHES only)
-- --------------------------------------------------------------------------
CREATE TABLE refresh_sessions (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  token_hash    TEXT NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,
  rotated_from  UUID NULL,
  revoked       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_user ON refresh_sessions (user_id);
CREATE INDEX ix_refresh_expiry ON refresh_sessions (expires_at);

-- --------------------------------------------------------------------------
-- audit_logs: append-only, redacted metadata (emission points: later phases)
-- --------------------------------------------------------------------------
CREATE TABLE audit_logs (
  id                BIGSERIAL PRIMARY KEY,
  user_id           UUID NULL REFERENCES users (id) ON DELETE SET NULL,
  event             VARCHAR(64) NOT NULL,
  actor             VARCHAR(16) NOT NULL,
  ip_hash           VARCHAR(128) NULL,
  user_agent_hash   VARCHAR(128) NULL,
  entity_type       VARCHAR(64) NULL,
  entity_id         VARCHAR(128) NULL,
  metadata          JSONB NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_audit_actor CHECK (actor IN ('user', 'system', 'anonymous'))
);

CREATE INDEX ix_audit_user_time ON audit_logs (user_id, created_at);
CREATE INDEX ix_audit_event_time ON audit_logs (event, created_at);

-- --------------------------------------------------------------------------
-- updated_at maintenance (users, user_settings)
-- --------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ghostclean_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_touch_updated_at
  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();

CREATE TRIGGER trg_settings_touch_updated_at
  BEFORE UPDATE ON user_settings
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();
