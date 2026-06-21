# M1b · Kernel — UUIDv7 IDs & aggregate-version locking

> **Lean module spec** — light tier (see `docs/spec/Spec_Driven_Build_Plan.md` §D).
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 (Shared Kernel & Event Backbone) |
| **Slice** | M1b — UUIDv7 ID generation + `aggregate_version` optimistic-locking base |
| **Tier** | Light |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-013) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-20 |

> ⚠️ **Idempotency is deferred, NOT cancelled.** The `command_id` idempotency store was originally
> in this slice; it has no consumer until the first state-changing command exists, so it moves to
> that module (see §1 *Does NOT own*). It remains **non-negotiable #4** — mandatory before any
> money-moving command ships. Deferring it ≠ dropping it.

## 1. Scope
**Owns:**
- **ID generation** — a UUIDv7 generator (`Ids.newId()`) for `event_id` / `command_id` /
  `correlation_id` / `causation_id`. Time-ordered so the audit/event chain has a lexicographic
  tie-breaker (B2 §2.1). *(Generation lives here; `command_id` dedup does not — see below.)*
- **`aggregate_version` optimistic-locking pattern** — a `@MappedSuperclass` base
  (`VersionedAggregate`) mapping the `aggregate_version INT` column to JPA `@Version`, so a
  stale-version write fails loudly instead of being a silent lost update (P5).

**Does NOT own (deferred):**
- **`command_id` idempotency store — DEFERRED to the first state-changing command module (or M1c
  if it lands first). STILL REQUIRED (non-negotiable #4, G18, P4).** Why deferred: there is no
  command to wrap yet, and the dedup/transaction-boundary design is far safer written against a
  real handler than in the abstract. The contract is already fixed by the existing `sys_command_log`
  table (PK `(actor_id, command_id)`, `resulting_event_id`), so deferring carries no schema risk.
  **Guardrail: no money-moving command may ship without it.**
- In-process **event bus** + X13 outbox → built at the **Walking Skeleton**; `AuditEventEnvelope` type
  → built in **M2**. The monolithic "M1c" is dissolved — see `M1c-event-backbone.md` + [[DL-BE-014]].
- The **audit hash-chain** / `sys_audit_event` writer → **M2 (BC14)**.

## 2. Upstream dependencies
- **M1a** (Money/Bps, error model) — Done.
- **M0 schema** — Done. M1b maps onto the `aggregate_version` columns already present on every
  aggregate table.

## 3. Invariants & rules
- **INV-1 — `aggregate_version` is monotonic, starts at 1; concurrent stale writes fail.** A second
  writer holding an out-of-date version gets an optimistic-lock error, never a lost update.
  _(ref: P5; schema `aggregate_version INT NOT NULL DEFAULT 1`)_
- **INV-2 — IDs are UUIDv7** (time-ordered), so `event_id`/`command_id` sort by creation time.
  _(ref: B2 §2.1)_

## 4. API / type surface
- **Types:** `Ids.newId()` → UUIDv7 `UUID` (via `com.fasterxml.uuid` `Generators.timeBasedEpochGenerator()`,
  held as a singleton).
- **Base class:** `@MappedSuperclass VersionedAggregate { @Version int aggregateVersion; }`.
- **Commands / Queries:** none — this is infrastructure.

## 5. Five non-negotiables — applicability
This slice changes no state and builds no command substrate. **All five are N/A here.** #4
(idempotency) first binds when the deferred `command_id` store is built with the first command
module; the other four bind there and with M2 (audit).

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | no commands here |
| 2 | MFA-fresh | no | — |
| 3 | SoD-checked | no | — |
| 4 | Idempotent on `command_id` | no (deferred) | store built with the first command module |
| 5 | Audit-logged envelope | no | audit sink is M2 |

## 6. Events
- **Publishes / Subscribes:** none — the event bus is M1c.

## 7. Test scenarios (write these first)
- [ ] Optimistic lock (integration, Testcontainers): two updates to one aggregate row, the second
      with a stale version → `OptimisticLockException`; `aggregate_version` increments by 1 (INV-1).
- [ ] `Ids.newId()` twice in order → second sorts after first (UUIDv7 time order); version nibble = 7 (INV-2).
- [ ] `Ids.newId()` returns distinct values across a tight loop (no collisions).

## 8. Definition of Done (light tier)
- [ ] §7 tests green (invariant tests prove the rule fires).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-013` entry (UUIDv7 source, version-locking base, **and the idempotency-deferral decision
      with its #4 guardrail** so the deferral is on the record).
- [ ] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Idempotency store — RESOLVED: deferred out of M1b (option 1).** No command consumer yet;
   building it against a real handler avoids designing the dedup/transaction boundary blind. **Still
   mandatory (#4)** — lands with the first state-changing command module; `sys_command_log` already
   holds the contract. Tracked here + in DL-BE-013 so it is not forgotten.
2. **Event bus split — RESOLVED: M1c** (built with M2).
3. **UUIDv7 source — RESOLVED: `com.fasterxml.uuid:java-uuid-generator`.** IDs are minted in app
   code, so a programmatic generator is required; Hibernate's `@UuidGenerator` only covers entity-id
   assignment. Use `Generators.timeBasedEpochGenerator()` (UUIDv7) behind `Ids`.
4. **Version helper — RESOLVED: ship now.** `VersionedAggregate` + one integration test using a
   minimal entity mapped to an existing aggregate table, so M6+ inherits a proven base.
