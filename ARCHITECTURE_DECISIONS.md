# GhostClean — Architecture Decision Records (ADRs)

## ADR-001: Modular monolith, no microservices (v1)
Context: small team, strong consistency needs (scan→evidence→account→request). Decision: single deployable, package-modular (`com.ghostclean.*`), ArchUnit-enforced boundaries, extractable later. Consequences: simpler ops; disciplined interfaces required.

## ADR-002: Metadata-first, no body storage
Context: highly sensitive mail. Decision: persist only normalized metadata + hashes; no body column exists. Consequences: smaller blast radius; snippet feature requires new ADR + opt-in.

## ADR-003: Two-gate deletion send (approve + confirm with re-auth)
Context: irreversible, legally sensitive sends. Decision: `approve` (intent) ≠ `generate` (preview) ≠ `confirm` (send, re-auth + Idempotency-Key). No single-call send API. Consequences: impossible to auto-send by bug; slightly longer UX.

## ADR-004: Provider abstraction (`EmailProviderConnector`)
Context: Google now, Microsoft next, more later. Decision: interface + per-provider impls; core provider-agnostic; scopes per provider config. Consequences: Phase 10 adds connector only.

## ADR-005: Rule engine first, LLM never in critical path
Context: explainability + determinism + prompt-injection risk. Decision: weighted rules + versioned YAML; ML/LLM optional additive scorer (capped ±15) behind flag, sanitized inputs only. Consequences: testable baseline; AI can't trigger sends.

## ADR-006: Minimum OAuth scopes; V1 READ-only, NO SEND (RESOLVED)
Context: Google verification risk + least privilege + approved V1 manual-draft-only. Decision: V1 requests READ bundle only (`gmail.readonly` / `Mail.Read`); `gmail.send` / `Mail.Send` MUST NOT be requested or configured active in V1 (CI-enforced); denied/restricted metadata access → labeled degraded manual mode, never bypass or wider scopes. Future sending needs separate grant + boundary + ADR. Consequences: v1 sends are user-sent manual drafts; trust-preserving.

## ADR-007: AES-256-GCM token encryption via interface; V1 env key, KMS later (RESOLVED)
Context: token theft = inbox access; approved V1 env-key + future cloud KMS. Decision: `TokenCryptoProvider` interface; V1 `EnvKeyProvider` reads 32B-base64 `TOKEN_ENCRYPTION_KEY` (never committed/logged, fail-fast if missing/weak), per-row IV + `key_version`, wipe on disconnect; future `KmsKeyProvider` (AWS KMS / Azure Key Vault / GCP KMS) swaps in with no schema/API change. Consequences: rotation/re-encrypt job + KMS cutover runbook needed (Phases 11/12).

## ADR-008: PostgreSQL + Flyway + Testcontainers; Redis for jobs/locks
Context: relational integrity + async without Kafka. Decision: PG versioned migrations; Redis locks/semaphores/buckets only. Consequences: no event bus; extraction later via domain events.

## ADR-009: Frontend — React+TS+Vite+Tailwind+Router+TanStack Query
Context: trust-focused dashboard, typed contracts. Decision: SPA behind Nginx, Bearer API, polling for scan progress, Recharts for risk/counts, Lucide icons. No dark patterns; all email strings escaped. Routes: `/, /login, /register, /connect, /dashboard, /accounts, /accounts/:id, /requests, /requests/:id, /activity, /connections, /settings, /privacy`.

## ADR-010: Observability — structured JSON logs + correlation IDs, no PII
Context: debug without leaking. Decision: `X-Request-Id` end-to-end, Micrometer metrics, `/actuator/health`, redaction filter + secret-scan CI. Consequences: request tracing; log-volume discipline.

## ADR-011: Local dev — Docker Compose + `.env.example`, Java 21 + Node 20 LTS
Context: reproducible onboarding. Decision: `app/db/redis/nginx` Compose; Maven wrapper; Node 20 + npm; PG16/Redis7 pinned. `java -version / mvn -version / node -version / docker --version` documented in PHASE_01.

## ADR-012: Required email verification for V1 (RESOLVED)
Context: privacy app; account ownership must be proven before OAuth/scan/deletion. Decision: register → `email_verified=false` + 24h single-use token; sensitive endpoints enforce `403 EMAIL_NOT_VERIFIED`; resend rate-limited; verification mailer is transactional (Phase 2), not a SEND-scope dependency. Consequences: extra Phase 2 endpoints + gate tests; slight onboarding friction accepted.

## ADR-013: Degraded manual-forward/import mode (RESOLVED)
Context: Gmail permission may be denied/restricted/unverified. Decision: no bypass, no wider scopes, no fabricated access; verified users may submit registration-email metadata (`source=manual`) through the same normalize→detect→resolve pipeline, labeled degraded, confidence-capped, rate-limited. Consequences: `service_evidence.source` column + manual endpoint + abuse controls.

## ADR-014: V1 manual-draft-only deletion (RESOLVED)
Context: irreversible, legally sensitive sends. Decision: GhostClean creates the FINAL DRAFT; the user sends it outside GhostClean and attests via `mark-sent`. No SMTP/API mail-send client and no SEND scopes exist in V1 (CI-asserted). Future sending = separate permission + boundary + ADR. Consequences: `confirm` yields a draft, not a send; deadlines run from attested `mark-sent`.

## ADR-015: EU production data residency, cloud-portable (RESOLVED)
Context: GDPR posture; avoid lock-in. Decision: production PostgreSQL + backups assumed in an EU region; no hard-coded cloud provider — portable across AWS/Azure/GCP/managed PG; exact provider/region chosen Phase 12. Consequences: residency note in privacy/arch docs; region selection + backup-drill in Phase 12 acceptance.

## ADR-016: Token-key management — V1 env key, prod KMS (RESOLVED)
Context: need encrypted tokens now, managed keys later. Decision: V1 `TOKEN_ENCRYPTION_KEY` env-only (never hard-coded/committed/logged); production migrates to AWS KMS / Azure Key Vault / GCP KMS via provider swap (no re-architecture). KMS NOT implemented in Phase 0/V1. Consequences: Phase 1 fail-fast + secret-scan tests; Phases 11/12 rotation + cutover runbooks.

## Resolved Phase 0 decisions (all five APPROVED choices applied)
1. Email verification: REQUIRED for V1 (ADR-012). 2. Gmail-denied: degraded manual mode, no bypass (ADR-013). 3. V1 sending: MANUAL-DRAFT ONLY, no SEND scopes (ADR-014 + ADR-006). 4. Prod DB: EU region, provider-portable (ADR-015). 5. Token keys: V1 env key, future KMS (ADR-016 + ADR-007).

## Phase 2 ADRs

## ADR-017: Oracle-free registration (identical 201 for new and duplicate)
Context: account-enumeration resistance for a privacy product. Decision: register returns the same 201 `{message, verificationRequired}` whether the address is new, taken-unverified (re-issues token), or taken-verified (no side effects). Unknown-email login costs the same as known (dummy-hash compare). Consequences: clients cannot probe address existence; support/debugging relies on user ids, not emails.

## ADR-018: HS256/HS512 JWT access + rotating opaque refresh (hash-stored)
Context: short-lived access with server-side session control. Decision: JJWT-signed JWT (15m, minimal claims, env secret ≥256-bit fail-fast) + 256-bit opaque refresh tokens whose SHA-256 only is stored; rotation on every use; reuse ⇒ whole-chain revocation; logout revokes. BCrypt (default cost) for passwords — no new KDF dependency. Consequences: access tokens expire naturally (no revocation list); sessions auditable via `rotated_from` chain.

## ADR-019: Single-instance in-memory rate limiting (Redis later)
Context: stop trivial brute force without over-engineering. Decision: sliding-window limiter in-process with documented budgets (login/register 5/min/IP, resend 5/day/email, …) + persisted per-account lockout (5 fails → 15m). Redis-backed enforcement deferred to scale-out. Consequences: budgets reset on restart and don't span replicas — acceptable for the monolith stage; must revisit before multi-instance deploy.

## ADR-020: Independent audit commits; FK-free audit trail
Context: failure audits (bad login, token reuse) must survive their own transaction's rollback, and audit must reference rows invisible to its transaction. Decision: audit writes are `REQUIRES_NEW`; mutating-then-throwing service paths commit state via `REQUIRES_NEW` boundaries first; `audit_logs→users` FK removed (V3), nullification enforced by the account-deletion job. Consequences: audit rows always land; orphan-user-id references possible only transiently and are resolved by the owning transaction's commit.

## ADR-021: Identity-only Google connect; mailbox scope wire-closed (Phase 3)
Context: Google verification risk + least privilege + the Phase 3 mandate to build connection without mailbox access. Decision: Phase 3 requests `openid email profile` only and resolves identity via OIDC userinfo; the mailbox-read scope exists solely as an empty-by-default `oauth.google.mailbox-scope` property that Phase 4 must set deliberately (build tests assert the Phase 3 URL carries no mailbox literal). No send/compose/modify scope exists in code, config, or tests. Consequences: reconnect uses `prompt=consent` + `access_type=offline` so refresh material is reliably issued; Google verification for mailbox scopes remains future work.

## ADR-022: Unauthenticated OAuth callback with state-bound ownership (Phase 3)
Context: the provider redirects a bare browser; no GhostClean JWT is present, yet the connection must land on the right account with no oracle. Decision: the callback consumes a single-use, expiring, hashed state row and resolves ownership server-side; success/denial end in a 302 carrying status codes only, while untrusted states render 400 JSON (no open redirect, no existence leak). Consequences: replayed/foreign/expired states fail safe; the SPA needs only the documented query params.

## Remaining open items (NOT architectural blockers; decided in later phases)
1. Exact EU cloud provider + region: RESOLVED Phase 12 — AWS eu-central-1 (ADR-029).
2. KMS vendor selection: RESOLVED Phase 12 — AWS KMS (ADR-030); live cutover per ADR-031.
3. Transactional verification-mail provider: Phase 2 shipped the seam with `silent`/`log` modes only; provider selection still open — Phase 12 requires a new implementation ADR before any provider code (ADR-035; must not depend on user mailbox SEND scopes).
4. Manual-import format details (form-only V1 vs optional `.eml`-header upload later; bodies never stored either way).
5. Password-reset endpoints (listed in API_SPEC table, deferred; needs ADR + mailer).

## Phase 9 ADRs (human architecture decisions)

## ADR-023: V1 tracking anchor is dispatch `accepted_at`; reminder calculation without a scheduler (Phase 9)
Context: V1 is manual-draft-only (ADR-006/ADR-014) and has no trusted in-application mark-sent attestation, so no `sent_at`/mark-sent event may be fabricated for deadline math; meanwhile the build forbids `@Scheduled`/`@Async`/`@EventListener` in production code. Decision: (a) Phase 9 anchors deadlines/reminders at dispatch `accepted_at`, defined strictly as "the time the request was accepted/handed off for the user's external action" — proving nothing about external delivery, receipt, processing, deletion, or legal deadlines, with all UI/docs wording preserving that distinction; (b) Phase 9 ships a deterministic, side-effect-free reminder calculation boundary (`ReminderCalculation`) with no in-process scheduler, threads, or polling — a future external scheduler may invoke it. Consequences: `DELETION_WORKFLOW.md`'s mark-sent-based deadline language is superseded for V1 (recorded here and in `docs/PHASE_09.md`); reminder delivery itself remains future work.

## Phase 11 ADRs (human architecture decisions — security hardening)

## ADR-024: Lazy re-encrypt-on-read, no re-encryption job (Phase 11)
Context: token-key rotation needs a migration path for existing ciphertext, but the build forbids `@Scheduled`/`@Async`/`@EventListener`/schedulers/threads in production code (ADR-023, `NoDeletionActionTests`). Decision: V1 uses lazy re-encrypt-on-read — on read, decrypt with the stored `key_version`; if it differs from the current version, re-encrypt with the current key and persist new ciphertext + current `key_version` atomically; the operation is idempotent, never logs plaintext, and preserves the `TokenCryptoProvider` abstraction. No in-process re-encryption job exists. Consequences: unused rows stay on the previous version until accessed (documented limitation); a complete offline rotation remains a future operational/runbook procedure, not an application job.

## ADR-025: KMS readiness is documentation/design only (Phase 11)
Context: production needs a KMS path (ADR-007/ADR-016) but no KMS vendor has been selected (open item) and V1 must stay provider-portable with no new secrets. Decision: Phase 11 documents key rotation, `key_version` semantics, cutover/rollback procedures, failure handling, operational responsibilities, and the future KMS integration boundary (`TokenCryptoProvider` → future KMS-backed implementation, no schema/API change where possible). No vendor is chosen, no vendor SDK is added, no `KmsKeyProvider` implementation is created, and KMS is never claimed as implemented. Consequences: cutover stays a Phase 12 concern with the seam proven by the env-key implementation plus the ADR-024 rotation path.

## ADR-019 amendment: Redis-backed rate limiting fails closed (Phase 11)
Context: ADR-019 established single-instance in-memory limiting with Redis-backed enforcement deferred. Decision: when the Redis-backed limiter is configured (`auth.rate-limiter.backend=redis`), any Redis unavailability fails closed — no silent bypass, no unlimited attempts, no indefinite retry, no Redis internals leaked; the response follows the existing error-envelope conventions (`429 RATE_LIMITED` via `AuthException.rateLimited()`). No in-memory fallback may silently bypass the intended distributed limiter. The default remains in-memory single-instance mode (status quo, documented). Consequences: Redis failure may temporarily reduce authentication availability in exchange for preserving the abuse-resistance boundary; multi-instance deploy sets `backend=redis` with `REDIS_HOST` pointing at the managed Redis.

## ADR-026: Enforcing PostgreSQL RLS as classified defense-in-depth (Phase 11)
Context: every user-data query is already scoped `where user_id = :uid` plus service-layer `requireOwner` plus 404-masking; RLS adds a database-layer backstop. Decision: RLS is ENFORCING for a least-privilege path, deployed with explicit per-class policies and tests — never a blind enable. Classes: (1) direct user-owned tables (`users` self-row, `email_verification_tokens`, `refresh_sessions`, `user_settings`, `oauth_connections`, `oauth_states`, `mailbox_scans`, `discovered_services`, `service_evidence`, `privacy_requests`); (2) indirectly-owned tables (`privacy_request_dispatches`, `privacy_request_responses`, scoped through the parent `privacy_requests` row); (3) global/reference tables (`service_directory` — no user ownership invented, no user RLS); (4) system/audit tables (`audit_logs` — nullable `user_id` + `REQUIRES_NEW` behavior respected, anonymous inserts permitted). RLS never replaces `requireOwner`/JWT/service-layer checks/scoped queries. Consequences: policy mistakes fail safely (deny) rather than exposing cross-user data; rollout/rollback is a documented migration procedure (`docs/PHASE_11.md`).

## ADR-027: Minimal CSP hardening while the frontend is placeholder-level (Phase 11)
Context: the frontend is placeholder-only, so a final strict SPA-specific CSP cannot be derived, but edge headers should still be tightened without breaking the OAuth 302/callback design. Decision: minimal safe additions now — `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'` alongside the existing `default-src 'self'` + `object-src 'none'`; existing `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy` unchanged; no invented restrictive `connect-src`/`img-src`/`script-src` until the real React dependency inventory exists. Consequences: the final strict CSP is deferred until the real frontend lands; OAuth compatibility is preserved and tested.

## ADR-028: Scoped security-testing plan, no fabricated claims (Phase 11)
Context: hardening needs verifiable gates without claiming tests that never ran. Decision: Phase 11 establishes a scoped plan — SAST follows the existing SpotBugs direction from `TESTING_STRATEGY.md` where compatible with the Maven setup (candidate documented, not blindly multiplied); dependency scanning uses an approved approach or documents the candidate instead of silently adding large CI dependencies; Gitleaks configuration continues where the binary is available plus existing key-grep/log-grep tests; OWASP ZAP is the proposed API/runtime DAST for a later controlled test environment — never claimed as already run, never run against production or with unsafe automated attacks against real services. Full scope, environments, endpoints, exclusions, evidence format, severity classification, and remediation workflow live in `docs/PHASE_11.md`. Consequences: gates are auditable and honest; pentest findings are remediated only when an actual test identifies them.

## Phase 12 ADRs (human architecture decisions — production deployment)

## ADR-029: Production on AWS eu-central-1 with Amazon RDS for PostgreSQL (Phase 12)
Context: production requires EU-resident infrastructure and managed PostgreSQL; the architecture must stay as provider-portable as reasonably possible. Decision: production provider is AWS, region eu-central-1 (Frankfurt), database Amazon RDS for PostgreSQL. Portability remains an architectural goal — not a claim that every infrastructure component is cloud-neutral — so Phase 12 uses AWS concretely without introducing unnecessary AWS-specific application logic. Consequences: residency evidence is fixed; managed-PG backup/HA surface is AWS-shaped; app code stays provider-agnostic behind existing seams.

## ADR-030: AWS KMS for production token encryption behind TokenCryptoProvider (Phase 12)
Context: production needs managed keys (ADR-007/ADR-016, ADR-025) with a human-selected vendor. Decision: AWS KMS is the production encryption architecture; `TokenCryptoProvider` remains the sole abstraction; local/dev/test stay on `EnvAesGcmTokenCrypto` and/or fakes and never require AWS credentials. KMS operations are never exposed through public REST APIs. Raw KMS key material never appears in source code, `.env` files, Docker images, application logs, frontend responses, or database rows. Access uses AWS IAM/workload identity (no long-lived KMS credentials where the deployment supports it) with documented least-privilege key permissions, key-use auditing, and key deletion-protection/recovery considerations. Consequences: crypto path becomes AWS-coupled in production while the interface preserves testability and rollback to the env-key writer.

## ADR-031: Live KMS cutover with versioned dual-read semantics (Phase 12)
Context: Phase 12 executes the production KMS integration/cutover (not documentation-only), reusing the Phase 11 rotation design. Decision: writers use the active key version; pre-existing ciphertext stays readable through the dual-read window (`OAuthTokenRotation`); previous-key retirement only after zero old-version rows plus backup aging; no in-process scheduler, `@Scheduled`, `@Async`, or background re-encryption worker — complete offline migration uses bounded, idempotent operational tooling. Local Maven tests never perform a real cloud cutover. Consequences: cutover is an ops-gated procedure with a safe rollback (prior writer) and no application-architecture change.

## ADR-032: Single least-privilege production DB role with SET LOCAL context (Phase 12)
Context: RLS (V13, ADR-026) enforces only on a least-privilege path that does not exist yet. Decision: initial production uses one application database role with least privilege (no `CREATE`/`DROP`/`SUPERUSER`/owner rights); request-scoped ownership context via `SET LOCAL app.user_id` where the RLS architecture requires it; migrations/bootstrap use a separate privileged role. Application authorization stays authoritative; RLS stays defense-in-depth. The full RLS matrix (A→A allowed, A→B and B→A denied, system/audit functional) is re-run after role activation. Consequences: RLS becomes genuinely enforcing in production without changing app-layer checks.

## ADR-033: RDS encrypted backups with an executed disposable restore drill (Phase 12)
Context: production needs proven, EU-resident, encrypted backups — proof, not configuration alone. Decision: AWS managed RDS encrypted backups kept in the EU residency boundary; Phase 12 executes a restore drill against a disposable target verifying backup existence/encryption, restorable start, schema + data presence, ownership isolation, application connectivity, and health. Production data is never restored into uncontrolled environments. RPO/RTO/retention below are recorded as human-approved operational targets (no values existed in project requirements): RPO ≤ 24h, RTO ≤ 4h, automated backup retention 30 days — adjustable at deploy time, evidenced by the drill, never presented as measured facts before the drill runs. Consequences: backup claims become evidenced; retention/aging gates key-retirement decisions.

## ADR-034: AWS-managed TLS edge with edge-only forwarded-header trust (Phase 12)
Context: `nginx.conf` is an HTTP dev edge; production needs managed TLS and a safe client-IP story (X-Forwarded-For trust was deferred from Phase 2; `AuthController` assumes last-hop address). Decision: production TLS terminates at an AWS-managed edge/load-balancing layer (managed certificates + renewal); HTTPS enforced with appropriate HSTS/security headers; the application never blindly trusts arbitrary `X-Forwarded-For` — only the trusted production edge may supply forwarded client information within an explicit proxy boundary, and rate-limit/audit IP extraction follows that boundary. Consequences: certificate management leaves the container; IP-spoofing via forged headers is closed by construction.

## ADR-035: Verification-mail provider decision deferred to an implementation ADR (Phase 12)
Context: production onboarding requires working verification email, but no provider has been selected and hard-coding one would violate abstraction discipline. Decision: the seam is `auth.VerificationEmailSender` (verification links only — never confused with deletion-request sending, which stays manual-draft-only behind `privacy.OutboundMailProvider`/sink); `silent`/`log` implementations remain dev/test-only (`log` mode keeps its startup warning and is forbidden in production). Before any provider implementation: inspect the seam, record the provider choice in a new ADR with required credentials, sending limits, failure behavior, and local/test behavior. Provider credentials never enter source control; no Gmail SEND scope, no Microsoft Mail.Send scope, no embedded SMTP credentials; usage limited to the approved verification-email functionality. Consequences: onboarding can work in production without creating a second send capability or weakening the V1 manual-draft guarantee.

## ADR-036: Local-only Docker-based deployment architecture (ACCEPTED)

## ADR-037: Bedrock Mantle OpenAI-compatible endpoint as the Phase 13 scorer transport

Status: ACCEPTED (Phase 13 implementation scope only). Context: Phase 13 needs an
external evidence scorer behind the ADR-005 additive-scorer design; Bedrock
connectivity for model `qwen.qwen3-coder-480b-a35b-instruct` was verified over the
Bedrock Mantle OpenAI-compatible Chat Completions API
(`https://bedrock-mantle.us-east-1.api.aws/v1`). Decision: (1) the scorer calls
only `{base-url}/chat/completions` with the already-present Spring `RestClient`
— no AWS SDK, no Bedrock SDK module, no new Maven dependency; (2) authentication
uses a short-term Bedrock API key from the `BEDROCK_API_KEY` environment variable
only, never committed, never in `application.yml`/`.env.example` (placeholder
`REPLACE_ME` only), never logged/audited/returned; (3) the feature stays OFF by
default (`detection.ml.enabled=false`) with zero network calls when off;
(4) Bedrock is introduced ONLY for this scoring request — ADR-036 otherwise
stands: no AWS KMS/RDS/SecretsManager/CloudWatch, no AWS deployment, local Docker
Compose remains the supported environment, and `mvn test` never touches live
AWS (mocked scorer tests only). Consequences: AI remains an isolated additive
scorer with no deletion/send/schedule capability; live Bedrock verification is a
documented manual step, never CI.
Status: ACCEPTED. Context: GhostClean was originally designed with optional AWS production infrastructure including AWS KMS, RDS, managed TLS/edge assumptions, AWS backup workflows, and AWS-specific deployment configuration, and the project now intentionally removes AWS dependencies; the application must remain fully runnable and testable locally without an AWS account, AWS credentials, AWS CLI, AWS KMS, AWS RDS, AWS Secrets Manager, AWS CloudWatch, AWS ACM, AWS IAM workload identity, AWS-managed Redis, or AWS-managed infrastructure. Decision: (1) PostgreSQL runs locally through Docker Compose; (2) Redis runs locally through Docker Compose; (3) Nginx remains the reverse proxy; (4) application monitoring uses Spring Boot Actuator and application logs; (5) database backup and restore use PostgreSQL-native tools (`pg_dump`, `pg_restore`, PostgreSQL verification SQL); (6) token encryption uses the existing local `EnvAesGcmTokenCrypto` implementation; (7) encryption remains AES-256-GCM; (8) encryption keys are never hard-coded; (9) encryption secrets come from environment variables or Docker secrets; (10) `TokenCryptoProvider` remains as the provider abstraction; (11) Google OAuth remains read-only; (12) Microsoft OAuth remains `Mail.Read` only; (13) no SEND OAuth scope is introduced; (14) RLS remains enabled as defense-in-depth; (15) the two-gate deletion workflow remains unchanged — approval is separate from dispatch/confirmation; (16) no automatic deletion is introduced; (17) no background schedulers, `@Async`, `@EventListener`, or production background threads are introduced; (18) the application remains a modular monolith; (19) local Docker Compose is the supported deployment/demo environment; (20) no AWS-specific runtime or build dependency is required. Consequences: the entire application can be developed and demonstrated without an AWS account; local integration tests can exercise the complete architecture; PostgreSQL and Redis behavior remains reproducible through Docker; no cloud credentials are required for development; no AWS-specific runtime coupling remains; the security architecture remains provider-independent. Trade-offs: AWS-managed infrastructure is no longer part of the supported deployment; cloud-managed backup, monitoring, TLS, IAM, and KMS capabilities are replaced by local equivalents; production cloud deployment would require a separate future architecture decision; local encryption key management requires careful operational handling; local deployment does not constitute validation of any cloud provider. Migration relationship: this decision supersedes the AWS-specific architecture in ADR-029 (AWS region/RDS), ADR-030 (AWS KMS), ADR-031 (live KMS cutover/rollback), ADR-033 (RDS encrypted backups/restore drill), and ADR-034 (AWS-managed TLS/edge) — those historical records are preserved unchanged above and must not be edited; ADR-032 (least-privilege DB role with `SET LOCAL` context) is not AWS-specific and remains in force; the "future cloud KMS" language in ADR-007/ADR-015/ADR-016/ADR-025 is redirected by this decision and any future cloud key management requires a new ADR. No AWS validation, production deployment, privacy approval, or cloud migration is claimed by this record.

## ADR-038: Resend as the transactional verification-mail provider (implementation ADR for ADR-035)

Status: ACCEPTED. Context: ADR-035 deferred the verification-mail provider to an implementation ADR; registration currently returns 201 while `silent`/`log` modes deliver nothing real, so `users.email_verified` can never become true outside log-scraping. Decision: (1) provider is Resend over plain HTTPS (`POST {base-url}/emails`, Bearer API key) using the already-present Spring `RestClient` -- no SDK, no SMTP, no new Maven dependency, no personal-Gmail relay; (2) the existing `auth.VerificationEmailSender` seam is extended, not duplicated: `ResendVerificationEmailSender` is active only when `verification.mail.provider=resend` (otherwise Phase 2 behavior is byte-identical), and carries verification links only -- deletion-request sending stays manual-draft-only behind `privacy.OutboundMailProvider`/sink; (3) required credentials: `EMAIL_API_KEY` plus verified sender `EMAIL_FROM`/`EMAIL_FROM_NAME`, all from the environment only (placeholders in `.env.example`, fail-fast at startup when `resend` is selected without them); (4) sending limits: single attempt per registration/resend with 3s connect + 8s read timeouts and no retries -- the rate-limited `POST /api/v1/auth/verification/resend` endpoint is the retry path, so no background scheduler, `@Async`, or queue is introduced; (5) failure behavior: a provider outage never breaks the anti-enumeration contract (registration stays 201-shaped, resend stays 202-shaped) and never rolls back the persisted token -- the failure is logged with outcome categories only and the user recovers via resend; (6) local/test behavior: empty provider (default) performs zero network calls; automated tests use JDK-stub HTTP servers and never touch live Resend. No Gmail SEND scope, no Microsoft Mail.Send scope, no embedded SMTP credentials; the key is never logged, audited, or returned. Consequences: onboarding works in production without a second send capability; live delivery verification is a documented manual step, never CI.
