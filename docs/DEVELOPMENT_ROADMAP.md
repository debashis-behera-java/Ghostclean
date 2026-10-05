# GhostClean — Development Roadmap (Phase 0 → 13)

> Rule: each phase has GOAL/FEATURES/FILES+MODULES/DB/APIs/TESTS/SECURITY/ACCEPTANCE/OUT-OF-SCOPE. No phase without tests + acceptance.

## Phase 0 — Architecture & docs ✅ (this bundle, incl. 5 resolved decisions)
Goal: blueprint. Files: `docs/*.md` + `ARCHITECTURE_DECISIONS.md`. Accept: all 18 docs + ADRs exist; no prod code; language guards pass. RESOLVED: required email verification; degraded manual mode; V1 manual-draft-only (no SEND); prod PG EU-region portable; V1 env-key encryption with KMS path.

## Phase 1 — Repo & infra foundation
Goal: buildable monolith skeleton. Features: Maven multimodule(packages), Flyway baseline (users incl. `email_verified` + verification-token table, settings), Docker Compose (app/pg/redis/nginx), `.env.example` (placeholders incl. `TOKEN_ENCRYPTION_KEY`, `APP_REGION=eu` label), health/actuator, correlation ID, error envelope, `TokenCryptoProvider` interface + env impl stub. DB: `V1 users (email_verified),user_settings,refresh_sessions,email_verification_tokens`. APIs: `/health`, `GET /users/me` stub behind auth. Tests: context-load, migration IT, ArchUnit module rules, startup-fails-without-encryption-key test. Security: secret-scan CI, headers. OOS: real auth logic (Phase 2), OAuth, SEND scopes (forbidden V1).

## Phase 2 — Authentication (incl. REQUIRED email verification)
Goal: register (unverified) → verify → login/refresh/logout/reset/lockout + `403 EMAIL_NOT_VERIFIED` gates. Modules `auth,user,audit,common` (+ transactional verification mail, provider TBD — not a SEND-scope dependency). DB: sessions/verification-tokens/audit. APIs: `/auth/*` incl. `/auth/verify`, `/auth/verification/resend`, `/users/me*`. Tests: hash/rotation/lockout/verify-expiry/resend-cap/cross-user + gate matrix. Accept: JWT 15m + rotation + reuse-detect + re-auth helper + verification enforced.

## Phase 3 — Google OAuth (READ-only + manual-mode offer)
Goal: connect/callback/refresh/revoke/disconnect with `gmail.readonly` only; denied/restricted → labeled manual mode (no bypass). Module `oauth` + `EmailProviderConnector` + Google impl (WireMock). DB: `oauth_connections (+key_version)`. APIs: `/oauth/*` + `POST /evidence/manual` stub (full build Phase 4). Tests: state/PKCE/tamper/encrypt-wipe + assert-SEND-scope-absent + unverified-blocked. OOS: scanning bodies, SEND scopes, auto-send.

## Phase 4 — Mailbox metadata scanning + manual import
Goal: full/delta pipeline + progress/cancel + manual metadata intake (`source=manual`, capped, labeled). Modules `mailbox,scanner`. DB: `mailbox_scans`, `service_evidence.source`. APIs: `/scans*` + `/evidence/manual`. Tests: pagination/watermark/idempotent ingest/cancel/429-resume + manual-validation/abuse-cap/source-label. OOS: scoring.

## Phase 5 — Service detection
Goal: rule engine + confidence + fixtures. Module `detection` (+`services` read). DB: `service_evidence`. Tests: weight/band matrix + calibration. OOS: grouping.

## Phase 6 — Account intelligence (+ directory seed + risk)
Goal: resolution + risk + curated seed. Modules `account,risk,services`. DB: `discovered_services,service_directory`. APIs: `/discovered*`. Tests: Spotify-3-mail upsert, dedupe, language guard.

## Phase 7 — Dashboard (frontend boot + read views)
Goal: React shell + dashboard/discovered/evidence/activity/connections/settings/privacy (read). Tests: RTL + API contract. OOS: send.

## Phase 8 — Deletion engine (MANUAL-DRAFT ONLY)
Goal: lifecycle + templates + two-gate + final-draft preview + mark-sent attestation; NO mail-send client, NO SEND scopes. Modules `deletion,legal,workflow,notification`. DB: `deletion_requests (send_channel=manual_draft only in V1),events,legal_templates,notifications`. APIs: full `/deletion-requests*` incl. `/confirm` (→final draft) + `/mark-sent` (attestation). Tests: machine fuzz + replay + re-auth + verified-gate + assert-no-send-client.

## Phase 9 — Request tracking
Goal: deadlines/reminders/responses/verification states + tracking UI. Tests: reminder cron, response transitions.

## Phase 10 — Microsoft/Outlook
Goal: second connector, no core rewrite. Tests: Graph WireMock + parity suite. Accept: provider-switch E2E both.

## Phase 11 — Security hardening
Goal: RLS, tightened CSP, rate buckets, backup redaction, KMS-migration readiness (`KmsKeyProvider` swap design, re-encrypt job), pentest fixes, SAST/DAST gates.

## Phase 12 — Local-only architecture (ADR-036 ACCEPTED; AWS path retired)
Goal: local Docker Compose deploy (PostgreSQL 16, Redis 7, Nginx edge profile), local AES-256-GCM encryption, `pg_dump`/`pg_restore` backup tooling with disposable drill, local runbooks, privacy-page route. The original provider-portable AWS deploy goal (managed PG, KMS cutover, managed TLS, cloud monitoring) was intentionally removed by ADR-036 and must not be reintroduced as future AWS work.

## Phase 13 — ML/AI (optional, NOT STARTED)
Goal: additive scorer behind flag; injection corpus green; rules remain source of truth. Phase 13 has not been started; no Phase 13 work exists in-repo.

Each phase: update relevant doc + changelog + ADR if direction changes.
