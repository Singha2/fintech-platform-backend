# M4a · Command substrate — idempotency, MFA-freshness & the audited command envelope

> **Lean module spec** — *foundation-critical* (the harness every state-changing command in every
> later module routes through): light ceremony, heavy invariant rigor.
> See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M4 — Admin IAM + Maker-Checker + SoD (BC10, BC16) |
| **Slice** | M4a — the command-control substrate: idempotency (#4) + MFA-freshness enforcement (#2) + optimistic concurrency (P8) + the `command_id`-stamped audit envelope (#5) |
| **Tier** | Foundation-critical (light ceremony · heavy control-invariant rigor) |
| **Status** | Done (impl test-first + tests green; `/code-review` findings fixed; DL-BE-018) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> M4 is sliced **M4a → M4b → M4c** (decided with the architect): **M4a** = this engine; **M4b** =
> Admin IAM + RBAC + TOTP enrollment; **M4c** = SoD engine + maker-checker. M4a is where the
> **M1b-deferred `command_id` idempotency store (#4) finally gets its first consumer**, and where the
> **MFA-freshness *check* M3b built (`isMfaFresh`) is *enforced* at the command boundary** (AU10.3).
> It binds three of the five non-negotiables into one harness (#2, #4, #5) and leaves typed hooks for
> #1/#3, which M4c fills. Service substrate only — no HTTP layer (that lands at the Walking Skeleton).

## 1. Scope
**Owns:**
- **`CommandGateway`** — the single harness every state-changing command runs through. For one command
  it: (1) checks idempotency, (2) gates MFA-freshness + aggregate-version, (3) claims, executes, and
  (4) appends exactly one audit envelope, all in one transaction.
- **Idempotency store consumer (#4)** — claim/replay against the existing `sys_command_log`
  (PK `(actor_id, command_id)`, `resulting_event_id`): a replay of a recorded command returns the
  original event and **re-runs no mutation**; first-write-wins. _(builds the [[M1b-ids-and-versioning]]
  deferral; contract unchanged — DL-BE-013 guardrail satisfied)_
- **MFA-freshness enforcement (#2 consumer, AU10.3, C7)** — for `admin_user` actors, the command must
  carry a fresh `mfa_assertion_id`; the gateway calls **M3b `isMfaFresh(session, sensitivity)`**.
  Missing/expired ⇒ reject `mfa_assertion_missing` / `mfa_assertion_expired`, **no envelope, no log
  row, no mutation** (B4 §4.2, §6.4).
- **Optimistic concurrency at the boundary (P8)** — a stale expected `aggregate_version` ⇒
  `version_conflict`, no envelope, no mutation (parallel to [[M1b-ids-and-versioning]]'s `@Version`).
- **The command audit envelope (#5)** — every executed command appends one `AuditEventEnvelope` via
  `AuditLog` (M2) with `command_id` and `actor.mfa_assertion_id` stamped; `resulting_event_id` is
  written back to `sys_command_log`. X13 (audit-before-success) holds because the append is in-tx.
- **First command — `disableAdminUser`** — `admin_user.status` `active→disabled` (version-checked),
  the thinnest real admin command, used to prove the harness end-to-end.
- **Types:** `CommandRequest`, `CommandHandler<R>`, `CommandOutcome<R>`/`CommandEvent`,
  `CommandResult<R>`, `CommandRejectedException` (extends `PlatformException`, carries a reason code).

**Does NOT own (deferred / other slice):**
- **Maker-checker (#1)** and **SoD (#3)** → **M4c**. M4a defines the boundary order so they slot in as
  additional gates, but ships **no** proposer≠approver check, queue projection, or SoD-pair logic.
- **Admin IAM workflow, RBAC role-authorization, TOTP enrollment, `invited→active` MFA gate** → **M4b**.
  Consequently the first command's *role authz* (only Super Admin may disable) is **M4b**; M4a exposes
  **no HTTP surface**, so the not-yet-authorized path is not externally reachable.
- **HTTP layer** — parsing `X-Command-Id` / `X-Mfa-Assertion-Id` / `X-Aggregate-Version`, the cookie
  filter → **Walking Skeleton**. M4a is the service substrate the controllers will call.
- **Payload-level idempotency conflict** (G32 by body hash) — `sys_command_log` has no payload-hash
  column; M4a detects conflict on `(command_type, aggregate_type, aggregate_id)` divergence only.
  Full body-hash detection → §10 watch-for.

## 2. Upstream dependencies
- **M3b sessions / `isMfaFresh`** — Done (the freshness check the gateway calls). **M2 Audit Log**,
  **M1a** (errors), **M1b** (`Ids`, `aggregate_version`) — Done.
- **M0 schema** — Done: `sys_command_log` + `sys_audit_event.command_id` (V4), `admin_user` (V2).
  **No new migration** — all M4 tables already exist (V2–V4).

## 3. Invariants & rules
- **INV-1 — Replaying a command is a no-op (#4).** A second call with the same `(actor_id,
  command_id)` whose row has a `resulting_event_id` returns the original event and runs the mutation
  **zero** further times. _(ref: `sys_command_log` PK; G18, P6, P8)_
- **INV-2 — A divergent reuse of a `command_id` is rejected.** Same `(actor_id, command_id)` but a
  different `(command_type, aggregate_type, aggregate_id)` ⇒ `idempotency_conflict`; first-write-wins.
  _(ref: G18/G32, B4 §4.2)_
- **INV-3 — Admin commands require fresh MFA (#2, AU10.3, C7).** A missing or stale `mfa_assertion_id`
  ⇒ reject with **no envelope, no `sys_command_log` row, no mutation**. _(ref: B4 §4.2 `mfa_missing_or_expired`, §6.4)_
- **INV-4 — Stale `aggregate_version` ⇒ `version_conflict`** — no envelope, no mutation. _(ref: B3 P8)_
- **INV-5 — Exactly one envelope per executed command, in-tx (#5/X13).** It carries `command_id` and
  `actor.mfa_assertion_id`; `sys_command_log.resulting_event_id` links it back. _(ref: non-negotiable
  #5, X13/G27)_
- **INV-6 — Reject leaves no trace; replay short-circuits.** Idempotency replay returns the original
  before any re-check. MFA is gated **before** the claim; version/invariant rejections throw **inside**
  the handler (after the claim) and the single transaction rolls the claim back — so either way a
  rejected command writes no log row, no envelope, no mutation. _(ref: ordering, B4 §7)_

## 4. API / type surface
- **Gateway:** `CommandGateway.execute(CommandRequest req, CommandHandler<R> handler) → CommandResult<R>`.
- **`CommandRequest`:** `session` (resolved `AuthSession`, carries `mfa_assertion_id`), `commandId`
  (UUIDv7), `context` (e.g. `admin_iam`), `commandType`, `aggregateType`, `aggregateId`,
  `expectedVersion`, `actorType`, `sensitivity` (`ActionSensitivity`).
- **`CommandHandler<R>.handle() → CommandOutcome<R>`** — performs the version-checked mutation, returns
  the result `R` + a `CommandEvent` (eventType, new `aggregateVersion`, payload, before/after state).
  The gateway assembles the full envelope (actor, `command_id`, correlation) so commands stay thin.
- **`CommandResult<R>`:** `executed(result, eventId)` | `replayed(eventId)`.
- **First command:** `AdminUserCommands.disableAdminUser(CommandRequest, targetAdminUserId)`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no (hook only) | proposer≠approver is **M4c**; M4a fixes the boundary order it slots into |
| 2 | MFA-fresh | **enforced here** | gateway calls M3b `isMfaFresh`; admin actor w/o fresh assertion → reject, no envelope (AU10.3) |
| 3 | SoD-checked | no (hook only) | role-pair SoD is **M4c** |
| 4 | Idempotent on `command_id` | **built here** | `sys_command_log` claim/replay — the [[M1b-ids-and-versioning]] deferral's first consumer |
| 5 | Audit-logged envelope | **yes** | every executed command appends one `command_id`-stamped envelope in-tx (X13) |

## 6. Events
- **Publishes (audit envelopes via `AuditLog`):** the command's own resulting event (M4a's first:
  `admin_iam.AdminUser.Disabled`). A 422 invariant violation emits
  `admin_iam.AdminUser.CommandRejected` (B4 §4.3). MFA / idempotency / version rejects emit **no**
  envelope (B4 §4.2 table). _Maker-checker `MakerChecker.Blocked/Approved` → M4c._
- **Subscribes:** none (no bus yet).

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] Happy path: `disableAdminUser` runs once → status `disabled`, `aggregate_version` +1, one envelope
      carrying `command_id` + `actor.mfa_assertion_id`, `sys_command_log.resulting_event_id` set (INV-1, INV-5).
- [ ] Idempotent replay: same `(actor, command_id)` again → no second mutation, returns the original
      event id, exactly one envelope total (INV-1).
- [ ] Concurrent duplicate: two threads, one `command_id` → exactly one execution (Postgres serializes
      on the PK unique index), the other replays (INV-1 race-safety).
- [ ] MFA missing / stale (age `consumed_at`, as in M3b) → reject `mfa_assertion_missing`/`_expired`,
      **no** envelope, **no** `sys_command_log` row, status unchanged (INV-3).
- [ ] Stale `expectedVersion` → `version_conflict`, no envelope, no mutation (INV-4).
- [ ] `idempotency_conflict`: reuse a `command_id` with a different aggregate → rejected, original row
      intact (INV-2).
- [ ] Audit: envelope `command_id` + `actor.mfa_assertion_id` populated; chain verifies (INV-5).

## 8. Definition of Done (foundation-critical)
- [x] §7 tests green (idempotency + MFA-fresh + version are the headline) — `CommandGatewayTest`,
      9 tests written **test-first** (red on the stubbed gateway → green); 83 total green.
- [x] `/code-review` on the diff; findings fixed (null-session NPE guards + request validation,
      clean reject when the actor has no admin row, accurate version on the lost-race path).
- [x] `DL-BE-018` entry (sys_command_log claim/replay design, boundary ordering, conflict-detection
      scope, MFA-fresh reuse, the #1/#3-hook deferral with its M4c guardrail).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED: M4a/M4b/M4c (architect's call, substrate-first).** This slice = the engine;
   Admin IAM/RBAC/TOTP = M4b; SoD + maker-checker = M4c.
2. **Migration — RESOLVED: none.** `admin_user`, `admin_role_assignment`, `admin_deviation_log`,
   `admin_sod_policy`, `sys_command_log`, `auth_mfa_factor` already exist (V2–V4). M4a maps onto
   `sys_command_log` + `admin_user`.
3. **Idempotency #4 home — RESOLVED: built here.** The [[M1b-ids-and-versioning]] deferral lands with
   its first real command, against the unchanged `sys_command_log` contract (DL-BE-013 guardrail met).
4. **Boundary order — RESOLVED:** null-session/missing-assertion guard → idempotency-replay →
   MFA-fresh gate → claim (`INSERT … ON CONFLICT DO NOTHING`) → execute (the version-checked mutation;
   a stale version / invariant throws here) → append envelope → record `resulting_event_id`. **As-built
   nuance:** the version gate lives *inside* the handler (a generic gateway can't read an arbitrary
   aggregate's version pre-claim), so it fires after the claim; the single transaction rolls the claim
   back on any throw, so a reject still leaves no trace. The #1/#3 gates (M4c) slot just before execute.
5. **Conflict detection — RESOLVED: identity-level now, body-hash later.** Detect on `(command_type,
   aggregate_type, aggregate_id)`; payload-hash G32 needs a `sys_command_log` column → §10.
6. **First command — RESOLVED: `disableAdminUser`.** Proves the harness; its Super-Admin role authz is
   M4b. M4a ships no HTTP surface, so the un-authorized path is not externally reachable.
7. **MFA reuse — RESOLVED:** reuse M3b `isMfaFresh(session, sensitivity)`; the command declares its
   `ActionSensitivity`; no new freshness code in M4a.

## 10. Watch-for (carry forward)
- **Maker-checker (#1) + SoD (#3) are unbuilt in M4a** — **guardrail:** no maker-checker-gated command
  (go-live, disbursement, role-assign) may ship before **M4c** fills the hooks. (Same discipline as the
  M1b #4 deferral.)
- **Full G32 (payload-hash) idempotency conflict** needs a `sys_command_log` payload-hash column or an
  envelope-payload compare — revisit when commands carry rich bodies.
- **Correlation / causation threading** across multi-step command chains (G29 maker-checker scope) —
  design with M4c; M4a mints one `correlation_id` per command for now.
- **Two version paths** — M4a uses native `UPDATE … WHERE aggregate_version = ?`; M1b uses JPA
  `@Version`. Keep them consistent as JPA-mapped aggregates arrive.
