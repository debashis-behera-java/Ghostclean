# Runbook: Database Backup (local-only, ADR-036)

## Prerequisites
- Local Docker Compose stack running (`docker compose up -d`): `db`
  (PostgreSQL 16) healthy.
- `pg_dump` installed where the script runs.
- `BACKUP_FILE` output path chosen (must be writable).

## Steps
1. Run `infrastructure/backup/backup-check.sh` with the standard `PG*`
   environment (password ONLY via `PGPASSWORD`, never a CLI argument):
   `BACKUP_FILE=/tmp/ghostclean-backup.dump PGHOST=localhost PGPORT=5432 PGDATABASE=ghostclean PGUSER=ghostclean PGPASSWORD=<from-.env> sh infrastructure/backup/backup-check.sh`.
2. The script creates a `pg_dump` custom-format backup and verifies the
   file exists and is non-empty.
   Expected: `OK: PostgreSQL backup present and non-empty: <path>`.

## Verification
Backup file present, non-empty, timestamped within the expected window.

## Failure handling
Missing/empty backup or missing `pg_dump` → the script fails clearly
(`FAIL` + non-zero exit). Treat a persistently failing backup check as
an incident (INCIDENT_RESPONSE.md): do not rotate keys, do not retire
key material until backups are healthy.

## Rollback
Not applicable (read-only verification plus a fresh artifact). Fix
forward by restoring a healthy local database/service, then re-verify.

## Evidence
Script output, backup file path + size + timestamps, verifier name.
