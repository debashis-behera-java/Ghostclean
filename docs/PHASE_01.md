# GhostClean — PHASE_01 (Repo & Infra Foundation) — IMPLEMENTED

> Implemented 2026-09-11. Phase 0 spec above is preserved; the record below
> describes what was actually built. No Phase 2 work started.

## IMPLEMENTATION RECORD

### Implemented components
- Spring Boot 3.3.5 / Java 21 app (`GhostCleanApplication`), 16 packages
  (`com.ghostclean.*`: all 15 approved modules + `health`), each with
  ownership `package-info`.
- Security defaults: stateless, CSRF off by design, public
  `/api/v1/health`, `/actuator/health`, `/api/v1/auth/**`; everything else
  authenticated → anonymous gets 401 (verified live).
- `TokenCryptoProvider` seam + `EnvAesGcmTokenCrypto` (AES-256-GCM, fresh IV,
  fail-fast on missing/weak key, never logs key). No KMS SDK.
- `ApiError` envelope + `GlobalExceptionHandler` (generic 500s, no traces) +
  `RequestIdFilter` (accept/echo `X-Request-Id`, MDC, 128-char bound).
- `GET /api/v1/health` → 200 `{"status":"UP","service":"ghostclean-api",
  "version","region"}` (static facts only); `GET /api/v1/users/me` stub
  (401 until Phase 2 auth).
- Entities+repos (schema only, no workflows): `User`, `EmailVerificationToken`
  (hashes only), `UserSettings`, `RefreshSession` (hashes only), `AuditLog`
  (redacted JSONB metadata).
- V1 migration `V1__baseline_foundation.sql`: citext+pgcrypto, 5 foundation
  tables, checks, indexes (incl. partial unique active-token), `updated_at`
  triggers. No business tables (asserted by test).
- Infra: `docker-compose.yml` (db/redis/api + optional `edge` nginx),
  `backend/Dockerfile`, `infrastructure/nginx.conf` (actuator blocked),
  `.env.example` (placeholders, `APP_REGION=eu`), `.gitignore`,
  `.gitleaks.toml`, `README.md`, `frontend/` placeholder.
- Deps: web, data-jpa, security, validation, actuator, flyway-core +
  flyway-database-postgresql, pg driver, testcontainers 1.21.4, archunit.
  No mail, no cloud SDKs, no Kafka/K8s.

### Files/modules created
`backend/pom.xml`, 16× `package-info.java`, `GhostCleanApplication`,
`common/{ApiError,RequestIdFilter,GlobalExceptionHandler}`,
`common/crypto/{TokenCryptoProvider,EnvAesGcmTokenCrypto}`,
`auth/{PasswordConfig,SecurityConfig}`, `user/*` (4 entities + 4 repos),
`audit/*`, `health/{HealthController,UsersMeController}`,
`application{,-local,-test}.yml`, `V1__baseline_foundation.sql`,
12 test classes, `Dockerfile`, `docker-compose.yml`, `.env.example`,
`.gitignore`, `.gitleaks.toml`, `infrastructure/nginx.conf`,
`frontend/{index.html,README.md}`, `README.md`.

### Database migrations
V1 only: `users`, `email_verification_tokens`, `user_settings`,
`refresh_sessions`, `audit_logs`. Validated live via container logs
("Successfully applied 1 migration") and `FlywayMigrationTests`.

### Configuration
Env-driven (`DATABASE_URL/USERNAME/PASSWORD`, `TOKEN_ENCRYPTION_KEY`
required, `APP_REGION=eu`); `ddl-auto: validate`; UTC Jackson/JDBC;
actuator exposes `health` only.

### Commands (executed)
`mvn -q test` (green, 34/34); `mvn -q package -DskipTests`;
`docker compose config` (validates; fails fast without key);
`docker compose up --build -d` (custom ports 5433/6380/8082 to avoid the
occupant on 5432); live `GET /api/v1/health` → 200.

### Tests
11 classes / 34 tests, all passing (surefire): context-load, health (public,
secret-free, 401-gate, id-echo), error envelope (401-first ordering + unit
envelope shape), request-id filter, crypto round-trip/tamper/IV-freshness,
missing-key fail-fast (4), BCrypt wiring, Flyway (applied/foundation-present/
business-absent/verification-columns), ArchUnit (no cycles, oauth
independence), no-send-capability source scan, `.env.example` placeholders.

### Acceptance
- [x] `mvn -q test` green on Java 21; Compose healthy; health 200.
- [x] Flyway migrate+validate green; `.env.example` placeholder-only.
- [x] ArchUnit + secret-pattern scan green. No OAuth/scan/detection/deletion
  logic (business-table-absence + no-send scans enforce it).

## GOAL
Buildable modular-monolith skeleton: `mvn test` green, `docker compose up` serves health + SPA placeholder, Flyway baseline applied, CI secret-scan passes.

## FEATURES
- Maven (Java 21, Spring Boot 3.x): root `pom.xml` + `com.ghostclean` packages (all 15 modules as packages with `package-info.java` ownership notes).
- `common.crypto.TokenCryptoProvider` interface + `EnvKeyProvider` (AES-256-GCM, reads `TOKEN_ENCRYPTION_KEY` 32B base64, fail-fast if missing/weak; never logs key). KMS impl explicitly out of scope (design seam only).
- Spring Security permit-list: `/actuator/health`, `/api/v1/auth/*` public; rest authenticated (stub, incl. `EMAIL_NOT_VERIFIED` error-code plumbing for Phase 2 gates).
- Common: `ApiError` envelope, `X-Request-Id` filter, JPA auditing, validation starter.
- Infra: `Dockerfile`, `docker-compose.yml` (app:8080, postgres:16, redis:7, nginx), `.env.example` (placeholders only: `DATABASE_URL, JWT_SIGNING_KEY, TOKEN_ENCRYPTION_KEY=<32B base64 placeholder>, APP_BASE_URL, APP_REGION=eu`, plus commented `OAUTH_GOOGLE_*`/`OAUTH_MS_*` placeholders with "NO real credentials" warning), `nginx.conf` (TLS-ready, security headers).
- Actuator health/liveness/readiness.

## FILES/MODULES
`pom.xml, src/main/java/com/ghostclean/{auth,user,oauth,mailbox,scanner,detection,services,account,risk,deletion,legal,workflow,notification,audit,common}/`, `src/main/resources/application.yml, db/migration/V1__baseline_users.sql`, `Dockerfile, docker-compose.yml, .env.example, nginx.conf, .gitignore, README.md`.

## DATABASE CHANGES
`V1`: `users (incl. email_verified BOOL NOT NULL DEFAULT FALSE), email_verification_tokens (id, user_id FK cascade, token_hash UNIQUE, expires_at, used_at, created_at)`, `user_settings, refresh_sessions, audit_logs` (minimal cols per `DATABASE_DESIGN.md`) + `CITEXT` ext + `updated_at` trigger. `oauth_connections` comes Phase 3 (with `token_iv`, `key_version`).

## API CHANGES
`GET /actuator/health`, `GET /api/v1/health` (`{status, version}`), `GET /api/v1/users/me` (401 without JWT, stub owner).

## TESTS
Context-load, Flyway-on-Testcontainers, ArchUnit (no cycles; `oauth` ↛ `scanner`), 404-mask placeholder, secret-scan CI (gitleaks) green, startup-fails-without-`TOKEN_ENCRYPTION_KEY` test, no-SEND-scope / no-mail-send-client assertion stub (fails build if `gmail.send`/`Mail.Send`/SMTP-send bean appears in V1 code).

## SECURITY
No secrets committed; least-privilege PG role; security headers via nginx; redaction filter stub with failing test if `token` logged.

## ACCEPTANCE CRITERIA
- [ ] `mvn -q test` passes on Java 21; `docker compose up --build` healthy; `GET /api/v1/health` 200.
- [ ] Flyway `migrate` + `validate` green; `.env.example` has zero real secrets.
- [ ] ArchUnit + secret-scan green. No OAuth/scan/detection logic present (OOS respected).

## OUT OF SCOPE
Real auth JWT logic, verification mailer (Phase 2), OAuth, SEND scopes / mail sending (FORBIDDEN in V1), scanning, detection, deletion, React app (placeholder `index.html` only). No real OAuth credentials. No cloud KMS.

## Verify commands (Phase 1, for later)
```powershell
java -version; mvn -version; docker --version
mvn -q test
docker compose up --build -d; curl http://localhost:8080/api/v1/health
```
