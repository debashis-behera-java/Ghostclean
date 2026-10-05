# GhostClean — Database Design (Conceptual ERD + Tables)

> PostgreSQL + Flyway. All migrations `V1__...` version-controlled. `UUID` PKs (gen_random_uuid), `timestamptz`, soft ownership via `user_id` FK. Multi-user isolation by `user_id` predicate on every user-data table.
> Phase 0 update (RESOLVED): required email verification (`email_verification_tokens`); `service_evidence.source` (oauth/manual) for degraded mode; `oauth_connections.key_version` for env-key rotation → KMS; prod PG EU-region (operational, provider-portable); V1 `send_channel` constrained to manual attestation.

## 1. ERD (text)

```
users 1──* oauth_connections 1──* mailbox_scans
users 1──* discovered_services 1──* service_evidence
users 1──* discovered_services 1──* deletion_requests 1──* deletion_events
service_directory 1──* discovered_services (nullable FK, lookup)
service_directory 1──* deletion_requests (snapshot FK + snapshot copy)
users 1──* deletion_requests 1──* notifications
users 1──* audit_logs
users 1──* user_settings
users 1──* refresh_sessions (auth)
users 1──* email_verification_tokens (auth, single active)
```

## 2. Tables

### users
Purpose: identity + credentials. PK `id UUID`. Unique `email CITEXT`. Fields: `password_hash TEXT (sensitive)`, `email_verified BOOL NOT NULL DEFAULT FALSE (RESOLVED: required V1; gates OAuth/scan/deletion)`, `status (pending_verification, active, locked)`, `created_at/updated_at`, `deleted_at (soft tombstone for grace period)`.
Indexes: `uq_users_email`, `ix_users_status`. Retention: until account deletion; then hard-delete row after 30d grace (anonymize audit). Deletion: cascade to all user tables (see §4).

### oauth_connections
Purpose: one row per (user, provider, provider_account_id). PK `id`. FK `user_id→users` cascade. Unique `(user_id, provider, provider_account_id)`. Fields: `provider ENUM(google,microsoft)`, `provider_account_id TEXT`, `provider_email CITEXT`, `scopes TEXT[] (V1: READ-only values; SEND values rejected by check constraint/app validation)`, `access_token_enc BYTEA (sensitive, AES-GCM, V1 env key)`, `refresh_token_enc BYTEA (sensitive)`, `expires_at`, `token_iv BYTEA`, `key_version INT NOT NULL DEFAULT 1 (rotation → future KMS)`, `status (active,expired,revoked,error)`, `last_error`, `connected_at/last_refreshed_at`.
Indexes: `ix_oauth_user`, `ix_oauth_expiry`. Retention: wiped on disconnect (tokens nulled immediately, row kept as `revoked` stub 30d or hard-deleted per setting). Never log tokens.

### mailbox_scans (+ mail_scan_watermark embedded)
Purpose: scan run + resume state. PK `id`. FK `user_id`, `oauth_connection_id`. Fields: `type (full,delta)`, `status (queued,running,paused,cancelled,completed,failed)`, `progress_total/progress_done`, `watermark (historyId/deltaLink/last_seen_ts)`, `started/completed_at`, `error`, `idempotency_key UNIQUE`.
Indexes: `ix_scans_user_status`. Retention: run rows 90d; watermark retained until disconnect.

### discovered_services ("accounts")
Purpose: entity-resolved account: one per (user, service). PK `id`. FK `user_id` cascade, `service_directory_id NULL`, `oauth_connection_id NULL`. Unique `(user_id, service_directory_id)` where not null + unique `(user_id, normalized_service_key)` fallback. Fields: `service_key CITEXT` (e.g. `spotify`), `display_name`, `category`, `confidence SMALLINT 0–100`, `confidence_band`, `risk_score`, `risk_band`, `evidence_count`, `first_seen_at/last_seen_at`, `status (discovered, review_required, approved, requested, completed, dismissed)`, `dismissed_reason`.
Indexes: `ix_disc_user_conf`, `ix_disc_user_risk`, `ix_disc_service`. Retention: user-controlled; purged on account delete/disconnect-purge.

### service_evidence
Purpose: one row per matched email/metadata item (OAuth or user-provided manual). PK `id`. FK `user_id`, `discovered_service_id`, `mailbox_scan_id NULL (NULL for manual)`. Unique `(user_id, provider, provider_message_id)` for OAuth — idempotency/dedupe; manual rows use unique `(user_id, source, sender_domain, subject_hash, received_at)` + app rate caps. Fields: `source ENUM(oauth,manual) NOT NULL DEFAULT oauth (RESOLVED degraded mode; manual labeled, confidence-capped downstream)`, `provider_message_id_hash TEXT`, `sender_domain CITEXT`, `sender_address_hash TEXT (avoid raw PII where possible)`, `subject_truncated VARCHAR(300) (sanitized)`, `received_at`, `matched_rules JSONB`, `signal_snapshot JSONB`, `confidence_contrib SMALLINT`.
Indexes: `ix_ev_disc`, `ix_ev_sender_domain`, `ix_ev_received`. Retention: 180d default, user-configurable 30–365d; auto-purge job. Deletion: cascade with discovered service.

### service_directory
Purpose: curated service catalog (global, not per-user). PK `id`. Unique `slug`, unique `primary_domain`. Fields: `name, slug, domains TEXT[], aliases TEXT[], category`, `privacy_url, deletion_url, account_settings_url`, `privacy_email CITEXT NULL (verified only, else NULL)`, `deletion_method ENUM`, `gdpr_note, ccpa_note TEXT (descriptive, not advisory)`, `api_deletion BOOL`, `manual_steps TEXT`, `last_verified_at`, `version INT`, `status (active, deprecated)`.
Indexes: GIN on `domains/aliases`, `ix_dir_category`. Retention: global; deprecate not delete (FK snapshot). Seed via versioned SQL/CSV + admin review (anti-poisoning).

### deletion_requests
Purpose: one lifecycle per (discovered service, request). PK `id`. FK `user_id`, `discovered_service_id`, `service_directory_id`, snapshot cols (`recipient_email, recipient_url, method`). Unique `(discovered_service_id, idempotency_key)`. Fields: `jurisdiction ENUM(gdpr,ccpa_cpra,other)`, `status (state machine)`, `template_id/version`, `rendered_body TEXT (final draft, user-visible, redacted logs)`, `recipient_*`, `deadline_at`, `approved_at/by, confirmed_at/by (confirm = final draft, no send)`, `sent_at (V1: user-attested manual send only), send_channel (V1 CHECK = manual_draft; api_future reserved post-V1)`, `response_summary`, `idempotency_key`. V1 MUST NOT persist any automated-send receipt.
Indexes: `ix_del_user_status`, `ix_del_deadline`. Retention: until user deletes; completed kept 2y default for proof, then anonymize.

### deletion_events
Purpose: append-only transitions + responses/reminders. PK `id`. FK `deletion_request_id` cascade. Fields: `type ENUM(created,approved,generated,confirmed,sent,reminder,response_received,rejected,partial,verification_required,completed)`, `payload JSONB (redacted)`, `actor (user,system)`, `created_at`. Index `ix_delev_req_time`.

### audit_logs
Purpose: security/audit trail. PK `id (BIGSERIAL)`. FK `user_id NULL (preserve anon events like login_failed)`. Fields: `event ENUM (see AUDIT_DESIGN)`, `actor, ip_hash, user_agent_hash`, `entity_type/id`, `metadata JSONB (redacted)`, `created_at`. Indexes: `ix_audit_user_time`, `ix_audit_event_time`. Retention: 1y rolling (or per policy), anonymized on account delete. Append-only: no UPDATE/DELETE grants to app role.

### Supporting: user_settings, refresh_sessions, email_verification_tokens, notifications, legal_templates
- `user_settings(user_id PK/FK)`: retention_days, reminder prefs, jurisdiction default.
- `refresh_sessions(id, user_id, token_hash UNIQUE, expires_at, rotated_from, revoked)`: rotation tracking.
- `email_verification_tokens(id UUID, user_id FK cascade, token_hash TEXT UNIQUE, expires_at, used_at NULL, created_at)`: RESOLVED V1; 24h single-use; partial index on `(user_id) WHERE used_at IS NULL`; cron purges expired/used.
- `notifications(id, user_id, type, ref_id, status, scheduled_at/sent_at)`.
- `legal_templates(id, jurisdiction, version, subject_tpl, body_tpl, disclaimer, active)`: versioned, never auto-edited.

## 3. Cross-cutting Rules

- **Isolation:** every query filters `user_id = :authUser`; add DB `ROW LEVEL SECURITY` post-v1 (defense in depth) + service-layer `requireOwner` + API tests for cross-user 403/404.
- **Encryption:** `*_token_enc` AES-256-GCM, per-row IV + `key_version`, V1 key ONLY from env `TOKEN_ENCRYPTION_KEY` (never committed/logged); future KMS swap via `TokenCryptoProvider`; no plaintext tokens in DB/logs/dumps. Current state (ADR-036, local-only): the KMS-swap future was retired; the env-key writer is the supported implementation.
- **Residency (RESOLVED):** production PostgreSQL + backups in an EU region; app code stays cloud-portable (no provider SDK in schema/migrations). Current state (ADR-036, local-only): the supported database is local Docker Compose PostgreSQL with `pg_dump`/`pg_restore` tooling; no cloud production exists.
- **Idempotency:** unique `(user,provider,messageId)`; scan ingest `ON CONFLICT DO NOTHING`; deletion confirm requires `Idempotency-Key`.
- **Cascades:** `users → all user tables CASCADE`; `discovered_services → evidence, deletion_requests CASCADE`; `deletion_requests → events CASCADE`; `service_directory` never cascade-deleted (nullify + keep snapshot).
- **Migrations:** Flyway, backward-compatible, `NOT NULL` via 2-step, indexes `CONCURRENTLY` in prod.

## 4. Acceptance

- [ ] All tables have purpose/PK/FK/index/unique/sensitivity/retention/deletion documented above.
- [ ] V1 migration sketch derivable (users → oauth → scans → directory → discovered → evidence → deletion → events → audit).
- [ ] Isolation + token-encryption + idempotency tests planned in `TESTING_STRATEGY.md`.
