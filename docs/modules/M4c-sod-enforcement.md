# M4c · Segregation-of-Duties enforcement & the managed deviation register

> **Lean module spec** — *foundation-critical* (the two-tier SoD control that gates every role
> assignment): light ceremony, heavy invariant rigor.
> See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M4 — Admin IAM + Maker-Checker + SoD (BC10, BC16) |
| **Slice** | M4c — SoD strict-block + soft-warn-with-deviation-log on role assignment (C5, DL-033) |
| **Tier** | Foundation-critical (light ceremony · heavy SoD-invariant rigor) |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-021) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> **Scope call (flagged for review):** the original plan grouped M4c = "SoD + maker-checker." Having
> built M4a/M4b, these are independently large and *unrelated* mechanisms — SoD continues M4b's
> `assignRole`; maker-checker is a distinct cross-cutting gate with its own queue projection. So
> **M4c = SoD enforcement + deviation register** (this), and **maker-checker (#1) becomes M4d**. Say
> the word to recombine them.
>
> M4c is where **non-negotiable #3 (SoD) is built** and the **M4b role-assign guardrail is lifted** —
> `assignRole` becomes SoD-gated. Every command still routes through the M4a `CommandGateway`,
> inheriting idempotency (#4), MFA-freshness (#2), `super_admin` authz, and audit (#5).

## 1. Scope
**Owns:**
- **SoD policy as rules-as-data (C5, SP.1/SP.2)** — seed the Phase-1 fixed `admin_sod_policy` (strict =
  `{(credit_reviewer, treasury_and_settlement)}`; soft = `{(super_admin, compliance_reviewer),
  (ops_executive, treasury_and_settlement), (credit_reviewer, compliance_reviewer)}`), read the
  *current* policy (one active, `superseded_by IS NULL`) to drive the checks, and a `publishSodPolicy`
  command (Super Admin) that supersedes the current policy.
- **SoD enforcement on `assignRole` (RA.1–RA.3)** — layered onto M4b's command:
  - **Strict (RA.1):** assigning a role that forms a *strict* pair with an existing active role is
    **blocked** → reject `sod_role_block` (403, no envelope — pre-authorisation, G22), no mutation.
  - **Soft (RA.2/RA.3):** assigning a role that forms a *soft* pair **requires** an `override_reason`;
    on override it creates **exactly one** `admin_deviation_log` entry, links it
    (`deviation_register_entry_id`) and sets `sod_warning_acknowledged_at` + `override_reason` on the
    assignment, and emits `SodSoftDeviation.Logged`. Missing reason ⇒ rejected.
- **Managed deviation register** — `reviewDeviation(entryId, decision)` (Super Admin, quarterly) sets
  the review fields **exactly once, atomically** → `DeviationRegister.EntryReviewed` (DE.1/DE.2).
- **Events:** `Role.Assigned` now carries `sod_warning?`/`override_reason?`; plus
  `SodSoftDeviation.Logged`, `DeviationRegister.EntryReviewed`, `SodPolicy.Published`.

**Does NOT own (deferred / other slice):**
- **Maker-checker engine (#1, C4)** — proposer≠approver, `MakerChecker.Blocked/Approved`, the queue
  projection → **M4d** (the last foundation control). **Guardrail:** the Walking Skeleton's go-live /
  disbursement need it before they ship.
- **RA.4 — auditor accounts cannot hold an operational role** (account-level SoD, C19/X16) → enforced
  when **BC13 auditor accounts exist (M17)**; there is no `audit_account` to cross-check yet.
- **Quarterly-review scheduler / reminders** (DE.2 is non-blocking) → **M5/ops**; M4c builds the
  `reviewDeviation` *command*, not the reminder job.
- **HTTP layer** → Walking Skeleton.

## 2. Upstream dependencies
- **M4b RBAC** — Done (`assignRole`/`revokeRole`, `RoleResolver`); M4c adds the SoD gate to assign.
- **M4a gateway**, **M3a/b**, **M2 audit** — Done.
- **M0 schema** — Done: `admin_sod_policy`, `admin_deviation_log` (+ enums) exist (V2). **No new migration.**

## 3. Invariants & rules
- **INV-1 — Strict SoD blocks at the command boundary (RA.1, C5).** `credit_reviewer` and
  `treasury_and_settlement` cannot both be active for one admin; the assign is rejected `sod_role_block`
  with no envelope and no row. _(ref: C5/DL-033; **app-enforced** — no DB CHECK for the pair, see §9.4)_
- **INV-2 — Soft SoD requires an override and logs exactly one deviation (RA.2/RA.3).** A soft-pair
  assign without `override_reason` is rejected; with it, exactly one `admin_deviation_log` row is
  created and linked, and the assignment's `deviation_register_entry_id` + `sod_warning_acknowledged_at`
  + `override_reason` are set together. _(ref: DB `admin_role_assignment_soft_sod_override_chk`)_
- **INV-3 — Exactly one current SoD policy (SP.1).** Publishing supersedes the prior; at most one row
  has `superseded_by IS NULL`. _(ref: DB `uidx_admin_sod_policy_one_active`)_
- **INV-4 — Deviation entries are immutable except the review fields, set once and atomically
  (DE.1/DE.2).** _(ref: DB `admin_deviation_log_review_chk`)_
- **INV-5 — SoD decisions are audited.** Soft override → `SodSoftDeviation.Logged`; review →
  `DeviationRegister.EntryReviewed`. A **strict block emits no envelope** (pre-authorisation, G22).
  _(ref: B2 §3.10, B4 §4.2)_
- **INV-6 — Inherited controls.** `assignRole`/`reviewDeviation`/`publishSodPolicy` are gateway
  commands ⇒ idempotent, MFA-fresh, `super_admin`-authorised, audited. _(ref: [[M4a-command-substrate]])_

## 4. API / type surface
- **Commands (via `CommandGateway`, `super_admin`):** `assignRole(req, role, overrideReason?)` (extends
  M4b with the SoD gate + optional reason), `reviewDeviation(req, decision)`,
  `publishSodPolicy(req, strictPairs, softPairs)`.
- **Queries:** `currentSodPolicy() → SodPolicy`; `pendingDeviations() → List` (quarterly-review queue input).
- **Types:** `SodPolicy` (strict/soft pairs), `SodDecision { STRICT_BLOCK, SOFT_WARN, CLEAR }`,
  `DeviationEntry`.
- **Internal:** a `SodPolicyEvaluator` — given a target's active roles + the role being added, returns
  the `SodDecision` from the current policy (rules-as-data, not hard-coded).

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no (→ M4d) | the C4 primitive is M4d |
| 2 | MFA-fresh | **yes (inherited)** | via the M4a gateway |
| 3 | SoD-checked | **built here** | the strict-block + soft-warn engine on `assignRole` (C5) |
| 4 | Idempotent on `command_id` | **yes (inherited)** | M4a `sys_command_log` |
| 5 | Audit-logged envelope | **yes (inherited)** | soft deviation + review emit envelopes; strict block does not (G22) |

## 6. Events (audit envelopes via the gateway)
`admin_iam.Role.Assigned` (now with `sod_warning?`/`override_reason?`), `admin_iam.SodSoftDeviation.Logged`
(sub: BC11 quarterly-review queue), `admin_iam.DeviationRegister.EntryReviewed`,
`admin_iam.SodPolicy.Published`. _Strict SoD block → no envelope (G22)._

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] Strict block: a credit_reviewer holder assigned treasury_and_settlement (and vice versa) →
      `sod_role_block`, no envelope, no row (INV-1).
- [ ] Soft warn: an ops_executive holder assigned treasury_and_settlement **without** reason →
      rejected; **with** reason → assigned, exactly one `admin_deviation_log` row linked, envelope
      emitted (INV-2).
- [ ] Policy: publishing a new policy supersedes the prior; only one `superseded_by IS NULL`; a
      second concurrent "current" is impossible (INV-3, DB).
- [ ] Deviation review: `reviewDeviation` sets the three review fields once; a second review →
      rejected; partial update → rejected by the DB CHECK (INV-4).
- [ ] Non-soft, non-strict assignment is unaffected (e.g. a lone role) — regression of M4b.
- [ ] Inherited: a non-`super_admin` actor's `assignRole` → `role_not_held` (M4b authz still fires first).

## 8. Definition of Done (foundation-critical)
- [x] §7 tests green — `SodEnforcementTest` (7); 103 total green.
- [x] `/code-review` on the diff; findings fixed (publish pair-validation, fail-closed on missing
      policy, review-decision validation, no duplicate deviation on re-assign, duplicate-key
      translation, `identity→admin_user_id` consolidated into `RoleResolver`).
- [x] `DL-BE-021` entry (rules-as-data evaluator, strict-block app-only + its concurrency guard,
      soft-override deviation flow, the maker-checker→M4d split, RA.4 deferral).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED: M4c = SoD; maker-checker → M4d.** Each is independently large and unrelated;
   SoD completes M4b's `assignRole` and lifts its guardrail. Flagged for the user to recombine if desired.
2. **Migration — RESOLVED: none.** `admin_sod_policy`, `admin_deviation_log` exist (V2).
3. **Policy source — RESOLVED: rules-as-data.** Seed the SP.2 fixed policy; `SodPolicyEvaluator` reads
   the current `admin_sod_policy` rows (not hard-coded pairs), so a future `publishSodPolicy` changes
   behaviour without code change.
4. **Strict block is app-only — RESOLVED, with a concurrency guard.** No DB CHECK encodes the strict
   pair (it spans two rows), so the rule is app-enforced; to close the read-then-insert TOCTOU (two
   concurrent assigns of the two halves), the handler **locks the admin's active role rows
   (`SELECT … FOR UPDATE`)** before the check+insert. (A DB exclusion constraint is the stronger
   long-term option — noted in §10.)
5. **M4b guardrail — RESOLVED: lifted.** `assignRole` is now SoD-gated; role-assign may be exposed
   once M4d's maker-checker also gates the privileged paths.
6. **RA.4 (auditor ⊕ operational) — RESOLVED: deferred to M17** (no `audit_account` exists to check).
7. **Quarterly scheduler — RESOLVED: M5/ops.** M4c ships the `reviewDeviation` command; the reminder
   job is ops.

## 10. Watch-for (carry forward)
- **Maker-checker (#1) is still unbuilt (M4d)** — the Walking Skeleton's go-live (M9) and disbursement
  (M13) gates depend on it; same guardrail discipline as the M1b #4 / M4a #1-#3 deferrals.
- **Strict-SoD concurrency** — `FOR UPDATE` on the role rows closes the in-process race; a DB-level
  exclusion (e.g. a partial unique/exclusion encoding "no admin holds both halves") would make the DB
  the last line of defence (the project's stated preference) — evaluate in M4d/hardening.
- **RA.4** when BC13 auditor accounts land (M17).
- **Policy evolution** — `publishSodPolicy` supersession must not orphan in-flight deviations; review
  the chain semantics when the policy first actually changes (Phase-1 policy is fixed).
