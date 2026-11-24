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

The host-run development profile supports `DB_SSL_MODE=disable` for local PostgreSQL. Production defaults to `verify-full`. Production SMTP is configured with `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM`. The Docker image runs as a non-root user; Compose makes the app filesystem read-only, enables a readiness health check, and drops Linux capabilities. `.env` files and local build artifacts are excluded from the Docker context, and `.env.*` is ignored by Git except for `.env.example`. Never copy development TLS settings into production.

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

When enabled, all four properties are required; the password must be at least 12 characters and no more than 72 UTF-8 bytes. Startup creates one active `SUPER_ADMIN` with a null `company_id`, BCrypt-hashed password, and configured display name. A PostgreSQL transaction advisory lock serializes concurrent application startups, and database unique indexes/check constraints independently enforce one Super Admin, unique case-insensitive email, and the Super Admin tenant rule. Existing Super Admin accounts are not modified. After bootstrapping, disable the setting and remove the bootstrap password from the runtime environment.

Login is `POST /api/v1/auth/login`. Send the returned token as `Authorization: Bearer <accessToken>`. `GET /api/v1/auth/me` returns the authenticated user. Logout is `POST /api/v1/auth/logout`; it invalidates all existing JWTs for that account.

## Company onboarding

Only a logged-in `SUPER_ADMIN` can register a company using `POST /api/v1/companies`. The request contains company details and the first admin's name/email; it does not accept a role or password. The company and inactive `COMPANY_ADMIN` account are committed in one transaction. A random, one-time password-setup token is generated; only its SHA-256 hash and expiration are stored. In `dev` and `test`, the token is returned for local use; in `prod`, it is sent to the company administrator using SMTP and omitted from the API response. It expires after `ACCOUNT_ACTIVATION_TTL` (default 24 hours).

The admin sets a password at `POST /api/v1/auth/activate`, then signs in through `/api/v1/auth/login`. Activation is one-time and cannot change the assigned role. In `dev` and `test`, the one-time token is returned for local testing; `prod` sends it through SMTP and never returns it in the API response. Password reset uses `POST /api/v1/auth/password-reset/request` and `/api/v1/auth/password-reset/confirm`; production reset requests have a generic response and use the same one-time SMTP delivery flow. There is no public company or Super Admin registration endpoint.

Company administrators and HR can create employee logins using `POST /api/v1/auth/employee-account`. A company administrator can create HR or manager logins at `POST /api/v1/auth/company-account`. `POST /api/v1/auth/password` lets a user change their password; all existing access tokens are invalidated and the user must sign in again.

## Modules

| Module | Main routes |
|---|---|
| Auth | `/auth/login`, `/auth/activate`, `/auth/logout`, `/auth/me` |
| Company | `POST /companies` (Super Admin only), `/companies/me`, `/companies/{id}/status` |
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

JWTs are signed with an environment-supplied HMAC secret, expire after a configurable interval, and contain no tenant authority claims. Every request reloads the account and checks its token version and company status. The logout implementation is deliberately stateful: it revokes all access tokens belonging to that account.

JWTs require `iss=workmark` and `aud=workmark-api` by default. Override only through `JWT_ISSUER` and `JWT_AUDIENCE` where all signing and verification deployments share the same values. Expiration must be positive and no more than 86400 seconds.

Failed logins are temporarily throttled by both source IP and normalized account identifier. Limits are configurable with `LOGIN_RATE_LIMIT_MAX_FAILURES`, `LOGIN_RATE_LIMIT_WINDOW`, `LOGIN_RATE_LIMIT_BASE_BLOCK`, `LOGIN_RATE_LIMIT_MAX_BLOCK`, and `LOGIN_RATE_LIMIT_MAX_TRACKED_KEYS`. Development and test use an in-memory limiter; production uses PostgreSQL-backed shared buckets with HMAC-hashed identifiers. Forwarded IP headers are ignored unless the immediate proxy is listed in `TRUSTED_PROXY_CIDRS`. Configure `CORS_ALLOWED_ORIGINS` as a comma-separated exact-origin allowlist; it is empty by default and wildcard origins are not supported.

Deactivating an employee deactivates every linked account and increments its token version in the same transaction. Reactivating the employee record does not reactivate its account; an authorized account-recovery/reactivation workflow must be completed separately.

## Database and configuration

- Schema migrations: `src/main/resources/db/migration/V1__initial_schema.sql` through `V9__tenant_query_indexes.sql`
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
- Spring Boot Actuator exposes health and info only.

## Tests

Run unit and PostgreSQL/Testcontainers integration tests with:

```powershell
.\gradlew.bat test
```

Unit tests use JUnit 5 and Mockito. The Testcontainers integration suite verifies startup bootstrap, concurrent idempotence, Super Admin-only company registration, and one-time account activation; it is automatically skipped when Docker is unavailable.
