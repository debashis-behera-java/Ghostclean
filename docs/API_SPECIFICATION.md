# GhostClean — API Specification (REST, v1 `/api/v1`)

> Conventions: JSON; auth `Bearer <accessJwt>` except refresh (httpOnly cookie) + OAuth callbacks; envelope `{ "data": …, "meta": { "requestId": … } }`; errors `{ "error": { "code": "SCREAMING_SNAKE", "message": "human", "details": … } }`; time RFC3339 UTC; pagination `?page&size&sort` → `{ data:[], page:{…} }`. `X-Request-Id` in/out. Idempotency: `Idempotency-Key: <uuid>` on POST scan / deletion-confirm / request-create.
> Phase 0 update (RESOLVED): email verification REQUIRED — sensitive endpoints return `403 EMAIL_NOT_VERIFIED` until verified; V1 MANUAL-DRAFT ONLY (confirm → final draft; `mark-sent` attestation; no automated send); manual metadata import endpoint; NO SEND scopes in V1.

## 1. Auth — `auth`

| M | Path | Auth | Req | Res | Err | Idem |
|---|---|---|---|---|---|---|
| POST | `/auth/register` | no | `{email,password}` | `201 {message, verificationRequired:true}` — IDENTICAL for new, duplicate-unverified, and duplicate-verified addresses (no account oracle; verified duplicates cause no side effects). Unverified duplicates re-issue a token. + audit | 400 validation/weak, 429 | no |
| POST | `/auth/verify` | no | `{token}` | `204` (sets `email_verified=true`, invalidates token) + audit `EMAIL_VERIFIED` | 400/410 invalid/expired | no |
| POST | `/auth/verification/resend` | no/optional-bearer | `{email}` (always 202, 5/24h cap per account+IP) | `202` | 429 | no |
| POST | `/auth/login` | no | `{email,password}` | `{accessToken, tokenType:Bearer, expiresIn, emailVerified}` + Set-Cookie refresh. Works while unverified (flagged); unknown email and wrong password return byte-identical 401s (dummy-hash compare). 5 fails/account → 423 locked | 401, 423 locked, 429 | no |
| POST | `/auth/refresh` | cookie | — | new access + rotated cookie | 401 revoked/expired | no |
| POST | `/auth/logout` | bearer+cookie | — | `204` revoke session | 401 | no |
| POST | `/auth/password/forgot` | no | `{email}` — IDENTICAL `202 {message}` whether the address exists or not (no account oracle; unknown/deleted addresses cause no side effects). Known accounts: issues a CSPRNG single-use token (SHA-256 hash at rest, 30m TTL via `AUTH_RESET_TOKEN_TTL`, supersedes prior tokens) + Resend mail. Caps: 10/min/IP, 5/24h per address (also bounds Resend calls). + audit `PASSWORD_RESET_REQUESTED` | 429 | no |
| POST | `/auth/password/reset` | no | `{token,newPassword}` | `200 {message}` — consumes the token, sets the BCrypt hash (existing password policy), revokes all refresh sessions, clears lockout; verification state untouched. Error codes never reveal account existence | 400 invalid/used/weak (`INVALID_TOKEN`/`VALIDATION_ERROR`), 410 expired (`EXPIRED_TOKEN`), 429 | no |
| GET | `/auth/password/reset?token=` | no | browser link | `200` HTML password-entry form for a live token (validated, NOT consumed; posts JSON to `POST /password/reset` via fetch, then navigates to the token-free success page); missing/malformed/invalid/expired → `302 /api/v1/auth/password/reset/failed` | — | no |
| GET | `/auth/password/reset/success` | no | — | `200` token-free HTML success page | — | no |
| GET | `/auth/password/reset/failed` | no | — | `400` generic token-free HTML failure page (no validity oracle) | — | no |

Lockout: 5 fails → 15m lock + audit `LOGIN_FAILED`. Verification gate: until `email_verified=true`, these return `403 EMAIL_NOT_VERIFIED`: `GET /oauth/*/authorize`, `POST /scans`, `POST /evidence/manual`, `POST /discovered/{id}/review`, all `POST /deletion-requests*` mutations, `GET /users/me/export`.

## 2. Users & Settings

| M | Path | AuthZ | Notes |
|---|---|---|---|
| GET/PATCH | `/users/me` | owner | profile, no password hash out |
| GET/PATCH | `/users/me/settings` | owner | retention, reminders, jurisdiction default |
| DELETE | `/users/me` | owner + re-auth (`X-Reauth`) | cascade delete GhostClean data; `202` + job id |
| GET | `/users/me/export` | owner | export discovered+requests (JSON), rate-limited |

## 3. OAuth

| M | Path | Notes |
|---|---|---|
| GET | `/oauth/{provider}/authorize?returnUrl=` | bearer; returns `{authorizationUrl, state}`; provider ∈ `google,microsoft`(410 if disabled) |
| GET | `/oauth/{provider}/callback?code&state` | **no bearer** (state+PKCE verify, open-redirect guard); exchanges server-side; `302` to SPA `?connected|error=` |
| GET | `/oauth/connections` | list `{id,provider,providerEmail,scopes,status,connectedAt}` — never tokens |
| DELETE | `/oauth/connections/{id}` | revoke at provider best-effort + wipe tokens; `204` |
| POST | `/oauth/connections/{id}/refresh` | force refresh (429-guarded) |

Errors: `OAUTH_DENIED, OAUTH_STATE_MISMATCH, PROVIDER_ERROR, SCOPE_INSUFFICIENT` with actionable messages.

## 4. Scans

| M | Path | Notes |
|---|---|---|
| POST | `/scans` | `{connectionId, type: full\|delta}` + Idempotency-Key → `202 {scanId,status:queued}` |
| GET | `/scans` | list with status/progress |
| GET | `/scans/{id}` | `{status, progressDone/Total, watermark, error}` (poll) |
| POST | `/scans/{id}/cancel` | `queued|running → cancelled` else 409 |
| DELETE | `/scans/{id}` | delete run record (not evidence) |
| POST | `/evidence/manual` | bearer + verified only; `{sender, subject(≤300), receivedAt}` validated, rate-limited (e.g. 30/day) + Idempotency-Key → `202 {evidenceId, source:manual, label:"Manual/degraded"}`; bodies never accepted |

## 5. Discovered Services + Evidence

| M | Path | Notes |
|---|---|---|
| GET | `/discovered?risk&confidence&category&sort&search` | paged accounts; 404-mask cross-user (return 404 not 403 for IDs) |
| GET | `/discovered/{id}` | detail + confidence breakdown + risk factors + directory snapshot |
| POST | `/discovered/{id}/review` | `{decision: keep\|dismiss, reason?}` → status |
| GET | `/discovered/{id}/evidence` | paged evidence (sanitized subjects, no bodies) |
| DELETE | `/discovered/{id}/evidence-purge` | user-triggered evidence purge |

## 6. Deletion Requests (two-gate, V1 MANUAL-DRAFT ONLY)

| M | Path | Notes |
|---|---|---|
| POST | `/deletion-requests` | verified only; `{discoveredServiceId, jurisdiction, idempotencyKey}` → draft `REVIEW_REQUIRED` |
| GET | `/deletion-requests?status` | list |
| GET | `/deletion-requests/{id}` | detail + deadline + recipient + state history + final-draft view (after confirm) |
| POST | `/deletion-requests/{id}/approve` | gate 1: intent → `USER_APPROVED` |
| POST | `/deletion-requests/{id}/generate` | render template preview → `REQUEST_GENERATED` (no send) |
| POST | `/deletion-requests/{id}/confirm` | **gate 2**: requires `X-Reauth` + Idempotency-Key → `USER_CONFIRMED` + returns **FINAL DRAFT** (subject/body/recipient/checklist). Creates NO send side-effect in V1 |
| POST | `/deletion-requests/{id}/mark-sent` | user attestation `{sentAt, channel: manual_draft}` → `REQUEST_SENT`(user-sent) → `AWAITING_RESPONSE`; drives deadlines/reminders |
| POST | `/deletion-requests/{id}/response` | record provider response `{outcome: completed\|rejected\|partial\|verification_required, summary}` |
| GET | `/deletion-requests/{id}/events` | state history |

Illegal transitions → `409 INVALID_TRANSITION`. Confirm without approve/generate → 409. V1 has no automated-send endpoint by design (no single-call create+send; no `sendChannel: api`).

## 7. Audit / Activity / Notifications

- `GET /activity` (own audit, redacted, paged), `GET /notifications?prefs`, `PATCH /notifications/{id}/read`.
- Admin/global audit explicitly out of scope v1 (no admin role).

## 8. Error Codes (stable)

`VALIDATION_ERROR, UNAUTHORIZED, INVALID_CREDENTIALS, ACCOUNT_LOCKED, INVALID_TOKEN, EXPIRED_TOKEN, EMAIL_NOT_VERIFIED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, INVALID_TRANSITION, OAUTH_*, PROVIDER_UNAVAILABLE, SCAN_CONFLICT, IDEMPOTENCY_REPLAY, PAYLOAD_TOO_LARGE, INTERNAL`. HTTP maps standard; 404 for cross-user IDs to avoid oracle; 403 (not 404) for unverified-gate so users understand verification is required.

## 9. Acceptance

- [ ] Every endpoint has method/path/auth/authZ/req/res/errors/idempotency above.
- [ ] No endpoint sends deletion in one call; confirm requires re-auth + idempotency key.
- [ ] No tokens/bodies/passwords in any response schema.
