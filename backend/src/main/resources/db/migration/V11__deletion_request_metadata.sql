-- ============================================================================
-- GhostClean V11 — deletion-request engine metadata (Phase 8)
-- ============================================================================
-- Extends privacy_requests (no new tables, no duplicate request entity):
-- template provenance (id + version, so later template changes can never
-- silently mutate stored content), resolved deletion method, and an
-- explicit manual-review flag. Also admits ACCESS_PERSONAL_DATA to the
-- request-type CHECK (V9 predates it). All columns nullable so Phase 6
-- rows keep working; new rows populate them at generation time.
-- ============================================================================

ALTER TABLE privacy_requests
  ADD COLUMN template_id VARCHAR(64) NULL,
  ADD COLUMN template_version INTEGER NULL,
  ADD COLUMN deletion_method VARCHAR(32) NULL,
  ADD COLUMN manual_review_required BOOLEAN NULL;

ALTER TABLE privacy_requests
  ADD CONSTRAINT ck_privacy_deletion_method CHECK (deletion_method IS NULL
    OR deletion_method IN (
      'EMAIL', 'PRIVACY_PORTAL', 'ACCOUNT_SETTINGS',
      'WEB_FORM', 'MANUAL', 'UNKNOWN'));

ALTER TABLE privacy_requests DROP CONSTRAINT ck_privacy_type;
ALTER TABLE privacy_requests
  ADD CONSTRAINT ck_privacy_type CHECK (request_type IN (
    'DELETE_PERSONAL_DATA', 'DELETE_ACCOUNT', 'ACCESS_PERSONAL_DATA',
    'GENERAL_PRIVACY_REQUEST', 'UNKNOWN'));

CREATE INDEX ix_privacy_template ON privacy_requests (template_id, template_version);
