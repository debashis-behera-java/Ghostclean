# GhostClean — System Architecture (Modular Monolith)

> Stack: Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA/Hibernate, PostgreSQL, Flyway, Maven, WebClient, OAuth2, JWT, Redis (jobs/cache), JUnit5/Mockito/Testcontainers. Frontend: React+TS+Vite+Tailwind+Router+TanStack Query+Axios. Infra: Docker Compose (app, postgres, redis, nginx).
> No microservices in v1.
> Phase 0 update (RESOLVED): email verification required (V1); degraded manual import path; V1 deletion MANUAL-DRAFT ONLY with no SEND scopes and no mail-send client; prod PostgreSQL assumed in EU region, cloud-portable (AWS/Azure/GCP/managed PG, no hard-coded provider); token encryption V1 = env key via `TokenCryptoProvider` interface, cloud KMS later.

## 1. Topology

```
Browser (React SPA)
  │ HTTPS (Nginx TLS terminate, security headers)
  ▼
Spring Boot monolith (:8080) ──► PostgreSQL (:5432)
  │                              Redis (:6379, jobs/locks/rate)
  └──► Google / Microsoft OAuth + Mail APIs (via WebClient, outbound only)
```

Single deployable. Nginx serves SPA + reverse-proxies `/api`. No direct DB exposure. All secrets via env (see §7). Production PostgreSQL is assumed hosted in an **EU region** (exact cloud/provider/region selected Phase 12); architecture stays portable across AWS, Azure, GCP, and managed-PostgreSQL providers — no provider SDKs or hard-coded endpoints in app code. Current state (ADR-036, local-only): the supported database is local Docker Compose PostgreSQL; no cloud production exists.

## 2. Module Boundaries (`com.ghostclean.*`)

| Module | Responsibility | Owns (entities/tables) | Must NOT |
|---|---|---|---|
| `auth` | register/login/refresh/logout/reset/lockout, JWT issue/rotation, password hashing | `users` (credentials part), refresh sessions | touch mailbox, detection logic |
| `user` | profile, settings, GhostClean account deletion orchestration | `users`, `user_settings` | issue tokens, call providers |
| `oauth` | provider abstraction, authorize/callback, token encrypt/store/refresh/revoke, disconnect | `oauth_connections` | parse email semantics |
| `mailbox` | provider pagination, metadata fetch, normalization, watermark persistence | `mailbox_scans`, `mail_scan_watermark` | score services |
| `scanner` | job orchestration: full/delta scan, progress, cancel, idempotency, backoff | `mailbox_scans` (run state) | render UI, send deletion |
| `detection` | rule engine + confidence scoring, candidate extraction | `service_evidence` (candidate) | group into accounts |
| `services` | service directory CRUD/seed/version, lookup by domain | `service_directory` | scan mail |
| `account` | entity resolution: evidence → discovered account, first/last seen, status | `discovered_services` | send requests |
| `risk` | risk scoring (configurable weights), band assignment | columns on `discovered_services` | claim breaches |
| `deletion` | request lifecycle, jurisdiction templates, deadlines, send-gate enforcement | `deletion_requests`, `deletion_events` | auto-send |
| `legal` | template registry, jurisdiction rules, disclaimer copy, deadline calc | `legal_templates` | decide legality |
| `workflow` | state machine guard (allowed transitions), deadline/reminder scheduling | — (policy over deletion tables) | store PII |
| `notification` | in-app/email reminders (deadlines, verification-required), preferences | `notifications` | send deletion requests |
| `audit` | append-only event log, redaction, query by user | `audit_logs` | log secrets |
| `common` | config, errors (`ApiError`), pagination, correlation ID, crypto, time | — | business logic |

**Dependency direction (allowed):** `scanner → mailbox → oauth`; `detection → services`; `account → detection,services,risk`; `deletion → account,services,legal,workflow`; `notification → deletion`; all → `audit, common`. **Forbidden:** `oauth → scanner/mailbox/detection`; `detection → account`; `deletion → scanner`; cycles across `account↔deletion` (use IDs + domain events). Enforce with ArchUnit test (`no cycles`, `oauth independent of scanner`).

Future extraction: each module has its own package + repository interfaces + domain events, so e.g. `scanner`/`detection` can become workers later without API rewrite.

## 3. Request Flows (happy path)

1. **Auth:** SPA → `POST /api/v1/auth/*` → `auth` (hash/verify, required email verification: unverified users get JWT but sensitive endpoints return `403 EMAIL_NOT_VERIFIED`; verify via `POST /auth/verify`, resend rate-limited) → JWT access 15m + rotating refresh 30d httpOnly cookie → `audit`.
2. **OAuth connect:** SPA → `GET /api/v1/oauth/{provider}/authorize` (state+PKCE, scope explainer) → provider → `GET /api/v1/oauth/{provider}/callback` (code→token server-side, encrypt, persist, fetch provider account id) → redirect SPA with status.
3. **Scan:** `POST /api/v1/scans` → `scanner` creates `mailbox_scans` row, enqueues job (DB + Redis lock) → `mailbox` pages via connector → normalize → `detection` emits evidence (idempotent on provider message id) → `account` upserts discovered service → `risk` scores → `audit` + progress via polling (`GET /scans/{id}`). **Degraded path:** `POST /api/v1/evidence/manual` (verified users only, rate-limited) → same normalize→detect→resolve with `source=manual`, labeled degraded, confidence-capped.
4. **Review → delete (V1 MANUAL-DRAFT ONLY):** `POST /discovered/{id}/review` → `POST /deletion-requests` (draft) → `POST …/approve` → `POST …/generate` (template preview) → `POST …/confirm` (second gate) → GhostClean renders **final draft** (no send) → user sends outside GhostClean → `POST …/mark-sent` (user attestation) → events + reminders. No mail-send client exists in V1; a future sender lives behind a separate permission/boundary (new ADR).

## 4. Key Design Decisions

- **Modular monolith, not microservices** (ADR-001): single DB schema with module-prefixed ownership; cross-module via application services, not direct repo access.
- **Metadata-first** (ADR-002): persist `messageId hash, sender domain/address hash?, subject (truncated), date, matched rules` — never bodies by default.
- **Two-gate send** (ADR-003): `approve` ≠ `confirm`; confirm requires re-auth (password or fresh token) + explicit recipient display.
- **Connector interface** (ADR-004): `EmailProviderConnector` isolates Google/Microsoft; core never branches on provider.
- **Rule engine first** (ADR-005): deterministic, explainable, testable; ML/LLM optional post-v1 as additive scorer.

## 5. Security Touchpoints

- JWT short-lived access + rotating refresh; Spring Security resource rules per `SECURITY_MODEL.md`; ownership checks at service layer (`requireOwner(userId, entity)`), not just controller.
- Token encryption: AES-256-GCM via `TokenCryptoProvider` interface. V1 impl = env key (`TOKEN_ENCRYPTION_KEY`, 32B base64, never committed/logged, fail-fast if missing/weak), per-row IV + `key_version` column; refresh on load; wipe on disconnect. Future impl = cloud KMS (AWS KMS / Azure Key Vault / GCP KMS), swappable without schema/API change (migration runbook Phase 11/12). Current state (ADR-036, local-only): the cloud-KMS future was retired; the env-key writer is the supported implementation.
- Outbound SSRF guard: connectors allow-list provider hosts; no user-supplied URLs fetched.
- Malicious email: treat sender/subject as untrusted; HTML-escape, never render raw; LLM (future) only on sanitized fields with injection tests.

## 6. Scalability & Jobs

- Async via Spring `@Async` / job table + Redis distributed lock (v1, no Kafka). Polling for progress; SSE/WebSocket deferred.
- Idempotency keys on scan ingest (`provider+messageId` unique) and deletion confirm (`Idempotency-Key` header).
- Readiness/liveness: `/actuator/health`; metrics via Micrometer; correlation ID `X-Request-Id` propagated to logs.

## 7. Environments

Local: Docker Compose (`app, db, redis, nginx`). Env: `DATABASE_URL, JWT_SIGNING_KEY, OAUTH_GOOGLE_{CLIENT_ID,SECRET,REDIRECT}, OAUTH_MS_{...}, TOKEN_ENCRYPTION_KEY, APP_BASE_URL, APP_REGION=eu (logical label; exact cloud region in Phase 12)`. Never commit `.env`; ship `.env.example` with placeholders only. See `BACKGROUND_JOBS.md` + Phase docs.

## 8. Acceptance

- [ ] Module graph documented + ArchUnit cycle test planned.
- [ ] All cross-module calls go through service interfaces; no provider logic outside `oauth`/`mailbox`.
- [ ] Two-gate deletion + metadata-first + ownership enforcement traceable to code scaffolding (Phase 1+).
