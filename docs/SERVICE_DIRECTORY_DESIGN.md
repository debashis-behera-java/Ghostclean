# GhostClean — Service Directory Design (Data-Driven Catalog)

## 1. Schema (→ `service_directory` table)

`slug UNIQUE, name, domains TEXT[], aliases TEXT[], category (social|shopping|finance|health|dev|media|travel|other), privacy_url, deletion_url NULL, account_settings_url NULL, privacy_email NULL (verified-only), deletion_method ENUM(DIRECT_API,PRIVACY_PORTAL,EMAIL_REQUEST,ACCOUNT_SETTINGS,SUPPORT_REQUEST,MANUAL,UNKNOWN), gdpr_note, ccpa_note (factual, non-advisory), api_deletion BOOL, manual_steps TEXT, last_verified_at, version, status(active|deprecated)`. GIN indexes on domains/aliases; lookup `lower(sender_domain) = ANY(domains)`.

## 2. Seed & Governance (anti-poisoning)

- Seeds: versioned `V…__service_directory_seed.csv/sql` + `directory/changelog.md`; each row needs `source_url` + `last_verified_at`; unverified URLs/emails → `NULL` + `UNKNOWN/MANUAL`, never fabricated.
- Review: two-person (author + reviewer) for email/URL changes; CI validates URL shape, email shape, enum, duplicate domains; checksum of seed in release notes.
- Runtime: read-through cache (Caffeine/Redis 1h); admin edit API deferred post-v1 (direct DB + migration for v1); deprecate-not-delete (keep FK snapshots).

## 3. Matching & Fallback

Exact domain → alias → eTLD+1 heuristic (`needs_review`). Unknown service → ephemeral `service_key` card with `UNKNOWN` method + "Verify on provider site" CTA + suggest-link (crowdsource backlog, manual triage only).

## 4. Deletion-Method Semantics

- `PRIVACY_PORTAL`: deep-link + steps; `EMAIL_REQUEST`: verified `privacy_email` + template; `ACCOUNT_SETTINGS`: settings URL + steps; `SUPPORT_REQUEST`: contact flow; `MANUAL`: free-text steps; `DIRECT_API`: reserved (never auto-used in v1; requires SEND consent + confirm even post-v1); `UNKNOWN`: verify-manually guidance.

## 5. Jurisdiction Notes (descriptive, not advice)

`gdpr_note` e.g. "Lists erasure contact; outcomes subject to Art. 17 exceptions." `ccpa_note` e.g. "Lists CCPA request portal." Copy reviewed by non-lawyer-safe language list (no "you have the right", use "you may request … to the extent applicable").

## 6. Acceptance

- [ ] Fields, methods, seeding/review, matching, UNKNOWN-default, no-fabrication rule specified.
