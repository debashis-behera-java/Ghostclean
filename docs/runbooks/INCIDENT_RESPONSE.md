# Runbook: Incident Response (local-only, ADR-036)

## Prerequisites
- Operator access to the local Compose stack, logs, and `.env`.
- Authority to freeze deploys and rotations during the incident.

## Steps
1. Acknowledge, declare severity, freeze deploys/key rotations/backups
   changes. Start a timestamped incident log.
2. Triage via HEALTH_TROUBLESHOOTING.md: app health, local DB status,
   Redis backend, local Nginx edge, error-envelope rates.
3. Mitigate (in order): rollback to last known-good (ROLLBACK.md);
   disable the affected integration (e.g. set the crypto provider back
   to the prior writer) rather than hot-patching the running service.
4. If credentials or keys may be involved → SECURITY_INCIDENT.md
   takes precedence from this point.
5. Recover, verify (full DEPLOYMENT.md verification), hold a
   blameless review, and file follow-up actions with owners.

## Verification
Health UP, error rates baseline, audit logging intact, incident log
complete, follow-ups filed.

## Failure handling
Escalate to human leadership with evidence (never credentials); state
explicitly if a required resource is unavailable rather than
improvising destructive fixes.

## Evidence
Incident log, severity, timeline, mitigations, verification outputs,
review notes.
