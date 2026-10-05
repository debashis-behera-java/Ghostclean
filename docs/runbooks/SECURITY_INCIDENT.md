# Runbook: Security Incident / Credential Compromise (local-only, ADR-036)

## Prerequisites
- Authority to revoke credentials, keys, and sessions immediately.
- Separate channel for coordination (assume affected systems
  may be observed).

## Steps
1. Contain: revoke the implicated credential/key/session FIRST
   (DB password reset, writer-key disable/swap in `.env`,
   refresh-session chain revocation via logout-all/reset paths,
   OAuth disconnect at the provider) — then investigate. For the
   token-encryption writer: deploy the replacement writer key but
   retain the old material pending the deletion window; rows stay
   decryptable for forensics via the dual-read window.
2. Preserve evidence: snapshot logs, audit rows (append-only — do
   not purge), relevant backups (pin retention).
3. Assess scope using audit events (actor/time/entity, no secrets):
   which users, rows, keys, time window.
4. Eradicate + recover: rotate per SECRET_ROTATION.md /
   KMS_ROTATION.md (local key rotation), force re-authentication
   where the vector allows, redeploy clean.
5. Review: root cause, control gap, regression test for the gap,
   disclosure obligations assessed (no legal conclusions in-repo).

## Verification
Vector closed, fresh credentials active everywhere, old material
revoked/disabled (not merely deleted from view), logs show
no residual abuse, full regression green.

## Failure handling
If scope cannot be bounded, keep the service in its safest degraded
posture (fail closed) and escalate to human leadership before
restoring full traffic.

## Evidence
Timeline, scope assessment, revocations, rotations, verification —
never secret values or key material.
