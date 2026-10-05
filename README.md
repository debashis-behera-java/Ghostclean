# GhostClean

> Automated Digital Footprint Discovery & Account Deletion Assistant

GhostClean is a privacy-focused SaaS platform designed to help users discover abandoned online accounts associated with their email inbox and manage account/data deletion requests from a single interface.

## Overview

GhostClean provides a controlled workflow:

```text
Connect Mailbox
      ↓
OAuth Approval
      ↓
Mailbox Scan
      ↓
Account Discovery
      ↓
Review Discovered Accounts
      ↓
Create Deletion Request
      ↓
User Approval
      ↓
Deletion Request Dispatch
      ↓
Track Request Status
```

**Privacy principle:** discovering an account does not automatically mean deleting it. The user remains in control.

## Features

### Authentication
- User registration and login
- JWT-based authentication
- Email verification
- Protected REST APIs
- Role-aware access control

### Email Verification
- Gmail SMTP transactional email
- Dynamic recipients
- Verification links
- STARTTLS
- Fail-fast configuration validation
- Environment-based credentials

### Google OAuth
- Google OAuth 2.0
- OAuth state validation
- Callback validation
- Encrypted token storage
- Connection status tracking
- Read-only mailbox access
- Admin approval workflow

### Google Connection Approval

```text
User
 ↓
Requests Google Connection
 ↓
Admin receives request
 ↓
Admin approves / rejects
 ↓
User sees approval status
 ↓
Google OAuth authorization
 ↓
Mailbox becomes connected
```

### Mailbox Discovery

After connecting a mailbox, GhostClean scans using the required read-only capability and identifies services/accounts associated with the user's email identity.

### Deletion Requests

GhostClean separates discovery from deletion:

```text
Discovered Account
        ↓
Review
        ↓
Create Deletion Request
        ↓
Draft
        ↓
User Approval
        ↓
Dispatch
        ↓
Track Status
```

This prevents accidental deletion of accounts the user wants to keep.

## Privacy & Security

- Read-only mailbox access for discovery
- Explicit user approval for sensitive actions
- OAuth state protection
- Encrypted OAuth tokens
- Environment-based secrets
- Fail-closed security behavior
- No hard-coded credentials
- Admin authorization for administrative operations

Example environment variables:

```env
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
JWT_SIGNING_KEY=
TOKEN_ENCRYPTION_KEY=
EMAIL_SMTP_USERNAME=
EMAIL_SMTP_PASSWORD=
```

**Never commit real credentials to GitHub.**

## Architecture

```text
                    ┌─────────────────────┐
                    │      Browser        │
                    │   React Frontend    │
                    └──────────┬──────────┘
                               │ REST / JWT
                               ▼
                    ┌─────────────────────┐
                    │   Spring Boot API   │
                    │ Authentication      │
                    │ OAuth               │
                    │ Discovery           │
                    │ Scan Management     │
                    │ Deletion Requests   │
                    │ Admin Approval      │
                    └───────┬─────┬───────┘
                            │     │
                  ┌─────────┘     └──────────┐
                  ▼                          ▼
          ┌───────────────┐          ┌───────────────┐
          │  PostgreSQL   │          │     Redis     │
          │ Users         │          │ State/Cache   │
          │ OAuth         │          └───────────────┘
          │ Discovery     │
          │ Requests      │
          └───────┬───────┘
                  │
                  ▼
          ┌─────────────────┐
          │ Google OAuth /  │
          │ Gmail Read API  │
          └─────────────────┘
```

## Technology Stack

### Backend
- Java 21
- Spring Boot
- Spring Security
- Spring Data JPA
- Hibernate
- PostgreSQL
- Flyway
- JWT
- OAuth 2.0
- JavaMail / SMTP
- REST APIs
- Maven

### Frontend
- React
- TypeScript
- Vite
- React Router
- React Query
- Axios
- Tailwind CSS

### Infrastructure
- Docker
- Docker Compose
- PostgreSQL
- Redis
- Nginx

### Testing
- JUnit
- Spring Boot Test
- Mockito
- Frontend component tests
- Integration tests
- Security tests

## Project Structure

```text
GhostClean/
├── backend/
│   ├── src/
│   └── Dockerfile
├── frontend/
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
├── docs/
├── infrastructure/
├── docker-compose.yml
├── .env.example
├── .gitignore
├── .gitleaks.toml
├── ARCHITECTURE_DECISIONS.md
└── README.md
```

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+
- npm
- Docker Desktop
- Git

Verify:

```bash
java -version
mvn -version
node -v
npm -v
docker --version
docker compose version
```

## Environment Configuration

Create a local `.env` from `.env.example`.

PowerShell:

```powershell
Copy-Item .env.example .env
```

Configure your local values in `.env`.

Example:

```env
APP_BASE_URL=http://localhost:5173

DATABASE_URL=jdbc:postgresql://db:5432/ghostclean
DATABASE_USERNAME=ghostclean
DATABASE_PASSWORD=your-local-password

JWT_SIGNING_KEY=your-development-key
TOKEN_ENCRYPTION_KEY=your-development-encryption-key

GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

EMAIL_PROVIDER=gmail
EMAIL_SMTP_HOST=smtp.gmail.com
EMAIL_SMTP_PORT=587
EMAIL_SMTP_USERNAME=your-email@gmail.com
EMAIL_SMTP_PASSWORD=your-app-password

ADMIN_EMAILS=your-admin-email@gmail.com
```

**Never commit `.env`. Only `.env.example` should be committed.**

## Run With Docker

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

Health check:

```bash
curl http://localhost:8082/api/v1/health
```

Stop:

```bash
docker compose down
```

## Run Backend Locally

```bash
cd backend
mvn test
mvn spring-boot:run
```

## Run Frontend Locally

```bash
cd frontend
npm install
npm run dev
```

Frontend:

```text
http://localhost:5173
```

## Testing

Backend:

```bash
cd backend
mvn test
```

Frontend:

```bash
cd frontend
npm test
npm run build
```

Coverage includes authentication, JWT security, OAuth, Google approval, email verification, token encryption, scanning, discovery, deletion requests, admin authorization, and security boundaries.

## Security Rules

Never commit:

```text
.env
OAuth client secrets
JWT signing keys
Encryption keys
API keys
SMTP passwords
Database passwords
Access tokens
Refresh tokens
Private keys
```

Check before pushing:

```bash
git status
```

Optional secret scan:

```bash
gitleaks detect --source . -v
```

## Application Workflow

### Register

```text
Register → Verification Email → Verify Email → Login
```

### Connect Google

```text
Connect Google → Connection Request → Admin Approval
→ Google OAuth → Connected
```

### Scan

```text
Connected Mailbox → Start Scan → Read-only Mailbox Analysis
→ Discovered Accounts
```

### Request Deletion

```text
Discovered Account → Review → Create Request
→ User Approval → Deletion Workflow
```

## Design Principles

- **User Control:** users decide what should be deleted.
- **Least Privilege:** integrations receive only required permissions.
- **Explicit Approval:** sensitive operations require explicit approval.
- **No Secret Hardcoding:** secrets come from environment configuration.
- **Auditability:** important actions are represented through application events and request states.
- **Fail Closed:** missing authorization or required permissions result in rejection rather than silent execution.

## Project Status

| Component | Status |
|---|---|
| Authentication | ✅ |
| JWT Security | ✅ |
| Email Verification | ✅ |
| Gmail SMTP | ✅ |
| Google OAuth | ✅ |
| Google Connection Approval | ✅ |
| OAuth Token Encryption | ✅ |
| Mailbox Discovery | ✅ |
| Scan Workflow | ✅ |
| Deletion Request Workflow | ✅ |
| Admin Controls | ✅ |
| PostgreSQL | ✅ |
| Redis | ✅ |
| Docker Compose | ✅ |
| Frontend | ✅ |
| Backend Tests | ✅ |
| Frontend Tests | ✅ |

## Current Limitations

GhostClean is currently designed as a development/local SaaS prototype.

External providers may impose restrictions around OAuth testing users, provider verification, account deletion APIs, privacy-request handling, production OAuth publishing, and service-specific deletion mechanisms.

Not every discovered service provides a public API for automatic account deletion. Where automation is not officially supported, GhostClean uses a controlled/manual workflow rather than bypassing provider restrictions.

## Future Improvements

- Production OAuth verification
- More mailbox providers
- Additional discovery sources
- More service-specific deletion integrations
- Deletion automation where officially supported
- Request status polling
- Improved discovery confidence scoring
- Service-specific deletion instructions
- User activity dashboard
- Privacy reports
- Production cloud deployment
- Improved observability and metrics

## Documentation

Additional documentation:

```text
docs/
```

Architecture decisions:

```text
ARCHITECTURE_DECISIONS.md
```

## Contributing

```bash
git clone <repository-url>
cd GhostClean
git checkout -b feature/your-feature

# Make changes

mvn test
npm test

git add .
git commit -m "Add your feature"
git push origin feature/your-feature
```

Then open a Pull Request.

## Disclaimer

GhostClean is a privacy-management and account-discovery project.

The application should only access accounts for which the user has authorization.

Deletion requests should only be submitted when the user intentionally wants the corresponding account or data removed.

Third-party services may have their own terms, APIs, authorization requirements, and deletion procedures.

GhostClean does not attempt to bypass provider security controls or unauthorized access restrictions.

## Author

**Debashis Behera**

Java | Spring Boot | React | Full-Stack Development

## Project

If you find GhostClean interesting, consider starring the repository and following the project as it evolves.

Built with:

**Java · Spring Boot · React · PostgreSQL · Redis · Docker · OAuth 2.0**
