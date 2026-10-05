-- ============================================================================
-- GhostClean V12 — privacy request response tracking (Phase 9)
-- ============================================================================
-- privacy_request_responses: append-only user-recorded service outcomes.
-- Responses are manual intake only (no provider polling exists); the
-- latest row per request determines the computed tracking state. No new
-- request states are introduced: the Phase 6/7 lifecycle is unchanged.
-- Table names avoid the Phase 0 blueprint names (deletion_requests,
-- deletion_events, notifications, legal_templates), which the build
-- forbids, and build on privacy_requests / privacy_request_dispatches.
-- Payloads are short user summaries (validated); no bodies, tokens, or
-- identity documents belong here.
-- ============================================================================

CREATE TABLE privacy_request_responses (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  privacy_request_id      UUID NOT NULL REFERENCES privacy_requests (id) ON DELETE CASCADE,
  outcome                 VARCHAR(32) NOT NULL,
  summary                 TEXT NULL,
  recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_response_outcome CHECK (outcome IN (
    'completed', 'rejected', 'partial', 'verification_required')),
  CONSTRAINT ck_response_summary CHECK (summary IS NULL
    OR char_length(summary) BETWEEN 1 AND 2000)
);

CREATE INDEX ix_response_request_time ON privacy_request_responses
  (privacy_request_id, recorded_at DESC);
