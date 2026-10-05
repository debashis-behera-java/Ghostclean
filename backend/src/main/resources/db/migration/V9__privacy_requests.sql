-- ============================================================================
-- GhostClean V9 — privacy request workflow foundation (Phase 6)
-- ============================================================================
-- privacy_requests: user-reviewed request drafts/approvals. Two-gate model:
-- DRAFT (generated, unreviewed) → READY_FOR_REVIEW (user-reviewed) →
-- APPROVED (explicit approval, exact version recorded). CANCELLED/EXPIRED
-- are terminal. No SENT/SUBMITTED/DELETED states exist in Phase 6 — later
-- phases add them with new migrations, never by editing this one.
-- Stores user-facing request text only (needed for review); never tokens,
-- bodies, attachments, or passwords. Audit history lives in audit_logs
-- (no FK to users) and survives detection/request cleanup.
-- ============================================================================

CREATE TABLE privacy_requests (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  discovered_service_id   UUID NOT NULL REFERENCES discovered_services (id) ON DELETE CASCADE,
  service_directory_id    UUID NULL REFERENCES service_directory (id) ON DELETE SET NULL,
  request_type            VARCHAR(32) NOT NULL DEFAULT 'GENERAL_PRIVACY_REQUEST',
  status                  VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  subject                 TEXT NOT NULL,
  body                    TEXT NOT NULL,
  jurisdiction            VARCHAR(16) NOT NULL DEFAULT 'GENERAL',
  version                 INTEGER NOT NULL DEFAULT 1,
  approved_version        INTEGER NULL,
  approved_at             TIMESTAMPTZ NULL,
  expires_at              TIMESTAMPTZ NOT NULL,
  idempotency_key         VARCHAR(64) NULL,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_privacy_user_idempotency UNIQUE (user_id, idempotency_key),
  CONSTRAINT ck_privacy_status CHECK (status IN (
    'DRAFT', 'READY_FOR_REVIEW', 'APPROVED', 'CANCELLED', 'EXPIRED')),
  CONSTRAINT ck_privacy_type CHECK (request_type IN (
    'DELETE_PERSONAL_DATA', 'DELETE_ACCOUNT',
    'GENERAL_PRIVACY_REQUEST', 'UNKNOWN')),
  CONSTRAINT ck_privacy_jurisdiction CHECK (jurisdiction IN (
    'GENERAL', 'GDPR', 'CCPA_CPRA')),
  CONSTRAINT ck_privacy_version CHECK (version >= 1),
  CONSTRAINT ck_privacy_body_bounds CHECK (
    char_length(subject) BETWEEN 1 AND 200
    AND char_length(body) BETWEEN 1 AND 8000)
);

-- One active (unfinished) request per (user, account, type): duplicate
-- creation returns the existing row instead of inserting.
CREATE UNIQUE INDEX uq_active_privacy_per_account_type ON privacy_requests
  (user_id, discovered_service_id, request_type)
  WHERE status IN ('DRAFT', 'READY_FOR_REVIEW');
CREATE INDEX ix_privacy_user_status ON privacy_requests (user_id, status);
CREATE INDEX ix_privacy_user_service ON privacy_requests (user_id, discovered_service_id);

CREATE TRIGGER trg_privacy_requests_touch_updated_at
  BEFORE UPDATE ON privacy_requests
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();
