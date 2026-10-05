# GhostClean — PHASE_07 (Controlled Outbound Dispatch) — IMPLEMENTED

> Implemented on top of Phase 6 (verified). No previous-phase behavior
> was changed. Gate 2 is a separate explicit user action; approval alone
> never sends, and no scheduler exists anywhere in this codebase.

## 1. What Phase 7 does (and does not do)

Phase 7 lets the user explicitly dispatch an APPROVED privacy request
through a dedicated outbound-mail abstraction to a verified email route.

- PHASE 7 does not directly delete external accounts.
- PHASE 7 sends privacy requests only where a verified email route exists.
- PHASE 7 does not use Gmail SEND.
- PHASE 7 does not automatically dispatch approved requests.
- PHASE 7 requires explicit user confirmation.
- PHASE 7 does not provide individualized legal advice.

## 2. Two-gate model

GATE 1 — approval (`POST /privacy-requests/{id}/approve`, Phase 6):
reviews the exact version. GATE 2 — dispatch (`POST
/privacy-requests/{id}/dispatch`, this phase): confirms the final send.
Separate endpoints, separate audits; no path invokes dispatch except the
gate-2 endpoint (verified by grep: no `@Scheduled`/`@Async`/
`@EventListener`/executors in main, build-enforced).

## 3. Approval vs dispatch

Approval records `approved_at`/`approved_version` and stops. Dispatch
re-verifies all 14 eligibility gates at send time, snapshots the exact
approved content, and records the provider outcome. Repeat dispatches
return the recorded outcome instead of resending.

## 4. Provider abstraction

`OutboundMailProvider.send(OutboundMailMessage)` — domain depends on the
interface only. Message is text-only by construction (record components
`from/to/subject/body`; no attachment capability exists). Providers must
be synchronous with no internal retry; retry policy lives in the
dispatch service where attempts are counted and bounded.

## 5. Test/sink provider

`SinkOutboundMailProvider` (default via `outbound.mail.provider=sink`,
`matchIfMissing=true`) records in memory: no network, no SMTP, no
Gmail, no credentials. References look like `sink-<uuid>` and mean
"recorded for tests" — never presented as real delivery. Scripted
temporary/permanent failures plus `reset()` are dev-only hooks for
proving bounded retry and failure mapping.

## 6. Production provider boundary

To add a real provider: implement the interface, gate the bean with
`@ConditionalOnProperty`, use HTTPS + environment credentials, bounded
timeouts/retries, never log secrets or content, report ACCEPTED only on
provider-confirmed acceptance. No real provider is implemented or
fabricated in Phase 7; `api-key` config exists as an empty reserved
slot, never logged/audited/returned.

## 7. Destination resolution

Backend-only, from the trusted registry: entry must be active,
VERIFIED, `routeType == EMAIL_REQUEST`, with a well-formed public
`privacyEmail` (format + non-localhost/non-private check). Any
client-supplied `recipient`/`to`/`destination` field is ignored
(whitelist parsing; test-proven). Nothing is guessed or invented.

## 8. Trusted route requirements

Unverified/missing/unusable destination → 409 `DESTINATION_UNKNOWN`.
Verified but non-email route → 409 `MANUAL_REVIEW_REQUIRED` (stays
manual until a future workflow). Inactive/REJECTED route → 409
`REQUEST_CONFLICT`. Stale approvals (version drift, expiry) → 409/410.

## 9. Idempotency

Client `Idempotency-Key` header (or body field) plus one-row-per-request
(`uq_dispatch_request`) plus pre-send lookups: key match, accepted-row
match, and lost-race handling all return the recorded outcome. A
network retry never duplicates a send.

## 10. Retry policy

Bounded by `outbound.mail.max-attempts` (default 3, clamped 1–10):
transient failures retry within the bound, permanent failures stop
immediately, exhaustion records FAILED. No sleeps/backoff threads in
the sync path; attempts, latency, and category are logged safely.

## 11. Rate limiting

Dispatch attempts 10/hour/user (repeats count as attempts);
dispatch-cancel 30/min/user; server-side via the Phase 2 limiter.

## 12. Audit events

`PRIVACY_REQUEST_DISPATCH_REQUESTED/ACCEPTED/FAILED/CANCELLED` with
`{status, version}` only. Serialized audit asserted free of request
text, tokens, and provider references.

## 13. Security

Principal-derived ownership on every endpoint (foreign → 404, no
oracle); verified-email gate; no recipient override; destination
hygiene; error-category allow-list (raw provider errors never reach
users); safe API view (ids, status, attempts, reference, category —
no secrets, no content, no raw responses); CSRF posture unchanged
(stateless JWT, CSRF disabled as before — no cookies carry dispatch
authority); no URL-fetching facility, hence no new SSRF surface.

## 14. Privacy

No Gmail bodies/attachments, no OAuth tokens, no passwords, no identity
documents — collected or transmitted — at any point. Stored per
dispatch: lifecycle + approved-version snapshot (the user's own review
text, required as the send record) + trusted destination + safe
outcome. "Manual verification" services stay manual; nothing is
auto-attached.

## 15. Failure handling

Categories: TEMPORARY_PROVIDER_ERROR, AUTHENTICATION_ERROR,
INVALID_DESTINATION, MESSAGE_REJECTED, RATE_LIMITED,
UNKNOWN_PROVIDER_ERROR (CHECK-constrained, allow-list sanitized).
FAILED rows may be retried (same id, refreshed snapshot, bounded) or
cancelled; ACCEPTED rows can never be cancelled or resent.

## 16. Request versioning

Snapshot (`requestVersion` + subject/body) is taken from the exact
approved content at send time; approval-version drift rejects with
409. Editing after approval forces re-approval before any dispatch.

## 17. Legal-language safeguards

Dispatch sends the user's Phase 6 text unchanged (no appended content,
no guarantees added). Generation-time conservatism (Phase 6) plus
response scanning carry over; nothing here gives legal advice.

## 18. Attachment restrictions

No attachment field, no collection, no transmission — by type design,
not just policy. Identity-verification cases remain manual.

## 19. Gmail read-only guarantee

Zero Gmail write surface added: no `gmail.send/compose/modify`, no
`mail.google.com`, no `users.messages.*` writes, no SMTP/JavaMailSender
(grep + `NoSendCapabilityTests` green). OAuth stays identity + readonly.

## 20. Testing

`mvn test` at Phase 7 completion: **263 tests, 0 failures** (historical record) — 28 `PrivacyDispatchFlowTests`
(sink-backed, synthetic data) + extended `NoDeletionActionTests`
(privacy package + no-background-dispatch guard) + all Phase 1–6 suites
green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-7 completion record. Covers all 40 items: auth/ownership, state gates (DRAFT/READY/
CANCELLED/EXPIRED blocked), inactive/unverified/non-email/unknown
routes blocked with distinct codes, server-side destination, override
rejection, explicit confirmation, version pinning + invalidation,
idempotency, no-duplicate-send, acceptance/failure/bounded-retry/
no-permanent-retry recording, cancel lifecycle, rate limits (10+1 →
429), audit + audit hygiene, clean message content, no attachments by
type, no Gmail capabilities, no auto-send (approval and background),
Phases 1–6 green. No real email sent by any test.

## 21. Limitations

- Sink is the only provider; real delivery needs a future
  implementation behind the documented boundary.
- Synchronous in-request sends (bounded attempts, small payloads).
- Single-instance in-memory rate limits (ADR-019 pattern).
- Unmapped-method probes surface via the existing generic error
  envelope (shared behavior, untouched).
- Google `gmail.readonly` verification still not started (pre-existing).
