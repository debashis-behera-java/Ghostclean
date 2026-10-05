# GhostClean — Security Model (AuthN/Z, Crypto, AppSec)

> Phase 0 update (RESOLVED): email verification REQUIRED for V1 and gates sensitive workflows; V1 requests NO SEND scopes and contains no mail-send path (manual-draft only); token encryption V1 = env key, KMS later; prod data residency EU (portable).

## 1. Authentication

- Register: Bean Validation email+strong password (12+ chars, zxcvbn ≥3), Argon2id (or bcrypt cost 12) hash, `email CITEXT unique`. RESOLVED V1: email verification REQUIRED — register creates `email_verified=false` + 24h single-use verification token (128-bit, SHA-256 hash stored in `email_verification_tokens`, one active per user). Verify via `POST /auth/verify`; rate-limited resend `POST /auth/verification/resend` (max 5/24h per account+IP). Verification links are random tokens (not JWTs), single-use, invalidated on success/expiry.
- Phase 2 implementation notes: BCrypt (framework default cost) chosen over Argon2id — no extra dependency, OWASP-acceptable, KDF peer-reviewed; password policy 12–128 chars + obvious-credential blocklist (documented in `docs/PHASE_02.md`). Registration returns the identical 201 for new/duplicate addresses (no oracle, ADR-017). JWT access via JJWT HS256/HS512 (algorithm follows key size; `JWT_SIGNING_KEY` ≥256-bit, fail-fast). Audit commits independently (`REQUIRES_NEW`) so failure audits survive rollbacks; `audit_logs→users` FK removed for the same reason (V3, application-enforced nullification on account delete).
- Login: allowed while unverified (so users can reach verification state), BUT every sensitive endpoint (OAuth authorize, scan create, manual import, discovered review, deletion-request create/approve/generate/confirm/mark-sent, export) MUST enforce `email_verified=true` else `403 EMAIL_NOT_VERIFIED`. Rate-limit 5/min/IP + 5 fails/account → 15m lock + `LOGIN_FAILED` audit (IP hash only). Constant-time compare.
- Tokens: access JWT 15m (EdDSA/RS256, `sub=userId`, `jti`, `iat/exp`, `scope`), refresh opaque 128-bit stored as SHA-256 hash in `refresh_sessions`, httpOnly+Secure+SameSite=Lax cookie, 30d, **rotation on every use** (reuse → revoke chain + alert).
- Logout: revoke session; refresh reuse detection.
- Reset: 60m single-use token hash in DB, always-202 response (no oracle), invalidate sessions on success.
- Re-auth: password confirm (or fresh login <10m) required for deletion-confirm + GhostClean account delete (`X-Reauth` proof / short-lived step-up JWT).

## 2. Authorization (ownership)

Rule: **User A never accesses User B's connections/scans/discovered/evidence/requests/audit.** Enforcement 3 layers:
1. **API:** Spring Security `authenticated()` + `@PreAuthorize("@owner.check(#id, authentication)")` / explicit service call.
2. **Service:** `requireOwner(userId, entity.getUserId())` throws `NotFound` (404-mask) — never 403-with-existence-leak for object IDs.
3. **Repository:** all queries scoped `where user_id = :uid`; add `current_setting('app.user_id')` RLS post-v1.
Tests: cross-user matrix for every endpoint (see `TESTING_STRATEGY.md`).

No admin role v1 → no privilege-escalation surface. Future admin needs separate ADR + MFA.

## 3. Crypto & Secrets

- Tokens at rest: AES-256-GCM, 96-bit random IV per row, `key_version` column. RESOLVED: V1 key comes ONLY from env `TOKEN_ENCRYPTION_KEY` (32 bytes, base64) via `TokenCryptoProvider`/`EnvKeyProvider`; never hard-coded, never committed, never logged; app fails fast at startup if missing/weak (<256-bit). Rotation: new `key_version` + re-encrypt-on-read job (runbook Phase 11). Production migration path (not implemented): swap to `KmsKeyProvider` (AWS KMS / Azure Key Vault / GCP KMS) with no schema/API change; envelope-encryption ready. Current state (ADR-036, local-only): the AWS/cloud-KMS migration path was retired; `EnvAesGcmTokenCrypto` is the supported writer. Passwords: Argon2id, never encrypted-reversible.
- Transit: TLS 1.2+ (Nginx), HSTS, Secure cookies. JWT signed; JWKS/keys via env, rotation documented.
- Secrets: `.env` never committed; `.env.example` only placeholders; CI uses vault/secret store; dumps exclude `*_enc/password_hash`.

## 4. AppSec Controls (per threat → control)

- XSS: React escapes by default; never `dangerouslySetInnerHTML` for email-derived strings; CSP `default-src 'self'`, `object-src 'none'`; subject truncation + sanitize.
- CSRF: Bearer+JSON (no cookie auth for API) → low surface; refresh/logout cookie endpoints use SameSite=Lax + `Origin/Referer` check + state param on OAuth.
- SQLi: JPA/bind params only; no string-concat JPQL; `CITEXT`/validation on inputs.
- SSRF: connectors allow-list `gmail.googleapis.com, people.googleapis.com, graph.microsoft.com, login.microsoftonline.com, oauth2.googleapis.com`; no user-URL fetch; WebClient with restricted redirect + timeout.
- OAuth callback: `state` (signed, 10m) + PKCE `code_verifier` server-side; `returnUrl` allow-list (same-origin); error params sanitized.
- Malicious email / prompt injection: treat all mail fields untrusted; size caps; strip HTML; future LLM only on allow-listed extracted signals with system-prompt isolation + output-schema validation (see `SERVICE_DETECTION_DESIGN.md`).
- Forged/unauthorized sends: V1 has NO send path by construction (no SMTP/API mail-send client, no SEND scopes requested — CI asserts this). Two-gate (approve + confirm→final-draft) + re-auth + idempotency + audit `APPROVED/CONFIRMED/DRAFT_READY/MARKED_SENT`. A future sender requires new ADR + separate permission/boundary.
- Manual-import abuse: verified-only + strict validation (sender domain, subject ≤300 chars, date bounds) + rate limit (e.g. 30/day/user) + XSS/HTML-strip + audit `MANUAL_EVIDENCE_IMPORTED`.
- Abuse: bucket4j/Redis rate limits (auth 5/min, scan 3/h, confirm 10/h), mailbox quotas, job cancellation.
- Logging: structured JSON, correlation ID, **redaction filter** (tokens, passwords, bodies, auth headers); code review gate "no secret in log".

## 5. Headers & Transport

Nginx: `Strict-Transport-Security, X-Content-Type-Options: nosniff, X-Frame-Options: DENY, Referrer-Policy: no-referrer, CSP` (tighten per frontend build), remove `Server` banner. Actuator exposed only internally.

## 6. Acceptance

- [ ] Password/JWT/refresh/rotation/lockout/re-auth specified; ownership at 3 layers.
- [ ] Required email verification + `403 EMAIL_NOT_VERIFIED` gate list specified.
- [ ] V1: no SEND scopes + no mail-send client asserted; manual-draft-only.
- [ ] Token encryption V1 env-key via interface + KMS migration path; secret handling + redaction specified.
- [ ] Each §4 control has a mapped test in `TESTING_STRATEGY.md` / `THREAT_MODEL.md`.
