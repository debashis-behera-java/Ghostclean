# GhostClean — PHASE_03 (Google OAuth Connections) — IMPLEMENTED

> Implemented on top of Phase 2 (verified). Phase 0 architecture remains
> authoritative; deviations are documented below with rationale.

## 1. What Phase 3 does (and does not do)

Connects an authenticated, verified GhostClean user's Google account via a
server-side OAuth 2.0 authorization-code flow (+PKCE) and stores the
connection encrypted. It does NOT scan Gmail, read messages, detect
services, send anything, or touch Microsoft/Outlook.

## 2. Scope decision (identity now, mailbox later)

- Phase 3 requests **identity scopes only**: `openid email profile`.
  Sufficient to establish the connection and resolve the stable Google
  account id (`sub`) + email via OIDC userinfo. No mailbox access.
- The mailbox-read scope lives behind `oauth.google.mailbox-scope`,
  **empty by default**. Only an explicitly configured value is ever
  requested (Phase 4 sets it deliberately). Verified by build tests that
  the Phase 3 authorize URL contains no mailbox scope literal.
- No message-send/compose/modify scope exists anywhere in code, config,
  or tests (`NoSendCapabilityTests` enforces this at build time).

## 3. Endpoints (per `API_SPECIFICATION.md`)

| Method | Path | Result |
|---|---|---|
| GET | `/oauth/{provider}/authorize?returnUrl=` | 200 `{authorizationUrl, state, expiresAt}` (bearer, verified); 410 for non-Google |
| GET | `/oauth/{provider}/callback?code&state` | 302 to validated return URL (`?provider&status&error`); 400 JSON when no trusted redirect exists |
| GET | `/oauth/connections` | 200 safe list (masked email, no token material) |
| DELETE | `/oauth/connections/{id}` | 204, idempotent, 404-masked cross-user |
| POST | `/oauth/connections/{id}/refresh` | 200 rotated status / 400 reconnect-required, 429-guarded |

## 4. State protection

256-bit random state (SHA-256 hash stored), 10-minute expiry, exactly-once
consumption (expired rows are marked used on discovery), server-side binding
to the initiating user (callback is unauthenticated by necessity; ownership
resolves from the state row). PKCE S256 verifiers stored per state and sent
at exchange. Replayed/foreign/expired states → safe failure, no oracle.

## 5. Token handling

- Exchange is server-side (secret never leaves the backend); access tokens
  stay server-side (userinfo call only) and are stored AES-GCM encrypted.
- Refresh tokens stored encrypted; a grant that withholds one keeps the
  previously stored value. Browsers receive no provider tokens, ever.
- Expiry tracked (`expires_at`); forced refresh updates credentials or marks
  `EXPIRED` with a reconnect signal. Disconnect revokes best-effort at
  Google (failures swallowed, local wipe unconditional), nulls token columns,
  keeps the revoked stub.
- Uniqueness on `(user, provider, account)` makes reconnect an update, never
  a duplicate.

## 6. Errors & audit

Envelope codes: `OAUTH_DENIED` (user denial → 302 error),
`OAUTH_STATE_MISMATCH` (400 JSON), `PROVIDER_ERROR` (502; exchange/account/
unconfigured failures), `SCOPE_INSUFFICIENT`, `OAUTH_PROVIDER_DISABLED`
(410). Provider details, codes, and tokens never leave the server.
Audit: `OAUTH_CONNECT_STARTED/SUCCEEDED/FAILED`, `OAUTH_DISCONNECTED`
(provider/status identifiers only).

## 7. Rate limits (in-memory, per the Phase 2 pattern)

authorize 10/min/user · callback 30/min/IP · disconnect 10/min/user ·
connection-refresh 5/min/user.

## 8. Migration

`V4__oauth_google.sql`: `oauth_connections` (unique
`(user,provider,account)`, status CHECK, expiry index) + `oauth_states`
(unique state hash). Deliberate deviation: encrypted columns are `TEXT`
(the crypto seam exchanges Base64 strings; BYTEA would add conversions
with identical security properties).

## 9. Tests

24 classes / 104 tests green (`mvn test`), incl. scripted-provider flow
tests (no live Google): gating (401/403/410), URL shape + identity-only
scopes, state strength/expiry/single-use/binding, denial/exchange/account
failures, encrypted persistence + decrypt round-trip, duplicate upsert,
withheld-refresh retention, disconnect (wipe/revoke/idempotency), revoked
unusability, cross-user 404-mask, refresh rotation + rate cap, audit
content without secrets, log capture without credentials, ArchUnit
boundaries, and all Phase 1/2 suites still green.

## 10. Manual verification (developer-owned test account)

See `docs/GOOGLE_OAUTH_SETUP.md`: create your own Google Cloud OAuth
client, set `GOOGLE_CLIENT_ID/SECRET/REDIRECT_URI` locally, connect,
verify, disconnect. No credentials in the repo, ever. Google verification
status: **not started — required before any public production use.**

## 11. Known limitations (carried forward, not blockers)

- Expired/used `oauth_states` rows accumulate until the Phase 4 cleanup job.
- Concurrent double-callback of one state fails safe (provider rejects the
  reused code) but is not single-flight serialized.
- `expires_at` may be null when a provider omits `expires_in`; treated as
  unknown, not as valid.
- Google `email_verified` from userinfo is recorded implicitly (email
  stored) but not separately gated; GhostClean's own verification gates
  features.
- Rate limiting stays single-instance in-memory (ADR-019).
