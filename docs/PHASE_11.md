# GhostClean — PHASE_11 (Security Hardening) — IMPLEMENTED

> Roadmap mandate ("RLS, tightened CSP, rate buckets, backup redaction,
> KMS-migration readiness, pentest fixes, SAST/DAST gates") executed
> under six explicit human architecture decisions (ADR-024 through
> ADR-028 plus the ADR-019 fail-closed amendment). No previous-phase
> behavior was changed except three narrow, behavior-preserving
> additions: lazy re-encrypt-on-read (no-op while `key_version` is
> current), an opt-in Redis rate-limiter backend (default unchanged),
> and an RLS migration that is dormant for the table-owner path. No
> new routes, no new business tables, no SEND capability, no
> scheduler, no KMS vendor code, no fabricated test claims.

## 1. Objective

Harden the Phase 0–10 platform in place: defense-in-depth at the
database layer (RLS), tighter edge headers (CSP), distributed
rate-limit readiness with fail-closed semantics, verified
backup/secret hygiene, a documented (not implemented) KMS future,
and an honest, scoped security-testing plan — while keeping all
383 pre-existing tests green and every Phase 0–10 guarantee intact.

## 2. Human architecture decisions (all six, verbatim intent)

1. **Re-encryption trigger: LAZY RE-ENCRYPT-ON-READ.** No
   `@Scheduled`/`@Async`/`@EventListener`/`TaskScheduler`/
   `TaskExecutor`/`ScheduledExecutorService`, no background polling
   or threads, no fake job. Read → decrypt with stored
   `key_version` → if stale, re-encrypt with the current key and
   persist ciphertext + current `key_version` atomically.
   Idempotent; plaintext never logged; documented limitation that
   untouched rows stay on the previous version. A complete offline
   rotation is a future operational/runbook procedure (see §4).
 2. **KMS readiness: DOCUMENTATION / DESIGN ONLY (historical at Phase 11 completion; superseded locally — see below).** True at Phase 11 completion: No vendor
    chosen (not AWS KMS, Azure Key Vault, or GCP KMS), no vendor
    SDK, no `KmsKeyProvider` implementation, no new cloud secrets.
    Architecture stays `TokenCryptoProvider` →
    `EnvAesGcmTokenCrypto`. KMS is documented, never claimed as
    implemented at Phase 11 time (see §4). Superseded locally by partial Phase-12 artifacts
    (`docs/PHASE_12.md`): AWS-KMS envelope provider class + SDK dependency + V14 role exist
    in-repo, unvalidated against AWS, no live cutover, no prod secrets. Original Phase-11 text preserved.
3. **Redis limiter failure mode: FAIL CLOSED.** Redis
   unavailability never bypasses limiting, never allows unlimited
   attempts, never retries indefinitely, never leaks Redis
   internals; the controlled `429 RATE_LIMITED` envelope applies.
   No silent in-memory fallback around the distributed limiter.
   Documented availability tradeoff (see §5 + ADR-019 amendment).
4. **PostgreSQL RLS: ENFORCING but CLASSIFIED.** Explicit
   per-class policies + tests; application authorization
   (`requireOwner`, JWT, service layer, `where user_id = :uid`)
   remains required and is never replaced. Nullable-ownership and
   global tables are classified, not blindly enabled (see §6).
5. **CSP: MINIMAL HARDENING NOW.** Adds only safe directives
   compatible with the placeholder frontend and the OAuth
   302/callback design (`frame-ancestors 'none'`,
   `base-uri 'self'`, `form-action 'self'` next to existing
   `default-src 'self'` + `object-src 'none'`); existing headers
   untouched; no invented `connect-src`/`img-src`/`script-src`
   until the real React inventory exists (see §7).
6. **Pentest/SAST/DAST: SCOPED PLAN, NO FABRICATED CLAIMS.**
   No pentest is claimed to have happened; findings are fixed only
   when an actual test identifies them. SpotBugs direction,
   dependency-scan candidate, Gitleaks continuation, and OWASP ZAP
   as the proposed later DAST are scoped below (see §8).

## 3. Lazy re-encrypt-on-read

- Boundary: `oauth/OAuthTokenRotation` (`decryptAccess`,
  `decryptRefresh`). Both decrypt leniently (absent/unreadable →
  `null`, exactly as before, so callers' reconnect flows are
  unchanged) and, only when decryption succeeds AND the row's
  `key_version` differs from `TokenCryptoProvider.keyVersion()`,
  re-encrypt both stored token columns with the current key, set
  the current `key_version`, and save — one atomic persistence
  per stale row.
- Wired into the two existing read sites:
  `OAuthService.decryptLenient` (disconnect/refresh paths) and
  `ScanService.decryptCurrent` (scan auth path). Behavior is
  byte-identical while versions match (no write, same return).
- Guarantees: idempotent (second read is a no-op); old-key rows
  remain readable; undecryptable rows are never corrupted (left
  untouched, callers fail safe to reconnect); plaintext and key
  material never logged, audited, or returned; no scheduler,
  thread, or polling involved (existing
  `noBackgroundDispatchMechanismExists` guard covers the new
  sources).
- Tests: `OAuthTokenRotationTests` — version-match no-op (no
  save), stale-version rotation (ciphertext + version bump, save
  once), idempotency, undecryptable-stale safety, blank-input
  safety.

## 4. Token-key rotation + KMS-readiness runbook (design only)

- **Key-version semantics.** `oauth_connections.key_version` is
  the version that encrypted the stored row.
  `TokenCryptoProvider.keyVersion()` is the active writer
  version (V1 `EnvAesGcmTokenCrypto` = `1`). Readers MUST accept
  any previously-issued version they can decrypt; writers MUST
  stamp the active version. Rotation = introduce version N+1 as
  the writer while retaining N for reads, then lazily converge
  rows via §3.
- **Rotation procedure (future).** (1) Provision the new key
  material through the secret manager (never committed/logged);
  (2) deploy a provider whose `keyVersion()` returns N+1 and
  which decrypts N (dual-read window); (3) verify §3 converges
  touched rows (`key_version` distribution query); (4) for
  untouched rows, run the *offline* procedure below; (5) retire
  key N only after zero rows remain on N and backups containing
  N-ciphertext have aged out per retention policy.
- **Complete offline rotation (future operational procedure,
  NOT an application job).** Quiesce writes to
  `oauth_connections`, snapshot the database (encrypted backup),
  run a bounded single-pass CLI/migration that decrypts with N
  and re-encrypts with N+1 per row (idempotent, resumable by
  `key_version`), verify zero N rows + application smoke tests,
  then re-enable traffic. Never implemented as `@Scheduled`,
  `@Async`, or any in-process background mechanism.
- **KMS cutover procedure (future, Phase 12).** (1) Human selects
  the vendor (open item); (2) implement a KMS-backed
  `TokenCryptoProvider` behind the existing interface
  (envelope-encryption ready, no schema/API change where
  possible); (3) cut over using the rotation procedure above
  with the KMS provider as version N+1; (4) keep the env-key
  provider available for rollback during the dual-read window.
- **Rollback.** Any step before key-N retirement rolls back by
  restoring the previous provider version as the writer; rows
  already on N+1 remain readable only while N+1 key material is
  retained — hence retirement gating above.
- **Failure handling.** Undecryptable rows are never rewritten
  (fail safe to user reconnect); rotation NEVER blocks
  authentication for unrelated users; all failures surface
  through the existing error envelope without key/token detail.
- **Operational responsibilities.** Key material lives in the
  secret manager / environment only; rotation is performed by
  operations with a second reviewer; audit MUST NOT record keys
  or plaintext (ids/versions/counts only).
- **Future KMS integration boundary.** Exactly the
  `TokenCryptoProvider` interface (`encrypt`/`decrypt`/
  `keyVersion`); callers (`OAuthService`, `ScanService` via
  `OAuthTokenRotation`) are already decoupled from the key
  source. No caller changes are foreseen for the swap.
- **KMS is NOT implemented in Phase 11.** No vendor, no SDK, no
  provider class, no cloud secret exists; any claim otherwise
  would be false.

## 5. Redis-backed rate limiting (fail-closed)

- Backend selection: `auth.rate-limiter.backend` (`memory` |
  `redis`, default `memory`; env `RATE_LIMITER_BACKEND`).
  `memory` is today's single-instance sliding window, unchanged.
  `redis` uses `auth/RedisRateLimiter`: atomic Lua sliding
  window (`ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `PEXPIRE`)
  over `StringRedisTemplate`, one Redis round-trip per check.
- Fail-closed contract (ADR-019 amendment): when `backend=redis`
  and Redis is unreachable, misconfigured, or errors — or the
  template bean is absent — the check throws
  `AuthException.rateLimited()` (`429 RATE_LIMITED`,
  "Too many requests, please slow down"). Exactly one attempt,
  no retry loop, no Redis internals in logs/responses (warn line
  carries key-agnostic wording only). Documented tradeoff:
  Redis failure may temporarily reduce authentication
  availability in exchange for preserving abuse resistance.
- No bypass fallback: there is deliberately no path where a
  configured-Redis failure silently falls back to allowing the
  request.
- Call sites unchanged: `AuthRateLimiter.check(key, limit,
  window)` keeps its signature and delegates per the configured
  backend, so all existing budgets (login/register/verify/
  resend/refresh, OAuth authorize/callback/disconnect/refresh,
  scans, dispatch, privacy requests, responses) keep their
  semantics under either backend.
- Configuration: `spring.data.redis.host/port` (env
  `REDIS_HOST`/`REDIS_PORT`, Compose defaults to the `redis`
  service); placeholders only in `.env.example`.
- Tests: existing `AuthRateLimiterTests` (memory) unchanged;
  new `RedisRateLimiterTests` — allow under limit, deny at
  limit, Redis-exception → `429 RATE_LIMITED` with generic
  message, single attempt (no retry), key isolation; plus a
  backend-selection test (memory default preserved).

## 6. PostgreSQL RLS (V13, classified, enforcing path)

- Classification (per Decision 4):
  - **Class 1 — direct user-owned** (`user_id` NOT NULL):
    `users` (self-row `id`), `email_verification_tokens`,
    `refresh_sessions`, `user_settings`, `oauth_connections`,
    `oauth_states`, `mailbox_scans`, `discovered_services`,
    `service_evidence`, `privacy_requests`. Policy:
    `user_id::text = current_setting('app.user_id', true)`
    (self-row: `id::text = ...`), applied as `USING` (+ `WITH
    CHECK` for writes).
  - **Class 2 — indirectly owned** (no `user_id`, parent FK):
    `privacy_request_dispatches`,
    `privacy_request_responses`. Policy: `EXISTS (SELECT 1 FROM
    privacy_requests pr WHERE pr.id = privacy_request_id AND
    pr.user_id::text = current_setting('app.user_id', true))`.
  - **Class 3 — global/reference:** `service_directory`
    (curated catalog). NO user RLS invented; documented as
    intentionally out of user scoping.
  - **Class 4 — system/audit:** `audit_logs` (nullable
    `user_id`, `REQUIRES_NEW` independence per ADR-020).
    Policies permit inserts unconditionally (`WITH CHECK
    (true)`, preserving anonymous `LOGIN_FAILED`/callback
    audits) and scope selects to
    `user_id IS NULL OR user_id::text =
    current_setting('app.user_id', true)`; no `UPDATE`/`DELETE`
    is granted to the app path (append-only preserved).
- Enforcement note (honest): the migration uses `ENABLE ROW
  LEVEL SECURITY` (not `FORCE`), so the table-owner path used by
  today's single-role deployment and test harness is unchanged
  (all Phase 0–10 tests green unmodified); the policies enforce
  for any least-privilege role (production Phase 12), verified
  by `RlsPolicyTests` which create a `rls_tester` role, set
  `app.user_id`, and assert: RLS enabled on the 13 user/audit
  tables, cross-user reads denied, same-user reads allowed,
  anonymous audit insert lands, global catalog readable,
  indirect tables scoped through the parent, and deletions
  cascade per existing FKs.
- What RLS does NOT do: replace `requireOwner`, JWT/service
  authorization, scoped queries, or the 404-mask; cover login's
  pre-authentication user lookup (owner path, unchanged); or
  change any API shape or audit vocabulary.
- **Rollout procedure.** (1) Apply V13 in staging; (2) run
  `RlsPolicyTests` + full suite; (3) verify
  `pg_tables.rowsecurity` for the 13 tables; (4) Phase 12:
  introduce the least-privilege app role with
  `GRANT SELECT/INSERT/UPDATE/DELETE` per class (+ `USAGE` on
  sequences), set `app.user_id` per transaction after
  authentication, re-run the matrix. **Rollback:** `ALTER TABLE
  … DISABLE ROW LEVEL SECURITY` per table (script in §6 note of
  the migration header) or redeploy the previous migration
  baseline; application code needs no change either way.

## 7. Edge headers / CSP

- `infrastructure/nginx.conf` CSP becomes:
  `default-src 'self'; object-src 'none'; base-uri 'self';
  form-action 'self'; frame-ancestors 'none'`.
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `server_tokens off`, and the
  `/actuator/` edge block are unchanged.
- Rationale: `frame-ancestors 'none'` + `base-uri` +
  `form-action` are safe without a SPA inventory; no
  `connect-src`/`img-src`/`script-src` is invented (ADR-027).
  OAuth 302/callback compatibility is preserved (no redirect or
  form-post semantics change) and covered by the existing OAuth
  flow suites plus new `SecurityHeadersTests` (directives
  present, legacy headers retained, no invented SPA
  directives).

## 8. Security-testing scope (plan; no runs claimed)

- **In scope (minimum):** authentication, JWT, OAuth, Google
  integration, Microsoft integration, token storage/encryption,
  ownership/isolation, privacy requests, deletion workflow,
  rate limiting, SSRF, API validation, security headers, CSP,
  sensitive-data leakage, audit/log leakage.
- **SAST:** SpotBugs direction per `TESTING_STRATEGY.md`.
  Status: candidate `com.github.spotbugs:spotbugs-maven-plugin`
  (version pinned at adoption time to the Boot-compatible
  release) documented but NOT wired into the build in Phase 11:
  the plugin is not in the offline artifact cache and the human
  decision forbids silently adding large CI dependencies or
  stacking overlapping SAST tools. Adoption = add the plugin +
  `check` execution + baseline, then fix-or-waive with reasons.
- **Dependency scanning:** no tool approved in-repo. Candidate:
  OWASP Dependency-Check (or the forge's native Dependabot
  alerts where the repo is hosted). Documented, not silently
  added. Adoption = scheduled scans + severity triage per the
  workflow below.
- **Secrets:** `.gitleaks.toml` retained; CI/runbook continues
  `gitleaks detect --source . -v` where the binary is
  available, plus the in-repo guards that always run:
  `EnvExamplePlaceholdersTests` (no PEM/`AKIA`/real secrets),
  `TokenCryptoTests`/`MissingEncryptionKeyTests` (fail-fast key
  hygiene), and the log-grep direction (no `BEGIN
  PRIVATE KEY`/`refresh_token`/`password`/key values in logs).
  Phase 11 result: Gitleaks binary not present in this
  environment — config retained, file-content guards green, no
  secret material introduced (verified by placeholder-only
  additions).
- **DAST (proposed, NOT performed):** OWASP ZAP against a later
  controlled test environment. Test-environment
  requirements: disposable Compose stack with synthetic data
  only, no production data, no real provider credentials, no
  real inboxes. Authentication setup: scripted
  register→verify→login harness yielding a Bearer token for
  the ZAP context (plus one unverified user for gate checks).
  Target endpoints: all `/api/v1` routes from
  `API_SPECIFICATION.md` (auth, users, OAuth authorize,
  scans, evidence/manual, discovered, privacy
  requests/dispatch/responses, activity). Exclusions: OAuth
  provider domains, any live Google/Microsoft endpoint,
  destructive rescan loops, logout-all storms. Evidence/report
  format: ZAP HTML+JSON report stored with the release
  checklist, findings mapped to `THREAT_MODEL.md` IDs.
  Severity classification: Critical/High/Medium/Low aligned to
  impact × exploitability with privacy-data impact weighted
  up. Remediation workflow: file issue per finding → fix →
  regression test → re-scan → close with evidence; Critical/
  High block release. **No ZAP scan has been run in Phase 11;
  no production system is ever scanned; no unsafe automated
  attacks against real services.**
- **Backup redaction (verified direction).** Dumps MUST exclude
  or hold only ciphertext for `access_token_enc`,
  `refresh_token_enc`, `password_hash`, verification/reset
  token hashes, and `token_iv` material; backups are encrypted
  at rest in an EU region (exact provider/region: Phase 12).
  Verified in-repo by schema review (no plaintext token
  columns exist) + no-plaintext guards; the production
  dump-command + restore drill stays a Phase 12 item.

## 9. Audit

No new audit events. All hardening paths reuse the existing
vocabulary; rotation/RLS/limiter paths log ids/versions/counts
only. Serialized-audit hygiene assertions remain green.

## 10. Security / privacy

Ownership + verified gate on every touched path; no new OAuth
scopes; no `Mail.Send`/`gmail.send` surface (capability tests
green); metadata-only fetching unchanged; per-user limits
preserved under either limiter backend; no bodies/tokens/
passwords in responses, logs, or audits; EU-residency posture
unchanged and provider-portable.

## 11. Testing

`mvn test` at Phase 11 completion: **383 pre-existing + new Phase 11 suites, 0
failures** (historical record; final counts in the completion report). New:
`OAuthTokenRotationTests`, `RedisRateLimiterTests` (+ backend
selection), `RlsPolicyTests`, `SecurityHeadersTests`. Existing
guards re-verified: `NoSendCapabilityTests`,
`NoDeletionActionTests` (incl. scheduler-annotation absence
over the new sources), scope-discipline suites, cross-user
matrix, rate-limit suites. No live provider account touched;
no production scan performed. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-11 completion record.

## 12. Configuration

`application.yml`: `spring.data.redis.host/port`
(`REDIS_HOST`/`REDIS_PORT`, Compose defaults) +
`auth.rate-limiter.backend` (`RATE_LIMITER_BACKEND`, default
`memory`). `.env.example`: placeholders only. Unconfigured
Redis answers nothing new — `memory` mode is today's behavior.

## 13. Limitations

- Rotation converges lazily; untouched rows stay on the
  previous version until accessed (by design, ADR-024).
- KMS was design-only at Phase 11 completion (by design, ADR-025) — historical. Partial local implementation now exists under Phase 12 (provider class + SDK + V14, AWS-unvalidated); vendor live cutover, drill, and prod secrets remain Phase-12 AWS-blocked items.
- RLS policies enforce on the least-privilege path; the
  current single-role deployment relies on the unchanged
  app-layer checks (by design, ADR-026).
- Full strict CSP awaits the real frontend (by design,
  ADR-027).
- No pentest/DAST run is claimed; SpotBugs and
  dependency-check are documented candidates, not wired gates
  (by design, ADR-028).
- `gmail.readonly` verification + Microsoft publisher
  verification: not started (pre-existing, release-gating).

## Explicit guarantees

- PHASE 11 ADDS NO SEND CAPABILITY.
- PHASE 11 ADDS NO SCHEDULER, THREAD, OR BACKGROUND JOB.
- PHASE 11 ADDS NO KMS VENDOR CODE OR SECRET (historical at Phase-11 completion; partial Phase-12 KMS code arrived later, still AWS-unvalidated with no prod secrets).
- PHASE 11 WEAKENS NO EXISTING CHECK (owner/JWT/scoped-query/audit).
- RLS IS CLASSIFIED DEFENSE-IN-DEPTH, NOT A REPLACEMENT.
- REDIS FAILURE FAILS CLOSED.
- CSP PRESERVES OAUTH COMPATIBILITY.
- NO PENTEST OR DAST RUN IS CLAIMED.
