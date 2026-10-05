# Runbook: Database Restore Drill (local-only, ADR-036)

## Prerequisites
- Healthy `pg_dump` backup (DATABASE_BACKUP.md green).
- Local PostgreSQL service reachable via the `PG*` environment
  (password ONLY via `PGPASSWORD`).
- `pg_restore`, `psql`, `curl`, and
  `infrastructure/backup/restore-verify.sql` available.
- A scratch identifier containing no production reference
  (identifiers containing `prod` or `live` are refused).
- Local API reachable at `APP_URL` for the health check.

## Steps
1. `BACKUP_FILE=/tmp/ghostclean-backup.dump SCRATCH_DB=ghostclean-drill-YYYYMMDD APP_URL=http://localhost:8080 sh infrastructure/backup/restore-drill.sh`.
2. The script creates the disposable database → restores with
   `pg_restore` → runs `restore-verify.sql` (migrations through V14,
   core tables, seed, role shape, RLS flags, no plaintext columns) →
   spot-checks ownership isolation → curls `/api/v1/health` for
   `"status":"UP"` → drops the disposable database.

## Verification
All six script stages pass; `DRILL COMPLETE` printed; the scratch
database is gone; the primary development database was never touched.

## Failure handling
Any stage failure → drill FAILS (record which stage + output); do not
retry against anything but a fresh disposable target. Fix the
underlying cause (backup health, script, verification SQL), then
re-run the full drill from step 1.

## Rollback
Destroy any partial disposable database (`DROP DATABASE IF EXISTS
"<scratch>"`); development data is untouched by design (the drill
never writes to it).

## Evidence
Full script log, backup file reference, scratch ID, verifier +
reviewer names, date. Without this evidence the drill stays
unmarked — never mark it on configuration alone.
