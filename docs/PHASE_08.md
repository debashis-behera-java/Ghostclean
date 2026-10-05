# GhostClean — PHASE_08 (Deletion Request Engine) — IMPLEMENTED

> Implemented on top of Phase 7 (verified). No previous-phase behavior
> was changed. The engine generates reviewable requests and routes
> eligible ones through the Phase 7 dispatch boundary; it performs no
> sending, automation, or deletion itself.

## 1. Phase objective

Convert detected account + service intelligence + privacy route into a
structured, template-versioned deletion/privacy request the user
reviews, approves (Phase 6), and confirms (Phase 7 dispatch) — with
manual-review routing wherever no verified email path exists.

## 2. Deletion-request architecture

```
DeletionRequestController (/api/v1/deletion-requests)
  → DeletionRequestService (resolve → render → create/get)
    → DeletionMethodResolver (trusted metadata → method)
    → RequestTemplates (versioned deterministic rendering)
    → PrivacyRequestService.createWithContent (Phase 6 lifecycle reuse)
    → PrivacyRequest entity (extended, not duplicated)
  review/approve → Phase 6 endpoints · confirm/send → Phase 7 dispatch
```

No second registry, entity, approval system, mail system, or dispatch
service. New code lives in `privacy/` (ArchUnit no-cycle rule green).

## 3. Service directory integration

Reuses the Phase 5 `service_directory` rows via `ServiceRegistry`
(trust gating) and `describe()` (verified-safe URLs). No second
registry; no new service tables.

## 4. Deletion methods

`EMAIL, PRIVACY_PORTAL, ACCOUNT_SETTINGS, WEB_FORM, MANUAL, UNKNOWN`
(lenient parse). Resolver (pure, rule-literal, VERIFIED + KNOWN +
active only): EMAIL_REQUEST→EMAIL, PRIVACY_PORTAL→PRIVACY_PORTAL,
ACCOUNT_SETTINGS→ACCOUNT_SETTINGS, PRIVACY_REQUEST_FORM→WEB_FORM,
everything else (incl. SELF_SERVICE/HELP_CENTER/unverified/inactive/
null) → UNKNOWN. Only EMAIL dispatches; all others are manual.

## 5. Request types

`DELETE_PERSONAL_DATA, DELETE_ACCOUNT, ACCESS_PERSONAL_DATA (new in
V11), GENERAL_PRIVACY_REQUEST, UNKNOWN`. `supportedTypes()` exposes
DELETE_ACCOUNT only for trusted routes; everywhere else it coerces to
GENERAL_PRIVACY_REQUEST at preview and creation alike.

## 6. Jurisdiction handling

Reuses `PrivacyJurisdiction` (GENERAL/GDPR/CCPA_CPRA): selects a canned
conservative paragraph, never a determination. Strict input validation;
no deadlines, guarantees, or individualized conclusions (test-scanned).

## 7. Template system

Code-held `privacy-request-v1` skeletons per type × jurisdiction with
allow-listed `{{variables}}` only (`service_name`, `display_name`,
`account_identifier`, `request_type`, `jurisdiction`,
`evidence_count`). Plain-text substitution — unknown placeholders
survive verbatim, so no expression/template injection can execute.
`display_name`/`account_identifier` resolve empty: Phase 8 invents no
identity (the User model has no display name; no account email is
verified). Rendered output passes the same validation as hand edits.

## 8. Template versioning

Requests store `template_id + template_version` (V11, nullable for
pre-existing rows). Stored content is never re-rendered; later template
changes cannot silently mutate approvals (test: body + version stable
while live method updates).

## 9. Request generation

`preview()` (no writes) and `create()` (DRAFT via the Phase 6 core, so
dedup, coercion, rate limits, expiry, and CREATED auditing behave
identically). DELETE_ACCOUNT coercion, type/jurisdiction validation,
and verified-email gating shared with Phase 6 paths.

## 10. Preview

Returns service, method, type, jurisdiction, destination (EMAIL +
trusted only), subject, body, template provenance, warnings, manual
instructions, supported types. Proves no persistence (row count
unchanged) and no sending (sink empty).

## 11. Approval

Reuses Phase 6 READY_FOR_REVIEW/APPROVED exactly (PATCH → approve).
Editing bumps version and invalidates approval; re-approval required.

## 12. Confirmation

There is no separate `/confirm` endpoint by design: confirmation IS the
Phase 7 `POST /privacy-requests/{id}/dispatch` call (explicit,
idempotent, version-pinned). Duplicate confirmations return the
recorded outcome without resending (test-proven end to end).

## 13. Phase 7 integration

`Phase 8 → PrivacyRequest → Phase 6 approval → Phase 7 dispatch →
OutboundMailProvider`. One mail system, one dispatch service, Gmail
untouched. Manual routes dispatch to 409, never to a sender.

## 14. Manual-review workflow

UNKNOWN/non-email methods return `manualReviewRequired: true`,
method-specific instructions built ONLY from verified-safe metadata
(`describe()` URLs or generic cautions — never fabricated contacts),
plus a `MANUAL_REVIEW_REQUIRED` audit. Route changes after generation
keep stored content but flip the manual flag, forcing re-review.

## 15. Destination resolution

Preview/create responses show the registry destination only for
EMAIL + trusted + hygienic addresses; frontend-supplied recipients are
not parsed (whitelist bodies) and cannot override anything.

## 16. Versioning

Request versions via Phase 6 (edit → +1, approval pinned); template
versions recorded at generation; live method/warnings recomputed on
read without touching stored content.

## 17. Idempotency

Phase 6 idempotency keys + per-account dedup on creation; Phase 7
dispatch idempotency on confirmation. Repeated previews create
nothing; repeated confirmations resend nothing.

## 18. Audit events

New: `DELETION_REQUEST_PREVIEWED/GENERATED/MANUAL_REVIEW_REQUIRED`
(ids/method/type only). Approval/confirmation reuse the Phase 6/7
events. Serialized audit asserted free of content and secrets.

## 19. Privacy/data minimization

Minimal evidence (service name, category, count — never subjects or
bodies); no tokens, passwords, attachments, identity documents, or
mailbox content in templates, storage, logs, or audits.

## 20. Security

Principal-derived ownership on all three endpoints (foreign → 404);
verified-email gate; strict type/jurisdiction validation; content
validation mirrored by DB CHECKs; no URL fetching (no SSRF surface);
no browser/form automation; no generic HTTP deletion calls.

## 21. Legal-language safeguards

Conservative Phase 6 wording extended to ACCESS types; response-wide
scans for absolute claims; jurisdiction as context, never advice;
application framed as request-preparation software, not counsel.

## 22. API contract

`POST /deletion-requests/preview` (200 preview, zero writes) ·
`POST /deletion-requests` (+Idempotency-Key → DRAFT w/ provenance) ·
`GET /deletion-requests/{id}` (stored content + live method/warnings).
Review/approve → `PATCH|POST /privacy-requests/{id}[/approve]`;
confirm/send → `POST /privacy-requests/{id}/dispatch`. No duplicate
lifecycle endpoints by design.

## 23. UI contract (frontend is a placeholder — no UI code added)

Show: service, deletion method, request type, jurisdiction,
destination (if any), subject, generated body, warnings; then
[Edit] [Approve] [Cancel]; after approval, [Send Privacy Request]
(calls Phase 7 dispatch) or [Manual Action Required] (no send
affordance). Never label a send button "Delete Account Now" — the
product sends requests; only the external service can delete.

## 24. Testing

`mvn test` at Phase 8 completion: **305 tests, 0 failures** (historical record) — 17
`DeletionRequestUnitTests` + 25 `DeletionRequestFlowTests` (MockMvc,
synthetic data) + all Phase 1–7 suites green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-8 completion record. Covers all 48 items:
resolution matrix (incl. null/inactive/unverified/unknown), no
fabrication, type gating + coercion, ACCESS/GENERAL/GDPR/CCPA wording,
no legal conclusions, determinism, template provenance + pinned-
version rejection, preview/create purity, approval-sends-nothing,
Phase 7 confirmation + duplicate-confirmation safety, manual/unknown
dispatch blocks, override rejection, edit/version semantics,
template/route-change immunity, cross-user 404s, secret/content
hygiene, no attachments, audit + audit hygiene, dedup (both keys),
Phases 1–7 green. No real requests sent.

## 25. Limitations

- Single template (`privacy-request-v1`); new templates arrive as code
  + stored provenance (old rows keep theirs).
- `account_identifier`/`display_name` resolve empty (no verified
  source in Phase 8).
- Lazy expiry, in-memory limits, generic-envelope for unmapped methods
  (shared behaviors, untouched).
- `gmail.readonly` verification not started (pre-existing).

## Explicit guarantees

- PHASE 8 DOES NOT DIRECTLY DELETE EXTERNAL ACCOUNTS.
- PHASE 8 DOES NOT USE GMAIL SEND.
- PHASE 8 DOES NOT AUTOMATE PRIVACY PORTALS.
- PHASE 8 DOES NOT AUTOMATE WEB FORMS.
- PHASE 8 DOES NOT GUESS SERVICE DESTINATIONS.
- PHASE 8 DOES NOT PROVIDE INDIVIDUALIZED LEGAL ADVICE.
- PHASE 8 USES THE PHASE 7 CONTROLLED DISPATCH BOUNDARY.
- PHASE 8 REQUIRES USER REVIEW AND APPROVAL.
