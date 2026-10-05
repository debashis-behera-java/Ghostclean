# Runbook: Secret Rotation (local-only, ADR-036)

## Prerequisites
- Access to `.env` (git-ignored; placeholders in `.env.example`); second reviewer for changes.
- Healthy backups (key retirement depends on them).

## Steps
1. `JWT_SIGNING_KEY`: put the new value in `.env` and redeploy —
   rotate during low traffic: existing 15-minute access tokens expire
   naturally; rotating refresh sessions re-issue. Verify
   login/refresh/logout smoke tests, then retire the old value.
2. Database credentials (`ghostclean` user, migration role): set the new
   password in Compose/`db` out of band, update `.env`,
   restart the app, verify connectivity + RLS matrix, retire the old password.
3. OAuth client secrets: rotate at the provider console first, then
   `.env`, then verify authorize/callback on a test connection.
4. Token-encryption key material: see KMS_ROTATION.md (local key
   rotation — never discard a writer key before the retirement gate).
5. Verification-mail credentials (once the ADR-035 provider lands):
   rotate per that provider's runbook section; verify with a test address.

## Verification
Per-secret smoke test; no secret value in logs, images, or Git
(re-grep the release tree); old values revoked/disabled, not merely
forgotten.

## Failure handling
Failed rotation → restore the prior value in `.env` and redeploy; if
compromise is suspected, use SECURITY_INCIDENT.md instead (revoke
first, rotate second).

## Evidence
What rotated, when, verifier + reviewer, smoke outputs. Never the
secret values themselves.
