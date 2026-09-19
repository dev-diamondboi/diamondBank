# Diamond Bank

A full-stack banking simulator for a developer portfolio: **Java 21, Spring Boot, Spring Security, PostgreSQL, Flyway, HTML, CSS and JavaScript**. All money and cards are fictional.

## Run on this Windows machine

In PowerShell 7, from the project folder:

```powershell
./scripts/start-local.ps1
```

Open **http://localhost:8080**, select **Create a demo account**, and choose a name, email and password. Each user receives independent checking/savings accounts, demo opening funds and savings goals. No real account is opened and no email is sent.

The script uses the installed Java 21 and PostgreSQL 17, initializes a separate database cluster under `.runtime/postgres`, and starts it on **127.0.0.1:55432**. It does not modify existing databases. It creates `diamond_bank` for the app and `diamond_bank_test` for tests. Credentials are generated in ignored `.runtime/db-password`; do not commit that directory.

Maven is available in `.tools`. On a fresh checkout, run `./scripts/setup-maven.ps1` first (downloads Maven from Maven Central and verifies SHA-512). Override `-JdkPath` and `-PostgresBin` if needed. Java 11 is not sufficient.

```powershell
./scripts/start-local.ps1 -Test
```

Stop Java with Ctrl+C. Stop the project database without deleting data:

```powershell
& 'C:/Program Files/PostgreSQL/17/bin/pg_ctl.exe' -D "$PWD/.runtime/postgres" -m fast stop
```

## Docker alternative

With Docker Desktop running, create an ignored `.env` file containing `DATABASE_PASSWORD=` followed by a strong local password:

```sh
docker compose up --build
```

Open http://localhost:8080. PostgreSQL is private to the Compose network and persists in the `diamond-data` volume. `docker compose down` preserves data. Docker builds skip tests because tests require a running PostgreSQL test database. This alternative is provided but has not been run on this machine.

## Standard Java setup

Create a PostgreSQL database and set `DATABASE_URL` (JDBC URL), `DATABASE_USER` and `DATABASE_PASSWORD`. With Java 21 and Maven, run `mvn spring-boot:run`. Set `TEST_DATABASE_URL` to a **separate test database** for `mvn test`. Flyway creates the schema. Spring Boot serves the frontend and API on the same origin; no Node server is needed.

## Features

- Registration, BCrypt password hashing, session sign-in and sign-out
- Checking and savings balances stored in PostgreSQL
- Atomic internal transfers and simulated external payments
- Searchable transactions, account filters and CSV export
- Savings goals backed by available savings
- Persistent simulated card controls, profile name and notification preferences
- Per-user demo reset, retaining the profile and sign-in identity
- Responsive dashboard and accessible forms

Login email is read-only after registration. Card freezing simulates physical-card status; it does not disable account transfers or simulated bank payments.

## Architecture

```text
Browser (HTML / CSS / JavaScript)
       | same-origin JSON + session cookie + CSRF token
Spring Security -> REST controller -> transactional bank service
                                            |
                               JdbcTemplate / PostgreSQL
                               Flyway schema migrations
```

The browser sends commands, never authoritative balances. Java validates decimal amounts with `BigDecimal`, converts to integer cents, and derives ownership from the authenticated session. Every customer mutation acquires a PostgreSQL row lock before checking balances or allocated savings. Balance changes, ledger entries and an idempotency record commit together. Internal transfers produce matching debit and credit entries. Explicit opening-balance entries enable reconciliation. External payments record a debit; this is not a complete general ledger with settlement accounts.

Mutating calls require CSRF tokens. The session cookie is HTTP-only and SameSite Strict. Each action includes an `Idempotency-Key` UUID: replaying the same command does not spend twice, and reusing a key with different content is rejected. The UI retains the key when a network response is lost. Reloading loses that in-memory key; review history before resubmitting after a reload.

## API

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/csrf` | Token and header name |
| POST | `/api/register` | JSON: name, email, password |
| POST | `/api/login` | Form-encoded username (email), password |
| POST | `/api/logout` | End session |
| GET | `/api/state` | Current user's profile, accounts, goals, transactions |
| POST | `/api/actions` | JSON: type, optional id, fields; Idempotency-Key header |

Actions: `transfer`, `payment`, `goal`, `fund`, `freeze`, `notifications`, `profile`, `reset`.

## Verification

PostgreSQL integration tests cover authentication, CSRF, password validation/hashing, duplicate registration, ledger reconciliation, idempotent retries, invalid amounts, overdrafts, allocated savings, concurrent withdrawals, customer isolation, goals, card controls, profile changes and reset.

## Portfolio scope

This is a real Java/database architecture with simulated financial operations, not a production bank. It does not include real banking integrations, email verification, password recovery, MFA, login rate limiting, payment settlement, immutable regulatory audit retention or compliance certification. Reset deliberately replaces demo activity. Sessions expire after 30 minutes and do not survive server restarts. Public hosting would require appropriate additional controls, HTTPS and `COOKIE_SECURE=true`.

Python is intentionally not in the core transaction path. A later analytics or fraud-simulation service can add Python without duplicating financial logic.

The original localStorage demo is no longer used or imported. Google Fonts are optional and have system-font fallbacks.
