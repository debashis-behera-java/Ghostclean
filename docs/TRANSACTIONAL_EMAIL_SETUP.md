# GhostClean — Transactional Email Setup (Verification Mail, ADR-038)

> Placeholders only. NEVER commit real API keys or sender addresses tied to
> real domains beyond example values. GhostClean's Google verification is
> NOT complete; transactional verification mail does not change that posture.

## 1. Provider selection

Resend (https://resend.com) — API-based transactional email over HTTPS.
Rationale: no SMTP credentials to manage, no SDK dependency (plain
`RestClient` call, same pattern as the Bedrock scorer), generous free
tier for verification volumes, per-key scoping, EU-friendly operation.
No personal-Gmail relay: Gmail SMTP would couple onboarding to a mailbox
and to Gmail SEND permissions GhostClean deliberately does not hold.

## 2. Account setup

1. Create a Resend account (resend.com/signup).
2. Create an API key with **Sending access** only (Dashboard → API Keys →
   Create API Key → permission "Sending access"). Copy it once — it is
   shown only at creation time.

## 3. Sender verification

Resend delivers only from verified identities:

- **Single sender (fastest for local testing):** verify your own mailbox
  as a sender address if your plan supports it, and use it as
  `EMAIL_FROM` temporarily.
- **Domain (production):** Domains → Add Domain → add the shown SPF/DKIM
  (and DMARC) records at your DNS host → wait for "Verified". Then use
  e.g. `verify@mail.ghostclean.com` as `EMAIL_FROM`. Until the domain is
  verified, production sends fail closed with a logged `http-error`.

## 4. Required environment variables

| Variable | Meaning |
|---|---|
| `EMAIL_PROVIDER` | `resend` to activate; empty (default) keeps silent/log behavior |
| `EMAIL_FROM` | Verified sender address |
| `EMAIL_FROM_NAME` | Display name (default `GhostClean`) |
| `EMAIL_API_KEY` | Resend key (Sending access), environment only |
| `EMAIL_API_BASE_URL` | Default `https://api.resend.com` (override for mocks/tests only) |
| `EMAIL_TIMEOUT_MS` | Per-request read timeout ms (default `8000`) |

The API fails fast at startup if `EMAIL_PROVIDER=resend` without
`EMAIL_API_KEY`/`EMAIL_FROM` — misconfiguration surfaces at boot, never
at the first signup.

## 5. Local development configuration

In GhostClean `.env` (never committed):

```text
VERIFICATION_MAIL_MODE=silent
EMAIL_PROVIDER=resend
EMAIL_FROM=<your verified sender>
EMAIL_FROM_NAME=GhostClean
EMAIL_API_KEY=<paste key here, never in chat>
APP_BASE_URL=http://localhost:8082
```

Recreate the `api` service so the container picks up the new
environment, then register. Keep `VERIFICATION_MAIL_MODE` at `silent`
once the provider is active (the `log` mode warning no longer applies
because links are delivered, not logged).

## 6. Production configuration

Same variables via the production secret manager / environment:

```text
EMAIL_PROVIDER=resend
EMAIL_FROM=verify@mail.ghostclean.com
EMAIL_FROM_NAME=GhostClean
EMAIL_API_KEY=<production key>
APP_BASE_URL=https://<production-domain>
AUTH_COOKIE_SECURE=true
VERIFICATION_MAIL_MODE=silent
```

No code or image change is required — only environment. Verification
links are built from `APP_BASE_URL`, so they point at production
automatically.

## 7. DNS requirements

- Domain senders: SPF + DKIM (+ DMARC) records from the Resend dashboard.
- Single-sender testing: none.
- No MX changes are needed (receiving mail is unrelated).

## 8. Testing procedure

1. Health: `GET /api/v1/health` → `{"status":"UP"}`.
2. Register a real address: `POST /api/v1/auth/register` → 201
   `verificationRequired: true`.
3. Inbox: GhostClean "Verify your GhostClean account" email arrives
   (link valid 24h, single-use). **Do not forward the link; it grants
   account activation.**
4. Click the link (a browser GET to `{APP_BASE_URL}/api/v1/auth/verify?token=…`,
   served by the API itself — no separate frontend required). The server
   consumes the single-use token and redirects to a token-free success
   page → account verified.
5. `POST /api/v1/auth/login` → `emailVerified: true`.
6. `GET /api/v1/oauth/google/authorize` no longer returns
   `EMAIL_NOT_VERIFIED`.

## 9. Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| 201 but no email | `EMAIL_PROVIDER` empty / key missing | Check boot logs for the fail-fast error; set vars; recreate `api` |
| Log `reason=http-auth` | Wrong/revoked key | Rotate key in Resend, update `.env`, recreate `api` |
| Log `reason=http-error` | Unverified sender/domain | Complete §3; Resend dashboard shows the rejection |
| Log `reason=timeout/transport` | Egress/proxy issue | Check container network egress to `https://api.resend.com` |
| Resend ok but link 404s | `APP_BASE_URL` mismatch | Must be the public base users open (local: port 8082) |
| Old link invalid after resend | By design (single-active) | Use the newest email only |

Provider secrets never appear in logs: outcomes log as
`requested / handed to delivery / succeeded / failed reason=<category>`.

## 10. Security notes

- API key scope: Sending access only; rotate via Resend dashboard;
  rotation = new key → `.env` → recreate `api` (see
  `docs/runbooks/SECRET_ROTATION.md` conventions).
- Verification tokens: 32 CSPRNG bytes, SHA-256 hash at rest, 24h TTL,
  single-use with sibling invalidation, never logged, never returned.
- Resend success never implies identity proof beyond inbox control —
  the same standard as any email-verification system.
- This sender carries verification links ONLY. Deletion-request email
  stays manual-draft-only; no Gmail SEND scope exists anywhere.
