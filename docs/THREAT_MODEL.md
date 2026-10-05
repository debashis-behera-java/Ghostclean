# GhostClean — Threat Model (STRIDE + per-threat table)

> Scope: SPA → monolith → PG/Redis → Google/Microsoft APIs. Trust boundary: browser | app | DB | provider. Attacker: network, malicious sender, curious user B, compromised DB/logs, abused OAuth.

## STRIDE Summary

| Category | Top threats | Controls |
|---|---|---|
| Spoofing | JWT theft, session hijack, OAuth callback forgery, forged deletion sender | short JWT, rotating refresh, state+PKCE, re-auth on send, SPF/DKIM on outbound (post-v1) |
| Tampering | IDOR, service-directory poisoning, forged responses | owner checks + 404-mask, signed/reviewed seeds, response provenance |
| Repudiation | user denies approving send | dual-gate audit (APPROVED+CONFIRMED with actor+time+IP hash) |
| Info disclosure | token/DB/log leak, XSS, cross-user oracle | AES-GCM, redaction, CSP/escape, 404-mask, RLS later |
| DoS | rate abuse, huge mailbox, slow provider | rate limits, pagination caps, backoff, cancel, job timeouts |
| EoP | user B reads user A, future admin | no admin v1, 3-layer ownership, ArchUnit |

## Per-Threat Table (THREAT / IMPACT / LIKELIHOOD / MITIGATION / TEST)

1. **OAuth token theft** — I:Critical L:Med — AES-GCM at rest, TLS, memory-minimize, wipe on disconnect, scope-min — TEST: dump-scan for plaintext, disconnect-wipe IT.
2. **DB compromise** — I:Critical L:Low — least-privilege role, no plaintext tokens, encrypted backups, RLS roadmap — TEST: backup redaction check, role-grant review.
3. **Cross-user access (IDOR)** — I:High L:Med — service `requireOwner` + scoped queries + 404-mask — TEST: cross-user matrix E2E (every endpoint).
4. **Broken authorization (missing check)** — I:High L:Med — `@PreAuthorize` + ArchUnit "all controllers call owner check" + review — TEST: static + fuzz IDs.
5. **JWT theft** — I:High L:Med — 15m TTL, rotation, Secure/httpOnly refresh, reuse-det revoke — TEST: replay/expired-token tests.
6. **Session hijacking** — I:High L:Low — binding (IP/UA hash anomaly log), logout-all on reset — TEST: cookie-flag + rotation IT.
7. **CSRF** — I:Med L:Low — Bearer API, SameSite=Lax, Origin check on cookie POSTs — TEST: cross-origin POST blocked.
8. **XSS via email subject/sender** — I:High L:Med — escape-all, no raw HTML, CSP, truncate/sanitize — TEST: payload corpus (`<script>`, `onerror=`) rendered inert.
9. **SQL injection** — I:Critical L:Low — JPA binds only, SAST (SpotBugs/ErrorProne), no concat — TEST: SAST + `'; DROP` IT.
10. **SSRF** — I:High L:Low — allow-list hosts, no user URL fetch, egress timeout — TEST: connector host-allow test + mock evil redirect.
11. **Malicious email content** — I:Med L:Med — size caps, HTML strip, never execute, store hashes — TEST: fuzz mailbox fixtures.
12. **Prompt injection via email** — I:Med L:Med (post-v1) — LLM never in critical path; sanitized signals only, schema-validated output, human review — TEST: injection corpus must not alter score/action.
13. **Forged deletion requests** — I:High L:Low — re-auth + dual-gate + recipient allow-list from directory + audit — TEST: single-call send returns 409.
14. **Unauthorized send** — I:Critical L:Low — confirm requires approve+generate+re-auth+idempotency — TEST: state-machine fuzz.
15. **Directory poisoning** — I:High L:Low — versioned seeds, admin review, `last_verified`, UNKNOWN-default — TEST: seed-signature + unverified-URL test.
16. **Abuse/rate** — I:Med L:Med — bucket limits, scan quotas, CAPTCHA-ready — TEST: 429 IT.
17. **OAuth callback attacks (CSRF/fixation/open-redirect)** — I:High L:Med — signed state, PKCE, allow-list returnUrl — TEST: tampered-state + evil-returnUrl rejected.
18. **Replay (confirm/scan)** — I:Med L:Med — Idempotency-Key + `jti` + single-use reset — TEST: double-POST same key → same result, no dup side-effect.
19. **Insecure logging** — I:High L:Med — redaction filter + secret-scan CI + no bodies — TEST: log-grep for `BEGIN|refresh_token|password` fails build.
20. **Sensitive leakage (responses/errors)** — I:Med L:Med — DTOs exclude secrets, generic 404/500, requestId only — TEST: response-schema + error-body assertions.

Residual risks: provider scope denial (design degraded-mode), user reuses weak password (zxcvbn + breach-check roadmap), provider API change (connector contract tests).
