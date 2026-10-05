# Runbook: Local Deployment (Docker Compose, ADR-036)

## Prerequisites
- Release commit built green (`mvn clean test`: 444 tests, 0 failures,
  0 errors, 0 skipped).
- `.env` filled from `.env.example` (placeholders replaced locally;
  never committed). Required: `TOKEN_ENCRYPTION_KEY` (32 random bytes,
  Base64), `JWT_SIGNING_KEY` (min 32 chars).
- Previous local backup exists (see DATABASE_BACKUP.md).
- Ports free: 8080 (api), 5432 (postgres), 6379 (redis).

## Steps
1. `copy .env.example .env` (first time only) and fill the required
   secrets locally.
2. `docker compose up --build -d` (add `--profile edge` for the local
   Nginx proxy on `http://localhost:8081`).
3. Flyway migrations apply automatically on API startup; confirm
   `flyway_schema_history` max version matches the release (V14).
4. Verify `/api/v1/health` returns `{"status":"UP",...,"region":"eu"}`.
5. Re-run the RLS matrix (A→A allowed, A→B/B→A denied) and one audit-write
   smoke test; confirm actuator stays edge-blocked (`/actuator/` → 404
   through Nginx).
6. Confirm `TOKEN_CRYPTO_PROVIDER` value (`env`) and `key_version`
   distribution
   (`SELECT key_version, COUNT(*) FROM oauth_connections GROUP BY 1`).

## Verification
Health UP, migration version correct, RLS matrix green, no error-envelope
spike in logs.

## Failure handling
Any verification failure → ROLLBACK.md immediately. Never debug forward on
a half-deployed release.

## Rollback
See ROLLBACK.md (prior image/commit + migration compatibility note).

## Evidence
Commit SHA, migration version, health output, RLS matrix
result, deployer + reviewer names, timestamps.
