# GhostClean — PHASE_12 (Local-Only Architecture) — RETIRED AWS PATH, ADR-036 ACCEPTED

> **Status: SUPERSEDED — the AWS-dependent deployment path described in the
> original Phase 12 specification was retired in favor of the local-only
> architecture established by ADR-036 (ACCEPTED).**
> This document now records (a) what was implemented locally, (b) the local
> integration-test evidence, (c) the historical AWS design for the record,
> and (d) what was never verified. Nothing below claims an AWS production
> deployment or AWS validation. No AWS production environment has been
> verified. Privacy approval remains pending. The verification-mail
> provider remains deferred per ADR-035 (production stays `silent`).
> Phase 13 has NOT been started.
>
> Current local evidence: full Maven suite **444 tests, 0 failures,
> 0 errors, 0 skipped, BUILD SUCCESS** (local Testcontainers only).

## 1. Objective (current)

Operate GhostClean as a local-only service: the Phase 0–11 application
running on local Docker Compose with local PostgreSQL, local Redis,
local AES-256-GCM token encryption, PostgreSQL-native backup tooling
with a disposable restore drill, local Nginx edge profile, a published
privacy page route, working local verification seam, and a minimal
runbook set — without regressing any Phase 0–11 guarantee.

The original objective (production service on AWS eu-central-1) is
retained in §3 below as historical design only.

## 2. Scope (current, local-only)

Local Docker Compose deployment (`docker-compose.yml`: app, PostgreSQL
16, Redis 7, optional Nginx edge profile); local token encryption via
`EnvAesGcmTokenCrypto` behind `TokenCryptoProvider`; single
least-privilege DB role + RLS; `pg_dump` backups + disposable
`pg_restore` drill executing `infrastructure/backup/restore-verify.sql`;
local Nginx edge (`infrastructure/nginx.conf`); `/api/v1/privacy` route;
verification-mail seam unchanged (`silent`/`log` only); Actuator +
application-log monitoring; the ten local runbooks; full regression
proof. No cloud credentials anywhere in build or test.

## 3. Historical AWS design (retained for the record, NOT supported)

Phase 12 was originally specified (ADR-029 through ADR-035) as a
production deployment on **AWS eu-central-1 (Frankfurt)** with: Amazon
RDS for PostgreSQL (v16-compatible, encrypted at rest, automated
snapshots); AWS-KMS-backed token encryption with envelope encryption
behind `TokenCryptoProvider`; live KMS cutover with versioned
dual-read semantics (ADR-031); IAM/workload identity with
least-privilege key policy and key-use audit; secret-manager-held
production secrets; AWS-managed TLS edge with managed certificates;
RDS encrypted backups with RPO ≤ 24h / RTO ≤ 4h / 30-day retention as
proposed operational targets; an executed disposable restore drill;
CloudWatch-style monitoring/alerts; multi-stage production image with
a `docker-compose.prod.yml` overlay, `.env.prod.example` template,
`infrastructure/aws/rds.md`, and `infrastructure/nginx-prod.conf`.

That entire path was **retired by ADR-036**. The AWS overlay files were
deleted (`docker-compose.prod.yml`, `.env.prod.example`,
`infrastructure/aws/rds.md`, `infrastructure/nginx-prod.conf`). The KMS
production implementation and health indicator were removed; no AWS SDK
remains in the build. The items below were never evidenced and are not
claimed: AWS provisioning, live KMS cutover/rotation/rollback,
managed-TLS cutover, secret-manager wiring, monitoring/alerts live,
executed cloud restore drill.

## 4. Local architecture (supported)

Local Docker Compose is the supported deployment/demo environment
(ADR-036): PostgreSQL 16 (`db` service) holds all data; Redis 7
(`redis` service) backs the optional distributed rate limiter
(fail-closed when unreachable); the Spring Boot API connects over
Compose networking (`DATABASE_URL=jdbc:postgresql://db:5432/...`,
`REDIS_HOST=redis`); Nginx (`edge` profile,
`infrastructure/nginx.conf`) terminates the local edge with the
minimal security headers and keeps `/actuator/` internal. Secrets come
only from `.env` (placeholders in `.env.example`, never committed).

## 5. Local PostgreSQL

PostgreSQL 16 via Compose (`db` service, `pgdata` volume).
Flyway migrations V1–V14 apply unchanged on startup; migrations use a
separate privileged role, never the application role. Connection is
local Compose networking; credentials come from `.env`. The
least-privilege application role (`ghostclean_app`, V14) plus RLS is
exercised by the local RLS test suite.

## 6. Local token encryption

Token encryption is local AES-256-GCM via `EnvAesGcmTokenCrypto`
behind the existing `TokenCryptoProvider`
(`encrypt`/`decrypt`/`keyVersion`), keyed ONLY by `TOKEN_ENCRYPTION_KEY`
(32 bytes, Base64) with fail-fast startup on missing/weak keys. No KMS
implementation exists; local/dev/test never require cloud credentials.
Key operations are never exposed through public REST APIs. Raw key
material never appears in source, `.env`, images, logs, responses, or
rows. Rotation converges lazily on read plus the bounded,
operator-invoked `rotation-cli` profile — never an in-process
scheduler, `@Scheduled`, `@Async`, or background worker.

## 7. Local key rotation

Semantics unchanged from the Phase 11 design: readers accept any
decryptable version; writers stamp the active version; rotation = new
writer key + retained readers + lazy convergence + bounded offline
sweep (`SPRING_PROFILES_ACTIVE=rotation-cli` with
`crypto.rotation-cli.max-rows`, rerunnable until the sweep set is
empty, resumable by `key_version`); undecryptable rows never rewritten
(fail safe to reconnect); rotation never blocks unrelated users;
operators run it explicitly; audit records ids/versions/counts only.
The AWS-KMS cutover procedure from the original spec was retired with
the KMS implementation.

## 8. Secret management (local)

Local secrets (`TOKEN_ENCRYPTION_KEY`, `JWT_SIGNING_KEY`, DB
credentials, OAuth client secrets) live in `.env` (git-ignored) only —
never committed, never logged, never baked into images.
`.env.example` stays placeholders-only. Local/dev/test use generated
keys and synthetic test keys. Rotation procedures per the
SECRET_ROTATION runbook.

## 9. RLS application role

Single application role, least privilege (no `CREATE`/`DROP`/
`SUPERUSER`/owner rights); request context via
`SET LOCAL app.user_id`; migrations via a separate privileged role
(ADR-032). App authorization stays authoritative; RLS stays
defense-in-depth. The RLS matrix (A→A allowed, A→B and B→A denied,
system/audit paths functional) runs locally in the regression suite.

## 10. Backup architecture (local)

PostgreSQL-native local backups: `infrastructure/backup/backup-check.sh`
creates a `pg_dump` (custom format) backup and verifies the artifact
exists and is non-empty, failing clearly otherwise. No `aws` CLI, no
RDS snapshots, no cloud upload, no automatic deletion. The historical
RDS-encrypted-backup design (§3) was retired; RPO/RTO/retention figures
from the original spec were proposed targets only and are not claimed
as measurements.

## 11. Restore drill (local)

Executed (not configured-only) against a **disposable** local target
via `infrastructure/backup/restore-drill.sh`: creates a scratch
database (refusing production-like names), restores the `pg_dump`
backup with `pg_restore`, runs `restore-verify.sql` (migrations
through V14, core tables, seed, role shape, RLS flags, no plaintext
columns), spot-checks ownership isolation, curls `/api/v1/health` for
UP, then drops the scratch database. Evidence format: dated drill log
+ commands + outputs + verifier signature, stored with the release
checklist. The primary development database is never modified. No
cloud restore has been executed or is claimed.

## 12. TLS architecture (local)

Local Nginx edge (`infrastructure/nginx.conf`, Compose `edge`
profile): HTTP listener with the minimal security headers
(`nosniff`, `DENY`, `no-referrer`, Phase 11 CSP) preserved;
`/actuator/` returns 404 at the edge. Forwarded client information is
accepted only from the configured trusted edge (`TRUSTED_PROXIES`,
ADR-034); the application never trusts arbitrary `X-Forwarded-For`.
The AWS-managed-TLS design (§3) was retired; no managed certificates
exist and none are claimed.

## 13. Privacy page

Content source of truth: `PRIVACY_MODEL.md` §2–§5 (what is stored,
why, retention, deletion) — no invented claims, no certifications, no
legal guarantees. Interim publication mechanism is the
`/api/v1/privacy` route until the React page lands. **Publication
acceptance stays pending: no human approval evidence exists in the
repository.**

## 14. Verification email

Seam: `auth.VerificationEmailSender` (`silent`/`log` impls; `log` warns
at startup, banned in production) — distinct from deletion sending,
which stays manual-draft-only. No provider has been implemented
(DEFERRED per ADR-035 — production remains `silent`); a provider still
requires a new ADR first. Hard rules hold: no hard-coded vendor, no
credentials in source, no Gmail SEND, no Mail.Send, no embedded SMTP
secrets, verification-links-only usage.

## 15. Local Docker/image notes

Local development builds the API from `./backend` via Compose
(`docker compose up --build`). No production image pipeline, image
scanning, or provenance documentation exists or is claimed. The
production-overlay requirements from the original spec (managed-TLS
edge, secret-manager references, resource limits) were retired with
the overlay file.

## 16. Monitoring (local)

Application health (`/api/v1/health`, actuator exposes `health` only
with `show-details: never`), local PostgreSQL reachability, Redis
backend reachability (fail-closed events), backup freshness via
`backup-check.sh`, error-envelope 5xx rate, auth-failure anomalies —
observed in application logs. No cloud dashboards, no alert routing,
and no certificate-expiry monitoring exist or are claimed.

## 17. Runbooks (local set — ten required)

1. Deployment (local Compose). 2. Rollback. 3. Database backup
(`pg_dump`). 4. Database restore (disposable `pg_restore` drill).
5. Local key rotation. 6. Local key rollback. 7. Secret rotation
(`.env`-based). 8. Incident response. 9. Health/readiness
troubleshooting. 10. Security incident / credential compromise. Each
runbook: purpose, prerequisites, step-by-step commands, verification,
rollback/failure procedure, and evidence to retain.

## 18. Security requirements (current)

All Phase 0–11 guarantees hold locally: READ-only provider scopes
(Gmail read-only, Microsoft `Mail.Read`); manual-draft-only deletion;
no SEND capability (CI-asserted); ownership + 404-mask + verified
gates; token hygiene (now local AES-256-GCM); RLS + least-privilege
role; fail-closed limiter on Redis backend; minimal CSP + local Nginx
headers; redaction everywhere; no plaintext credentials; no cloud key
material in any API; secret/log/token leakage tests green.

## 19. Testing (local evidence only)

Env-key crypto tests (no cloud in unit tests); rotation/rollback/
partial-migration/old-key-compat/new-key-write tests; concurrency +
idempotency; leakage greps; RLS matrix re-run under the real
`ghostclean_app` role via Testcontainers; monitoring/actuator
configuration and local env/template parity asserted by file-content
tests; restore-drill evidence (manual, disposable local target); full
Maven regression green — locally **444 tests, 0 failures, 0 errors,
0 skipped, BUILD SUCCESS**. No cloud is touched by `mvn test`, and no
cloud evidence is claimed.

## 20. Rollback (local)

Every change rolls back: deployment (prior image + migration
compatibility); local key rotation (prior `TOKEN_ENCRYPTION_KEY`
before retirement; old-version material retained until gated); DB
role (revert grants/context plumbing; app-layer checks carry
authorization regardless); local Nginx (prior config); verification
mail (back to `silent` + manual onboarding note). The
ENVIRONMENT-BLOCKED convention is historical: it applied to the
retired AWS items and must not be read as current validation state.

## 21. Known limitations (all explicit)

The AWS deployment path is retired, not validated: every production
"is" from the original spec remains unbuilt, and the checklist below
is retired rather than passed. Frontend is placeholder-only, so
`/api/v1/privacy` is the interim publication mechanism (content
approval still pending). Live cloud cutover, cloud drill,
managed-TLS, cloud monitoring, and mail provider remain
unbuilt/unexecuted by design under ADR-036. Publisher verification
(Google/Microsoft) and password-reset endpoints remain out of scope.
Phase 13 remains NOT STARTED.

## PHASE 12 ACCEPTANCE CRITERIA (RETIRED — retained for the record, NOT marked pass)

The original 34-item AWS checklist is retired with the AWS deployment
path. It is reproduced here unmarked as a historical record; none of
its AWS-dependent items were evidenced and none are claimed:

- [ ] 1. AWS production region is documented as eu-central-1. (RETIRED)
- [ ] 2. Production database is managed PostgreSQL in the approved EU region. (RETIRED — local PostgreSQL instead)
- [ ] 3. Production deployment architecture is documented. (RETIRED — local Compose architecture documented instead)
- [ ] 4. Production secrets are not committed to source control. (HOLDS LOCALLY — `.env` git-ignored, placeholders only)
- [ ] 5. Production application uses least-privilege IAM/workload identity. (RETIRED — least-privilege DB role holds locally)
- [ ] 6. AWS KMS integration is implemented behind TokenCryptoProvider. (RETIRED — local AES-256-GCM instead)
- [ ] 7. Local/dev/test do not require AWS credentials. (HOLDS — no cloud credentials anywhere)
- [ ] 8. Existing encrypted records remain readable during cutover. (HOLDS LOCALLY — dual-read + lazy convergence)
- [ ] 9. New writes use the active encryption version. (HOLDS LOCALLY)
- [ ] 10. KMS rotation is documented and tested. (RETIRED — local key rotation documented and tested instead)
- [ ] 11. KMS rollback is documented and tested. (RETIRED — local key rollback documented instead)
- [ ] 12. No in-process scheduler is introduced. (HOLDS)
- [ ] 13. No @Scheduled/@Async/@EventListener production behavior is introduced. (HOLDS)
- [ ] 14. Production DB application role is least privilege. (HOLDS LOCALLY — V14 role + local RLS tests)
- [ ] 15. RLS remains enabled and cross-user access is denied. (HOLDS)
- [ ] 16. Owner access remains functional. (HOLDS)
- [ ] 17. Audit/system operations remain functional. (HOLDS)
- [ ] 18. Encrypted EU backups are configured. (RETIRED — local `pg_dump` backups instead)
- [ ] 19. An actual disposable restore drill is executed and evidenced. (LOCAL DRILL PROCEDURE EXISTS — cloud drill never executed)
- [ ] 20. Restore health verification passes. (LOCAL PROCEDURE EXISTS — cloud verification never executed)
- [ ] 21. Production TLS is HTTPS. (RETIRED — local HTTP edge; no managed TLS)
- [ ] 22. Forwarded headers are trusted only from the approved edge. (HOLDS LOCALLY — TRUSTED_PROXIES boundary)
- [ ] 23. HSTS/security headers are configured appropriately. (HOLDS LOCALLY — minimal headers in local Nginx)
- [ ] 24. Privacy page content is approved and published. (PENDING — no approval evidence)
- [ ] 25. Production verification email functionality is implemented behind the mail abstraction. (DEFERRED per ADR-035)
- [ ] 26. No Gmail SEND scope is introduced. (HOLDS)
- [ ] 27. No Microsoft Mail.Send scope is introduced. (HOLDS)
- [ ] 28. Production runbooks exist for deploy/rollback/backup/restore/KMS/secret rotation/incident response. (LOCAL RUNBOOK SET EXISTS — cloud procedures retired)
- [ ] 29. Monitoring and alert requirements are documented. (LOCAL MONITORING DOCUMENTED — cloud alerts do not exist)
- [ ] 30. Secret/log/token leakage tests remain green. (HOLDS)
- [ ] 31. Full Maven regression suite passes. (HOLDS — 444/0/0/0)
- [ ] 32. No Phase 0–11 security guarantees regress. (HOLDS)
- [ ] 33. No plaintext credentials are introduced. (HOLDS)
- [ ] 34. No Phase 13 work is started. (HOLDS)

No Phase 13 work is started.
