# GhostClean

> "Find what the internet remembers. Decide what it should forget."

GhostClean is a privacy engineering platform that helps users discover online
services plausibly linked to their email address (via explicitly authorized
OAuth mailbox-metadata access), review them, draft cautious deletion requests,
and track those requests. It is **not** a law firm or legal service, never
sends anything automatically, and never claims an account is "active" from
email evidence alone.

**Current state: Phases 1–11 implemented; local-only architecture established by ADR-036 (no AWS).**
Gmail/Graph metadata scanning, detection, service intelligence, privacy-request lifecycle, controlled dispatch, tracking, Microsoft connector, and hardening (RLS, fail-closed Redis option, lazy rotation, CSP) are implemented and covered by the current 444-test suite (444 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS). There is no AWS runtime or build dependency, no KMS implementation, and no AWS deployment path: PostgreSQL 16 and Redis 7 run locally via Docker Compose, token encryption is local AES-256-GCM (`EnvAesGcmTokenCrypto`), backups use `pg_dump`/`pg_restore`, and monitoring is Spring Boot Actuator + application logs. Privacy approval pending, production mail remains `silent`, Phase 13 not started.

## Architecture summary

- **Modular monolith** (single Spring Boot deployable, ADR-001): packages
  `auth, user, oauth, mailbox, scanner, detection, services, account, risk,
  deletion, legal, workflow, notification, audit, common` (+ `health`).
- **Metadata-first** (ADR-002): mailbox bodies are never stored.
- **Two-gate manual drafts** (ADR-003/ADR-014): V1 has no send capability.
- **READ-only OAuth in V1, degraded manual mode on denial** (ADR-006/ADR-013).
- **Local AES-256-GCM token encryption** (ADR-036): `TokenCryptoProvider` abstraction backed by `EnvAesGcmTokenCrypto`; the historical AWS-KMS path was retired, so there is no cloud key dependency.
- Full decisions: [`ARCHITECTURE_DECISIONS.md`](ARCHITECTURE_DECISIONS.md);
  specs: [`docs/`](docs/).

## Technology stack

Backend: Java 21, Spring Boot 3.3, Spring Security, Spring Data JPA /
Hibernate, PostgreSQL 16, Flyway, Redis 7, JUnit 5, Mockito,
Testcontainers, ArchUnit. Infra: Docker, Docker Compose, Nginx (optional).
Frontend (Phase 7): React, TypeScript, Vite, Tailwind.

## Repository structure

```
backend/            Spring Boot modular monolith (pom.xml, src/…)
  src/main/java/com/ghostclean/   auth user oauth mailbox scanner detection
                                  services account risk deletion legal
                                  workflow notification audit common health
  src/main/resources/             application*.yml, db/migration/V1__…
  src/test/                       444 tests, 0 failures, 0 errors, 0 skipped (Phases 1–11 + local-only architecture; per-phase counts in docs are historical)
frontend/           Phase 1 placeholder (React arrives Phase 7)
infrastructure/     nginx.conf (optional "edge" profile)
docs/               Phase 0 architecture (authoritative, do not overwrite)
ARCHITECTURE_DECISIONS.md         ADRs (authoritative)
docker-compose.yml  db + redis + api (+ optional nginx)
.env.example        placeholders only — never real secrets
```

## Prerequisites

- Java 21, Maven 3.9+, Docker + Docker Compose v2, Python 3 (key generation)
- Ports free: 8080 (api), 5432 (postgres), 6379 (redis)

## Local setup

```powershell
copy .env.example .env
# Generate the REQUIRED local encryption key (32 random bytes, Base64):
python3 -c "import secrets,base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"
# Paste the output as TOKEN_ENCRYPTION_KEY in .env

docker compose up --build -d
curl http://localhost:8080/api/v1/health
# {"status":"UP","service":"ghostclean-api","version":"0.2.0-phase2","region":"eu"}
```

With the edge proxy: `docker compose --profile edge up --build -d`
(API then also reachable via `http://localhost:8081`).

## Authentication setup

Required in `.env` (besides Phase 1 keys): `JWT_SIGNING_KEY` (min 32 chars;
Compose fails fast without it). Local http dev: `AUTH_COOKIE_SECURE=false`.
Local verification links: `VERIFICATION_MAIL_MODE=log` (dev only — never in
production; default `silent` delivers nothing).

Local registration flow:

```powershell
# 1. register (always 201, even for duplicates — no oracle)
curl -X POST http://localhost:8080/api/v1/auth/register `
  -H 'Content-Type: application/json' `
  -d '{"email":"you@example.com","password":"a-strong-password-here"}'
# 2. read the dev verification link from app logs
docker logs ghostclean-api-1 | Select-String "Verification link"
# 3. verify (204), then login (200 + httpOnly refresh cookie)
curl -X POST http://localhost:8080/api/v1/auth/login `
  -H 'Content-Type: application/json' `
  -d '{"email":"you@example.com","password":"a-strong-password-here"}'
# 4. call with the access token; refresh via cookie; logout revokes it
```

Login works while unverified but returns `emailVerified:false`; sensitive
features gate on verification later. Five failed logins lock an account for
15 minutes (423). Full flow: [`docs/PHASE_02.md`](docs/PHASE_02.md).

## Environment variables

See [`.env.example`](.env.example). Categories: `DATABASE_*`, `REDIS_*`,
`APP_*` (`APP_REGION=eu`), `TOKEN_ENCRYPTION_KEY` (required, fail-fast),
`JWT_SIGNING_KEY` (required since Phase 2, fail-fast), `AUTH_COOKIE_SECURE`,
`VERIFICATION_MAIL_MODE`, optional `AUTH_*` TTL/rate-limit overrides,
commented `OAUTH_*` placeholders (Phase 3+ — never production credentials).

## Database migration

Flyway runs automatically on startup from
`backend/src/main/resources/db/migration/`. V1 created the foundation
(`users`, `email_verification_tokens`, `user_settings`, `refresh_sessions`,
`audit_logs`); V2 added lockout/last-login columns; V3 dropped the
`audit_logs→users` FK so audit commits independently (see
[`docs/PHASE_02.md`](docs/PHASE_02.md)). Business tables arrive in their own
phases.

## Test commands

```powershell
cd backend
mvn -q test
```

Integration tests use Testcontainers (Docker required) with an obviously
synthetic in-code key. Secret hygiene: `.gitleaks.toml` +
`gitleaks detect --source . -v` (if gitleaks is installed).

## Health endpoint

`GET /api/v1/health` → `200 {"status":"UP","service":"ghostclean-api",…}`.
Returns static facts only — no secrets, tokens, or connection details.
`GET /api/v1/users/me` requires a Bearer access token and returns the
caller's own profile (no `{id}` route exists by design).

## Security notes

- Passwords: BCrypt hashes only. Refresh/verification artifacts: hashes only.
- Access: 15m JWT (minimal claims, env secret, fail-fast); refresh sessions
  rotate, reuse revokes the chain, logout revokes. Lockout after 5 fails.
- OAuth tokens (future): AES-256-GCM via env key, per-row IV, `key_version`
  column; app refuses to boot without a ≥256-bit key; keys never logged.
- API: stateless, CSRF-disabled-by-design for token auth, unauthenticated →
  401. Correlation id on every response (`X-Request-Id`).
- Logs carry the request id and never secrets (see `RequestIdFilter`).
- V1 provably contains **no** mail-send capability (`NoSendCapabilityTests`).

## Phase 2 scope

Authentication: oracle-free registration, required email verification
(hash-stored, expiring, single-use tokens; silent/log mail seam, no
provider), JWT access + rotating refresh sessions, logout, per-IP budgets +
persisted lockout, principal-only `/users/me`, audit trail with secret
denylist, V2/V3 migrations, 77 green tests. See
[`docs/PHASE_02.md`](docs/PHASE_02.md).

## Phase 3 scope

Google OAuth identity-only connections: authorize URL (identity scopes,
state + PKCE), unauthenticated callback with state-bound ownership,
encrypted connection storage, safe status list, disconnect with
best-effort revocation + wipe, forced refresh, V4 migration, scripted-
provider tests (no live Google), 104 green tests. See
[`docs/PHASE_03.md`](docs/PHASE_03.md) and
[`docs/GOOGLE_OAUTH_SETUP.md`](docs/GOOGLE_OAUTH_SETUP.md).

## Implementation status

Implemented (Phases 1–11 + ADR-036 local-only): Google OAuth + Gmail read-only scanning + detection, service intelligence/directory, privacy-request draft/approve/dispatch/tracking, Microsoft/Graph connector (delegated `Mail.Read`), RLS/CSP/rate-limit hardening, lazy re-encrypt-on-read, `silent`/`log` verification seam + `sink` dispatch provider only — all on local Docker Compose (PostgreSQL 16, Redis 7, Nginx edge profile) with local AES-256-GCM encryption, `pg_dump`/`pg_restore` backup scripts, Actuator + log monitoring, and ten local runbooks. Full local suite 444/0/0/0 BUILD SUCCESS (local Testcontainers only).
Still absent: privacy-content human approval (pending — no approval evidence in-repo), production verification-mail provider (DEFERRED per ADR-035, production `silent`), real OAuth credentials, publisher verification (Google/Microsoft), React business UI. AWS deployment is explicitly NOT SUPPORTED (no RDS/KMS/ACM/CloudWatch/Secrets Manager wiring, retired by ADR-036); no AWS production environment has been verified. Next: privacy approval + mail-provider ADR when human review requires them; Phase 13 explicitly NOT STARTED.
Historical per-phase test counts below are phase-completion records; current suite is 444 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESS).
#   P r i v a c y M a s k  
 