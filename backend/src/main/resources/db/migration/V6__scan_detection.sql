-- ============================================================================
-- GhostClean V6 — scan runs, detected accounts, evidence (Phase 4)
-- ============================================================================
-- mailbox_scans: one bounded run per row; exactly one non-terminal scan per
-- user enforced by partial unique index (application check mirrors it for
-- a friendly 409). discovered_services: one normalized candidate per
-- (user, service_key); statuses are DETECTED/REVIEWED/DISMISSED only —
-- no deletion-request states in Phase 4. service_evidence: scored metadata
-- observations only (no bodies, no attachments, no full addresses).
-- ============================================================================

CREATE TABLE mailbox_scans (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  oauth_connection_id   UUID NOT NULL REFERENCES oauth_connections (id) ON DELETE CASCADE,
  type                  VARCHAR(16) NOT NULL DEFAULT 'FULL',
  status                VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  progress_total        INTEGER NULL,
  progress_done         INTEGER NOT NULL DEFAULT 0,
  messages_scanned      INTEGER NOT NULL DEFAULT 0,
  candidates_detected   INTEGER NOT NULL DEFAULT 0,
  errors_count          INTEGER NOT NULL DEFAULT 0,
  error_code            VARCHAR(64) NULL,
  cancel_requested      BOOLEAN NOT NULL DEFAULT FALSE,
  idempotency_key       VARCHAR(64) NULL,
  query_used            VARCHAR(500) NULL,
  checkpoint            TEXT NULL,
  started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at              TIMESTAMPTZ NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_scan_user_idempotency UNIQUE (user_id, idempotency_key),
  CONSTRAINT ck_scan_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
  CONSTRAINT ck_scan_type CHECK (type IN ('FULL'))
);

CREATE UNIQUE INDEX uq_active_scan_per_user ON mailbox_scans (user_id)
  WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_scans_user_status ON mailbox_scans (user_id, status);

CREATE TRIGGER trg_mailbox_scans_touch_updated_at
  BEFORE UPDATE ON mailbox_scans
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();

-- --------------------------------------------------------------------------
-- discovered_services: normalized account candidates
-- --------------------------------------------------------------------------
CREATE TABLE discovered_services (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  service_directory_id  UUID NULL REFERENCES service_directory (id),
  service_key           CITEXT NOT NULL,
  display_name          TEXT NOT NULL,
  category              VARCHAR(32) NOT NULL DEFAULT 'other',
  confidence            INTEGER NOT NULL DEFAULT 0,
  confidence_band       VARCHAR(16) NOT NULL DEFAULT 'LOW',
  risk_score            INTEGER NULL,
  risk_band             VARCHAR(16) NULL,
  evidence_count        INTEGER NOT NULL DEFAULT 0,
  first_seen_at         TIMESTAMPTZ NULL,
  last_seen_at          TIMESTAMPTZ NULL,
  status                VARCHAR(32) NOT NULL DEFAULT 'DETECTED',
  dismissed_reason      VARCHAR(300) NULL,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_discovered_user_service_key UNIQUE (user_id, service_key),
  CONSTRAINT ck_discovered_status
    CHECK (status IN ('DETECTED', 'REVIEWED', 'DISMISSED')),
  CONSTRAINT ck_discovered_confidence CHECK (confidence BETWEEN 0 AND 100),
  CONSTRAINT ck_discovered_band CHECK (confidence_band IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE INDEX ix_disc_user_conf ON discovered_services (user_id, confidence DESC);
CREATE INDEX ix_disc_user_status ON discovered_services (user_id, status);

CREATE TRIGGER trg_discovered_services_touch_updated_at
  BEFORE UPDATE ON discovered_services
  FOR EACH ROW EXECUTE FUNCTION ghostclean_touch_updated_at();

-- --------------------------------------------------------------------------
-- service_evidence: scored metadata observations (no bodies, no attachments)
-- --------------------------------------------------------------------------
CREATE TABLE service_evidence (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  discovered_service_id   UUID NOT NULL REFERENCES discovered_services (id) ON DELETE CASCADE,
  mailbox_scan_id         UUID NULL REFERENCES mailbox_scans (id) ON DELETE SET NULL,
  source                  VARCHAR(16) NOT NULL DEFAULT 'oauth',
  provider                VARCHAR(32) NOT NULL DEFAULT 'google',
  provider_message_id     TEXT NOT NULL,
  sender_domain           CITEXT NULL,
  sender_address_hash     TEXT NULL,
  subject_truncated       VARCHAR(300) NULL,
  received_at             TIMESTAMPTZ NULL,
  matched_rules           JSONB NULL,
  signal_snapshot         JSONB NULL,
  confidence_contrib      INTEGER NOT NULL DEFAULT 0,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_evidence_user_provider_message
    UNIQUE (user_id, provider, provider_message_id),
  CONSTRAINT ck_evidence_source CHECK (source IN ('oauth', 'manual'))
);

CREATE INDEX ix_ev_disc ON service_evidence (discovered_service_id);
CREATE INDEX ix_ev_sender_domain ON service_evidence (sender_domain);
CREATE INDEX ix_ev_received ON service_evidence (received_at);
