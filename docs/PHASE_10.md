# GhostClean — PHASE_10 (Microsoft/Outlook Connector) — IMPLEMENTED

> Implemented on top of Phase 9 (verified) per the roadmap mandate
> ("second connector, no core rewrite") and explicit human architecture
> decisions for tenancy, scopes, verification, refresh, configuration, and
> throttling. No previous-phase behavior was changed except two minimal,
> behavior-preserving generalizations (provider-neutral seams + a stale
> test). The integration is delegated **READ-ONLY**: GhostClean does not
> send, modify, or delete Microsoft email, and publisher verification has
> NOT been completed.

## 1. Objective

Give Outlook/Microsoft-account users the same connect → scan → evidence
pipeline Google users have (Phases 3–4), through the existing
provider-neutral seams — no core rewrite, no new routes, no new tables,
no SEND capability, no scheduler, no fabricated evidence.

## 2. Architecture decisions (human)

1. **Tenancy (delegated only).** One Microsoft connection belongs to
   exactly one GhostClean user; all operations enforce ownership
   (foreign ids → 400/404, never cross-user access). No enterprise
   administration, no Graph application permissions.
2. **READ-ONLY mail permission.** Delegated scopes:
   `openid profile offline_access User.Read` +
   `https://graph.microsoft.com/Mail.Read`. `Mail.Send`,
   `Mail.ReadWrite`, and the `.Shared` variants are post-V1 only and
   appear nowhere in code, config, or tests (build-enforced). No SMTP,
   no `JavaMailSender`, no message mutation or deletion.
3. **Publisher verification NOT complete.** Never claimed; documented
   here and in `docs/MICROSOFT_OAUTH_SETUP.md` as a release requirement.
4. **Refresh/re-consent.** Reuses the Phase 3 token architecture
   (`offline_access` for refresh support); tokens stay server-side,
   AES-GCM encrypted. Permanently invalid grants mark the connection
   `EXPIRED` (single reactive refresh, no infinite retry); the scan
   surfaces `MICROSOFT_REAUTH_REQUIRED` + audit and stops.
5. **Configuration.** `oauth.microsoft.*` + `graph.*` namespaces;
   authority/tenant/redirect/Graph URLs settable, never hard-coded;
   secrets env-only, never committed (`MICROSOFT_*` placeholders only).
6. **Throttling/pagination.** Bounded Graph calls: 429 honored with
   `Retry-After`, bounded retries, same-origin `@odata.nextLink` only,
   page/message/time/API-call ceilings, no parallel fan-out.

## 3. OAuth lifecycle (existing routes, per `API_SPECIFICATION.md`)

No new routes. `GET /oauth/{provider}/authorize` (bearer, verified;
410 only for truly unwired providers) → `GET
/oauth/{provider}/callback` (unauthenticated, state+PKCE, 302) →
`GET /oauth/connections` (masked) → `DELETE
/oauth/connections/{id}` (204, wipe) → `POST
/oauth/connections/{id}/refresh` (200/400, 429-guarded).

- `MicrosoftOAuthProperties` (`oauth.microsoft`): kill-switch,
  client id/secret/redirect, authority + tenant (endpoints composed,
  default `common`), Graph base URL, identity scopes, `Mail.Read`
  mailbox scope, 10m state TTL.
- `MicrosoftOAuthClient implements OAuthProviderClient`
  (`providerId()="microsoft"`, `@Profile("!oauth-fake")`): Entra
  authorize URL (+PKCE S256, `prompt=consent`), server-side code
  exchange/refresh, identity via Graph `/me?$select=id,mail,
  userPrincipalName` (stable `id`, `mail` else UPN). No provider-side
  revocation API exists for delegated permissions, so `revoke()` is a
  documented no-op — the local wipe is the guarantee and no provider
  revocation is ever claimed. Secret server-side only; statuses/shapes
  logged, never codes/tokens/secrets/bodies.
- `OAuthService` generalization (minimal): per-client
  `isConfigured()`/`stateTtl()`/`requestedScopes()` default methods on
  `OAuthProviderClient` (Google overrides delegate to its properties);
  the Google-only `GoogleOAuthProperties` dependency is removed.
  Behavior for Google is byte-identical (all Phase 3 tests green
  unchanged, except `microsoftIsGone` → `unwiredProviderIsGone`).

## 4. Token storage

Reused exactly: `oauth_connections(user,provider,account)` unique row,
AES-GCM ciphertext + `key_version` via `TokenCryptoProvider`, expiry
tracking, disconnect nulls token columns and keeps the `REVOKED` stub.
No second encryption system, no plaintext, no token files.

## 5. Graph mailbox reading (metadata only)

- `GraphMailboxReader implements MailboxReader`
  (`providerId()="microsoft"`, `@Profile("!graph-fake")`): exactly two
  read endpoints — `GET /v1.0/me/messages` (`$select=id`, `$top`,
  `$filter`, `$orderby`, `$count`) and `GET
  /v1.0/me/messages/{id}` (`$select=id,receivedDateTime,from,subject,
  internetMessageHeaders`). No bodies, no attachments, no other Graph
  surface referenced (capability-scan enforced).
- Pagination follows `@odata.nextLink` **only** when it is an HTTPS URL
  on the configured Graph host (`isTrustedNextLink`, unit-tested);
  anything else ends the walk. `GraphQueryBuilder` builds the OData
  `$filter` (lookback date + escaped `contains(subject,…)` hints).
- Failures normalize to `GraphApiException extends MailboxApiException`
  (401/404/429+`Retry-After`/403-scope/5xx/timeout), so the scanner's
  existing bounded retry/refresh/terminal mapping applies unchanged
  (`GmailApiException` now extends the same base; Phase 4 untouched).

## 6. Scan integration (same pipeline, per-provider budgets)

`ScanService` resolves reader/budgets/query/scopes/codes by provider
key (`readers` map, `ProviderScanConfig` record): Microsoft scans use
`graph.*` budgets, the Graph `$filter`, and the `Mail.Read` scope
check; evidence rows carry `provider="microsoft"` under the existing
unique `(user,provider,messageId)` dedupe. Detection, directory,
accounts, dispatch (Phase 7), and tracking (Phase 9) are untouched and
provider-agnostic. New codes `MICROSOFT_CONNECTION_REQUIRED` /
`MICROSOFT_REAUTH_REQUIRED` (+ `MICROSOFT_REAUTH_REQUIRED` audit) mirror
the Google convention. Google scan behavior is unchanged.

## 7. Detection language

Microsoft evidence is **detected-account evidence** (same confidence
model): candidate registrations, never proof an account exists, the
user registered, or any deletion request was received. No new claims.

## 8. Audit

Reused provider-neutral vocabulary with `provider="microsoft"`:
`OAUTH_CONNECT_STARTED/SUCCEEDED/FAILED`, `OAUTH_DISCONNECTED`,
`SCAN_STARTED/COMPLETED/FAILED/CANCELLED`,
`ACCOUNT_EVIDENCE_DETECTED`, `MICROSOFT_REAUTH_REQUIRED`. Payloads
carry ids/status/provider/counts only — serialized audits asserted
free of tokens, subjects, and secrets.

## 9. Security / privacy

Ownership + verified gate on every endpoint; state+PKCE+same-origin
return URLs; SSRF allow-list already covered Graph/Entra hosts; no
token material in URLs/logs/audits/responses; metadata-only fetch;
per-user rate limits; revoked stubs retained per policy. Publisher
verification NOT complete — testing tenants only.

## 10. Testing

`mvn test` at Phase 10 completion: **383 tests, 0 failures** (historical record) — 5
`MicrosoftOAuthConfigUnitTests` + 4 `GraphReaderUnitTests` + 3
`GraphQueryBuilderTests` + 23 `MicrosoftOAuthFlowTests` (scripted
Entra/Graph double, synthetic data) + 17 `MicrosoftScanFlowTests`
(scripted Graph double) + all Phase 0–9 suites green at that time (331 pre-existing,
1 renamed: `microsoftIsGone` → `unwiredProviderIsGone` since Microsoft
is now wired). Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-10 completion record. Covers: scope discipline (no SEND/WRITE literals),
tenant configurability, state valid/invalid/expired/replayed,
cross-user isolation, evil return URLs, denial/exchange/account
failures, encrypted storage + decrypt round-trip, reconnect upsert +
withheld-refresh retention, disconnect wipe + idempotency, refresh
rotation + rate cap, invalid-refresh → EXPIRED (no infinite retry),
audit/log hygiene, Graph pagination trust boundary, malformed payloads,
lookback $filter shape with single-quote escaping and date-only fallback,
message/page limits, 429→bounded-retry→success,
exhaustion→`PROVIDER_ERROR`, scope-denial→`SCOPE_INSUFFICIENT`,
reauth→`MICROSOFT_REAUTH_REQUIRED`, dedupe, capability scans (existing
`NoSendCapabilityTests`/`NoDeletionActionTests` cover all new sources),
scheduler-annotation absence. No live Microsoft account touched.

## 11. Configuration

`application.yml`: `oauth.microsoft.*` (`MICROSOFT_CLIENT_ID/SECRET/
REDIRECT_URI`, optional `MICROSOFT_TENANT`, `MICROSOFT_AUTHORITY`,
`MICROSOFT_GRAPH_BASE_URL`, `MICROSOFT_MAILBOX_SCOPE`) + `graph.*`
budgets (`GRAPH_MAX_MESSAGES_PER_SCAN`, `GRAPH_LOOKBACK_MONTHS`).
`.env.example`: placeholders only. Unconfigured Microsoft answers
`authorize` with safe `PROVIDER_ERROR` (same posture as Google).

## 12. Limitations

- Publisher verification + any Microsoft security assessment: **not
  started** (pre-existing pattern, release-gating).
- Single-tenant Entra default is `common`; deployments needing a fixed
  tenant set `MICROSOFT_TENANT` (documented, not validated here).
- Single-instance in-memory limits; synchronous scans; generic-envelope
  for unmapped methods (shared behaviors, untouched).
- `gmail.readonly` verification not started (pre-existing).

## Explicit guarantees

- PHASE 10 IS DELEGATED READ-ONLY.
- GHOSTCLEAN DOES NOT SEND MICROSOFT EMAIL.
- GHOSTCLEAN DOES NOT MODIFY MICROSOFT EMAIL.
- GHOSTCLEAN DOES NOT DELETE MICROSOFT EMAIL.
- GHOSTCLEAN DOES NOT AUTOMATICALLY SEND DELETION REQUESTS.
- NO `Mail.Send`/`Mail.ReadWrite` SCOPE EXISTS IN V1.
- NO SMTP OR `JavaMailSender` EXISTS IN V1.
- PUBLISHER VERIFICATION HAS NOT BEEN COMPLETED.
- NO IN-PROCESS SCHEDULER IS USED.
- MICROSOFT EVIDENCE IS CANDIDATE DETECTION, NOT PROOF.
