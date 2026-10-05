# Runbook: Local Key Rotation (ADR-036 local-only; AWS-KMS path retired)

> The AWS-KMS rotation procedure this file once described was retired
> with the KMS implementation (ADR-036). This runbook now covers
> rotation of the local env-key writer. No KMS key, key ARN, IAM role,
> or cloud console is involved.

## Prerequisites
- Healthy backups (DATABASE_BACKUP.md green) — rotation never starts
  without them.
- New local key generated offline (32 random bytes, Base64); old key
  retained and available for the dual-read window.
- Writes to `oauth_connections` quiesced for the offline sweep (lazy
  rotation needs no quiesce; the sweep does).

## Steps
1. Deploy the application with the new local writer key
   (`TOKEN_ENCRYPTION_KEY` → new key, next `key_version`), keeping the
   legacy key material present for the dual-read window.
2. Verify: new writes carry the new version; old rows still decrypt
   (`SELECT key_version, COUNT(*) FROM oauth_connections GROUP BY 1`
   shows both versions, application error rate flat).
3. Run the bounded offline sweep for untouched rows:
   `SPRING_PROFILES_ACTIVE=rotation-cli` with
   `crypto.rotation-cli.max-rows` set, rerunning until the sweep set
   is empty (idempotent, resumable by `key_version`).
4. Retire the old key ONLY after zero rows remain on it AND backups
   containing old-ciphertext have aged out per retention.

## Verification
Distribution query shows zero old rows; decrypt smoke tests pass;
`mvn test` rotation suites green; audit shows versions/counts
only (no key material).

## Failure handling
Any decrypt regression → KEY-ROLLBACK runbook (prior writer) immediately.
Corrupt/unreadable rows are counted, never force-rewritten; affected
users reconnect through the existing flow.

## Evidence
Key identifiers/versions (never material), version distribution
before/after, sweep summaries, verifier + reviewer names.
