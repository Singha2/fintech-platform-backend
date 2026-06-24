# WS-7 · Disbursement → `disbursed` (BC4 = M13 min) — the second maker-checker+MFA gate · the finale

> **Lean sub-slice spec** (Walking Skeleton §4; = M13 min). Light tier, but the **last slice**: the second
> two-endpoint maker-checker+MFA gate (the payout), behind the C27 gate WS-6 opened. Completing it walks one
> invoice **`listed → disbursed`** and finishes **Milestone 1**. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-7 (= M13 Settlement, min) |
| **Slice** | WS-7 — disbursement draft (maker) → approve (checker) → escrow payout → `Listing.Disbursed` (BC4 + M5b) |
| **Tier** | Light (skeleton-thin — happy path + the gate's reject paths; alternates are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-24 |

> **DoR decisions (settled at the gate):**
> 1. **Column-based maker-checker** (`cash_payout_instruction.maker_id`/`checker_id`/`checker_mfa_assertion_id`
>    + DB CHECKs) — the **twin of WS-4 go-live**, no M4d extraction. App-guard checker≠maker → 409, DB CHECK
>    backstop. Maker at `draft`, checker ∈ treasury_and_settlement at `approve`.
> 2. **Inline payout execution** (real BC18 `PayoutLegWebhookProcessed` webhook is M13-full; the inbound
>    mechanism is proven by WS-5, and WS-4/WS-6 set the inline precedent).
> 3. **Disbursement amount = `funding_target`** — the discounted value paid to the supplier
>    (gross = net = funding_target, fee = 0, tds = null). TDS/fee live at *distribution* (deferred).
> 4. **PI.1 idempotency:** `payout_instruction_id` PK **is** the bank vendor `client_instruction_id`.
> 5. **`approve` targets the real instruction id** (the WS-6 lesson) so its audit envelope chains to the
>    aggregate, not a synthetic id.

---

## 1. Scope
**Owns:** a `DisbursementService` + `DisbursementController` in the `settlement` package (BC4). Tables:
`cash_payout_instruction`; flips `deal_listing.status fully_funded → disbursed`. Reuses M5b
`EscrowAclService.instructPayoutSingle`.

**Also delivers — the milestone capstone:** `WalkingSkeletonE2ETest` driving the money-flow spine WS-4→WS-7
**over HTTP** (create listing → go-live → subscribe → inflow webhook → assign → sign → disburse) on seeded
active counterparties — the *"the skeleton walks `listed → disbursed`"* proof that completes Milestone 1.

**Does NOT own (deferred):** the distribution leg (maturity → investor payout + TDS, WS-8/M13-full); the
BC18 payout *webhook* + the EoD master-statement reconciliation overlay (PI.7 — straight to `executed`);
partial/failed legs + remediation (PI.6); the T+1 SLA *enforcement* (PI.8 — date recorded only); refund. No
new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-4/5/6** produce the `fully_funded` + `all_signed` listing (seeded in focused tests; driven over HTTP
  in the capstone). **M5b** `EscrowAclService.instructPayoutSingle`. **M4a–d** roles
  (`treasury_and_settlement`, MFA-freshness). Done.

## 3. Invariants & rules
- **INV-1 — Disbursement gate (PI.2/C27).** `draft` allowed only when `deal_listing.status='fully_funded'
  AND all_signed=TRUE`. _(ref: PI.2, C27, L.5)_
- **INV-2 — Payout maker-checker + MFA (PI.5/C4/C7).** `approve`: checker ∈ treasury_and_settlement,
  `checker_id ≠ maker_id` (app 409 `checker_equals_maker` + DB CHECK), fresh MFA (gateway +
  `checker_mfa_assertion_id` column). _(ref: PI.5, C4, C7)_
- **INV-3 — PI.1 idempotency.** `payout_instruction_id` is the bank `client_instruction_id`; the M5b ACL is
  idempotent on it (re-instruct returns the original UTR). _(ref: PI.1, C9)_
- **INV-4 — One disbursement per listing.** App guard (no DB UNIQUE on `(listing_id, kind)`) → a re-draft is
  a clean 400. _(ref: PI.2)_
- **INV-5 — Status path (PI.5).** `drafted → approved → … → executed`; the listing flips
  `fully_funded → disbursed` exactly when the payout executes. _(ref: PI.5)_
- **INV-6 — Idempotent + audited.** `X-Command-Id` on both commands; one+ envelope each; the disbursement
  amount carries no PII. _(ref: G18, X13)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Effect |
|---|---|---|
| `POST /listings/{id}/disbursement/draft` → 201 | treasury_and_settlement (**maker**) | PI.2 gate; create `cash_payout_instruction` (`disbursement`, `drafted`, maker_id, gross=net=funding_target, fee=0, sla=now+1d) |
| `POST /listings/{id}/disbursement/approve` | treasury_and_settlement (**checker** ≠ maker, MFA) | checker≠maker + MFA; `drafted→approved`; `instructPayoutSingle` → `executed`; listing `fully_funded→disbursed` |
| `GET /listings/{id}/disbursement` | (any authenticated) | read (status, amounts, maker/checker, listing status) |

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **yes** | two-endpoint pair: maker at draft, checker at approve (INV-2) |
| 2 | MFA-fresh | **yes** | gateway `isMfaFresh` on the checker + `checker_mfa_assertion_id` column |
| 3 | SoD-checked | yes | `treasury_and_settlement` role gate on both |
| 4 | Idempotent | yes | `X-Command-Id` (gateway) + `payout_instruction_id` (bank key, M5b ACL) |
| 5 | Audit-logged | yes | gateway envelopes + `banking.PayoutLegWebhookProcessed` (ACL) + `Listing.Disbursed` |

## 6. Events
- **Publishes:** `settlement.PayoutInstruction.DisbursementDrafted` / `.DisbursementInstructed`;
  `listing.Listing.Disbursed`; (`banking.PayoutLegWebhookProcessed` from the M5b ACL).
- **Subscribes:** none (draft is an explicit command; real BC4 reacts to the disbursement gate event).

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest`
- [ ] **Happy path:** seed `fully_funded` + `all_signed` listing → draft (maker) → approve (checker) →
      listing `disbursed`, payout `executed` with an escrow UTR.
- [ ] **PI.2 gate:** draft on a listing with `all_signed=false` → rejected.
- [ ] **Maker-checker:** the maker (same actor) calling `approve` → 409 `checker_equals_maker`; listing not
      disbursed.
- [ ] **MFA on checker:** stale assertion → 401 `mfa_assertion_expired`.
- [ ] **SoD:** an ops actor (not T&S) on draft or approve → 403 `role_not_held`.
- [ ] **Capstone `WalkingSkeletonE2ETest`:** money-flow spine WS-4→WS-7 over HTTP → listing `disbursed`.

## 8. Definition of Done (milestone-closing)
- [x] §7 tests green (incl. the capstone) — `DisbursementTest` **6/6**, `WalkingSkeletonE2ETest` green; full
      suite **198**.
- [x] `/code-review` on the diff; findings fixed — 2 findings (one root: concurrent double-draft, no DB
      UNIQUE) → **V6 migration** (partial UNIQUE) + `DuplicateKeyException` catch + regression test.
- [x] `DL-BE-037` added; `DL-BE-029` finalized (**Milestone 1 / Walking Skeleton complete**).
- [x] This spec **and** the WS umbrella spec flipped to **Status: Done**. → Milestone 2 (widen Wave 1).

> **Note:** WS-7 was planned as no-migration, but the code review surfaced a genuine missing constraint, so
> **V6** (the first migration since M0) was added — the Constitution-aligned right-depth fix.
