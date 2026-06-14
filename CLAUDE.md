# Fintech Platform — Backend · CLAUDE.md

## What this is
The **backend** for a regulated invoice-discounting platform (Phase 1 = full production:
real counterparties, real money, real audit/compliance obligations). Java + Spring Boot
monolith over PostgreSQL, built **spec-first** with GitHub Spec Kit.

The frontend is a separate repo (`../fintech-patform-mock`): 15 React screens that are the
**frontend contract** — each backend module must satisfy the screen(s) that consume it.

> **The authoritative plan is `docs/spec/Spec_Driven_Build_Plan.md`.** Read it before any work.
> Section 0 (repo housekeeping) is done. Phase 0 (bootstrap) is the next step.

---

## Stack & commands
```
Java 21 · Spring Boot 3.5.15 · Spring Data JPA + Hibernate · Flyway · PostgreSQL 16
Spring Security · Bean Validation · Actuator · Lombok · Maven (wrapper)
Tests: JUnit 5 · Testcontainers (postgres:16-alpine) · spring-security-test
```
```bash
./mvnw spring-boot:run     # run app (needs Postgres at localhost:5432, db/user/pw = platform)
./mvnw test                # unit + integration tests (Testcontainers spins up Postgres via Docker)
./mvnw clean verify        # full build + tests
./mvnw flyway:info         # migration status (once migrations exist)
```
Package root: `com.arthvritt.platform` · artifact `platform-backend`.

---

## Directory layout
```
src/main/java/com/arthvritt/platform/   # application code (bounded contexts as packages)
src/main/resources/
  application.properties                 # datasource + JPA/Flyway config
  db/migration/                          # Flyway migrations V1__*.sql … (created in M0)
src/test/java/com/arthvritt/platform/    # tests; TestcontainersConfiguration spins up Postgres

docs/
  DECISION_LOG.md                        # backend build decisions (DL-BE-xxx) — keep this alive
  spec/                                  # Spec Kit corpus (authoritative inputs — treat as read-only)
    Spec_Driven_Build_Plan.md            # THE plan: gates, module register, build order
    02_Product_Specification.docx, 03_Founder_Review.docx
    04_Gap_Log.md (G…), 05_A1_Architectural_Constraints.md (C1–C28),
    06_A2_Integration_Contracts.md, 07_B1_Bounded_Contexts.md, 08_B2_Event_Model.md,
    09_B3_Aggregates.md, 10_B4_API_Conventions.md
    Bounded_Contexts_Reference.md (19 BCs), domain_entity_responsibility.md (data dictionary)
    STEP2/STEP4/STEP5 blueprints (mirrored from mock — backend copy is rules-canonical)
  sql/                                   # canonical schema bundle → source for Flyway migrations
    01_core.sql, 02_counterparty_platform.sql, 03_auth.sql, 04_generic_acl.sql
    SQL_FILES_MANIFEST.md
```
*Not yet present (created during Phase 0 / M0):* `db/migration/` migrations, ArchUnit harness,
Dockerfile / compose. Don't claim these exist until they do.

---

## Non-negotiables (the Constitution — never violate)
Encoded from the plan's Section A. Every state-changing command is **all five**:
1. **Maker-checker gated** — proposer ≠ approver.
2. **MFA-fresh** — carries a valid `mfa_assertion_id`.
3. **SoD-checked** — segregation-of-duties policy enforced at the command boundary.
4. **Idempotent on `command_id`** — replaying a command is a no-op.
5. **Audit-logged** — emits an immutable, cryptographically-chained `sys_audit_event` envelope.

Plus:
- **Money is paise (`BIGINT`). Rates are bps. Time is `TIMESTAMPTZ`. Never floats for money.**
- **One module in flight at a time.** Finish to DoD (plan Section F) before starting the next.

---

## Persistence rules (load-bearing)
- **The SQL bundle (`docs/sql/`) is the schema source of truth** — it encodes CHECK constraints,
  enums, triggers, maker-checker columns, `aggregate_version`. The DB is the last line of defence.
- **Flyway owns the schema.** Port the 4 SQL files into versioned migrations under
  `src/main/resources/db/migration`. **Hibernate `ddl-auto=validate` only — never `create`/`update`.**
  (`open-in-view=false`, `flyway.baseline-on-migrate=true` are already set.)
- **JPA maps onto the existing schema.** For invariant-heavy reads (funding equality,
  reconciliation), reach for native SQL / jOOQ rather than fragile JPQL.
- **Bounded contexts are enforced in-code** (target: ArchUnit) — no cross-BC table joins;
  contexts coordinate via in-process events + identity references.
- **Integrations are stubbed behind ACL ports** (BC15/17/18/19): real interfaces, fake in-process
  adapters. Swap to real sandbox → production credentials only at the Production gate.

---

## How we work (spec-driven)
Follow the per-module loop in `docs/spec/Spec_Driven_Build_Plan.md` Section D:
`/specify → /clarify → DoR gate (E) → /plan → /tasks → /implement → DoD gate (F)`.
- **No coding before the DoR gate is green.** The spec/clarify cost is the cheapest insurance.
- Write the invariant test (proving both the app rule *and* the DB constraint fire) **before**
  implementing the rule.
- Run `/code-review` on every diff; fix findings before DoD.
- Build order: Wave 0 (M0–M5) → Walking Skeleton → widen Wave 1 (M6–M13) → Wave 2 (M14–M17).

---

## Decision log (`DL-BE`)
Append a `docs/DECISION_LOG.md` entry for every **non-obvious build decision or bug** (why jOOQ
here, why this aggregate boundary, why this migration split, a tricky reconciliation fix). Format:
what changed, why, what to watch for. This is a **DoD requirement** for each module.

Three separate decision streams — keep them distinct, never merge:
- **`DL-0xx`** — domain/product decisions, defined **inline within `docs/spec/`**. Frozen spec
  inputs you cite from; **do not append** implementation decisions to them.
- **`DL-BE-xxx`** — backend build decisions → `docs/DECISION_LOG.md` (this repo). Living.
- **`DL-MOCK-xxx`** — frontend/mock decisions, in the mock repo. Not ours.

When a spec references `DL-0xx`, `C1–C28`, or `G…`, the definition lives in the `docs/spec/` corpus.

---

## What NOT to do
- Don't let Hibernate generate or alter the schema. Flyway + the SQL bundle own it.
- Don't edit the `docs/spec/` corpus as if it were scratch space — it's authoritative input.
  (Mirrored blueprints: if a rule changes, edit here, then re-copy to the mock.)
- Don't start a second module before the current one hits DoD.
- Don't use floats for money, or skip any of the five non-negotiables on a state change.
- Don't add a new external integration without an ACL port + stub adapter.
