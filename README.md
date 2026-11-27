# WorkMark

WorkMark is a Java 21 / Spring Boot 3 modular monolith for company leave and attendance management. It uses a shared PostgreSQL schema with company-scoped rows, Flyway migrations, stateless JWT access tokens, and role-based authorization.

## Requirements

- Java 21
- Gradle 8.14 or newer
- PostgreSQL 15 or newer (or Docker Compose)

## Run locally

1. Create a PostgreSQL database and user.
2. Set the required environment variables in the shell that will run the app:

   ```powershell
   $env:SPRING_PROFILES_ACTIVE = "dev"
   $env:DB_URL = "jdbc:postgresql://localhost:5432/workmark"
   $env:DB_USERNAME = "your-database-user"
   $env:DB_PASSWORD = "your-database-password"
   $env:DB_SSL_MODE = "disable"
   $env:JWT_SECRET = "use-a-private-random-secret-with-at-least-32-bytes"
   $env:JWT_EXPIRATION_SECONDS = "3600"
   gradle bootRun
   ```

   Hibernate validates the schema; Flyway applies migrations on startup. Do not configure Hibernate schema update in production.

## Run with Docker Compose

For local development, copy `.env.example` to `.env`, replace all sample passwords and the JWT secret with private values, and run:

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
```

The development override publishes PostgreSQL only on loopback at `127.0.0.1:5432` and enables public Swagger. The base Compose file does not publish the database port; PostgreSQL is reachable only by the application on the internal Compose network. It creates a separate, least-privilege application role for the app. Persistent data is stored in the `workmark-data` volume. The sample `.env` values are for local development only and must be replaced.

For production Compose deployments, do not use the development override. Configure production secrets through the deployment platform, provide a trusted PostgreSQL server certificate/key using `POSTGRES_TLS_CERT_PATH` and `POSTGRES_TLS_KEY_PATH`, and run:

```powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build
```

The production override enables PostgreSQL TLS and requires JDBC `sslmode=verify-full`; the certificate must be valid for the `postgres` service name and trusted by the application's JVM. Keep the database host port unpublished. The application uses a separate database superuser only to initialize the least-privilege app role. For an externally managed database, deploy the application container without the local PostgreSQL service and provide `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `DB_SSL_MODE=verify-full` through the deployment platform.

The host-run development profile supports `DB_SSL_MODE=disable` for local PostgreSQL. Production defaults to `verify-full`. Production SMTP is configured with `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM`. Invitation, activation/setup, and password-reset messages are stored in the same transaction as their token changes, encrypted with AES-256-GCM, then delivered by a background worker after commit. Retries reuse the same expiring token with capped exponential backoff; successful, expired, and exhausted messages have their encrypted payload removed. Set `ACCOUNT_TOKEN_OUTBOX_KEY` to a separately managed Base64-encoded 32-byte key (for example, generate one with `openssl rand -base64 32`); do not reuse `JWT_SECRET` or commit the key. `ACCOUNT_TOKEN_OUTBOX_MAX_ATTEMPTS`, `ACCOUNT_TOKEN_OUTBOX_BASE_RETRY_DELAY`, `ACCOUNT_TOKEN_OUTBOX_MAX_RETRY_DELAY`, and `ACCOUNT_TOKEN_OUTBOX_POLL_INTERVAL_MS` control worker retry behavior. The worker logs message IDs and failure categories only, never email addresses or tokens. The Docker image runs as a non-root user; Compose makes the app filesystem read-only, enables a readiness health check, and drops Linux capabilities. `.env` files and local build artifacts are excluded from the Docker context, and `.env.*` is ignored by Git except for `.env.example`. Never copy development TLS settings into production.

## Staging deployment

1. Provision a persistent PostgreSQL 15 or newer database, a least-privilege application role able to create and migrate objects in its application schema, private network access, and TLS. Before starting the application, have a database administrator connect to **that database** and run `CREATE EXTENSION IF NOT EXISTS btree_gist;`. Do not grant the application role superuser privileges. Use a DNS name matching the server certificate in `DB_URL` (for example `jdbc:postgresql://db.example.internal:5432/workmark`) and set `DB_SSL_MODE=verify-full`. Provide the issuing CA to the PostgreSQL JDBC driver, for example as a read-only mount at `/home/appuser/.postgresql/root.crt`, readable by container UID 10001, or use the JDBC `sslrootcert` URL parameter. Do not disable certificate or hostname checks.
2. Set `SPRING_PROFILES_ACTIVE=staging` and supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ACCOUNT_TOKEN_OUTBOX_KEY`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and a provider-verified `SMTP_FROM` using the deployment platform's secret manager. Set non-secret routing settings such as `CORS_ALLOWED_ORIGINS` and `TRUSTED_PROXY_CIDRS` for the actual staging topology. Keep secrets out of Git, image layers, build arguments, logs, and shell history; restrict access and rotate them independently. Generate the outbox key with `openssl rand -base64 32` and a separate JWT secret with `openssl rand -base64 48` in a secure environment. Store the outputs directly in the secret manager. Preserve the outbox key for queued messages and corresponding database backups; changing it makes existing encrypted messages unreadable.
3. Configure an SMTP provider supporting authenticated STARTTLS (normally port 587), with `SMTP_AUTH=true`, `SMTP_STARTTLS=true`, and `SMTP_STARTTLS_REQUIRED=true`. The application requires STARTTLS and hostname verification; trust the provider's CA in the JVM truststore, and never configure trust-all. From the same network and with equivalent CA trust, check the actual endpoint without sending credentials:

   ```sh
   openssl s_client -starttls smtp -connect smtp.example.com:587 \
     -servername smtp.example.com -verify_hostname smtp.example.com \
     -verify_return_error -CAfile /path/to/trusted-ca.pem </dev/null
   ```

   Confirm verification succeeds, then use an authorized staging mailbox to exercise both account activation and password reset through the application. Check the outbox reaches `DELIVERED`, and test retries and expiry with an isolated unavailable SMTP endpoint. In an isolated test, verify a wrong hostname, untrusted certificate, and a server without STARTTLS fail closed. Do not print mailbox contents, SMTP credentials, activation tokens, or reset tokens in CI, tickets, or logs. These checks require the real staging server and are not covered by mocked CI tests.
4. Deploy the reviewed image by immutable digest, for example `ghcr.io/rajneesh-sys/workmark@sha256:<digest>`, through the platform's container deployment mechanism. Inject secrets at runtime rather than baking them into the image; use TLS at the ingress and restrict database and SMTP network access. The production Compose example builds locally and uses a local PostgreSQL service; for managed staging PostgreSQL deploy the app container separately with the staging settings above.
5. After migrations and startup, check `/actuator/health` and `/actuator/health/readiness` from an authorized network location and confirm HTTP 200 / `UP`. Readiness alone does not guarantee SMTP delivery or a working database query; check Flyway history and perform an authenticated database-backed API request as well. Monitor outbox delivery, retry, expiry and exhaustion metrics and alert on persistent failures.

### Backup and migration rehearsal

Before deploying, take an encrypted PostgreSQL backup and retain it with access controls and an independently tested recovery procedure. For example, with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGSSLMODE=verify-full`, and `PGSSLROOTCERT` set securely, use `pg_dump -Fc -f workmark.backup` and securely transfer the backup. Supply database authentication through a protected password file or secret manager, not command-line arguments. Restore with `pg_restore --exit-on-error --no-owner -d <isolated-empty-database> workmark.backup` to a separately provisioned database with `btree_gist`, and validate the data, Flyway history, and application access.

Rehearse the target image's migrations on a restored, sanitized production-like copy, including existing V11 data where applicable; check `flyway_schema_history` and representative tenant data. Plan a maintenance window, stop writers, take and verify a fresh backup, and define recovery owners and restore time objectives. Flyway forward migrations do not provide an automatic rollback: if rollback is required, restore the compatible database backup and previous image together, accounting for writes lost since the backup. Never change historical migration files or rehearse against the live target database.

### GitHub Actions and image publishing

CI checks the push or pull request diff, runs Java 21 tests with Docker-backed PostgreSQL (including PostgreSQL 15 fresh and populated V11 upgrades), builds the application and image, and scans resolved runtime dependencies with OWASP Dependency-Check. Test and scan reports are uploaded even on failure when available. The scan currently reports findings without a CVSS failure threshold; review the report and establish a vulnerability remediation policy. NVD availability or rate limiting can fail or delay a scan.

No manually created GitHub secret is required for basic CI. The optional `NVD_API_KEY` repository secret speeds vulnerability data updates; it is not supplied to forked pull requests. Optional GHCR publishing requires the repository variable `ENABLE_GHCR_PUBLISH=true`, Actions package-write permission, and the built-in `GITHUB_TOKEN` (no personal access token). Only successful pushes to `main` publish, tagged `sha-<full commit SHA>`. Release tags matching `v1.2.3` or `v1.2.3-rc.1` already pointing at that commit are also published: push the Git tag before pushing that commit to `main`. A tag push alone never publishes. Pull requests and other branches build without publishing. Package visibility and deployment pull authorization must be configured separately.

## API and initial Super Admin

- API prefix: `/api/v1`
- OpenAPI UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI document: `http://localhost:8080/v3/api-docs`
- Health: `http://localhost:8080/actuator/health`

Swagger and OpenAPI are enabled and public in the `dev` profile. They are disabled and non-public by default in production. To enable authenticated production access, set `SWAGGER_ENABLED=true` while leaving `SWAGGER_PUBLIC=false`; the endpoints then require a valid bearer token. Do not set `SWAGGER_PUBLIC=true` in production.

Super Admin bootstrap is disabled by default. To create the initial platform administrator, set these environment variables before starting the application:

```powershell
$env:SUPER_ADMIN_BOOTSTRAP_ENABLED = "true"
$env:SUPER_ADMIN_NAME = "Platform Owner"
$env:SUPER_ADMIN_EMAIL = "platform-owner@example.com"
$env:SUPER_ADMIN_PASSWORD = "use-a-private-password-of-at-least-12-characters"
.\gradlew.bat bootRun
```

Run bootstrap against the persistent target database, **not** a temporary GitHub Actions database. Inject these values securely into the target deployment for one startup. When enabled, all four properties are required; the password must be at least 12 characters and no more than 72 UTF-8 bytes. Startup creates one active `SUPER_ADMIN` with a null `company_id`, BCrypt-hashed password, and configured display name. A PostgreSQL transaction advisory lock serializes concurrent application startups, and database unique indexes/check constraints independently enforce one Super Admin, unique case-insensitive email, and the Super Admin tenant rule. Existing Super Admin accounts are not modified. After successful creation, set `SUPER_ADMIN_BOOTSTRAP_ENABLED=false`, remove `SUPER_ADMIN_PASSWORD` from the deployment environment and secret injection, and redeploy. Verify login with `POST /api/v1/auth/login`, then call `GET /api/v1/auth/me` with the returned bearer token and verify its role is `SUPER_ADMIN`. Handle the password and token securely and do not log them.

Login is `POST /api/v1/auth/login`. Send the returned token as `Authorization: Bearer <accessToken>`. `GET /api/v1/auth/me` returns the authenticated user. Logout is `POST /api/v1/auth/logout`; it invalidates all existing JWTs for that account.

## Company onboarding

Only a logged-in `SUPER_ADMIN` can register a company using `POST /api/v1/companies`. The request contains company details and the first admin's name/email; it does not accept a role or password. The company and inactive `COMPANY_ADMIN` account are committed in one transaction. A random, one-time password-setup token is generated; only its SHA-256 hash and expiration are stored. In `dev` and `test`, the token is returned for local use; in `prod` and `staging`, it is queued for encrypted SMTP delivery and omitted from the API response. The durable queue is claimed using PostgreSQL row locking and recovers pending messages after restarts. The token expires after `ACCOUNT_ACTIVATION_TTL` (default 24 hours).

The admin sets a password at `POST /api/v1/auth/activate`, then signs in through `/api/v1/auth/login`. Passwords must be at least 12 characters, fit within BCrypt's 72 UTF-8-byte limit, and not be common, repetitive, or sequential. Activation is one-time and cannot change the assigned role. In `dev` and `test`, the one-time token is returned for local testing; `prod` and `staging` queue it for SMTP and never return it in the API response. Password reset uses `POST /api/v1/auth/password-reset/request` and `/api/v1/auth/password-reset/confirm`; production reset requests have a generic response and use the same encrypted outbox delivery flow. There is no public company or Super Admin registration endpoint.

Company administrators and HR can create employee logins using `POST /api/v1/auth/employee-account`. A company administrator can create HR or manager logins at `POST /api/v1/auth/company-account`. `POST /api/v1/auth/password` lets a user change their password; all existing access tokens are invalidated and the user must sign in again.

## Modules

| Module | Main routes |
|---|---|
| Auth | `/auth/login`, `/auth/activate`, `/auth/logout`, `/auth/me` |
| Company | `POST /companies` (Super Admin only), `/companies/me`, `/companies/{id}/status` (Super Admin only; transitions are audited) |
| Employee | `/employees` |
| Department | `/departments` |
| Leave | `/leaves`, `/leaves/pending`, `/leaves/policies`, `/leaves/balances`, `/leaves/{id}/history`, `/leaves/{id}/approve`, `/leaves/{id}/reject`, `/leaves/{id}/cancel` |
| Attendance | `/attendance/check-in`, `/attendance/check-out`, `/attendance/daily`, `/attendance/employee`, `/attendance/{id}/corrections`, `/attendance/summary/monthly` |

Employee, department, attendance, leave request, balance ledger and leave history list APIs accept Spring pageable parameters (`page`, `size`, and `sort`); page size is capped at 100. Department deletion is rejected while employees reference it. Employee and department status values are `ACTIVE` and `INACTIVE`. Leave days are counted as inclusive calendar days; holiday/weekend calendars and half-day policies are intentionally not included. Paid leave approvals require an active leave policy and available ledger balance. HR and company administrators can manage policy and allocation; unpaid leave does not consume a balance. Approved future leave cancellation restores its balance and every leave status transition is audited. Carry-forward limits are supported, but non-zero carry-forward expiry is rejected until the ledger can correctly account for consumption across expiring allocation lots.

Attendance check-in uses the company's configured IANA timezone (UTC by default); update it through the company profile. Check-out can close an open check-in from the previous local day. HR and company administrators can correct attendance only with a reason; before/after values are retained in an immutable audit history. Attendance summary totals cover one employee and one calendar month.

## Tenant and authorization model

Clients cannot supply a tenant selector for data access. The authenticated principal's company ID is the sole source of tenant context, and every company-owned lookup includes that ID. A company-owned employee reference (manager, department, leave, attendance, or user account) is validated within that same company; database composite foreign keys reinforce those boundaries.

Roles:

- `SUPER_ADMIN`: platform-wide company lifecycle operations.
- `COMPANY_ADMIN`: company profile, staff, and approvals.
- `HR`: staff administration and approvals.
- `MANAGER`: employee, leave, and attendance access limited to direct reports; approval only for their direct reports.
- `EMPLOYEE`: self-service leave and attendance when the account is linked to an employee.

`SUPER_ADMIN` is not publicly self-registered; the initial platform account must be provisioned through a controlled operator process.

JWTs are signed with an environment-supplied HMAC secret, expire after a configurable interval, and contain no tenant authority claims. Every request reloads the account and checks its token version and company status. The logout implementation is deliberately stateful: it revokes all access tokens belonging to that account. Security responses include a restrictive Content-Security-Policy and a Permissions-Policy; inline scripts/styles are allowed only to preserve the bundled Swagger UI.

JWTs require `iss=workmark` and `aud=workmark-api` by default. Override only through `JWT_ISSUER` and `JWT_AUDIENCE` where all signing and verification deployments share the same values. Expiration must be positive and no more than 86400 seconds.

Failed logins are temporarily throttled by both source IP and normalized account identifier. Limits are configurable with `LOGIN_RATE_LIMIT_MAX_FAILURES`, `LOGIN_RATE_LIMIT_WINDOW`, `LOGIN_RATE_LIMIT_BASE_BLOCK`, `LOGIN_RATE_LIMIT_MAX_BLOCK`, and `LOGIN_RATE_LIMIT_MAX_TRACKED_KEYS`. Development and test use an in-memory limiter; production uses PostgreSQL-backed shared buckets with HMAC-hashed identifiers. Forwarded IP headers are ignored unless the immediate proxy is listed in `TRUSTED_PROXY_CIDRS`. Configure `CORS_ALLOWED_ORIGINS` as a comma-separated exact-origin allowlist; it is empty by default and wildcard origins are not supported.

Deactivating an employee deactivates every linked account and increments its token version in the same transaction. Reactivating the employee record does not reactivate its account; an authorized account-recovery/reactivation workflow must be completed separately.

## Database and configuration

- Schema migrations: `src/main/resources/db/migration/V1__initial_schema.sql` through `V14__password_reset_bucket_cleanup_index.sql`
- V8 leave overlap exclusion requires PostgreSQL's `btree_gist` extension in the same database used by `DB_URL`. Docker initialization installs it before application migrations on a new data volume. For an existing/local database, connect as a database administrator and run `CREATE EXTENSION IF NOT EXISTS btree_gist;`, for example:

  ```powershell
  psql -h localhost -p 5432 -U your-database-admin -d workmark -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"
  ```

  For an existing Docker volume, run:

  ```powershell
  docker compose exec postgres sh -lc 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"'
  ```

  Init scripts do not rerun for an existing volume; do not delete the volume to apply this change. Restart the application after the extension is installed. Keep the application role least-privileged rather than granting it superuser access.

For externally managed PostgreSQL, provision `btree_gist` with a database administrator before deploying V8 or V11. The application role must not be granted superuser privileges. From an administrator shell:

```powershell
psql "$env:DB_URL" -U "database-admin" -v ON_ERROR_STOP=1 -c "CREATE EXTENSION IF NOT EXISTS btree_gist;"
```

The repository also provides `scripts/provision-btree-gist.ps1`; it requires the database URL, administrator username, and secure administrator password, and intentionally does not grant any role-based privilege escalation.
- Configuration: `application.yml`, `application-dev.yml`, and `application-prod.yml`
- Required secrets and database properties are environment variables; see `.env.example`. `SUPER_ADMIN_BOOTSTRAP_ENABLED` defaults to `false`.
- Spring Boot Actuator exposes health and info publicly; metrics require a Super Admin token.

## Tests

Run unit and PostgreSQL/Testcontainers integration tests with:

```powershell
.\gradlew.bat test
```

Unit tests use JUnit 5 and Mockito. PostgreSQL Testcontainers cover fresh and populated V11-to-current migrations, bootstrap, company onboarding, account-token outbox persistence/retries/expiry/failure, rate limiting, and leave concurrency. They are skipped locally when Docker is unavailable; CI requires Docker and fails if an integration suite is skipped. SMTP sender tests use mocks and do not require credentials. Real database TLS, SMTP STARTTLS/certificate/hostname verification, and delivery require the staging checks above.
