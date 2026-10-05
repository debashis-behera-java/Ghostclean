# GhostClean — Google OAuth Setup (Developer Guide)

> Placeholders only. NEVER commit real client ids, secrets, or redirect
> URIs. GhostClean has NOT passed Google verification: verification
> requirements must be satisfied before public production use.

## 1. Exact scopes GhostClean requests

| Scope | Why it is required | What is accessed |
|---|---|---|
| `openid` | Proves the Google identity; returns the stable `sub` account id | OIDC identity only |
| `email` | Resolves the account email shown in Connections + consent receipt | Email address only |
| `profile` | Basic profile for the OIDC userinfo response shape | Basic profile only |
| `https://www.googleapis.com/auth/gmail.readonly` | Phase 4 mailbox scanning: lists message ids + reads From/Subject/List-Unsubscribe metadata only | Mailbox metadata, read-only |

What GhostClean will NOT access: message bodies, attachments, contacts,
Drive, calendars, or anything else. No message-send scope is requested,
configured, or present in the codebase (build-enforced):
`gmail.send`, `gmail.modify`, `gmail.compose`, and `mail.google.com`
appear nowhere in provider code or configuration.

## 2. Redirect URIs (environment-driven, no code changes)

The Java code never hard-codes a redirect URI. The running container uses
whatever `GOOGLE_REDIRECT_URI` the environment supplies
(`application.yml` → `GoogleOAuthProperties`), so local and production
differ by configuration only.

- LOCAL: `http://localhost:8082/api/v1/oauth/google/callback`
  (host port 8082 → container port 8080; must match `GOOGLE_REDIRECT_URI`
  character-for-character; no wildcards).
- PRODUCTION: `https://api.ghostclean.com/api/v1/oauth/google/callback`
  (set `GOOGLE_REDIRECT_URI` and `APP_BASE_URL=https://api.ghostclean.com`
  in the production environment; no Java source-code change required).

Register every URI you use as an Authorized redirect URI on the same
Google Cloud OAuth client (local + production may coexist on one client).

## 3. Manual test setup (your own Google Cloud project)

1. Create a Google Cloud project (console.cloud.google.com).
2. Enable the required API(s): the default OAuth/OIDC endpoints for
   connect; plus the Gmail API for Phase 4 mailbox scanning
   (`https://gmail.googleapis.com` read calls only).
3. Configure the OAuth consent screen: app name, support email, and — for
   testing status — add your own Gmail as a **test user**. External test
   users cannot consent until added.
4. Create an OAuth client (Web application type).
5. Configure the **exact** Authorized redirect URI(s), e.g. local:
   `http://localhost:8082/api/v1/oauth/google/callback`
   (and additionally the production URI above when deploying).
6. Copy the client id + secret into your local `.env` only:
   `GOOGLE_CLIENT_ID=…`, `GOOGLE_CLIENT_SECRET=…`,
   `GOOGLE_REDIRECT_URI=http://localhost:8082/api/v1/oauth/google/callback`,
   `GOOGLE_MAILBOX_SCOPE=https://www.googleapis.com/auth/gmail.readonly`
7. Start GhostClean (`docker compose up --build -d`).
8. Register + verify + log in to GhostClean.
9. `GET /api/v1/oauth/google/authorize` (Bearer) → open `authorizationUrl`.
10. Approve **only** the identity + read-only permissions shown.
11. You land on `…/oauth/callback?provider=google&status=connected`;
    `GET /api/v1/oauth/connections` lists the connection (masked email).
12. `DELETE /api/v1/oauth/connections/{id}` → 204; the connection shows
    `REVOKED` and stored credentials are wiped (re-check the list).
13. Confirm at myaccount.google.com → Security → Third-party access that
    GhostClean access is removed.

## 4. Scope minimization & verification posture

- Identity (`openid email profile`) establishes the connection; the
  mailbox scope (`gmail.readonly`) is required for Phase 4 scans
  (`ScanService` rejects scans whose granted scopes lack it with
  `SCOPE_INSUFFICIENT` — reconnect to grant it).
- Google classifies `gmail.readonly` as a sensitive/restricted scope:
  expect a verification flow, a published privacy policy, a data-use
  disclosure, and possibly a security assessment before public production
  use. None of this is started; do not represent otherwise to users or
  reviewers.
- `prompt=consent` + `access_type=offline` are used so reconnects reliably
  re-issue refresh material; users can revoke at any time (in-app
  disconnect + Google account settings).

## 5. Security assessment / privacy-policy notes (for later)

Before public production use: publish a privacy policy describing the
OAuth data collected (provider account id, email, encrypted tokens,
granted scopes), encryption (AES-256-GCM, env key → KMS path), EU
residency, retention (revoked stubs 30d), disconnection, and account
deletion; complete Google's verification for every granted scope; and
record the outcome in this file. Until then, testing mode + test users
only.

## 6. Test-user provisioning is manual (verified 2026-10-04)

GhostClean admin approval does NOT add anyone to Google's Test Users list:
Google offers no supported public API for it (`oauthconfig.testusers.*`
exist only as IAM permissions for Beta roles — no public REST reference,
no gcloud command, no discovery document; the backing endpoint is
private). Automating it via console scraping or undocumented endpoints is
explicitly out of scope and forbidden.

Operational flow: admin approves in `/admin/google-requests`, then adds
the user's Google account under Audience → Test users
(`console.cloud.google.com/auth/audience`). GhostClean's UI states this
plainly at the approval, callback-denied, and admin-queue surfaces.
Production path: publish the app / complete verification instead of
growing the test-user list (Testing mode caps at 100 test users).
