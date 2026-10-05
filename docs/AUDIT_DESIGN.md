# GhostClean — Audit Design (Append-Only Trail)

## 1. Events

`USER_REGISTERED, EMAIL_VERIFIED, VERIFICATION_RESENT, LOGIN_SUCCESS, LOGIN_FAILED, OAUTH_CONNECTED, OAUTH_REVOKED, SCAN_STARTED, SCAN_COMPLETED, SCAN_FAILED, MANUAL_EVIDENCE_IMPORTED, SERVICE_DETECTED, REVIEW_DECIDED, DELETION_REQUEST_CREATED, DELETION_REQUEST_APPROVED, DELETION_REQUEST_GENERATED, DELETION_REQUEST_CONFIRMED, DELETION_REQUEST_DRAFT_READY, DELETION_REQUEST_MARKED_SENT, DELETION_RESPONSE_RECEIVED, REMINDER_SENT, EVIDENCE_PURGED, DATA_DELETED, ACCOUNT_DELETED, SETTINGS_CHANGED`. (V1: no GhostClean-sent event exists; `MARKED_SENT` = user attestation of manual send.)

## 2. Schema & Rules

`audit_logs{id, user_id NULL, event, actor(user|system), ip_hash, user_agent_hash, entity_type, entity_id, metadata JSONB redacted, created_at}`. Append-only: app role `INSERT+SELECT own` only (no UPDATE/DELETE); partitioned by month (roadmap); indexes `(user_id,created_at)`, `(event,created_at)`.

**Redaction:** never `access/refresh_token, password, email body/subject-full, auth headers`. Metadata holds IDs + bands + recipient domains + counts. Central `AuditService.emit(...)` with allow-listed metadata builder + log-filter test.

## 3. Query & Retention

`GET /activity` returns own events (paged, human strings). Retention 1y rolling job; on GhostClean delete → anonymize (`user_id→NULL`, drop IP/UA, keep event+time for security baseline). Export includes audit subset.

## 4. Acceptance

- [ ] Event enum, schema, append-only grants, redaction list, query/retention/anonymize specified + secret-in-log CI test planned.
