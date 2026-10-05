-- ============================================================================
-- GhostClean V17 — Google connection approval requests
-- ============================================================================
-- GhostClean-level admin approval gate before Google OAuth may start.
-- Business gate only: it never replaces Google's consent flow and stores
-- no tokens, codes, or mailbox content. At most one PENDING row per user
-- (partial unique index). RLS mirrors the Class 1b direct user-owned
-- pattern (defense-in-depth backstop; application checks stay authoritative).
-- ============================================================================

CREATE TABLE google_connection_requests (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at       TIMESTAMPTZ NULL,
  reviewed_by       UUID NULL REFERENCES users(id) ON DELETE SET NULL,
  rejection_reason  VARCHAR(300) NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_google_conn_req_status CHECK (status IN (
    'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

-- One active PENDING request per user (service layer also checks; this is
-- the race backstop).
CREATE UNIQUE INDEX uq_google_conn_req_pending_per_user
  ON google_connection_requests (user_id)
  WHERE status = 'PENDING';

CREATE INDEX ix_google_conn_req_user ON google_connection_requests (user_id);
CREATE INDEX ix_google_conn_req_status ON google_connection_requests (status);
CREATE INDEX ix_google_conn_req_requested ON google_connection_requests (requested_at);

ALTER TABLE google_connection_requests ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS google_connection_requests_owner_isolation
  ON google_connection_requests;
CREATE POLICY google_connection_requests_owner_isolation ON google_connection_requests
  USING (user_id::text = current_setting('app.user_id', true))
  WITH CHECK (user_id::text = current_setting('app.user_id', true));
