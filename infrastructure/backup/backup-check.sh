#!/bin/sh
# GhostClean local backup check (ADR-036, local-only).
#
# Creates a PostgreSQL backup with pg_dump (custom format) and verifies
# the backup file exists and is non-empty. Intended for scheduled
# monitoring AND the restore drill preconditions.
#
# Connection comes from the standard PG* environment (PGHOST, PGPORT,
# PGDATABASE, PGUSER); the password comes ONLY from PGPASSWORD and is
# never passed as a command argument.
#
#   BACKUP_FILE=/tmp/ghostclean-backup.dump \
#   PGHOST=localhost PGPORT=5432 PGDATABASE=ghostclean PGUSER=ghostclean \
#   PGPASSWORD=changeme-local-only \
#   sh infrastructure/backup/backup-check.sh
#
# Nothing here touches a network beyond the local PostgreSQL service.
set -eu

: "${BACKUP_FILE:?BACKUP_FILE (output path for the pg_dump backup) is required}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGDATABASE:=ghostclean}"
: "${PGUSER:=ghostclean}"

command -v pg_dump >/dev/null 2>&1 || { echo "FAIL: pg_dump not installed." >&2; exit 2; }

echo "Creating PostgreSQL backup of $PGDATABASE ($PGHOST:$PGPORT) ..."
pg_dump -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
  --format=custom --file="$BACKUP_FILE"

test -f "$BACKUP_FILE" \
  || { echo "FAIL: backup file was not created: $BACKUP_FILE" >&2; exit 1; }
test -s "$BACKUP_FILE" \
  || { echo "FAIL: backup file is empty: $BACKUP_FILE" >&2; exit 1; }

echo "OK: PostgreSQL backup present and non-empty: $BACKUP_FILE"
echo "NOTE: this check gates the restore drill preconditions. Capture output as evidence."
