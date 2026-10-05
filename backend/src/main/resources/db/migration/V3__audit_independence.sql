-- ============================================================================
-- GhostClean V3 — audit trail independence (Phase 2)
-- ============================================================================
-- Drops the audit_logs -> users foreign key. Rationale: the audit trail is
-- append-only evidence that must be writable from independent transactions
-- (e.g. auditing a registration whose user row is still uncommitted, or
-- auditing a failure that rolls its own transaction back). A foreign key
-- would couple audit writes to audited-row visibility and break both cases.
--
-- user_id stays nullable and indexed. Nullification/anonymization on
-- GhostClean account deletion is enforced by the deletion job (Phase 2+
-- account lifecycle), not by the database. See docs/PHASE_02.md.
-- ============================================================================

ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_user_id_fkey;
