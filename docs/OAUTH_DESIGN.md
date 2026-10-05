# GhostClean — OAuth Design (Multi-Provider Abstraction)

> Phase 0 update (RESOLVED): V1 = READ-only, NO SEND scopes requested; Gmail-denied → degraded manual-forward/import mode (no bypass, no wider scopes, no fabricated access); OAuth/scan endpoints require verified email; token encryption V1 = env key, KMS later.

## 1. Abstraction

```java
package com.ghostclean.oauth.spi;

public interface EmailProviderConnector {
  String provider(); // "google" | "microsoft"
  String buildAuthorizationUrl(AuthorizeCmd cmd); // state+PKCE+scopes
  TokenBundle exchangeCode(CallbackCmd cmd);      // server-side, validate state
  TokenBundle refresh(TokenBundle expired);
  void revoke(TokenBundle tokens);                // best-effort
  ProviderAccount fetchAccount(TokenBundle tokens);
  MailPage fetchMetadata(MailQuery q, TokenBundle tokens); // metadata only
}
```

Core (`scanner`, `mailbox`, `detection`) depends only on the interface. `GoogleMailConnector`, `MicrosoftMailConnector` live in `oauth.infra`. Adding a provider = new impl + scopes config + Flyway enum value; no core rewrite. Contract tests mock the interface.

## 2. Flows

**Authorize:** `GET /oauth/{provider}/authorize` (bearer, requires `email_verified=true` else `403 EMAIL_NOT_VERIFIED`) → create signed `state` (10m, `{userId, nonce, returnUrl}`) + PKCE verifier (server session/Redis) → return provider URL. SPA shows scope explainer first.
**Callback:** `GET /oauth/{provider}/callback?code&state` (no bearer) → verify state sig+expiry, check PKCE, allow-list `returnUrl` (same-origin) → `exchangeCode` server-side → fetch provider account id/email → encrypt tokens (AES-GCM, fresh IV) → upsert `oauth_connections(user,provider,provider_account_id)` → audit `OAUTH_CONNECTED` → `302` SPA `?connected=google|error=…`.
**Refresh:** on 401/expiry: single-flight refresh per connection (Redis lock) → update `*_enc`, `expires_at` → retry once → else `status=expired` + notify reconnect.
**Disconnect:** `DELETE /connections/{id}` → provider revoke call (Google `revoke`, MS `logout`/token invalidation, failures logged not fatal) → null token cols + `status=revoked` → release locks/cancel runs → audit `OAUTH_REVOKED`.

## 3. Scopes (minimum-first; V1 READ-only; degraded manual mode if denied)

**Google (Gmail) V1:**
- READ baseline: `openid email profile` + `https://www.googleapis.com/auth/gmail.readonly`. Required for metadata scan.
- V1 FORBIDDEN: `https://www.googleapis.com/auth/gmail.send` MUST NOT be requested in V1 (no SEND permission, no mail-send client, CI-enforced). A future sending capability needs a separate permission grant + separate security boundary + new ADR.
- Do NOT request `gmail.modify/delete`, Drive, Contacts.

**Microsoft (Phase 10):**
- READ: `openid email profile offline_access` + `https://graph.microsoft.com/Mail.Read`.
- `https://graph.microsoft.com/Mail.Send` is post-V1 only (same gating).

### Degraded manual-forward/import mode (RESOLVED, Gmail-denied path)
If the Gmail metadata permission is unavailable, denied, restricted, or Google verification is incomplete:
- DO NOT bypass Google restrictions. DO NOT request broader scopes. DO NOT fabricate access.
- Offer manual mode: verified user submits registration-email **metadata only** (sender domain/address, subject ≤300 chars, received date) via `POST /api/v1/evidence/manual` (rate-limited, validated, XSS-stripped). The backend runs the same normalize→detect→resolve pipeline with `source=manual`.
- Label everywhere: "Manual mode — mailbox not connected. Evidence is user-provided, not mailbox-verified." Confidence capped (single manual item never HIGH); manual evidence carries `source=manual` + lower weight; abuse caps + audit `MANUAL_EVIDENCE_IMPORTED`.
- Full bodies/attachments are never required and never stored in manual mode either.

## 4. Token Storage / Expiry / Failure / Rate

- Storage: `access_token_enc/refresh_token_enc BYTEA` + `token_iv` + `key_version`, key ONLY from env `TOKEN_ENCRYPTION_KEY` in V1 (never committed/logged; fail-fast if missing/weak); future swap to cloud KMS (AWS KMS / Azure Key Vault / GCP KMS) via `TokenCryptoProvider` with no schema/API change; tokens never in logs/URLs/responses; memory held briefly, zeroed where feasible.
- Expiry: `expires_at` checked pre-call with 5m skew; proactive refresh job flags expiring-soon.
- Failures: `invalid_grant` → `revoked/expired` + reconnect CTA; `rateLimitExceeded/429` → exponential backoff + job pause; `5xx` → retry 3× (1s,5s,30s) then `failed` run preserving watermark; user-actionable error strings (no token leakage).
- Rate: per-connection token bucket (e.g. 60/min Google, per Graph limits MS) + global scan concurrency cap (Redis semaphore); respect `Retry-After`.
- Provider account id: Google `sub`/Gmail `profile.id`; MS `oid`/`id` — used for unique `(user,provider,account)` + mismatch detection (reconnect to different inbox warns before merging).

## 5. Consent Management

Scope explainer UI (V1: READ-only; SEND explicitly listed as "not requested in V1"), granted-scopes display in Connections, re-consent diff on scope change, consent receipt + revocation receipt in audit. Partial consent → run with granted subset + banner; denied metadata scope → manual-mode offer (never silent).

## 6. Acceptance

- [ ] Interface + flows + storage/refresh/revoke/disconnect/failure/rate documented; V1 READ-only, NO SEND requested (CI-enforced), manual mode specified with no-bypass rule.
- [ ] Minimum scopes listed; degraded-mode + no-fake-credentials rules respected.
