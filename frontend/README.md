# GhostClean Frontend

React + TypeScript + Vite dashboard for the GhostClean Spring Boot API
(`../backend`). The backend is the authority for every safety decision; this
app renders backend state and calls backend endpoints — it never invents
routes, destinations, approvals, or dispatches.

## Prerequisites

- Node.js 20+
- The backend running locally (default `http://localhost:8082`), or
  `VITE_API_BASE_URL` pointed at a reachable API origin.

## Installation

```bash
cd frontend
npm install
```

## Commands

```bash
npm run dev      # dev server on http://localhost:5173 (/api proxied to :8082)
npm run build    # typecheck + production build into dist/
npm run preview  # serve the production build locally
npm test         # frontend tests (Vitest, mocked API — no network)
```

## Environment setup

Copy `.env.example` to `.env` only if you need a non-default API origin:

```bash
VITE_API_BASE_URL=http://localhost:8082
```

In development, leave this unset: Vite proxies same-origin `/api` calls to
`http://localhost:8082`, which also avoids the backend's lack of CORS
configuration. Anything starting with `VITE_` is public in the browser
bundle — never put secrets there (no DB passwords, JWT secret, OAuth client
secrets, encryption keys, API keys, or tokens).

## Authentication flow

- `POST /api/v1/auth/login` returns `{ accessToken, emailVerified }`; the
  refresh token arrives as an httpOnly `gc_refresh` cookie.
- The access token lives **in memory only** (`src/api/client.ts`), never in
  localStorage. Reloads restore the session via `POST /api/v1/auth/refresh`
  using the cookie.
- On 401, the client attempts exactly one refresh+retry; concurrent 401s
  share one refresh. Refresh failure clears auth and redirects to `/login`.
- Unverified accounts are routed to `/verify-pending` (backend gates most
  actions on verification).
- OAuth: `GET /api/v1/oauth/{google,microsoft}/authorize` returns the
  provider URL from the backend — the frontend never constructs OAuth URLs.
  The backend 302-redirects to the return URL with status codes only
  (`/oauth/callback?provider=&status=`); no tokens ever touch the URL.
  Note: the backend only accepts a returnUrl on its own origin
  (`APP_BASE_URL`), so local OAuth testing requires serving the app from the
  backend origin or adjusting `APP_BASE_URL` — no code change is needed.

## Key user flows

- **Scan:** pick a connected mailbox → `POST /api/v1/scans` (backend runs it
  synchronously; no polling loops). Cancel/delete act on explicit clicks.
- **Discoveries:** `GET /api/v1/discovered` with client-side search/confidence/
  status filters. Evidence shows truncated, redacted subjects only.
- **Deletion preview:** `POST /api/v1/deletion-requests/preview` renders a
  labeled DRY RUN. "Create Request" stores a draft only.
- **Lifecycle:** edit (`PATCH`) → approve exact version (`POST …/approve`) →
  explicit dispatch (`POST …/dispatch`) behind a confirmation modal with a
  mandatory understanding checkbox. Approval never dispatches; dispatch is
  called from exactly one place (`PrivacyRequestDetailPage`).
- **Google approval:** `Connect Google` first creates a GhostClean approval
  request (`POST /api/v1/google/connection/request`); OAuth starts only after
  the backend reports APPROVED. Admins review at `/admin/google-requests`
  (visible only when the profile reports `admin: true`; APIs re-check
  server-side with 403 otherwise).
- **Tracking/responses:** computed tracking view plus manual service-outcome
  intake (`completed|rejected|partial|verification_required`).

## Security considerations

- Backend is authoritative: no local approval/dispatch state, no
  user-supplied destinations (create/dispatch forms have no recipient field),
  no route-verification overrides.
- Responses are rendered defensively: `sanitize.ts` redacts token-shaped
  strings; connection lists show masked emails; evidence shows no bodies.
- Error mapping (`utils/errors.ts`) surfaces safe text only — no stack
  traces, SQL, or tokens.
- `console` output must never include tokens or payloads (enforced by review;
  see the secret-scan checklist in the project instructions).

## Project structure

```
src/
  api/        Axios client + per-domain endpoint modules (no raw axios in UI)
  auth/       AuthProvider, ProtectedRoute, session orchestration
  components/ Layout/sidebar, badges, modal, loading/error/empty states
  hooks/      useApi (one-shot fetch with reload, no polling)
  pages/      one module per route (see src/routes/router.tsx)
  routes/     browser router
  styles/     global theme (dark, high-contrast, responsive)
  test/       Vitest setup
  types/      backend DTO mirrors
  utils/      error mapping, formatting, sanitization
```
