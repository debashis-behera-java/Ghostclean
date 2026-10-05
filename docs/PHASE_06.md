# GhostClean — PHASE_06 (Privacy Request Workflow Foundation) — IMPLEMENTED

> Implemented on top of Phase 5 (verified). No previous-phase behavior
> was changed. This phase creates the user-reviewed request and approval
> model only.

## 1. What Phase 6 does (and does not do)

Phase 4 discovers evidence, Phase 5 describes routes, Phase 6 lets the
user draft, review, edit, approve, or cancel a privacy request over a
detected account.

- PHASE 6 DOES NOT SEND REQUESTS.
- PHASE 6 DOES NOT SEND EMAIL.
- PHASE 6 DOES NOT DELETE ACCOUNTS.
- PHASE 6 DOES NOT SUBMIT THIRD-PARTY PRIVACY FORMS.
- PHASE 6 DOES NOT PROVIDE INDIVIDUALIZED LEGAL ADVICE.

There are no SENT/SUBMITTED/DELETED states; later phases add them with
new migrations.

## 2. Two-gate model

GATE 1 — detection review (`POST /discovered/{id}/review`, Phase 4):
the user confirms the evidence is theirs. GATE 2 — request approval
(`POST /privacy-requests/{id}/approve`, this phase): the user approves
the exact generated version. The gates are separate endpoints; detection
never auto-creates an approved request (creation always yields DRAFT).

## 3. Request lifecycle

`DRAFT` (generated, unreviewed) → `READY_FOR_REVIEW` (any PATCH, even
empty = reviewed) → `APPROVED` (explicit, version-pinned). `CANCELLED`
and `EXPIRED` are terminal. Editing an APPROVED request returns it to
`READY_FOR_REVIEW` with version+1 and cleared approval — approval can
never silently cover modified content.

## 4. Request states / types

States: DRAFT, READY_FOR_REVIEW, APPROVED, CANCELLED, EXPIRED (CHECK-
constrained). Types: DELETE_PERSONAL_DATA, DELETE_ACCOUNT,
GENERAL_PRIVACY_REQUEST, UNKNOWN. DELETE_ACCOUNT is stored only with a
trusted (KNOWN + VERIFIED) route; otherwise creation coerces to
GENERAL_PRIVACY_REQUEST instead of claiming unsupported support.

## 5. Approval workflow

`POST /{id}/approve` (optional `version`) verifies: ownership (else
404), READY_FOR_REVIEW (else 409), version match (else 409), account
still owned, route still valid (deprecated/REJECTED → 409; DELETE_ACCOUNT
additionally requires current trust), non-empty content, unexpired
(expired → 410 + transition). Then records `approved_at` +
`approved_version`. Sends nothing.

## 6. Editing/versioning

PATCH subject/body (200/8000 chars, plain text, script/`javascript:`/
event-handler patterns rejected with 400). Every PATCH bumps `version`.
DRAFT→READY on any PATCH; APPROVED→READY with approval cleared.

## 7. Expiration

`expires_at` = created + `privacy.request-ttl-days` (default 7).
Applied lazily on every access; due rows transition exactly once with a
PRIVACY_REQUEST_EXPIRED audit. Expired rows approve to 410 and can never
be sent later — they must be regenerated.

## 8. Cancellation

DRAFT/READY/APPROVED → CANCELLED; repeat cancels return 200 unchanged
(idempotent); EXPIRED → 409. No delete endpoint exists — audit history
is preserved (audit rows carry no user FK by V3 design).

## 9. Duplicate handling

Idempotency-Key returns the existing row; otherwise one active
(DRAFT/READY) request per (user, account, type) — enforced by the
partial unique index `uq_active_privacy_per_account_type` plus an app-
level check for a friendly 200-with-existing.

## 10. Request generation (deterministic, no AI/ML/LLM)

Pure template function of service name, category, evidence count, type,
jurisdiction. Minimal evidence only (count, never subjects/bodies).
Professional neutral text; jurisdiction paragraphs (GENERAL/GDPR/
CCPA_CPRA) use "under applicable law … to the extent applicable" with
"exceptions may apply" — no deadlines, no guarantees, no personal data
(the User model has no display name; contact details stay with the user
until any future sending step).

## 11. Legal-language safeguards

Generator and API responses are scanned by tests for absolute claims
(`legally required`, `guarantee`, `immediately`, `deadline`,
`You definitely`). Jurisdiction selects wording, never rights.

## 12. Data minimization

Stored: lifecycle fields + user-facing subject/body (required for
review). Explicitly NOT stored: Gmail tokens, auth codes, passwords,
mailbox bodies, attachments, unrelated content. Request text is the only
PII-adjacent payload and exists solely for review.

## 13. Security / ownership

Every endpoint derives ownership from the principal; foreign ids →
404 (no existence oracle). Verified-email gate on mutations (403
otherwise), consistent with scans/reviews. Content validated
(length + XSS patterns, mirrored by DB CHECKs).

## 14. Audit events

PRIVACY_REQUEST_CREATED/EDITED/APPROVED/CANCELLED/EXPIRED with
`{status, version}` metadata only — denylist-safe keys, no text, no
secrets (test-serialized and asserted).

## 15. APIs

`POST /api/v1/privacy-requests` (+Idempotency-Key) → DRAFT ·
`GET /api/v1/privacy-requests[?status]` · `GET /{id}` ·
`PATCH /{id}` (edit/review) · `POST /{id}/approve` (optional version) ·
`POST /{id}/cancel`. Standard envelope + request-id conventions.

## 16. Database model

`V9__privacy_requests.sql` (new; V1–V8 untouched): `privacy_requests`
with user/account/directory FKs (cascade / set-null), status/type/
jurisdiction CHECKs, body-bounds CHECK, version floor, per-user
idempotency unique, partial unique for active dedup, user/status and
user/service indexes, UTC timestamps + touch trigger.

## 17. Testing

`mvn test` at Phase 6 completion: **234 tests, 0 failures** (historical record) — 7 `PrivacyRequestGeneratorTests`
+ 28 `PrivacyRequestFlowTests` (MockMvc, synthetic data) + extended
`NoDeletionActionTests` (now also scans `privacy/`) + all Phase 1–5
suites green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-6 completion record. Covers all 36 items: create/auth/ownership, service
resolution, DRAFT-start/never-auto-approved, clean content, type/content
validation, view/edit isolation, versioning, approval gates + timestamp
+ exact-version, re-approval, cancel + idempotency, expiry, dedup,
idempotency keys, audit + audit hygiene, rate limits (10 creates/hour →
429), no-send/modify/delete, zero external execution, Phases 1–5 green.

## 18. Limitations

- Lazy (access-time) expiry — no background sweeper yet.
- Single-instance in-memory rate limits (ADR-019 pattern).
- Unmapped-method probes surface via the existing generic error
  envelope, not 405 (shared behavior, untouched).
- MockMvc reads non-ASCII through the default response encoding in
  tests; production JSON is UTF-8.
- No sending, no SENT state, no admin UI; Google verification for
  `gmail.readonly` still not started (pre-existing).
