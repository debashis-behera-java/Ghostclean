# Runbook: Health / Readiness Troubleshooting (local-only, ADR-036)

## Prerequisites
- Access to application logs (redacted), internal actuator,
  local Compose service status (`docker compose ps`).

## Signals and steps
- `/api/v1/health` not UP: check container status (`docker compose ps`)
  → JVM logs for fail-fast errors (missing `TOKEN_ENCRYPTION_KEY` /
  `JWT_SIGNING_KEY`) → local database connectivity (`db` service,
  `pg_isready`) → Redis reachability (fail-closed limiter means Redis
  loss surfaces as 429s, by design).
- Actuator surprising output: only `/actuator/health` is exposed with
  `show-details: never`; anything else is not served — check
  `application.yml` management block, not the network.
- Local DB unavailable: check the `db` container, volume health,
  credential match with `.env`; the application reconnects via Hikari
  — do not restart-loop faster than the pool backoff.
- 5xx spike with `INTERNAL`: correlate request IDs in structured
  logs; check for a bad deploy (ROLLBACK.md) before anything else.
- 429 spike: expected under Redis outage (fail-closed) or real abuse;
  distinguish via limiter backend + per-key distribution before
  changing budgets.

## Monitoring this runbook assumes (local)
App availability, local DB availability, auth failures, rate-limit
failures, backup freshness, restore-drill outcome, token encryption
failures, verification-mail seam state — observed in application logs
and Actuator health. No cloud dashboards exist.

## Evidence
Symptom, checks run, outputs, resolution, timestamps.
