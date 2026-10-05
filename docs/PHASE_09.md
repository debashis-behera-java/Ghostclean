# GhostClean — PHASE_09 (Request Tracking) — IMPLEMENTED

> Implemented on top of Phase 8 (verified) per explicit human
> architecture decisions (ADR-023). No previous-phase behavior was
> changed. Tracking is computed, manual, and honest: deadlines estimate
> from the handoff timestamp, responses are user-recorded, and nothing
> proves external delivery.

## 1. Objective

Close the lifecycle loop after dispatch: deadline estimates, reminder
state, manual service-outcome intake, and verification-required
follow-up — without schedulers, sending, or fabricated evidence.

## 2. Architecture decisions (human, ADR-023)

1. **V1 uses dispatch `accepted_at` as the internal deadline/reminder
   anchor.** V1 has no trusted mark-sent attestation, so no `sent_at`
   is fabricated and no `/mark-sent` endpoint exists. `accepted_at`
   means "accepted/handed off for the user's external action" — not
   receipt, processing, deletion, or proof a legal deadline started.
2. **No in-process scheduler.** `@Scheduled`/`@Async`/`@EventListener`
   remain build-forbidden in production code. Phase 9 ships the
   deterministic, side-effect-free `ReminderCalculation` boundary; a
   future external scheduler may invoke it.

## 3. Tracking model

No new request states. `PrivacyRequest`/`PrivacyDispatch` lifecycles
are unchanged; tracking is computed per read from (request status,
dispatch acceptance, response history, opt-out, clock). New table
`privacy_request_responses` (append-only outcomes) via V12; forbidden
blueprint names (`deletion_requests`, `deletion_events`,
`notifications`, `legal_templates`) untouched per build guard.

## 4. Deadline handling

`deadline = accepted_at + configured target` (GDPR 30d, CCPA_CPRA 45d,
GENERAL 30d — repo-specified values only), always labeled
"privacy-request deadline estimate — verify locally". No `accepted_at`
(manual routes, undispatched) yields no deadline — never a fabricated
date. Source reported as `accepted_at` on every view.

## 5. Reminder calculation

Pure function of `(acceptedAt, jurisdiction, now, optedOut,
latestOutcome)`: thresholds T-7d/T-1d/overdue (repo-specified
intervals only), earliest-future `nextReminderDue`, null when opted
out, terminal outcome recorded, or anchor unknown.
`verification_required` resumes follow-up. Equal inputs → equal
outputs; evaluation writes nothing, so duplicates are impossible by
construction. Clock is a parameter (fixed-instant tested).

## 6. Response intake

`POST /{id}/responses {outcome, summary?}` — owner, verified,
rate-limited (30/min), APPROVED-only (DRAFT/READY/CANCELLED/EXPIRED
rejected). Outcomes: `completed|rejected|partial|
verification_required` (400 otherwise); optional summary ≤2000 chars,
XSS-validated. Latest row drives state; terminal outcomes close
reminders; `verification_required` re-opens follow-up.

## 7. Tracking states (computed, never stored)

`NOT_APPLICABLE, AWAITING_RESPONSE, OVERDUE, RESPONDED_COMPLETED,
RESPONDED_REJECTED, RESPONDED_PARTIAL, AWAITING_AFTER_VERIFICATION`
plus `reminderState ACTIVE/OPTED_OUT/CLOSED/NONE`. No DELIVERED,
EXTERNAL_SERVICE_RECEIVED, or ACCOUNT_DELETED concept exists.

## 8. APIs

`GET /api/v1/privacy-requests/{id}/tracking` (deadline, reminders,
state, latest outcome, counts) · `GET /{id}/responses` (history) ·
`POST /{id}/responses` (intake). Principal ownership (foreign → 404),
verified gate, standard envelope. Review/approve/dispatch stay on
Phase 6/7 endpoints.

## 9. Audit

`PRIVACY_REQUEST_RESPONSE_RECORDED` (`{outcome}` only). Serialized
audit asserted free of response text, tokens, and references.
Evaluation itself emits nothing (read-only by design).

## 10. Privacy / minimization

Response summaries (≤2000, user-authored, owner-visible); no bodies,
tokens, passwords, attachments, identity documents anywhere in
tracking, storage, logs, or audits. Opt-out honored from existing
`user_settings`.

## 11. Security

Ownership + verified gate on all endpoints; strict outcome validation;
content validation mirrored by DB CHECKs; allow-listed error
categories unchanged; no URL fetching (no SSRF); no automation; audit
denylist holds.

## 12. Legal safeguards

Estimate/follow-up vocabulary only ("configured response target",
"reminder due", "request follow-up date"); response texts scanned for
absolute claims; jurisdiction as context, never advice; application
framed as preparation software.

## 13. Testing

`mvn test` at Phase 9 completion: **331 tests, 0 failures** (historical record) — 8 `ReminderCalculationTests`
(fixed clock) + 18 `TrackingFlowTests` (MockMvc, synthetic data) + all
Phase 1–8 suites green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-9 completion record. Covers all 20 required items: anchor math,
no-`sent_at` schema assertion, no mark-sent routes (GET 404 / POST
rejected), determinism, idempotency (write-free proof), due/next
progression, configured-only targets, manual-route null deadline,
opt-out, conservative wording, cross-user 404s, terminal close +
verification reopen, invalid input rejection, non-approved rejection,
rate limits (30+1 → 429), audit + audit hygiene, scheduler-annotation
absence (existing build guard), Gmail/SMTP absence (existing guards),
Phases 1–8 green. No real email sent.

## 14. Limitations

- Reminders are computed state only; nothing delivers them
  in-process (future external scheduler + notification channel).
- Manual intake relies on the user reporting outcomes truthfully.
- Single-instance in-memory limits; lazy expiry; generic-envelope for
  unmapped methods (shared behaviors, untouched).
- `gmail.readonly` verification not started (pre-existing).

## Explicit guarantees

1. V1 uses accepted_at as the internal deadline/reminder anchor.
2. accepted_at does not prove external delivery.
3. V1 has no mark-sent attestation.
4. GhostClean does not fabricate sent_at.
5. GhostClean does not claim external receipt.
6. Reminder calculation is deterministic.
7. No in-process scheduler is used.
8. @Scheduled/@Async/@EventListener remain prohibited in production code.
9. An external scheduler may invoke the reminder calculation in a future deployment.
10. Legal deadlines are not fabricated.
11. No individualized legal advice is provided.
12. No Gmail SEND is introduced.
13. V1 remains manual-draft-only.
