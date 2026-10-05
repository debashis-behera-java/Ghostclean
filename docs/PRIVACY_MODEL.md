# GhostClean — Privacy Model (Classification, Minimization, Retention)

> Principles: data minimization, purpose limitation, least privilege, explicit consent, encryption, short retention, revocation, user-controlled deletion, auditability, secure defaults. Metadata-first; no permanent mailbox mirror.
> Phase 0 update (RESOLVED): verification required (V1); manual metadata import (degraded, labeled); V1 manual-draft-only (no SEND); prod data residency EU, provider-portable; token encryption V1 env-key, KMS later.

## 1. What We Need & Why

| Data | Why (purpose) |
|---|---|
| GhostClean login email + password hash + verification state | authenticate user, prove account ownership before sensitive workflows (OAuth/scan/deletion) |
| OAuth tokens (encrypted) + provider account id/email + granted scopes | fetch mailbox metadata only for consented purpose; refresh; prove which inbox was scanned |
| Message metadata: provider message-id (hash), sender domain, sender-address hash, truncated subject, date | detect candidate registrations, group evidence, explain confidence — without bodies |
| Manually provided registration metadata (`source=manual`: sender, subject, date typed/pasted by user) | degraded detection when mailbox access unavailable; same pipeline, labeled manual, confidence-capped |
| Detection outputs: matched rules, confidence, risk, first/last seen | show "detected account evidence", prioritize review |
| Deletion request draft + recipient + jurisdiction + events | generate cautious request, track lifecycle, prove timeline |
| Audit events (actor, time, entity refs) | security + repudiation resistance, no secrets |
| Settings (retention, reminders, jurisdiction default) | honor user choices |

Explicitly NOT collected by default: email bodies, attachments, full inbox text, contacts, non-registration mail content, precise geolocation.

## 2. Classification Table

| DATA | SENSITIVITY | PURPOSE | RETENTION | ENCRYPTION | ACCESS CONTROL | DELETION BEHAVIOR |
|---|---|---|---|---|---|---|
| password_hash | High | auth | until reset/delete | hash (Argon2id, no decrypt) | app-role only, never returned | re-hash on reset; wiped on account delete |
| oauth access/refresh tokens | Critical | metadata fetch | until disconnect/expiry/revoke | AES-256-GCM (V1 env key `TOKEN_ENCRYPTION_KEY`, never committed/logged; future cloud KMS) at rest, TLS transit | owner + `oauth` module only | nulled immediately on disconnect; revoke best-effort at provider |
| provider account id/email | High | identify inbox, prevent dup | until disconnect + 30d stub or purge | TLS + at-rest DB encryption | owner only | deleted/purged with connection |
| message metadata (ids, sender domain, subject trunc.) | High | detection + evidence | 180d default (user 30–365d), auto-purge | TLS + DB encryption (DB hosted in EU region in production) | owner only, sanitized display | cascade on account/disconnect-purge |
| email bodies/attachments | Prohibited by default | — | not stored; transient memory only if fetched for snippet (default off, explicit opt-in) | — | — | never persisted |
| discovered confidence/risk | Medium | prioritization | with evidence | DB encryption | owner only | deleted with account |
| deletion drafts/bodies | High | request + proof | 2y post-completion default, then anonymize | DB encryption | owner only | user-deletable; anonymized after retention |
| audit logs | Medium | security | 1y rolling | DB encryption, append-only role | owner-scoped read (own events) | anonymize (drop IP/UA/entity PII) on account delete |
| logs/metrics/traces | Medium | ops | 30d | TLS, redaction filter | ops only, no PII | redacted; no tokens/bodies ever |

## 3. Consent & Purpose Limitation

- V1 requests READ-ONLY mailbox scopes; V1 requests NO SEND permissions and performs NO automated sending — deletion output is a user-sent manual draft.
- If mailbox permission is unavailable/denied/restricted, GhostClean offers the degraded manual-metadata path instead of widening scopes; the manual path is explicitly labeled and never claims mailbox verification.
- Plain-language scope screen before OAuth redirect (what + why + READ-only). A future SEND capability requires a separate screen + separate grant + new ADR; denied SEND never blocks READ features.
- Consent receipt stored (`scopes`, timestamp, provider) in `oauth_connections` + audit `OAUTH_CONNECTED`.
- Google connection data (Phase 3): why — establishing the provider link Phase 4 scanning will use. Collected: provider account id (`sub`), account email, granted scopes, token expiry. Stored encrypted: OAuth access/refresh tokens (AES-256-GCM, never plaintext, never logged, never returned to the browser, never in audit). Retained until disconnect (tokens wiped immediately, revoked stub kept 30d) or GhostClean account deletion (cascade). NOT collected in Phase 3: mailbox content, message metadata, contacts, Drive, or anything beyond OIDC identity. Disconnect = best-effort provider revocation + local credential wipe + audit receipt.
- Secondary use prohibited: detection outputs never used for ads/profiling; no sale of data.
- Evidence snippets (if ever enabled) require explicit per-connection opt-in + visible indicator + one-click purge.

## 4. Retention & Purge Jobs

- Production data residency: PostgreSQL + backups hosted in an **EU region** (exact cloud/provider/region selected Phase 12; architecture portable across AWS/Azure/GCP/managed PG). Local/dev may run anywhere; no production data in dev.

- Nightly: `evidence_purge` (older than setting), `scan_run_gc` (90d), `audit_rollup` (1y), `revoked_stub_gc` (30d), `completed_request_anonymize` (2y).
- Disconnect: stop jobs (Redis lock release), wipe tokens, keep evidence per setting with banner "tokens revoked — rescan requires reconnect".
- GhostClean delete: confirm + re-auth → job cascades all user rows → anonymize audit → delete auth session → provider revoke best-effort → receipt.

## 5. User Rights Mapping (GDPR-style / CCPA-style, descriptive)

Access (export JSON), rectification (profile/settings), erasure (purge + account delete), restriction (pause scans/disconnect), portability (export), objection (opt-out reminders). Requests honored in-app without email round-trip. No legal-advice copy; point to provider/authority for disputes.

## 6. Acceptance

- [ ] Table above enforced by schema (no body column), purge jobs, and DTO redaction.
- [ ] Consent screens + retention controls in UX spec; privacy page copy drafted from §2–§5.
