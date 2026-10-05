-- ============================================================================
-- GhostClean V8 — service intelligence registry (Phase 5)
-- ============================================================================
-- Extends service_directory with deletion/privacy-route intelligence.
-- No new tables: one route description per service is the Phase 5 model
-- (a service has at most one trusted route description; multiplicity
-- would invite unverified-route sprawl). All route columns default to
-- UNKNOWN/UNVERIFIED/manual-review so no route is trusted by accident.
-- Nothing here executes deletions — the registry only DESCRIBES routes.
-- ============================================================================

ALTER TABLE service_directory
  ADD COLUMN route_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN request_method VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN automation_status VARCHAR(32) NOT NULL DEFAULT 'MANUAL_ONLY',
  ADD COLUMN route_confidence VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN route_availability VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN source_url TEXT NULL,
  ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN verification_status VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED',
  ADD COLUMN jurisdiction TEXT[] NOT NULL DEFAULT '{}',
  ADD COLUMN manual_review_required BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE service_directory
  ADD CONSTRAINT ck_dir_route_type CHECK (route_type IN (
    'SELF_SERVICE_ACCOUNT_DELETE', 'PRIVACY_REQUEST_FORM', 'PRIVACY_PORTAL',
    'EMAIL_REQUEST', 'ACCOUNT_SETTINGS', 'HELP_CENTER', 'UNKNOWN')),
  ADD CONSTRAINT ck_dir_request_method CHECK (request_method IN (
    'WEB_FORM', 'PRIVACY_PORTAL', 'EMAIL', 'ACCOUNT_SETTINGS',
    'MANUAL', 'UNKNOWN')),
  ADD CONSTRAINT ck_dir_automation_status CHECK (automation_status IN (
    'NOT_SUPPORTED', 'MANUAL_ONLY', 'FUTURE_AUTOMATION', 'AUTOMATABLE')),
  ADD CONSTRAINT ck_dir_route_confidence CHECK (route_confidence IN (
    'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
  ADD CONSTRAINT ck_dir_route_availability CHECK (route_availability IN (
    'KNOWN', 'UNKNOWN', 'AMBIGUOUS')),
  ADD CONSTRAINT ck_dir_source_type CHECK (source_type IN (
    'OFFICIAL', 'HELP_CENTER', 'THIRD_PARTY', 'UNKNOWN')),
  ADD CONSTRAINT ck_dir_verification_status CHECK (verification_status IN (
    'VERIFIED', 'UNVERIFIED', 'REJECTED'));

-- Phase 4 seeds carry verified identity (domains/aliases) but no verified
-- route: state that explicitly rather than implying anything.
UPDATE service_directory
SET route_type = 'UNKNOWN',
    request_method = 'UNKNOWN',
    automation_status = 'MANUAL_ONLY',
    route_confidence = 'UNKNOWN',
    route_availability = 'UNKNOWN',
    source_url = NULL,
    source_type = 'UNKNOWN',
    verification_status = 'UNVERIFIED',
    jurisdiction = '{}',
    manual_review_required = TRUE;

CREATE INDEX ix_dir_route_availability ON service_directory (route_availability);
