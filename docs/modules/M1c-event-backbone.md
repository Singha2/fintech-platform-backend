# M1c · Event Backbone — sequencing & home (DISSOLVED into M2 + Walking Skeleton)

> **Status: Deferred / Scheduled — NOT a "now" slice.** This doc is the *home* for the event-backbone
> work so it isn't lost. The decision below dissolves the monolithic "M1c" and re-plugs its three
> pieces at their real first-consumers. See `docs/spec/Spec_Driven_Build_Plan.md` §C (M1/M2) and
> [[DL-BE-014]].

| | |
|---|---|
| **Module** | M1 (Shared Kernel & Event Backbone) — the "Event Backbone" half |
| **Status** | Deferred / Scheduled (build trigger in §4) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

## 1. Why this isn't built now
The event backbone is three things with three different *first consumers*. Building any of them before
its consumer exists is infra with no rider (the same reason `command_id` idempotency was deferred —
[[DL-BE-013]]). In a Phase-1 monolith (G17), audit events reach the log via a **direct, synchronous
`AuditLog.append(envelope)` inside the command's DB transaction**, which already satisfies
audit-publish-before-success (X13 / P8) — so M2/M3/M4/M5 need **no bus**.

## 2. The split (where each piece lands)
| Piece | First real consumer | Built in |
|---|---|---|
| **`AuditEventEnvelope`** (B2 §2 wire/record type) | the audit log itself | **M2** (its own type) |
| **In-process pub/sub bus** (G17) | the first *cross-context* reaction (e.g. Subscription confirmed → Listing `committed_total`) | **Walking Skeleton** |
| **X13 transactional outbox** (G27 — publish-before-success across aggregates) | the first state-changing command needing "2xx ⇒ logged" across aggregates | **Walking Skeleton** |

When the bus arrives, the audit log is refactored from a direct call into **"the first subscriber"** —
a small, mechanical change, not a rewrite.

## 3. Scope (for when it is built, at the Walking Skeleton)
- In-process **pub/sub**: a synchronous, same-transaction event publisher + typed subscriber registry,
  carrying `AuditEventEnvelope`. Broker-future-proof (G17) — no cross-process broker in Phase 1.
- **Transactional outbox** (X13/G27): producer writes envelope(s) to a local outbox in the command's
  transaction; flush appends to BC14. The 2xx is not returned until the append succeeds (P8).
- At-least-once semantics (P4): subscribers dedupe on `event_id`; producers on `(actor_id, command_id)`
  (the deferred idempotency store, [[DL-BE-013]]).

## 4. Build trigger (DoR for this slice)
Promote from Deferred → Ready when **both** hold:
- **M2 (Audit Log) is Done** — so `AuditEventEnvelope` + the append path exist.
- The **first cross-context subscriber** is about to be built (Walking Skeleton: e.g. Subscription →
  Listing). That is the first moment a real publisher *and* a non-audit subscriber co-exist.

Until then: contexts call `AuditLog.append()` directly; no bus, no outbox.

## 5. Non-negotiables it serves
- **#5 Audit-logged** — every state change emits an `AuditEventEnvelope` to BC14 (satisfied by direct
  append in Wave 0; by bus+outbox from the Walking Skeleton on).
- Enables #4 (idempotency) end-to-end once producers dedupe on `command_id` before publishing.

## 6. Pointers
- Spec: B2 (`08_B2_Event_Model.md`) §2 envelope, §5 ordering; B4 (`10_B4_API_Conventions.md`) §7 X13;
  Gap Log G17 / G27 / P4.
- Decisions: [[DL-BE-014]] (this sequencing), [[DL-BE-013]] (idempotency deferral), [[DL-BE-003]]
  (command_id / envelope_hash), [[DL-BE-010]] (audit chain shard key).
