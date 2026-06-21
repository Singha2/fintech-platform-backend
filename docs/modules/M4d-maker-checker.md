# M4d · Maker-checker engine (record-level four-eyes)

> **Lean module spec** — *foundation-critical* (the C4 control every gated transition routes through):
> light ceremony, heavy invariant rigor. See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M4 — Admin IAM + Maker-Checker + SoD (BC10, BC16) |
| **Slice** | M4d — the record-level maker≠checker primitive (C4, X11, DL-033) + the queue projection |
| **Tier** | Foundation-critical (light ceremony · heavy control-invariant rigor) |
| **Status** | Done (impl test-first + tests green; `/code-review` findings fixed; DL-BE-023) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> M4d is the **last M4 foundation control** and builds **non-negotiable #1**. It is the reusable
> primitive that **Wave-1 gated transitions plug into** — listing go-live (M9, Treasury maker-checker),
> disbursement (M13), KYC approval (M15), suspend commands (M8/M9). It does not own those flows; it
> owns the **maker≠checker check, the `MakerChecker.Blocked/Approved` envelopes, and the queue
> projection**, proven on one representative BC10 four-eyes flow. Commands route through the M4a
> `CommandGateway`, inheriting idempotency (#4), MFA-freshness (#2), authz (#3-role, M4b), and audit (#5).

## 1. Scope
**Owns:**
- **The maker≠checker check (C4, X11, B4 §6.2)** — a `MakerCheckerGate` the checker command invokes:
  read the most-recent **maker (proposal) envelope** on the originating aggregate, compare its
  `actor.actor_id` to the checker's `actor_id`. **The check is on `actor_id`** (the human identity),
  never on role or session — a user holding two roles still cannot be both maker and checker (C18/C4).
- **Blocked = a committed, audited reject (X11/G22)** — `actor_id` equal ⇒ emit
  `admin_iam.MakerChecker.Blocked` and return **409 `maker_checker_violation`**, aggregate **unchanged**.
  Unlike the M4a pre-authorisation rejects (which throw → roll back → *no* envelope), the Blocked
  envelope **must persist** — it is the record of an attempted-but-blocked action.
- **Approved = transition + `MakerChecker.Approved`, atomically (X13)** — `actor_id` distinct ⇒ emit
  `admin_iam.MakerChecker.Approved` **and** the originating-aggregate transition envelope in the **same
  transaction** (both commit or neither).
- **Scope rule G29** — a checker command with downstream effects evaluates maker-checker on the
  **initiating aggregate only**; dependents inherit via the `causation_id` chain (no per-dependent pair-check).
- **The maker-checker queue projection (B4 §6.5)** — a read over the envelope stream: proposals not yet
  answered by a paired `Approved`/`Blocked`, **excluding the requesting actor** (UX defence-in-depth;
  the gate still enforces C4 at the handler).
- **Proving flow (representative four-eyes):** maker-checker'd **admin disable** — `proposeDisableAdmin`
  (maker, emits a proposal envelope) + `approveDisableAdmin` (checker, runs the gate → on approve,
  applies the M4b disable cascade). Demonstrates the primitive M9/M13 will reuse.

**Does NOT own (deferred / other slice):**
- **The real Wave-1 consumers** — listing go-live (M9), disbursement (M13), KYC approval (M15),
  suspend (M8/M9). They call `MakerCheckerGate`; M4d ships the engine + one proving flow.
- **HTTP queue endpoint** (`GET /admin/queues/maker-checker`) → Walking Skeleton; M4d ships the
  projection **query**, not the endpoint.
- **`maker_aggregate_state_invalid`** (the maker prepared the aggregate to the wrong state) — full
  state-machine validation lands with the real state-machine aggregates (M9+); M4d does the
  proposer≠approver core + a basic "a proposal exists" check.
- **Whether to retrofit other sensitive BC10 commands** (provision, role-assign) to four-eyes → a
  product/compliance decision (§9.4), not assumed here.

## 2. Upstream dependencies
- **M4a gateway** (idempotency/MFA/authz/audit), **M4b** (role authz on the checker), **M2 audit**
  (the envelope stream maker-checker reads), **M3a/b** — all Done.
- **M0 schema** — Done. **No new migration:** maker-checker state lives in `sys_audit_event` (the
  proposal + answer envelopes); the queue is a projection over it — no dedicated table.

## 3. Invariants & rules
- **INV-1 — Proposer ≠ approver on `actor_id` (C4, X11).** A checker whose `actor_id` equals the
  maker's is blocked, regardless of roles or sessions held. _(ref: C4, DL-033, B4 §6.2)_
- **INV-2 — Blocked is emitted and committed; the transition does not apply (X11/G22).** A blocked
  checker command persists a `MakerChecker.Blocked` envelope and returns 409 — it is **not** a
  rollback-reject. _(ref: B4 §4.2 `maker_checker_violation` → emits envelope; §6.2)_
- **INV-3 — Approved applies the transition and `MakerChecker.Approved` atomically (X13).** Both
  envelopes + the mutation commit together. _(ref: B4 §6.2, X13)_
- **INV-4 — The check reads the originating aggregate's most-recent maker envelope; scope is the
  initiating aggregate only (G29).** Dependents inherit via causation. _(ref: B4 §6.3, X11)_
- **INV-5 — The queue projection excludes the requesting actor** (the gate still enforces C4 at the
  handler — the projection is defence-in-depth, not the control). _(ref: B4 §6.5, C4)_
- **INV-6 — Inherited controls.** Maker + checker commands are idempotent, MFA-fresh, role-authorised,
  and audited via the M4a gateway. _(ref: [[M4a-command-substrate]], B4 §6.4)_

## 4. API / type surface
- **`MakerCheckerGate.check(aggregateType, aggregateId, proposalEventType, checkerActorId) →
  MakerCheckerOutcome`** — returns `Approved(makerActorId)` or `Blocked(makerActorId)` after reading
  the latest proposal envelope. The Blocked path's envelope is emitted by the gate.
- **Commands (via `CommandGateway`):** `proposeDisableAdmin(req, targetAdminUserId)` (maker; emits
  `admin_iam.AdminUser.DisableProposed`), `approveDisableAdmin(req, targetAdminUserId)` (checker; gate
  → disable cascade on approve).
- **Query:** `makerCheckerQueue(excludingActorId, …) → List<PendingApproval>` (proposals awaiting a
  different checker).
- **Types:** `MakerCheckerOutcome`, `PendingApproval`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **built here** | the `actor_id` maker≠checker gate (C4, X11) |
| 2 | MFA-fresh | **yes (inherited)** | the checker carries a fresh assertion (B4 §6.4) via the M4a gateway |
| 3 | SoD-checked | role-authz (M4b) | the checker must hold the gating role (e.g. Treasury for go-live) — authz, not pair-SoD |
| 4 | Idempotent on `command_id` | **yes (inherited)** | M4a `sys_command_log` |
| 5 | Audit-logged envelope | **yes** | `MakerChecker.Blocked` / `MakerChecker.Approved` + the transition |

## 6. Events (audit envelopes via the gateway/gate)
`admin_iam.AdminUser.DisableProposed` (the proving maker envelope), `admin_iam.MakerChecker.Blocked`
(`record_id, admin_user_id, action`; sub BC15 admin alert), `admin_iam.MakerChecker.Approved`
(`record_id, maker_user_id, checker_user_id, decision`; sub: originating context), plus the transition
envelope (here `admin_iam.AdminUser.Disabled`). Emitted by **BC10**, not the originating context (X11).

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] Same actor proposes then approves → `maker_checker_violation` (409), `MakerChecker.Blocked`
      envelope **persisted**, admin still active (INV-1, INV-2).
- [ ] Distinct actors (A proposes, B approves) → admin disabled, `MakerChecker.Approved` + the
      transition envelope both present, all in one transaction (INV-3).
- [ ] The check is on `actor_id`, not role: A and B both Super Admin — A cannot approve their own
      proposal even though both hold the role (INV-1).
- [ ] Approve with no prior proposal → rejected (a maker envelope must exist) (INV-4).
- [ ] Queue projection lists the pending proposal for B but **not** for A (the maker) (INV-5).
- [ ] Inherited: a non-authorised checker → `role_not_held`; a stale-MFA checker → `mfa_assertion_expired` (INV-6).

## 8. Definition of Done (foundation-critical)
- [x] §7 tests green — `MakerCheckerTest` (7, written test-first: red on the stubbed commands → green);
      110 total green.
- [x] `/code-review` on the diff; findings fixed (answer-aware gate, proposed-version anchor on approve,
      one-open-proposal guard + row lock, version-stamped Approved envelope, `event_id` ordering).
- [x] `DL-BE-023` entry (the `actor_id` check, Blocked-is-committed model vs M4a rollback-rejects,
      envelope-stream-as-state + queue projection, G29 scope, the proving-flow + retrofit decision).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED:** M4d is the maker-checker engine; M4 is then complete (M4a–M4d). Real
   consumers are Wave 1.
2. **Migration — RESOLVED: none.** Maker-checker state is the envelope stream (`sys_audit_event`); the
   queue is a projection over it. (A read-model table is a later optimisation — §10.)
3. **Blocked-is-committed — RESOLVED.** This is the key departure from M4a: a blocked checker command
   does **not** throw-and-roll-back. The gate appends `MakerChecker.Blocked` and the command returns a
   `Blocked` outcome (committed); the `CommandResult` carries the block so the caller maps it to 409.
   Approve emits `MakerChecker.Approved` as a side envelope (the M4c soft-deviation pattern) plus the
   transition as the gateway's `CommandEvent`. Both paths commit; neither rolls back.
4. **Proving flow — RESOLVED: maker-checker'd admin disable** (propose → approve). It is naturally
   four-eyes, reuses the M4b disable cascade as the transition, and cleanly demonstrates `actor_id`
   equality. **Open decision (flagged):** which *other* sensitive BC10 commands (provision, role-assign)
   should become four-eyes is a product/compliance call — M4d does not retrofit them, and the existing
   direct disable remains until that decision (note the control-coverage gap).
5. **Proposal lookup — RESOLVED.** Read the most-recent envelope of the proposal event type on
   `(aggregate_type, aggregate_id)` from `sys_audit_event`; "unanswered" = no later
   `MakerChecker.Approved/Blocked` for it. The latest proposal's `actor.actor_id` is the maker.
6. **Queue — RESOLVED.** A projection query over `sys_audit_event` (proposals without a paired answer),
   filtered to exclude the requesting `actor_id`; the HTTP endpoint is the Walking Skeleton's.

## 10. Watch-for (carry forward)
- **Audit-log-as-read-model** — querying the append-only `sys_audit_event` for maker-checker state is
  correct (single source of truth) but can get query-heavy; if the queue/check becomes hot, a
  projection (read-model) table updated from envelopes is the optimisation — the audit log stays canonical.
- **Concurrent checkers** on one proposal — two approvals racing; the gateway idempotency + the
  transition's optimistic lock should serialise. Verify when the first real consumer (M9) lands.
- **`maker_aggregate_state_invalid`** — the full "maker left the aggregate in the right state" check
  arrives with the real state-machine aggregates (M9+).
- **Four-eyes coverage decision** (§9.4) — track which sensitive commands must be retrofitted; until
  then the direct M4b disable bypasses maker-checker (acceptable only pre-production).
