# fintech-platform-backend

Backend for the Arthvritt invoice-discounting platform.
Java 21 · Spring Boot 3.5.x · PostgreSQL 16 · Flyway · Maven.

---

## Prerequisites

| Tool | Minimum version | Notes |
|------|----------------|-------|
| JDK 21 | 21 | Temurin recommended. Must be JDK 21 specifically — not 17, not 23. |
| Docker Desktop | 4.x | Provides both Docker Engine and the `docker compose` plugin. |
| Git | any | — |

> **No Maven install needed.** The repo ships a Maven wrapper (`./mvnw` / `mvnw.cmd`). Use it for
> every Maven command in this guide.

---

## Quick start (first-time setup)

```bash
# 1. Clone and enter the repo
git clone <repo-url>
cd fintech-platform-backend

# 2. Start the database (detached — runs in the background)
docker compose up -d

# 3. Wait for Postgres to be healthy (≈5 seconds)
docker compose ps          # STATUS should show "healthy"

# 4. Start the application
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080**.
Actuator health endpoint: **http://localhost:8080/actuator/health**

---

## Day-to-day commands

```bash
# Start / stop the database
docker compose up -d          # start (keeps data between restarts)
docker compose down           # stop containers (data volume persists)
docker compose down -v        # stop AND wipe data — full clean slate

# Run the application
./mvnw spring-boot:run

# Run tests
# Testcontainers manages its own Postgres automatically — no manual DB step needed.
./mvnw test

# Full build (compile + test + package)
./mvnw clean verify

# Build a runnable JAR (skip tests)
./mvnw -DskipTests package    # output: target/platform-backend-*.jar

# Check Flyway migration status (once migrations exist)
./mvnw flyway:info
```

---

## Local database

`docker-compose.yml` starts a **postgres:16-alpine** container with these fixed dev credentials:

| Setting  | Value      |
|----------|------------|
| Host     | localhost  |
| Port     | 5432       |
| Database | platform   |
| Username | platform   |
| Password | platform   |

These match `application.properties` exactly — no extra configuration required for local dev.

Data lives in a named Docker volume (`postgres_data`) and survives `docker compose down`.
Use `docker compose down -v` only when you want a full reset.

### Connecting with a DB client

Use the credentials above in any SQL client (DBeaver, TablePlus, IntelliJ Database tool, psql):

```bash
psql -h localhost -p 5432 -U platform -d platform
```

---

## Cloud / CI deployment

The application reads three environment variables before falling back to the dev defaults:

| Environment variable         | Overrides                        |
|------------------------------|----------------------------------|
| `SPRING_DATASOURCE_URL`      | `jdbc:postgresql://localhost:5432/platform` |
| `SPRING_DATASOURCE_USERNAME` | `platform`                       |
| `SPRING_DATASOURCE_PASSWORD` | `platform`                       |

Set these in your cloud environment (AWS ECS task definition, Kubernetes secret, CI secret, etc.)
and no file changes are required — the same JAR works everywhere.

```bash
# Example: run the JAR locally against a remote database
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-host:5432/platform \
SPRING_DATASOURCE_USERNAME=app_user \
SPRING_DATASOURCE_PASSWORD=<secret> \
java -jar target/platform-backend-*.jar
```

---

## IntelliJ IDEA setup

1. *File → Open* → select this folder (or its `pom.xml`).
2. *File → Project Structure → Project → SDK* → choose **temurin-21**.
3. Wait for Maven import to finish.
4. Start the database: `docker compose up -d` in a terminal.
5. Run `PlatformBackendApplication` from the IDE.

---

## Architecture notes

- **Schema source of truth:** `docs/sql/` — four SQL files that define the full schema including
  constraints, enums, triggers, and maker-checker columns.
- **Flyway owns migrations** under `src/main/resources/db/migration/`. Hibernate is
  `ddl-auto=validate` — it never generates or alters the schema.
- **Tests use Testcontainers** — `postgres:16-alpine` is spun up automatically per test run.
  Docker must be running, but you never manage a test database manually.
- **Money is stored in paise (BIGINT), rates in basis points.** No floats for financial values.
- Build plan and bounded-context spec: `docs/spec/Spec_Driven_Build_Plan.md`.
