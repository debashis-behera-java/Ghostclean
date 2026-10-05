# GhostClean — Product Requirements (Phase 0)

> Tagline: "Find what the internet remembers. Decide what it should forget."
> Status: Phase 0 blueprint. Normative for future phases unless amended by ADR.
> Phase 0 update (RESOLVED): (1) email verification REQUIRED for V1; (2) degraded manual-forward/import mode supported; (3) V1 deletion sending MANUAL-DRAFT ONLY, no SEND scopes; (4) prod PostgreSQL in EU region, provider-portable; (5) token encryption V1=env key, future=cloud KMS.

## 1. Vision & Positioning

GhostClean helps users **discover online services/accounts plausibly associated with their email address** by analyzing **email/account-registration metadata** obtained through **explicitly authorized OAuth connections**, then helps them **review, request deletion, and track** those requests.

GhostClean is:

- A **privacy engineering platform**, not a legal decision-maker.
- NOT a law firm, NOT a legal service, NOT a guarantee of deletion.
- A user-agent that drafts **legally cautious, jurisdiction-aware requests** and requires **explicit user confirmation** before sending anything.
- Trust-first: no dark patterns, no auto-sending, no overstated claims.

## 2. Non-Goals (Phase 0 → v1)

- No automatic deletion sending on detection.
- No claim of "active account" from email evidence alone. Use **"Detected account evidence"**.
- No claim of absolute deletion right. Requests cite the user-selected framework and allow the recipient to apply exceptions.
- No inbox takeover; V1 requests NO email SEND permissions at all (see `OAUTH_DESIGN.md`).
- No bypass of Google/Microsoft verification or scope restrictions; denied access → labeled manual mode.
- No permanent mailbox mirroring. Metadata-first, short retention.
- No microservices, K8s, Kafka, or LLM-in-critical-path in v1.

## 3. Personas

| Persona | Goal | Concern |
|---|---|---|
| Privacy-conscious individual | Find forgotten accounts, reduce footprint | Trust, minimal permissions, no surprise sends |
| Breach-aware user | Prioritize risky/financial accounts | Accurate risk signal, no FUD |
| Minimalist | Clean up subscriptions | Low friction review flow |

Out of scope v1: enterprise admin, delegated/family accounts, non-email identity sources.

## 4. Functional Requirements

### 4.1 Onboarding & Auth (FR-AUTH)
- FR-AUTH-01: Register with email + password (argon2/bcrypt). Creates `email_verified=false` user + issues 24h single-use verification token. RESOLVED: verification REQUIRED for V1.
- FR-AUTH-02: Login, logout, refresh rotation, lockout/rate-limit. Login permitted while unverified, but sensitive workflows are gated (see FR-AUTH-05).
- FR-AUTH-03: Password reset via time-boxed single-use token.
- FR-AUTH-04: Delete GhostClean account + GhostClean-held data (see §6). Requires verified email + re-auth.
- FR-AUTH-05 (new): Email verification gate. `POST /auth/verify` + rate-limited `POST /auth/verification/resend`. Until `email_verified=true`, the API MUST return `403 EMAIL_NOT_VERIFIED` for: OAuth authorize/callback-link, scan create, manual import, discovered review, deletion-request create/approve/generate/confirm/mark-sent. Verification email sent via transactional mail (provider selected Phase 2; no SEND-scope dependency).

### 4.2 OAuth Connections (FR-OAUTH)
- FR-OAUTH-01: Connect Google/Gmail via OAuth 2.0 + PKCE (server-side code exchange).
- FR-OAUTH-02: Connect Microsoft/Outlook (Phase 10; abstraction ready Phase 1).
- FR-OAUTH-03: Display requested scopes in plain language before redirect.
- FR-OAUTH-04: Disconnect revokes at provider (where API allows) + deletes/encrypt-wipes local tokens.
- FR-OAUTH-05: READ and SEND scopes strictly separated. RESOLVED V1: DO NOT request any SEND permission (`gmail.send`, `Mail.Send`) in V1. SEND capability is post-V1 only, behind a separate permission, separate security boundary, and separate user consent. V1 code/config MUST NOT include active SEND scopes.
- FR-OAUTH-06: Handle denied/partial consent, expired grants, revoked grants with actionable UX. If Gmail metadata permission is unavailable/denied/restricted (incl. incomplete Google verification): DO NOT bypass, DO NOT widen scopes, DO NOT fabricate access — offer degraded manual-forward/import mode (see FR-SCAN-05), clearly labeled.
- FR-OAUTH-07 (new): Manual mode is first-class degraded path, never silent fallback. UI badge: "Manual mode — mailbox not connected".

### 4.3 Scanning (FR-SCAN)
- FR-SCAN-01: Initial full metadata scan with progress, cancellation, resumability.
- FR-SCAN-02: Incremental delta scans (historyId / deltaLink / timestamp watermark).
- FR-SCAN-03: Never store full bodies by default; store normalized metadata + hashes for idempotency.
- FR-SCAN-04: Scanner must not assume every email = account. All output is *candidate evidence*.
- FR-SCAN-05 (new, degraded manual mode): If mailbox access is unavailable, user may manually provide registration-email **metadata** (sender domain/address, subject, received date) via a guided form/import. Manual evidence shares the normalize→detect→resolve pipeline with `source=manual`, is labeled "Manual/degraded — not from mailbox connection", is confidence-capped (never HIGH from a single manual item), and is subject to abuse caps. Full bodies/attachments are never required and never stored.

### 4.4 Detection & Resolution (FR-DETECT)
- FR-DETECT-01: Rule-based detection over sender domain/address, subject patterns, known aliases, verification/welcome/reset keywords.
- FR-DETECT-02: Entity resolution: N evidence rows → 1 discovered-service record per (user, service).
- FR-DETECT-03: Confidence score 0–100 + band (LOW/MEDIUM/HIGH) with explainable contribution breakdown.
- FR-DETECT-04: Evidence view shows why (matched rules, sender, subject, date). Body snippet only if strictly needed and consented; default off.
- FR-DETECT-05: Terminology gate: "Detected account evidence", first/last seen, evidence count. Never "Active account" without corroboration.

### 4.5 Service Directory (FR-DIR)
- FR-DIR-01: Data-driven directory (DB + versioned seed), not hard-coded if/else.
- FR-DIR-02: Fields per `SERVICE_DIRECTORY_DESIGN.md` (domains, aliases, deletion method, URLs, emails, jurisdictional notes, last_verified).
- FR-DIR-03: Deletion methods: `DIRECT_API | PRIVACY_PORTAL | EMAIL_REQUEST | ACCOUNT_SETTINGS | SUPPORT_REQUEST | MANUAL | UNKNOWN`.
- FR-DIR-04: Curated updates require review; poisoning resistance (signed seeds / admin approval).

### 4.6 Risk (FR-RISK)
- FR-RISK-01: LOW/MEDIUM/HIGH/CRITICAL from configurable weights: category sensitivity, data sensitivity, age, recency, abandonment signals, financial/identity flag.
- FR-RISK-02: No fabricated breach claims. Show factors, not FUD.

### 4.7 Deletion Workflow (FR-DEL)
States: `DISCOVERED → REVIEW_REQUIRED → USER_APPROVED → REQUEST_GENERATED → USER_CONFIRMED → REQUEST_SENT → AWAITING_RESPONSE → RESPONSE_RECEIVED → COMPLETED / REJECTED / PARTIAL / VERIFICATION_REQUIRED`.

- FR-DEL-01: Two explicit gates: approve (intent) + confirm (final draft). RESOLVED V1 MANUAL-DRAFT ONLY: ACCOUNT DETECTED → USER REVIEWS → REQUEST GENERATED → USER REVIEWS REQUEST → USER CONFIRMS → GHOSTCLEAN CREATES FINAL DRAFT → USER MANUALLY SENDS IT. GhostClean MUST NOT automatically send any deletion/privacy request in V1 (no SMTP/API send of user requests).
- FR-DEL-02: Jurisdiction selection (GDPR / CCPA-CPRA / OTHER-GENERIC) selects template + deadline computation; no legal conclusions.
- FR-DEL-03: Request preview with recipient, identity-verification checklist, evidence refs.
- FR-DEL-04: Track events, deadlines, reminders, responses (completed/rejected/partial/verification-required).
- FR-DEL-05: V1 send channels MANUAL-DRAFT ONLY: rendered final draft + `mailto:` + copy-button + "Mark as sent" attestation (user confirms they sent it outside GhostClean). No `DIRECT_API`/SMTP send in V1. A future sending capability requires separate SEND permission, separate security boundary, per-request confirm, and a new ADR. Directory `DIRECT_API` entries stay informational in V1.

### 4.8 Audit & Activity (FR-AUDIT)
- FR-AUDIT-01: Append-only audit for auth, OAuth, scans, detections, deletion lifecycle, data deletion.
- FR-AUDIT-02: Never log tokens, passwords, bodies, or excess PII.

### 4.9 Settings & Privacy (FR-SET)
- FR-SET-01: Connections management, scan retention controls, export, GhostClean account deletion.
- FR-SET-02: Privacy page stating what is stored, why, retention, and how to delete.

## 5. UX Requirements

Pages: Landing, Login, Register, OAuth connection, Dashboard, Discovered Accounts, Account Details, Deletion Request, Request Tracking, Activity, Connections, Settings, Privacy.

Dashboard (v1): total discovered, high-risk count, pending requests, completed deletions, recent discoveries, recent activity. Emphasize trust: plain-language scopes, evidence explanations, reversible actions, no dark patterns, accessible (WCAG AA target), mobile-responsive.

## 6. Data Lifecycle & Deletion

- User can disconnect provider → tokens wiped + scans stop. Historical evidence retained per retention setting until user purges.
- User can delete GhostClean account → cascade-delete user data (connections, evidence, discovered services, requests, notifications) + anonymize audit to non-identifying record per `PRIVACY_MODEL.md`. Explicit confirm + re-auth required.

## 7. Legal Guardrails

- Copy deck must include: "GhostClean is not a law firm and does not provide legal advice. Deletion outcomes depend on the receiving organization's legal obligations and applicable exceptions."
- Templates use "request … under [framework] to the extent applicable" language; deadlines labeled "estimated, verify locally".
- Never fabricate provider APIs, deletion endpoints, or privacy emails. Unverified → `UNKNOWN` + `MANUAL` + "verify on provider site".

## 8. Acceptance Criteria (Product)

- [ ] All 15 user capabilities from project brief trace to FR IDs above.
- [ ] Every "send" path has two-gate confirm in spec + API (`POST …/approve`, `POST …/confirm` separate).
- [ ] No spec language asserts active-account or absolute-right claims.
- [ ] Directory + risk + detection are data-driven/configurable, not hard-coded.
- [ ] Privacy page + retention table drafted (see `PRIVACY_MODEL.md`).
- [ ] RESOLVED-01: verification required + gated endpoints enumerated.
- [ ] RESOLVED-02: manual-forward mode specified, labeled degraded, no bypass.
- [ ] RESOLVED-03: V1 manual-draft-only; no SEND scope; no auto-send path.
- [ ] RESOLVED-04: prod PG EU-region assumption recorded; no hard-coded provider.
- [ ] RESOLVED-05: V1 env-key encryption; KMS migration path recorded, not implemented.
