# M2 · Audit Log (BC14) — immutable hash-chained event ledger

> **Lean module spec** — *foundation-critical*: light process ceremony, but the chain-integrity
> invariants get **heavy-tier test rigor**. See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M2 — Audit Log (BC14) |
| **Tier** | Foundation-critical (light ceremony · heavy invariant rigor) |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-015) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> This module *is* non-negotiable #5. It is the platform's tamper-evident system-of-record and the
> universal sink every other module appends to. In Wave 0 it is reached by a **direct synchronous
> `AuditLog.append()`** in the caller's transaction — no event bus yet ([[DL-BE-014]]).

## 1. Scope
**Owns:**
- **`AuditEventEnvelope`** — the immutable Java record for the B2 §2 wire format (all fields below).
- **`AuditLog.append(envelope)`** — the **single, serialized write path** to `sys_audit_event`. It
  stamps `recorded_at`, derives `chain_shard`, resolves `previous_envelope_hash` (prior row in the
  shard), computes `envelope_hash`, and inserts. **No other code may insert into `sys_audit_event`.**
- **Canonical hashing** — **RFC 8785 (JCS)** canonical JSON of the envelope (minus `envelope_hash`) →
  SHA-256, tagged with `hash_encoding_version = 1`. Tamper-evidence (G7) depends on this being frozen.
- **`AuditChainVerifier`** — walks a `chain_shard` in arrival order and proves the chain is intact
  (each `previous_envelope_hash` == prior `envelope_hash`; each `envelope_hash` recomputes; first row
  in a shard has NULL `previous_envelope_hash`).
- **`V5` migration** — `CREATE UNIQUE INDEX uidx_audit_chain_link ON sys_audit_event (chain_shard,
  previous_envelope_hash) NULLS NOT DISTINCT` — makes chain-forking *declaratively impossible* (the
  serialization mechanism; see INV-4).

**Does NOT own (deferred / elsewhere):**
- In-process **event bus** + **X13 outbox** → Walking Skeleton ([[DL-BE-014]]). M2 ships a direct
  append API; the audit log becomes "the first subscriber" when the bus lands.
- **Domain event *types*** (`listing.Listing.GoneLive`, etc.) → each feature module.
- **Enforcement** of MFA-freshness / maker-checker / SoD → the command modules. M2 only *records*
  the assertions carried in the envelope's `actor`.

## 2. Upstream dependencies
- **M1a** (error model; `Money` for money fields in payloads) — Done.
- **M1b** (`Ids.newId()` UUIDv7 for `event_id`) — Done.
- **M0 schema** — Done. `sys_audit_event` exists with: `envelope_hash BYTEA NOT NULL` (UNIQUE, len 32),
  `previous_envelope_hash` (nullable, len 32), `chain_shard TEXT NOT NULL`, `is_state_transition`,
  `command_id` (nullable), `corrects` (self-FK), the actor-keys / `occurred_at<=recorded_at` /
  transition-snapshot CHECKs, and the **`prevent_audit_modification` trigger** (immutability —
  retained, [[DL-BE-002]]/[[DL-BE-010]]).

## 3. Invariants & rules
- **INV-1 — Append-only / immutable.** No `UPDATE`/`DELETE` on `sys_audit_event` succeeds; the only
  app write path is `AuditLog.append()`. _(ref: AE.1, C1; DB trigger `prevent_audit_modification`)_
- **INV-2 — Per-shard hash chain.** Within a `chain_shard`, each row's `previous_envelope_hash`
  equals the immediately prior row's `envelope_hash`; the first row in a shard has NULL. _(ref: AE.2,
  G7, G25, [[DL-BE-010]])_
- **INV-3 — Tamper-evidence.** `envelope_hash` = SHA-256 over the **canonical encoding of the envelope
  minus `envelope_hash`**; recomputation detects any altered field. _(ref: AE.2, B2 §2.1)_
- **INV-4 — Linear under concurrency.** Concurrent appends to the *same* shard must not fork the chain
  (no two rows share a `previous_envelope_hash`, incl. the NULL genesis). Enforced **declaratively** by
  `uidx_audit_chain_link (chain_shard, previous_envelope_hash) NULLS NOT DISTINCT`; `append()` does an
  **optimistic insert-retry** on unique violation (re-read head → recompute → re-insert). No procedural
  lock. _(ref: AE.2; declarative-first, [[DL-BE-002]])_
- **INV-5 — Time & sequence sanity.** `recorded_at >= occurred_at`; `aggregate_version >= 1`
  (app-stamped monotonic per aggregate; distinct from the JPA `@Version` lock — [[DL-BE-013]]).
  _(ref: AE; DB CHECKs)_
- **INV-6 — State-transition snapshots.** `is_state_transition` ⇒ `before_state` and `after_state`
  both present. _(ref: AE; DB CHECK `sys_audit_event_transition_snapshot_chk`)_
- **INV-7 — Actor completeness.** `actor` JSONB carries `actor_type`, `actor_id`, `session_id`
  (the latter may be JSON null for non-user actors). _(ref: AE.5; DB CHECK)_

## 4. API / type surface
- **`AuditEventEnvelope`** (record) — B2 §2.1 fields: `eventId`, `eventType`, `eventVersion`,
  `schemaUri?`, `occurredAt`, `actor` (typed sub-record: `actorType`, `actorId`, `sessionId?`,
  `mfaAssertionId?`, `agencyConsentId?`), `context`, `aggregateType`, `aggregateId`,
  `aggregateVersion`, `correlationId`, `causationId?`, `commandId?`, `externalRef?`, `payload`,
  `beforeState?`, `afterState?`, `corrects?`. **`recordedAt`, `chainShard`, `previousEnvelopeHash`,
  `envelopeHash` are assigned by `append()`, not by the caller.**
- **`AuditLog.append(AuditEventEnvelope) → AppendedEvent`** — the only write path; runs in the
  caller's transaction (publish-before-success when called from a command).
- **`AuditChainVerifier.verify(chainShard) → VerificationResult`** (intact | first-break location).
- **Commands / Queries:** no HTTP commands. A read API (auditor queries) is **M17**, not here.

## 5. Five non-negotiables — applicability
M2 is the audit sink, not a command. **#5 is the module itself**; the rest are N/A (M2 records, it
does not enforce them).

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | recorded in envelope; enforced by command modules |
| 2 | MFA-fresh | no | `actor.mfaAssertionId` recorded; enforced by commands (C7) |
| 3 | SoD-checked | no | — |
| 4 | Idempotent on `command_id` | no | `command_id` recorded; dedup is the deferred store [[DL-BE-013]] |
| 5 | Audit-logged envelope | **YES — this is it** | `AuditLog.append()` + the hash chain |

## 6. Events
- **Publishes:** none. **Subscribes:** conceptually all events (P3) — but in Wave 0 via **direct
  `append()` calls**, not a bus ([[DL-BE-014]]).

## 7. Test scenarios (write these first) — integration, against Testcontainers
- [ ] **Append happy path:** one envelope → row persisted; `recorded_at`, `chain_shard`,
      `envelope_hash` populated; first row in a fresh shard has NULL `previous_envelope_hash`.
- [ ] **Chain links (INV-2):** N appends to one shard → each `previous_envelope_hash` == prior
      `envelope_hash`; `AuditChainVerifier.verify` returns intact.
- [ ] **Immutability (INV-1):** a raw `UPDATE` and a `DELETE` on `sys_audit_event` both fail
      (proves the DB trigger), and there is no app method that mutates a row.
- [ ] **Tamper detection (INV-3):** given a row whose stored `envelope_hash` no longer matches the
      recomputed canonical hash, the verifier flags the exact break point.
- [ ] **Deterministic hashing (INV-3):** the same logical envelope canonicalizes to the same bytes
      across runs / map-ordering (no hash drift).
- [ ] **Concurrent same-shard appends (INV-4):** parallel appends produce a valid *linear* chain
      (no two rows share a `previous_envelope_hash`).
- [ ] **State-transition (INV-6):** `is_state_transition=true` with a missing snapshot is rejected.
- [ ] **Actor / time (INV-5, INV-7):** missing actor key rejected; `occurred_at > recorded_at` rejected.

## 8. Definition of Done (foundation-critical)
- [ ] §7 tests green (each invariant proven; the chain + immutability ones are the headline).
- [ ] `AuditLog.append()` is demonstrably the *only* write path (ArchUnit guard or documented review).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-015` entry (canonical encoding spec + version, shard-serialization mechanism, chain_shard
      formula, native-SQL decision).
- [ ] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Canonical encoding — RESOLVED: RFC 8785 JCS.** Hash = SHA-256 over the JCS canonical JSON of the
   envelope minus `envelope_hash`, tagged `hash_encoding_version = 1` (a standardized scheme beats a
   bespoke format for tamper-evidence). Freeze it; test byte-stability across runs/map-ordering.
2. **Per-shard serialization — RESOLVED: declarative UNIQUE + optimistic retry (NOT an advisory lock).**
   `uidx_audit_chain_link (chain_shard, previous_envelope_hash) NULLS NOT DISTINCT` makes a fork
   impossible (incl. the genesis NULL row); `append()` retries on unique violation. Chosen over
   `pg_advisory_xact_lock` because a session lock is hard to observe when debugging and doesn't carry
   cleanly to multi-instance / Phase-2 topologies; a declarative constraint is the single source of
   truth everywhere ([[DL-BE-002]]). **Watch:** a very hot single shard could thrash retries → narrow
   `chain_shard` granularity (add hour) if that ever happens.
3. **`chain_shard` formula — RESOLVED (confirm tz): `<context>:<business_date>`**, business_date in
   **IST** (Indian platform). Flag if a different business timezone is intended.
4. **Persistence — RESOLVED: native SQL / JdbcTemplate** for append + verifier (CLAUDE.md "native SQL
   for invariant-heavy"); no JPA entity for the append-only table (no updates ever).
5. **`aggregate_version` — RESOLVED: caller-supplied** (app-stamped per aggregate, ≥1); `append()` does
   not derive it. Distinct from the JPA `@Version` lock ([[DL-BE-013]]).
