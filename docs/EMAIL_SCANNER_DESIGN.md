# GhostClean — Email Scanner Design (Metadata Pipeline + Degraded Manual Path)

> Phase 0 update (RESOLVED): degraded manual-forward/import mode when Gmail permission is unavailable/denied/restricted — no bypass, no wider scopes; manual metadata shares normalize→detect→resolve with `source=manual`, labeled degraded, confidence-capped. Scan/manual endpoints require verified email.

## Pipeline

```
OAuth connection → Connector.fetchMetadata → Normalize → Candidate extract
→ Detection (rules) → Evidence (idempotent write, source=oauth) → (Account resolution async)

Degraded/manual: User-typed metadata → Validate+sanitize → Normalize (source=manual)
→ Detection (capped) → Evidence (source=manual, labeled) → (Account resolution async)
```

Scanner orchestrates; connectors fetch; detection scores. Bodies never required.

## 1. Normalization

Per message: `{ provider, providerMessageIdHash, threadId?, senderDomain (lower, punycode), senderAddressHash, subjectTrunc(200, stripped/escaped), receivedAt, labels? }`. Dedupe key `(user_id, provider, provider_message_id)` unique. Unicode/punycode normalize domains; `+`/dot variants ignored for grouping (evidence-level only).

## 2. Retrieval: Pagination / Full / Delta

- Pagination: page size 100–500; follow `nextPageToken` (Gmail) / `@odata.nextLink` (Graph); checkpoint watermark every page (historyId / deltaLink / max receivedAt + id set) so resume never re-emits.
- Initial full scan: oldest→newest or newest→oldest with `progressTotal` estimate (Gmail `resultSizeEstimate`, Graph `@odata.count` best-effort; else indeterminate bar). Query hints: `in:anywhere (subject:(verify OR welcome OR "sign up" OR receipt OR invoice OR "password reset"))` — hints only, never exclusion that hides evidence; full fallback documented for restricted scopes.
- Delta: Gmail `historyId`, Graph `deltaLink`; fallback timestamp watermark. First delta bootstraps baseline watermark at full-scan end.
- Idempotency: `ON CONFLICT (user,provider,messageId) DO NOTHING`; matched-rule recompute allowed, no duplicate evidence rows.

## 3. Resilience

- Rate limiting: per-connection bucket + honor 429/`Retry-After`; pause run, keep watermark.
- Retries: transient (429/5xx/timeout) 3× exp backoff + jitter; `invalid_grant` → stop + mark connection expired (no retry loop).
- Provider failures: run → `failed` with `error` code + resume-from-watermark button; partial progress preserved; audit `SCAN_FAILED`.
- Cancellation: `POST /scans/{id}/cancel` sets flag checked each page + releases Redis lock; status `cancelled`, resumable as new delta.
- Background: job row `mailbox_scans` + Redis lock `scan:{connectionId}` (TTL 10m, heartbeat); only one active run per connection; progress via `GET /scans/{id}` polling (SSE deferred).
- Timeouts: page fetch 15s, run soft-limit 30m (checkpoint + requeue), payload caps (subject 300 chars, no attachments).

## 4. Privacy Guards

Metadata-only queries (`metadata` format / `$select=from,subject,receivedDateTime`); no body/attachment fetch in v1; transient objects only; redacted logs (ids hashed at debug). Malicious content treated as untrusted (see `THREAT_MODEL.md`).

## 5. Progress Model

`{ status, progressDone, progressTotal|null, lastWatermark, error }`. UX: determinate when estimate exists, else "N messages checked". Completion → `SCAN_COMPLETED` + detection summary counts (no per-body data).

## 6. Degraded Manual-Forward/Import Mode (RESOLVED)

When `gmail.readonly` (or Graph `Mail.Read`) is unavailable/denied/restricted: offer `POST /evidence/manual` (verified users, 30/day cap, Idempotency-Key). Accepts ONLY `{sender (domain or address), subject ≤300 chars, receivedAt within sane bounds}`; rejects bodies/attachments/URLs. Server: lowercase/punycode sender, strip HTML, escape, hash for idempotency `(user, manual, domain, subject_hash, date)`, then reuse normalization + rule engine with manual cap (single manual item never HIGH; manual corroboration capped). Every manual card shows "Manual/degraded — not from mailbox connection". Audit `MANUAL_EVIDENCE_IMPORTED`; abuse (spam/rate) → 429 + review flag. No scope widening ever.

## 7. Acceptance

- [ ] Full/delta, pagination, watermarking, idempotency, cancel, backoff, progress specified; "not every email = account" enforced (scanner emits candidates only).
- [ ] Manual path specified: no-bypass rule, metadata-only, validation/caps/labeling/capped confidence, verified-only.
