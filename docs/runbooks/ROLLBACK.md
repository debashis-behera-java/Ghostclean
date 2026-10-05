# Runbook: Local Rollback (Docker Compose, ADR-036)

## Prerequisites
- Prior known-good commit/image tag + its migration version recorded (see DEPLOYMENT.md evidence).
- Migration compatibility assessed: Flyway migrations in GhostClean are
  additive/backward-compatible; rolling back the image never rolls back
  applied migrations.

## Steps
1. Stop the failing release: bring the previous commit up with the same
   `.env` (secrets unchanged unless the incident is credential-related —
   then see SECURITY_INCIDENT.md first).
2. Re-run health + RLS matrix + one login/refresh smoke test.
3. If the failure was migration-caused and the migration is not
   backward-compatible: restore from the pre-deploy backup to a
   disposable instance (DATABASE_RESTORE.md), reconcile, and re-deploy
   forward — never hand-edit the live schema.

## Verification
Same as DEPLOYMENT.md verification, plus confirmation that the incident
symptom is gone and audit logging is intact.

## Failure handling
If rollback does not restore health → escalate to INCIDENT_RESPONSE.md;
state explicitly what is unavailable rather than improvising
destructive fixes.

## Evidence
Prior/new tags, reason, verification outputs, timestamps, approver.
