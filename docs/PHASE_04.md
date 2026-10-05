# GhostClean — PHASE_04 (Gmail Read-Only Scan + Detection) — IMPLEMENTED

> Implemented on top of Phase 3 (verified). Phase 0 architecture remains
> authoritative; deviations are documented below with rationale.

## 1. What Phase 4 does (and does not do)

Bounded, read-only Gmail mailbox scans over an owned Phase 3 Google
connection, deterministic rule-based detection of account-registration
evidence, and review of the resulting "Detected account evidence" cards.
It does NOT send, compose, modify, delete, or trash anything; NOT start
Phase 5 (no deletion requests, no GDPR/CCPA workflows, no mail sending,
no AI/ML, no additional providers).

## 2. Architecture

```
ScanController → ScanService → MailboxReader (GmailMailboxReader)
                                → DetectionEngine + DomainNormalizer
                                → ServiceDirectoryService (lookup)
                                → AccountService (evidence → discovered_services)
                                → OAuthService.refreshConnection (token refresh reuse)
                                → AuditService (safe events)
```

- `mailbox`: `GmailMailboxReader` — the only Gmail HTTP surface.
- `scanner`: `ScanService` — bounded synchronous orchestration, one active
  scan per user (app check + partial unique index
  `uq_active_scan_per_user`).
- `detection`: `DetectionEngine` (pure scorer) + `DomainNormalizer`.
- `services`: `ServiceDirectoryService` — curated domain lookup (V7 seed).
- `account`: `AccountService` — evidence upsert → one
  `discovered_services` row per `(user, service_key)`.
- Token refresh reuses Phase 3 exactly (`OAuthService.refreshConnection`,
  `TokenCryptoProvider`); no second encryption implementation exists.

## 3. Gmail scope

- Exactly one mailbox scope:
  `https://www.googleapis.com/auth/gmail.readonly`
  (default of `gmail.mailbox-scope` / `GOOGLE_MAILBOX_SCOPE`; identity
  scopes `openid email profile` unchanged from Phase 3).
- No broader scope was introduced. `gmail.send`, `gmail.compose`,
  `gmail.modify`, `mail.google.com` appear nowhere in code or config.

## 4. Gmail APIs used (read-only)

Exactly two endpoints, both `GET`:

| Call | Purpose |
|---|---|
| `GET /gmail/v1/users/me/messages?q&maxResults&pageToken&includeSpamTrash=false` | paged id listing |
| `GET /gmail/v1/users/me/messages/{id}?format=metadata&metadataHeaders=From,Subject,List-Unsubscribe` | per-message metadata (3 headers only) |

## 5. Read-only guarantee

- No `POST/PUT/PATCH/DELETE` to any Gmail surface; no batch endpoints.
- Write terms (`gmail.send`, `gmail.compose`, `gmail.modify`,
  `mail.google.com`, `users.messages.send/modify/delete/trash`,
  `batchModify`) have zero occurrences in `backend/src/main`
  (verified by repo grep + `NoSendCapabilityTests`, which fails the build
  if send scopes/mail senders appear). Remaining repo occurrences are
  negative/prohibitive references in design docs ("MUST NOT").

## 6. Scan strategy

Synchronous, bounded, in-request execution: `POST /api/v1/scans` runs the
full page loop and returns the finished record (`200`), not a queue stub.
Setup (gates, idempotency, row creation) commits first; the page loop runs
outside any long transaction and persists progress per page, so polling
and cooperative cancellation observe live state. One message is fetched
and processed at a time — the mailbox is never loaded into memory.

## 7. Pagination

`pageToken` loop over `users.messages.list`; `maxResults` = `pageSize`
(clamped 1–500). The loop stops at the first of: no `nextPageToken`,
message cap, page cap, API-call budget, wall-clock deadline, or
cancellation. Multi-page traversal is covered by
`paginationWalksEveryPage` (3 pages → 5 messages).

## 8. Scan limits (all in `GmailProperties`, env-overridable)

| Bound | Default |
|---|---|
| `pageSize` (per page) | 100 |
| `maxPages` | 5 |
| `maxMessagesPerScan` | 500 |
| `maxDurationSeconds` | 120 |
| `maxApiCalls` (list + get ceiling) | 1200 |
| `maxAttempts` per call (1 + retries) | 4 |
| backoff base / max (+ jitter ≤250ms, `Retry-After` honored) | 1000ms / 15000ms |
| `persistThreshold` (min score stored) | 10 |

Unlimited mailbox downloads are impossible by construction.

## 9. Lookback period

`lookbackMonths = 12` → `GmailQueryBuilder` emits
`newer_than:360d` plus the curated registration-evidence keyword group
(`subject:(welcome verify confirm …)`, 12 keywords). The exact query is
persisted per scan (`mailbox_scans.query_used`) and asserted in
`lookbackWindowIsSentToProvider`.

## 10. Evidence model (`service_evidence`)

One row per matched message; unique `(user_id, provider,
provider_message_id)` makes rescans idempotent (concurrent-write races
are absorbed via the constraint, not duplicated). Stored per row:
`sender_domain` (citext), `sender_address_hash` (SHA-256, no raw address),
`subject_truncated` (≤200 chars, control chars + `<>` stripped),
`received_at`, `matched_rules` (JSONB), `signal_snapshot`
`{band, score, directory}` (JSONB), `confidence_contrib`,
`provider_message_id` (opaque dedupe reference — never logged, never
returned raw). See §13 for what is NOT stored.

## 11. Detection rules (deterministic, no AI/ML/LLM/embeddings)

`DetectionEngine.score` is a pure function (no I/O, no randomness).
Weights: domain exact +30 / subdomain +18, sender-alias exact +20, known
service +10, registration subject +25, verification +20, security +12,
commercial +10; newsletter-only −20, list-header-only −10,
security-only −15. Clamp 0–100. Directory hits come from the V7 seed
(spotify, netflix, github, amazon, google, apple, microsoft, dropbox);
unknown senders score on subject signals alone. Sender domains are
normalized (lowercase/punycode, registrable-domain grouping) so three
Spotify senders become one account with `evidenceCount = 3`.

## 12. Confidence model (bands, not certainties)

`HIGH ≥ 70`, `MEDIUM ≥ 40`, `LOW < 40`. Strong evidence (directory +
registration/verification, e.g. Spotify welcome + verify) → HIGH. Single
commercial signal (e.g. GitHub receipt) → MEDIUM. Weak/ambiguous evidence
stays LOW and is still labeled **"Detected account evidence"** — Phase 4
has no "confirmed" state and never claims one.

## 13. False-positive handling

Scores below `persistThreshold` (10) are dropped (newsletter bulk mail).
Newsletter-only and list-header signals are discounted; a lone security
notice is discounted to the floor. Covered by
`newsletterMentionIsNotStored`, `weakEvidenceStaysLowAndUnconfirmed`,
`mediumConfidenceFromCommercialMail`.

## 14. Privacy / data minimization

Persisted: §10 only. Explicitly NOT persisted: complete email bodies,
MIME content, attachments, unnecessary headers, unrelated mailbox
content. The reader requests `format=metadata` with 3 headers; bodies are
never fetched. Enforced by `evidenceTableHoldsNoBodiesOrAttachments`
(schema assertion) and `apiResponsesCarryNoCredentialsOrBodies`.

## 15. Retention

Run rows: 90d (`mailbox_scans`); evidence: 180d default per
`DATABASE_DESIGN.md`; provider tokens wiped on disconnect (revoked stub
retained). `DELETE /scans/{id}` removes finished run records only
(evidence untouched); `DELETE /discovered/{id}/evidence-purge` removes
evidence and drops emptied accounts.

## 16. Rate limits (in-memory, Phase 2 pattern)

`scan:start` 5/hour/user (`scanRateLimitStopsSpam` asserts 5×200 then
429 `RATE_LIMITED`); `scan:cancel` 30/min/user; refresh reuse keeps the
Phase 3 caps (connection-refresh 5/min/user).

## 17. Token refresh

Proactive refresh inside the 5-minute expiry skew, plus one reactive
refresh per scan on a mid-scan 401; anything harder fails safe to
`GOOGLE_REAUTH_REQUIRED` (scan `FAILED`, connection `EXPIRED`,
`GOOGLE_REAUTH_REQUIRED` audit). Fix note (this run): a rejected refresh
previously lost its `EXPIRED` marker because `OAuthService`
threw `OAuthException` (a `RuntimeException`) from inside the same
transaction that saved it — the save rolled back and scans observed a
stale `ACTIVE` row. Fixed with
`@Transactional(noRollbackFor = OAuthException.class)` on
`refreshConnection`; `revokedRefreshFailsSafeToReauth` now passes.

## 18. Error handling (stable codes, no provider detail leaks)

401 → refresh-once → reauth-fail-safe; 403 non-quota →
`SCOPE_INSUFFICIENT`; 403 quota/429 → bounded retry with backoff;
5xx/timeout → bounded retry then `PROVIDER_ERROR`; 404 between list and
get → skipped silently; API-call budget exhaustion → bounded
`PROVIDER_ERROR`. Retries are bounded by `maxAttempts` (4) —
`exhaustedRetriesFailBounded` asserts exactly 4 list calls.

## 19. Incremental scanning

`mailbox_scans.checkpoint` stores the newest-seen message timestamp per
completed run (watermark foundation). Only `type=FULL` exists in
Phase 4 (`ck_scan_type`); delta scans are roadmap, not implemented.

## 20. API endpoints (ownership from the principal, never request bodies)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/scans` `{connectionId}` + `Idempotency-Key` → 200 finished scan | 401 unauthenticated; 400 no-connection/reauth; 403 scope/unverified; 409 concurrent scan |
| GET | `/api/v1/scans` | own history, newest first |
| GET | `/api/v1/scans/{id}` | own scan; foreign id → 404 mask |
| POST | `/api/v1/scans/{id}/cancel` | cooperative; terminal → 409 |
| DELETE | `/api/v1/scans/{id}` | finished runs only |
| GET | `/api/v1/discovered[?status=]` | own accounts (HIGH first) |
| GET | `/api/v1/discovered/{id}` | detail; foreign id → 404 mask |
| GET | `/api/v1/discovered/{id}/evidence` | sanitized rows; no raw message ids |
| POST | `/api/v1/discovered/{id}/review` `{decision: keep\|dismiss, reason?}` | → REVIEWED/DISMISSED |
| DELETE | `/api/v1/discovered/{id}/evidence-purge` | removes evidence + emptied account |

Deviations from `API_SPECIFICATION.md` §4 (deliberate, recorded here):
scans execute synchronously and return `200` with the finished record
instead of `202 {scanId, status:queued}`; only `type=FULL` is accepted.

## 21. Audit events (secret-denylisted at the choke point)

`SCAN_STARTED`, `SCAN_COMPLETED`, `SCAN_FAILED`, `SCAN_CANCELLED`,
`ACCOUNT_EVIDENCE_DETECTED`, `ACCOUNT_EVIDENCE_DISMISSED`,
`GOOGLE_REAUTH_REQUIRED` — metadata carries counts/provider/reason only.
`AuditService` rejects any metadata key containing
`password/token/secret/hash/credential/authorization/cookie/key`, so
bodies and tokens cannot enter the trail by accident.

## 22. Testing

`mvn test` at Phase 4 completion: **153 tests, 0 failures** (historical record) — incl. 34 `GmailScanFlowTests`
(all §23 items below on synthetic/mock Gmail data, no live calls),
`DetectionEngineTests` (8), `DomainNormalizerTests` (4),
`GmailQueryBuilderTests` (3), `NoSendCapabilityTests` (1),
`FlywayMigrationTests` (7), plus all Phase 1/2/3 suites green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped** (see latest `backend/target/surefire-reports/`); this section is preserved as the Phase-4 completion record.
Coverage map: unauthenticated-reject, no-connection-reject,
valid-connection scan, lifecycle/history, concurrency-409,
cancellation, pagination, message cap, lookback query, 401/403/429/5xx,
bounded retries (4 calls), refresh-once, revoked-refresh→reauth,
no-token-in-API, no-token-in-logs, no-body/attachment columns,
normalization (3 senders → 1 account), HIGH/MEDIUM/LOW bands,
newsletter false-positive guard, cross-user 404-mask (scans + detections),
dismiss flow, idempotent resubmission, no-duplicate-evidence,
audit-without-secrets, rate-limit 429.

## 23. Known limitations (carried forward, not blockers)

- Scans run synchronously in-request (bounded by §8 caps); background
  jobs/SSE are roadmap.
- Only `FULL` scans; delta/incremental scanning not yet implemented
  (checkpoint stored as foundation).
- Single-flight is per user (app check + partial unique index), not per
  connection; Redis distributed locks are roadmap.
- Rate limiting is single-instance in-memory (ADR-019).
- Directory seed is a small curated foundation (8 services); privacy/
  deletion columns stay NULL/UNKNOWN until later phases verify them.
- Google verification for the restricted `gmail.readonly` scope: **not
  started — required before any public production use.**
