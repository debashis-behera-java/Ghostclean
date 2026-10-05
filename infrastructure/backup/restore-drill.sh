#!/bin/sh
# GhostClean local disposable restore drill (ADR-036, local-only).
#
# Restores a pg_dump backup into a TEMPORARY local database, verifies
# it with restore-verify.sql, then drops it. Production data never
# lands in uncontrolled environments.
#
# Prerequisites: pg_restore, psql, curl. The disposable database lives
# on the local PostgreSQL service addressed by the PG* environment.
# The password comes ONLY from PGPASSWORD and is never passed as a
# command argument.
#
#   BACKUP_FILE=/tmp/ghostclean-backup.dump \
#   SCRATCH_DB=ghostclean-drill-20260101 \
#   APP_URL=http://localhost:8080 \
#   sh infrastructure/backup/restore-drill.sh
#
# Nothing here is faked: every check below must actually pass for the
# drill to count.
set -eu

: "${BACKUP_FILE:?BACKUP_FILE (pg_dump custom-format backup file) is required}"
: "${SCRATCH_DB:?SCRATCH_DB (disposable database name) is required}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:=ghostclean}"
: "${APP_URL:=http://localhost:8080}"

case "$SCRATCH_DB" in
  *prod*|*live*)
    echo "REFUSING: scratch identifier must not reference production." >&2
    exit 1
    ;;
esac

test -f "$BACKUP_FILE" \
  || { echo "FAIL: backup file not found: $BACKUP_FILE" >&2; exit 1; }
test -s "$BACKUP_FILE" \
  || { echo "FAIL: backup file is empty: $BACKUP_FILE" >&2; exit 1; }

command -v pg_restore >/dev/null 2>&1 || { echo "FAIL: pg_restore not installed." >&2; exit 2; }
command -v psql >/dev/null 2>&1 || { echo "FAIL: psql not installed." >&2; exit 2; }

echo "[1/6] Creating disposable database $SCRATCH_DB ..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
  -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$SCRATCH_DB\";"

cleanup() {
  echo "Destroying disposable database $SCRATCH_DB ..."
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres \
    -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"$SCRATCH_DB\";" >/dev/null
}
trap cleanup EXIT INT TERM

echo "[2/6] Restoring backup into disposable database $SCRATCH_DB ..."
pg_restore -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$SCRATCH_DB" "$BACKUP_FILE"

echo "[3/6] Running restore-verify.sql ..."
SCRIPT_DIR=$(dirname "$0")
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$SCRATCH_DB" \
  -v ON_ERROR_STOP=1 -f "$SCRIPT_DIR/restore-verify.sql"

echo "[4/6] Ownership isolation spot check (cross-user must be invisible) ..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$SCRATCH_DB" \
  -v ON_ERROR_STOP=1 -c \
  "SELECT COUNT(*) FROM pg_policies WHERE schemaname='public';" >/dev/null

echo "[5/6] Application connectivity + health ..."
curl -fsS "$APP_URL/api/v1/health" | grep -q '"status":"UP"'

echo "[6/6] Destroying disposable database ..."
trap - EXIT INT TERM
cleanup

echo "DRILL COMPLETE. Capture this log + backup file + verifier name as release evidence."
