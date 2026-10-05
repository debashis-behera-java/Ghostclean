# GhostClean — PHASE_02 (Authentication) — IMPLEMENTED

> Implemented 2026-09-11 on top of Phase 1 (verified). Phase 0 architecture
> remains authoritative; deviations are documented below with ADRs.

## 1. Implemented features

- `POST /api/v1/auth/register` → 201 `{message, verificationRequired:true}`,
  identical for new, duplicate-unverified, and duplicate-verified addresses
  (no account oracle, ADR-017).
- Required email verification: 32-byte url-safe token, SHA-256 hash stored,
  24h expiry, single-active (old invalidated on resend), single-use with
  replay rejection, transactional consume.
- `POST /api/v1/auth/verify` → 204; `POST /api/v1/auth/verification/resend`
  → 202 always (rate-capped, no oracle, no spam vector).
- `POST /api/v1/auth/login` → 200 `{accessToken, tokenType, expiresIn,
  emailVerified}` + `gc_refresh` httpOnly/Lax cookie. Works while unverified
  (flagged) per the security model; sensitive features gate later.
- Short-lived JWT access (default 15m, HS256/HS512 auto by key size, minimal
  claims `iss/sub/jti/iat/exp/scope/ev`), validated by
  `JwtAuthenticationFilter` on every non-public route.
- Opaque 256-bit refresh sessions (SHA-256 hash stored), 30d expiry,
  rotation on every use, reuse → whole-chain revocation, logout revocation.
- `POST /api/v1/auth/refresh` (cookie) → rotated pair; `POST
  /api/v1/auth/logout` → 204 + cleared cookie.
- `GET /api/v1/users/me` from the principal only (no `{id}` route —
  cross-user access impossible by construction).
- Abuse protection: per-IP/endpoint sliding windows + 5 fails/account →
  15m persisted lockout (423). Single-instance in-memory (ADR-019).
- `VerificationEmailSender` seam; `silent` default, `log` for local dev with
  startup warning. No external provider (unselected, by design).
- Audit events for every transition; metadata denylist enforced in code;
  audit commits independently so failures are still recorded.

## 2. Authentication flow

```
REGISTER (201, always) → token issued → VERIFY (204) → user active
LOGIN (200 + cookie; emailVerified flag) → Bearer JWT (15m) on API calls
REFRESH (cookie, rotates) → new pair → LOGOUT (204, revokes + clears)
```

## 3. Email verification flow

```
USER CREATED (email_verified=false) → token (hash stored, 24h)
→ user clicks link → VERIFY checks hash/expiry/used → used_at set,
   siblings invalidated, user active → EMAIL_VERIFIED audit
RESEND: invalidate actives → new token → 202 (verified/unknown: silent 202)
```

## 4. Token lifecycle

- Verification: random 256-bit → url-safe; hash-only storage; 24h;
  consumed on success AND on expiry discovery (anti-replay); resend rotates.
- Access JWT: 15m; stateless; logout does not revoke (expires naturally).
- Refresh: opaque 256-bit; hash-only storage; 30d; single-use rotation;
  presented-after-rotation ⇒ theft assumption ⇒ revoke all user sessions.

## 5. Session lifecycle

`created (login)` → `rotated (refresh)` → … → `revoked (logout|expiry|
reuse-detection)`; `rotated_from` links the chain for forensics.

## 6. API endpoints

| Method | Path | Result |
|---|---|---|
| POST | `/auth/register` | 201 (oracle-free), 400 weak/invalid, 429 |
| POST | `/auth/verify` | 204, 400 invalid/used, 410 expired, 429 |
| POST | `/auth/verification/resend` | 202 always, 429 |
| POST | `/auth/login` | 200 + cookie, 401 identical, 423 locked, 429 |
| POST | `/auth/refresh` | 200 rotated / 401, 429 |
| POST | `/auth/logout` | 204 + cleared cookie / 401 |
| GET | `/users/me` | 200 own profile / 401 |

## 7. Database migration

- `V2__auth_phase2.sql`: `users.locked_until`, `users.failed_login_attempts`
  (default 0), `users.last_login_at` + partial lockout index.
- `V3__audit_independence.sql`: drops `audit_logs→users` FK (audit must
  commit independently of audited rows; nullification stays an
  application job). Deviation from DATABASE_DESIGN §2, justified + tested
  (`auditTrailHasNoUserForeignKey`).

## 8. Security decisions

- BCrypt (framework default cost) for passwords — salted, adaptive, hashed
  never encrypted (allowed alternative in SECURITY_MODEL §1).
- JJWT HS256/HS512 (algorithm follows key size; ≥256-bit secret, fail-fast
  on missing/weak `JWT_SIGNING_KEY`).
- Hashes only: refresh tokens, verification tokens (SHA-256 hex).
- Dummy-hash compare on unknown emails (constant-shape 401s).
- `AuthException` + envelope codes; 401/423/429/400/410 semantics.
- `AuthException`/`AuthPrincipal` live in `common` (ArchUnit: no cycles).

## 9. Rate limits (defaults, all env-overridable)

login 5/min/IP · register 5/min/IP · verify 10/min/IP · resend 10/min/IP +
5/day/email · refresh 30/min/IP · 5 account fails → 15m lock.

## 10. Local verification-email testing

```powershell
$env:VERIFICATION_MAIL_MODE="log"   # or in .env
docker compose up --build -d
# register, then:
docker logs ghostclean-api-1 | Select-String "Verification link"
# POST /api/v1/auth/verify {"token":"..."} → 204
```

`log` mode prints a startup warning and must never be enabled in production.
Production provider selection is explicitly deferred (open item).

## 11. Tests

22 classes / 77 tests green (`mvn -q test`), incl. 24-item security list:
registration ×6, verification ×7, login ×5, session ×7, adversarial ×5,
policy/JWT/limiter units, plus all Phase 1 suites still green. Live Docker
lifecycle also verified (register→verify→login→me→refresh→logout→401).

## 12. Acceptance

All 28 Phase 2 criteria met (see final report): build green, hashes,
required verification, expiring/single-use/non-replayable tokens, verified
login, unverified-flagged login, JWT validation, rotation, logout, rate
limits, 401 defaults, principal-only isolation, leak-free responses/logs,
no mail provider, no OAuth/scan/deletion/SEND, Flyway V1→V3, health 200,
Phase 1 suites green, meaningful Phase 2 tests.

## 13. Known limitations (carried forward, not blockers)

- Rate limiter is single-instance in-memory (Redis-backed later, ADR-019).
- Boot logs an unused generated security password at startup (JWT-only
  auth; no login mechanism consumes it).
- No password-reset endpoints yet (spec table lists them; deferred —
  recommended Phase 2b or Phase 3 pre-work, needs new ADR + mailer).
- `GET /users/me` returns `lastLoginAt:""` when never logged in (empty
  string instead of null — JSON-object constraint; client-tolerable).
- Verification-mail production provider unselected (open item).
- X-Forwarded-For trust deferred to Phase 12 edge termination.
