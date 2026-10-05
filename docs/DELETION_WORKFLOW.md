# GhostClean — Deletion Workflow (State Machine + Legal Caution)

> GhostClean is NOT a legal decision-maker, law firm, or legal service. Templates request action "to the extent applicable" and leave exceptions to the recipient. Two explicit user gates before any draft is finalized. RESOLVED V1: MANUAL-DRAFT ONLY — GhostClean never sends deletion/privacy requests itself; the user sends the final draft outside GhostClean. No SEND permissions in V1.

## 1. States & Transitions

```
DISCOVERED → REVIEW_REQUIRED (auto when eligible / user opens review)
REVIEW_REQUIRED → USER_APPROVED (POST …/approve, verified email only) | DISMISSED
USER_APPROVED → REQUEST_GENERATED (POST …/generate renders template preview; user reviews request)
REQUEST_GENERATED → USER_CONFIRMED (POST …/confirm + re-auth + Idempotency-Key → GhostClean creates FINAL DRAFT, no send)
USER_CONFIRMED (final draft ready) → MARKED_SENT (POST …/mark-sent: user attests "I sent this myself outside GhostClean", stores sent_at + channel=manual_draft) → AWAITING_RESPONSE
AWAITING_RESPONSE → RESPONSE_RECEIVED → COMPLETED | REJECTED | PARTIAL | VERIFICATION_REQUIRED
VERIFICATION_REQUIRED → (user supplies ID per recipient) → AWAITING_RESPONSE (re-notify)
Any pre-SENT → CANCELLED (user). Illegal jump → 409 INVALID_TRANSITION.
V1: there is NO automated-send transition. `REQUEST_SENT` (if used in code) MUST mean "user-attested manual send", never GhostClean-sent.
```

Guarded by `workflow` module (`DeletionStateMachine.allow(from,to)`), unit-tested matrix. `confirm` requires `approved && generated` + fresh re-auth (<10m) + displayed recipient.

## 2. Jurisdiction Selection

User picks per request: `GDPR | CCPA_CPRA | OTHER_GENERIC`. Selects `legal_templates{jurisdiction,version}` + deadline: GDPR ~30d (1 month), CCPA ~45d, OTHER 30d labeled **"estimated — verify locally"**. No legal conclusions in UI; disclaimer on every preview:

> "GhostClean is not a law firm and provides no legal advice. This draft requests action to the extent applicable law requires; the receiving organization determines applicable exceptions and verification."

## 3. Templates & Recipients

`legal_templates` versioned `{subject_tpl, body_tpl, disclaimer}` with placeholders `{user_name, user_email, service_name, jurisdiction, date, evidence_refs(no bodies)}`. Recipient from `service_directory` snapshot (`privacy_email` verified-only else portal URL + manual steps); `UNKNOWN` → manual checklist, never fabricated address. Identity-verification checklist rendered (e.g. "recipient may ask for…") without instructing falsification.

## 4. Draft Channels (V1 MANUAL-DRAFT ONLY, RESOLVED)

Flow: ACCOUNT DETECTED → USER REVIEWS → REQUEST GENERATED → USER REVIEWS REQUEST → USER CONFIRMS → GHOSTCLEAN CREATES FINAL DRAFT → USER MANUALLY SENDS IT (outside GhostClean).
- GhostClean output = rendered final draft (subject + body + recipient + checklist) + `mailto:` link + copy-button. `POST …/mark-sent` records user attestation (`sent_at`, `channel=manual_draft`); deadlines/reminders run from attested date.
- V1 contains NO SMTP/API mail-send client and requests NO SEND scopes. A future sending capability requires a separate permission, separate security boundary, per-request confirm, and a new ADR; `DIRECT_API` directory entries remain informational in V1.
- Every draft/mark-sent writes `deletion_events(draft_ready / marked_sent)` + audit `DELETION_REQUEST_CONFIRMED` / `DELETION_REQUEST_MARKED_SENT` (recipient domain only, no body in logs).

## 5. Tracking: Deadlines / Reminders / Responses

`deadline_at` computed at send; `notification` schedules T-7d, T-1d, overdue reminders (user-opt-out). Response intake: user pastes/records outcome → `response_received` + `completed|rejected|partial|verification_required` + summary; reminders stop on terminal except `verification_required` (new checklist). Evidence refs retained for disputes.

## 6. Risk Appendix (initial model)

Score 0–100, bands LOW/MED/HIGH/CRIT. Weights (config `risk-weights.yml`): `categorySensitivity(finance/health +30) + dataSensitivity +25 + abandonment(last_seen>12m +15) + age>3y +10 + recency<30d +5 + financial/identity flag +20`, cap 100. Display factors; never claim breach. Thresholds CI-tested.

## 7. Acceptance

- [ ] 8-state machine + guards + two-gate + re-auth + idempotency + deadlines/reminders/responses + risk weights specified; copy passes legal-caution review.
- [ ] V1 manual-draft-only enforced: no automated-send transition, no SEND scope, `mark-sent` attestation semantics; all mutation endpoints require verified email.
