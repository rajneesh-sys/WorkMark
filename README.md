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

The host-run development profile supports `DB_SSL_MODE=disable` for local PostgreSQL. Production defaults to `verify-full`. Never copy development TLS settings into production.

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

Only a logged-in `SUPER_ADMIN` can register a company using `POST /api/v1/companies`. The request contains company details and the first admin's name/email; it does not accept a role or password. The company and inactive `COMPANY_ADMIN` account are committed in one transaction. The response includes a random, one-time password-setup token; only its SHA-256 hash and expiration are stored. Deliver the raw token to the company administrator through a secure channel. It expires after `ACCOUNT_ACTIVATION_TTL` (default 24 hours).

The admin sets a password at `POST /api/v1/auth/activate`, then signs in through `/api/v1/auth/login`. Activation is one-time and cannot change the assigned role. There is no public company or Super Admin registration endpoint.

Company administrators and HR can create employee logins using `POST /api/v1/auth/employee-account`. A company administrator can create HR or manager logins at `POST /api/v1/auth/company-account`. `POST /api/v1/auth/password` lets a user change their password; all existing access tokens are invalidated and the user must sign in again.

## Modules

| Module | Main routes |
|---|---|
| Auth | `/auth/login`, `/auth/activate`, `/auth/logout`, `/auth/me` |
| Company | `POST /companies` (Super Admin only), `/companies/me`, `/companies/{id}/status` |
| Employee | `/employees` |
| Department | `/departments` |
| Leave | `/leaves`, `/leaves/pending`, `/leaves/{id}/approve`, `/leaves/{id}/reject`, `/leaves/{id}/cancel` |
| Attendance | `/attendance/check-in`, `/attendance/check-out`, `/attendance/daily`, `/attendance/summary/monthly` |

Employee list APIs accept Spring pageable parameters (`page`, `size`, and `sort`); page size is capped at 100. Department deletion is rejected while employees reference it. Employee and department status values are `ACTIVE` and `INACTIVE`. Leave days are counted as inclusive calendar days; holiday/weekend calendars and half-day policies are intentionally not included. Attendance dates and working minutes use UTC until tenant-specific time zones and shift policies are introduced.

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

Failed logins are temporarily throttled by both servlet-observed source IP and normalized account identifier. Limits are configurable with `LOGIN_RATE_LIMIT_MAX_FAILURES`, `LOGIN_RATE_LIMIT_WINDOW`, `LOGIN_RATE_LIMIT_BASE_BLOCK`, `LOGIN_RATE_LIMIT_MAX_BLOCK`, and `LOGIN_RATE_LIMIT_MAX_TRACKED_KEYS`. Keys are hashed in memory; forwarded IP headers are not trusted. The in-process limiter protects each application instance; production deployments with multiple instances should also enforce a shared rate limit at a trusted gateway/WAF.

Deactivating an employee deactivates every linked account and increments its token version in the same transaction. Reactivating the employee record does not reactivate its account; an authorized account-recovery/reactivation workflow must be completed separately.

## Database and configuration

- Schema migration: `src/main/resources/db/migration/V1__initial_schema.sql`
- Bootstrap/onboarding constraints: `src/main/resources/db/migration/V2__super_admin_bootstrap_and_account_activation.sql`
- Configuration: `application.yml`, `application-dev.yml`, and `application-prod.yml`
- Required secrets and database properties are environment variables; see `.env.example`. `SUPER_ADMIN_BOOTSTRAP_ENABLED` defaults to `false`.
- Spring Boot Actuator exposes health and info only.

## Tests

Run unit and PostgreSQL/Testcontainers integration tests with:

```powershell
.\gradlew.bat test
```

Unit tests use JUnit 5 and Mockito. The Testcontainers integration suite verifies startup bootstrap, concurrent idempotence, Super Admin-only company registration, and one-time account activation; it is automatically skipped when Docker is unavailable.
