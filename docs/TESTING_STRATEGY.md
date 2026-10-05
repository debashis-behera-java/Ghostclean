# GhostClean — Testing Strategy (Pyramid + Isolation)

> Phase 0 update (RESOLVED): verification-gate matrix; manual-import validation/caps/labeling; V1 no-SEND/no-mail-send assertions; env-key fail-fast; EU-residency is operational (no provider SDK in code).

## Layers

- **Integration (Testcontainers PG + Redis):** Flyway migrations, cascade deletes, unique `(user,provider,messageId)` + manual unique key, purge jobs, token encrypt/decrypt via env key + fail-fast without key, connector contract (WireMock Google/Graph: pagination, 429/5xx, invalid_grant, delta).
- **Unit (JUnit5+Mockito):** state machine matrix (incl. confirm→draft→mark-sent, no auto-send transition), `score()` weights/bands (incl. manual cap), risk calc, grouping upsert (oauth+manual sources), template render, owner-check + verified-gate, crypto round-trip + key_version.
- **API (MockMvc/RestAssured):** every endpoint incl. error codes, pagination, Idempotency-Key replay (double confirm → single draft, no dup side-effect), illegal transition 409, re-auth gate, `403 EMAIL_NOT_VERIFIED` matrix (unverified blocked from oauth/scan/manual/review/deletion/export), DTO secret-absence assertions.
- **Security:** cross-user matrix (A cannot GET/PUT/DELETE B's IDs → 404), expired/tampered JWT, verification-token expiry/single-use/resend-cap, CSRF Origin, XSS corpus render (incl. manual-import subjects), SAST (SpotBugs), secret-scan (gitleaks) + log-grep CI + key-grep (no `TOKEN_ENCRYPTION_KEY` value in repo/logs), SSRF host-allow, OAuth tampered-state/evil-returnUrl, assert V1 requests no `gmail.send`/`Mail.Send` scope and no mail-send bean exists.
- **DB:** RLS-ready scoped queries, index `EXPLAIN` on hot paths, backup-redaction check.
- **OAuth strategy:** never hit real Google in CI; WireMock + recorded sanitized fixtures; manual pre-prod checklist against real consent screen (scope text, deny-flow, revoke).
- **Frontend (post-v1):** Vitest/RTL (two-gate flow, evidence display, no raw HTML), Playwright E2E (register→connect(mock)→scan→review→draft→approve→generate→confirm-blocked-without-reauth).
- **E2E (backend):** full lifecycle on Testcontainers: register→verify→connect(fake connector)→scan→HIGH Spotify→approve→generate→confirm(draft)→mark-sent→response→completed; parallel manual-mode path: register→verify→manual-import→capped MEDIUM→draft→mark-sent.

## Gates

Coverage ≥80% services/detection/workflow; mutation spot-check on scorer/machine; every phase ships tests; flaky quarantine policy. Multi-user isolation suite mandatory green before merge.

## Acceptance

- [ ] All 8 layers + OAuth-mock rule + isolation matrix + gates defined; Phase 1 test list in `PHASE_01.md`.
