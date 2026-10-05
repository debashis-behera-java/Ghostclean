# GhostClean — Microsoft OAuth Setup (Developer Guide)

> Placeholders only. NEVER commit real client ids, secrets, tenant ids,
> or redirect URIs. GhostClean has NOT completed Microsoft publisher
> verification: verification requirements must be satisfied before public
> production use. Testing tenants / test accounts only until then.

## 1. Exact scopes GhostClean requests (Phase 10, delegated, READ-only)

| Scope | Why it is required | What is accessed |
|---|---|---|
| `openid` | Proves the Microsoft identity (OIDC) | Identity only |
| `profile` | Basic profile claims | Basic profile only |
| `offline_access` | Refresh-token support so scans survive access-token expiry | Refresh delegation only |
| `User.Read` | Resolves the stable account id + email via Graph `/me` | Own profile only |
| `https://graph.microsoft.com/Mail.Read` | Reads registration-email **metadata** for service discovery | Message metadata only |

What GhostClean will NOT access: message bodies, attachments, contacts,
calendars, files, or anything else. No message-send scope is requested,
configured, or present in the codebase (build-enforced):
`Mail.Send`, `Mail.ReadWrite`, `Mail.ReadWrite.Shared`,
`Mail.Send.Shared`, and `Mail.Read.Shared` are post-V1 only.

## 2. Manual test setup (your own Entra app registration)

1. Create an app registration (entra.microsoft.com) in your own tenant.
2. Set it up for delegated permissions; add the five scopes from §1.
3. Configure the **exact** redirect URI, e.g. local development:
   `http://localhost:8080/api/v1/oauth/microsoft/callback`
   (must match `MICROSOFT_REDIRECT_URI` character-for-character).
4. Create a client secret; note the tenant id (or keep `common`).
5. Copy values into your local `.env` only:
   `MICROSOFT_CLIENT_ID=…`, `MICROSOFT_CLIENT_SECRET=…`,
   `MICROSOFT_REDIRECT_URI=http://localhost:8080/api/v1/oauth/microsoft/callback`
   (optional: `MICROSOFT_TENANT=…`).
6. Start GhostClean (`docker compose up --build -d`).
7. Register + verify + log in to GhostClean.
8. `GET /api/v1/oauth/microsoft/authorize` (Bearer) → open
   `authorizationUrl`.
9. Approve **only** the delegated READ permissions shown.
10. You land on `…/oauth/callback?provider=microsoft&status=connected`;
    `GET /api/v1/oauth/connections` lists the connection (masked email).
11. `POST /api/v1/scans` with the Microsoft `connectionId` runs a
    bounded metadata scan (same pipeline as Gmail).
12. `DELETE /api/v1/oauth/connections/{id}` → 204; the connection shows
    `REVOKED` and stored credentials are wiped (re-check the list).
    Note: delegated permissions expose no provider-side revocation API,
    so GhostClean performs a local credential wipe; also revoke consent
    at account.microsoft.com → Privacy → Apps and services.
13. Disconnecting a Microsoft connection never deletes scanned evidence
    already attributed to your GhostClean account (see retention rules).

## 3. Scope minimization & verification posture

- Phase 10 ships delegated READ-only: the smallest scope set that
  establishes the connection (`/me`) plus mailbox metadata read.
- Microsoft may require publisher verification and/or consent review
  for production use of `Mail.Read`: expect a verification flow, a
  published privacy policy, and a data-use disclosure. None of this is
  started; do not represent otherwise to users or reviewers.
- `prompt=consent` is used so reconnects reliably re-issue refresh
  material; users can revoke at any time (in-app disconnect +
  Microsoft account settings).

## 4. Privacy-policy notes (for later)

Before public production use: publish a privacy policy describing the
OAuth data collected (provider account id, email, encrypted tokens,
granted scopes), encryption (AES-256-GCM, env key → KMS path), EU
residency, retention (revoked stubs 30d), disconnection, and account
deletion; complete Microsoft's verification for every granted scope;
and record the outcome in this file. Until then, testing tenants +
test accounts only.
