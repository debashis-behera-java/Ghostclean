# GhostClean — Background Jobs (Async without Kafka)

> v1: Spring `@Async` + job tables + Redis locks/coordination. No Kafka/K8s. Polling for progress.

## Jobs

| Job | Trigger | Idempotency | Retry/Backoff | Failure/Cancel |
|---|---|---|---|---|
| `mailbox-scan-full` | POST /scans | lock `scan:{conn}` + run `idempotency_key` | 3× exp (1s/5s/30s) transient only | watermark checkpoint; `failed` resumable; cancel flag per page |
| `mailbox-scan-delta` | schedule/manual | watermark compare, no-op if unchanged | same | same |
| `service-detection` | inline per page (sync) + async re-score | evidence unique key | n/a (deterministic) | poison message → quarantine + continue |
| `deadline-reminders` | cron 15m | `(request_id, kind, due)` unique | — | opt-out respected |
| `response-poll` | future/manual | — | — | v1 manual intake |
| `purge-cleanup` | nightly | date-window claim | — | dry-run flag; covers expired/used `email_verification_tokens`, old scan runs, retention-window evidence, 1y audit rollup |
| `token-refresh-sweep` | 10m | per-conn lock | once then mark expired | notify reconnect |

## Job Row

`job_id UUID, type, status(queued|running|done|failed|cancelled), attempts, next_run_at, payload JSONB(redacted), last_error, created/updated`. Redis: locks (TTL+heartbeat), semaphore (max 4 scans), rate buckets. Correlation ID `X-Request-Id` → MDC → all logs/events. Notes: manual evidence import runs inline (no mailbox job); V1 has no mail-send job; crypto uses env key (KMS swap is Phase 11/12 ops work, not a job change).

## Acceptance

- [ ] Each job has id/status/retry/backoff/idempotency/failure/cancel; no silent token loops; progress pollable.
