# GhostClean — PHASE_05 (Service Intelligence + Deletion-Route Registry) — IMPLEMENTED

> Implemented on top of Phase 4 (verified). Phase 0 architecture remains
> authoritative; no previous-phase behavior was changed.

## 1. What Phase 5 does (and does not do)

Phase 4 discovers **"Detected account evidence."** Phase 5 enriches it
into normalized service identity, category, route descriptions, and
conservative jurisdiction/automation metadata. Explicitly NOT in Phase 5:

- Phase 5 does NOT send deletion requests.
- Phase 5 does NOT delete external accounts.
- Phase 5 does NOT send email.
- Phase 5 does NOT modify Gmail.
- Phase 5 does NOT provide individualized legal advice.

## 2. Core principle (never collapsed claims)

Evidence, identity, route, right, and automation method stay distinct:

```
DETECTED ≠ CONFIRMED ≠ DELETABLE ≠ AUTOMATABLE
```

Confidence bands describe evidence strength; route availability describes
registry verification; automation status describes structural
compatibility — never current capability. Phase 5 executes zero actions.

## 3. Service intelligence architecture

```
ServiceController (/api/v1/services, GET-only)
  → ServiceRegistry (tri-state matching, trust gating, enrichment)
    → ServiceDirectoryRepository (service_directory, extended by V8)
AccountController (/api/v1/discovered, enriched DTOs)
  → ServiceRegistry.enrichmentFor (FK-linked entry or domain fallback)
```

- No new tables: one route description per `service_directory` row
  (multiplicity would invite unverified-route sprawl).
- `ServiceDirectoryService` (Phase 4 detection lookup) is untouched;
  the registry reads the active catalog fresh per call, so there is no
  cache-staleness window for tests or future admin flows.
- Layering holds: `services` never depends on `account` (ArchUnit
  no-cycle rule green); `account → services` reuses the existing
  direction.

## 4. Service registry

`ServiceDirectory` (extended): stable slug (= service ID), display name,
category, known domains (`domains[]`), sender aliases (`aliases[]`),
`account_settings_url`, `privacy_url`, `deletion_url`, `privacy_email`
(exposed only when VERIFIED + EMAIL_REQUEST + URL-safe), route type,
request method, automation status, route confidence, route availability,
`source_url`, source type, verification status, `jurisdiction[]`,
`manual_review_required`, `last_verified_at`, `status`, version,
timestamps. All route columns default to UNKNOWN/UNVERIFIED/
manual-review — no route is trusted by accident.

## 5. Service normalization

Phase 4 normalization reused unchanged (lowercase/punycode, registrable-
domain grouping, alias matching). `ServiceRegistry.match` additionally
accepts a full sender address by extracting the domain exactly like the
scan pipeline does.

## 6. Service matching (deterministic, no AI/ML/LLM/embeddings)

Exact domain claimants first, then subdomains, over active rows only.
Tri-state result: exactly one claimant → KNOWN; several → AMBIGUOUS
(never silently picks one); none → UNKNOWN. Inactive rows never match.

## 7. Route types

`SELF_SERVICE_ACCOUNT_DELETE`, `PRIVACY_REQUEST_FORM`, `PRIVACY_PORTAL`,
`EMAIL_REQUEST`, `ACCOUNT_SETTINGS`, `HELP_CENTER`, `UNKNOWN`.
Extensible enum with lenient parsing (unknown → UNKNOWN, never throws).
A help-center page is typed as such — never as a deletion endpoint.

## 8. Request methods

`WEB_FORM`, `PRIVACY_PORTAL`, `EMAIL`, `ACCOUNT_SETTINGS`, `MANUAL`,
`UNKNOWN` — kept separate from route type (where vs. how). The registry
only describes the mechanism.

## 9. Automation status

`NOT_SUPPORTED`, `MANUAL_ONLY`, `FUTURE_AUTOMATION`, `AUTOMATABLE`
(+ lenient `UNKNOWN`). Compatibility is not capability: even
AUTOMATABLE ships with *"GhostClean does not perform this action."*
Every automation note denies current execution (test-enforced).

## 10. Route confidence

`HIGH` (official portal), `MEDIUM` (official help page),
`LOW` (third-party article), `UNKNOWN`. Guessed URLs are never accepted;
unverified rows present UNKNOWN-facing confidence with links suppressed.

## 11. Source verification

`source_url`, source type (`OFFICIAL`/`HELP_CENTER`/`THIRD_PARTY`/
`UNKNOWN`), `verification_status` (`VERIFIED`/`UNVERIFIED`/`REJECTED`),
`last_verified_at`. Trust rule: a route presents as KNOWN only when the
row is active + `route_availability=KNOWN` + `VERIFIED`. UNVERIFIED rows
stay UNKNOWN-facing; REJECTED rows are suppressed from enrichment
entirely. No scraped content is stored.

## 12. Jurisdiction metadata

Coarse catalog tags: `GDPR_RELEVANT`, `CCPA_CPRA_RELEVANT`,
`GENERAL_PRIVACY_REQUEST`, `UNKNOWN`. Always paired with:
*"Jurisdictional support information only — not legal advice and not a
determination of individual rights."* No individualized conclusions;
responses are scanned for certainty language (`definitely`,
`guarantee`, `You have…`, `Confirmed account`) by tests.

## 13. Privacy model

Phase 4 minimization continues: no Gmail bodies, no attachments, no
OAuth tokens, no passwords, no unrelated mailbox content. Intelligence
derives from minimal evidence + the curated catalog. Registry payloads
contain no credentials and no mailbox content (test-enforced).

## 14. False positives

Phase 4's conservative model is preserved: registry presence never
upgrades weak evidence (unknown senders stay UNKNOWN even with a
non-empty catalog); marketing/mention-only signals stay weak;
verification/welcome + directory hits stay strong. Enrichment output
never contains CONFIRMED/ACTIVE/DELETED states.

## 15. Ambiguous matches

Multi-claimant domains resolve AMBIGUOUS with *"Manual review required;
no service was chosen."* Evidence is preserved for future review.

## 16. Account statuses (unchanged)

`DETECTED`, `REVIEWED`, `DISMISSED` (+ internal `UNKNOWN` route
availability). No `ACTIVE`/`CONFIRMED`/`DELETED` — deletion belongs to a
later workflow and is not implemented here.

## 17. Limitations

- Seed routes are all UNKNOWN/MANUAL_REVIEW_REQUIRED: the V7 seeds carry
  verified identity only, and no deletion URL is fabricated. KNOWN paths
  are exercised with synthetic `.test` fixtures.
- Unmapped-method probes surface via the existing generic error envelope
  (shared Phase 1/2 behavior) rather than a framework 405; rejection +
  catalog-immutability is what the tests assert.
- Rate limiting stays single-instance in-memory.

## 18. APIs (all authenticated, ownership from the principal)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/services` | active catalog summaries (registry metadata only) |
| GET | `/api/v1/services/{slug}` | stable slug, case-insensitive; unknown/inactive → 404 |
| GET | `/api/v1/discovered`, `/{id}`, `/{id}/evidence` | Phase 4 + `privacyRoute`/`automation`/`jurisdiction` blocks |

No mutation endpoints exist under `/api/v1/services` — ordinary users
cannot modify trusted metadata (probe-tested for POST/PUT/PATCH/DELETE,
catalog proven unchanged). No admin UI in Phase 5.

## 19. Database model

`V8__service_intelligence.sql` (new; V1–V7 untouched): ten columns on
`service_directory` with CHECK constraints per enum, `jurisdiction
TEXT[]`, `manual_review_required` default TRUE, explicit backfill of the
8 seeds to UNKNOWN/UNVERIFIED, plus an availability index. Uniqueness
(`uq_service_directory_slug`) prevents duplicate definitions; single-row
route description prevents duplicate routes (both test-enforced).

## 20. Testing

`mvn test` at Phase 5 completion: **199 tests, 0 failures** (historical record) — 28 `ServiceRegistryTests`
(dependency-free, `.test` fixtures), 15 `ServiceIntelligenceFlowTests`
(MockMvc on synthetic data), 3 `NoDeletionActionTests` (structural
guards), plus all Phase 1–4 suites green at that time. Superseded: current suite is **462 tests, 0 failures, 0 errors, 0 skipped**; preserved as Phase-5 completion record. Covers all 35 required items:
definition load, normalization, alias/known/unknown/ambiguous matching,
weak-vs-strong evidence, categories, KNOWN/UNKNOWN routes, confidence,
unverified distrust, type/method/automation mapping, GDPR/CCPA + non-
certainty language, cross-user 404-mask, registry immutability, no
credentials/bodies, zero deletion execution, no Gmail SEND/MODIFY/DELETE,
slug/route dedupe, source persistence, inactive handling, Phases 1–4
green. No live external requests anywhere.

## 21. Security

Read-only registry; user-scoped detections; principal-derived ownership;
URL trust gate (`ServiceUrls`: http(s) only, no credentials, no
localhost/loopback/private targets); audit vocabulary
(`SERVICE_METADATA_UPDATED`, `SERVICE_ROUTE_VERIFIED`,
`SERVICE_ROUTE_MARKED_UNKNOWN`) reserved for the future admin mechanism
— no user path emits them because no user mutation path exists. Greps
confirm zero Gmail write/send, zero automation, zero secret material.
