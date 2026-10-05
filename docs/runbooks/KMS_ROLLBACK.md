# Runbook: Local Key Rollback (ADR-036 local-only; AWS-KMS path retired)

> The AWS-KMS rollback procedure this file once described was retired
> with the KMS implementation (ADR-036). This runbook now covers
> restoring the prior local env-key writer. No key ARN, IAM role, or
> cloud console is involved.

## Prerequisites
- Prior writer key recorded (previous `TOKEN_ENCRYPTION_KEY` value
  reference and its `key_version`).
- Prior key material retained (rollback is impossible after old-key
  retirement — that gate exists for exactly this reason).

## Steps
1. Restore the previous writer key (`TOKEN_ENCRYPTION_KEY` → prior
   value) and redeploy.
2. Verify: reads succeed on both versions (dual-read), new writes
   carry the restored version, error rate flat.
3. Rows already converted to the newer version remain readable ONLY
   while the newer key material is retained — do not discard the
   newer key until a forward re-rotation converges them back.

## Verification
Version distribution query, decrypt smoke tests, health UP, audit
hygiene intact.

## Failure handling
If neither writer decrypts (key loss): stop, declare a security
incident (SECURITY_INCIDENT.md), restore service from the last
healthy backup to a disposable target first — never experiment on
live rows.

## Evidence
Configurations before/after, distribution queries, approver, timestamps.
