-- ============================================================================
-- GhostClean V10 — privacy request dispatch (Phase 7)
-- ============================================================================
-- privacy_request_dispatches: one row per privacy request, created by the
-- explicit user dispatch action (gate 2) and never by approval, scans, or
-- background jobs (no scheduler exists in this codebase). The row records
-- the approved-version snapshot reference actually handed to the outbound
-- provider, provider acceptance/failure, and idempotency state.
-- No credentials, tokens, or mailbox content are stored here: destination
-- is the service's public route contact; subject/body are the user's own
-- approved request text required as the send record.
-- ============================================================================

CREATE TABLE privacy_request_dispatches (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  privacy_request_id      UUID NOT NULL REFERENCES privacy_requests (id) ON DELETE CASCADE,
  request_version         INTEGER NOT NULL,
  provider                VARCHAR(32) NOT NULL DEFAULT 'sink',
  status                  VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  destination             TEXT NOT NULL,
  subject_snapshot        TEXT NOT NULL,
  body_snapshot           TEXT NOT NULL,
  idempotency_key         VARCHAR(64) NULL,
  provider_reference      TEXT NULL,
  failure_category        VARCHAR(32) NULL,
  attempts                INTEGER NOT NULL DEFAULT 0,
  requested_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  accepted_at             TIMESTAMPTZ NULL,
  failed_at               TIMESTAMPTZ NULL,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_dispatch_request UNIQUE (privacy_request_id),
  CONSTRAINT uq_dispatch_idempotency UNIQUE (idempotency_key),
  CONSTRAINT ck_dispatch_status CHECK (status IN (
    'READY', 'QUEUED', 'SENDING', 'ACCEPTED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_dispatch_failure_category CHECK (failure_category IS NULL OR failure_category IN (
    'TEMPORARY_PROVIDER_ERROR', 'AUTHENTICATION_ERROR', 'INVALID_DESTINATION',
    'MESSAGE_REJECTED', 'RATE_LIMITED', 'UNKNOWN_PROVIDER_ERROR')),
  CONSTRAINT ck_dispatch_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_dispatch_request ON privacy_request_dispatches (privacy_request_id);
CREATE INDEX ix_dispatch_status ON privacy_request_dispatches (status);

CREATE TRIGGER trg_privacy_dispatches_touch_updated_at
  BEFORE UPDATE ON privacy_request_dispatches
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();
