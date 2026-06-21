# WS · Walking Skeleton — one invoice, `listed → disbursed`

> **Milestone spec** (not a single light slice). The thin vertical cut that takes **one** invoice
> end-to-end through the money-flow, on top of the finished foundation (M0–M5). See
> `docs/spec/Spec_Driven_Build_Plan.md` §H Milestone 1. Spec before code; invariant test before rule.
> Each sub-slice (WS-1…WS-7) runs the per-module loop (§D) at the *light* tier and lands to its own
> mini-DoD; the capstone is one green end-to-end test.

| | |
|---|---|
| **Milestone** | M1 — Walking Skeleton (highest-value first build) |
| **Touches** | BC1/2/3/4/5 (money-flow) · BC7/8/9 (counterparties) · BC11 (compliance, auto-approve stub) — minimal cut of M6–M13 |
| **Tier** | Light (skeleton-thin — happy path only; Milestone 2 widens each to full rigor) |
| **Status** | Draft |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-22 |

> **Why this exists.** It proves the hardest invariants *early*, on real tables, before we widen any
> module: **maker-checker**, **MFA freshness**, **funding equality (G10)**, **idempotency**, and
> **audit chaining** — all walked under a single `correlation_id` from `Listing.GoneLive` to
> `Listing.Disbursed` (the B2 §4.1 Trace A spine). No new migrations: the whole money-flow schema was
> ported in M0 (V1–V5). This is **pure application wiring** onto an existing schema.

---

## 1. Goal — what "done" looks like

One green integration test (`WalkingSkeletonE2ETest`) drives this golden path to completion, asserting
the state at each hop **and** the chained audit envelope sequence:

```
[foundation, already built]  Admin logs in (M3) · is an active admin with roles (M4)
WS-1  Supplier onboarded → sup_account.status = active            (BC8, admin-on-behalf)
WS-2  Buyer + ack user set up → buyer_account.status = active     (BC9)
WS-3  Investor onboarded → inv_account.status = active            (BC7, invite→active; KYC auto-approved)
WS-4  Invoice listed, priced (snapshot), gone live  ┐            (BC1 + BC3 pricing + BC4 VA)
        deal_listing: draft → … → ready_for_review → live        ← maker-checker + MFA #1 (go-live)
        cash_virtual_account.status = created
WS-5  Single investor subscribes 100% → fully_funded             (BC2 + stubbed escrow inflow)
        Σ confirmed sub.amount = listing.committed_total
                               = VA.observed_inflow_total = funding_target   ← G10
        deal_listing.status = fully_funded
WS-6  Assignment requested, single leg signed → all_signed       (BC5 + stubbed signing)
        legal_assignment_set.status = all_signed; deal_listing.all_signed = true
WS-7  Disbursement drafted → approved → instructed → executed    (BC4 + stubbed escrow payout)
        cash_payout_instruction: drafted → approved → … → executed  ← maker-checker + MFA #2 (payout)
        deal_listing.status = disbursed
```

**The slice is "walking" when** `deal_listing.status = 'disbursed'`, the two maker-checker+MFA gates
fired with proposer ≠ approver, G10 held to the paise, and the audit chain links unbroken end-to-end.

> The maturity → distribution → closed tail is the *same* maker-checker shape (WS-7 repeated for
> `kind = distribution`). The skeleton **stops at `disbursed`**; the tail is an optional WS-8 (deferred,
> see §8) — disbursement is the load-bearing proof.

---

## 2. Scope & the minimal config

**In scope — the happy path only,** wiring the smallest application service per BC that advances the
golden path. **Out of scope — every reject / shortfall / timeout / cancellation branch**, deferred to
Milestone 2 (§8). The config is deliberately the smallest that still exercises every cross-cutting
invariant:

- **One supplier, one buyer + one acknowledgment user, one investor** (`sub_type = resident_individual`).
- **One invoice, face value < ₹1 Cr** — keeps `risk_*_profile` four-eyes (C6, threshold ₹10 Cr / `1e10`
  paise) **out** of the skeleton (`exposure_cap`/`credit_limit` under threshold ⇒ co-approver columns
  stay null and their CHECKs pass).
- **A single subscriber funding 100%** — so `legal_assignment_set.total_count = 1`. This collapses BC5
  to **one** signature leg and dodges the 24 h time-box abandonment branch (G13, AS.2), while still
  proving the `AllSigned → disbursement` gate (C27).
- **Compliance auto-approved** — the WS compliance step writes `comp_kyc_file.status = approved`
  immediately (a stub standing in for the real BC11 engine, replaced wholesale at M15). No AML/PEP.
- **All vendors stubbed** — verification (M5a), escrow inflow + payout (M5b), e-sign (M5c), notifications
  (M5d) are the in-process ACL stubs already built. Real sandboxes swap in at the Production gate only.
- **Suitability has no mismatch** — keeps the override-ack path (IA.4 / C21 / G26) out of WS-3.

**Owns (new application code):** thin command services + their handlers for BC8/9/7/1/3/2/5/4 happy
paths, plus a `compliance/` auto-approve stub, all under `com.arthvritt.platform.<context>`. **Does not
own:** any new migration (schema is V1–V5, frozen); the full state machines, reject paths, and
per-invoice stamping — Milestone 2.

---

## 3. Upstream dependencies — all DONE (foundation M0–M5)

| Dependency | Used for |
|---|---|
| **M2** Audit Log (`AuditLog`, chained `sys_audit_event`) | every command's envelope (#5), the end-to-end chain assert |
| **M3a/b** Auth + sessions + MFA freshness (`mfa_assertion_id`) | admin login; the *fresh-MFA* assertion the two checker steps consume; ack-user & investor OTP |
| **M4a** Command substrate (`sys_command_log`, `command_id` idempotency) | idempotent replay of every state-changing command |
| **M4b/c/d** Admin IAM + RBAC + SoD + **maker-checker engine** | the propose→approve gate behind go-live (WS-4) and payout (WS-7); SoD/role checks |
| **M5a** Verification ACL (stub) | supplier/buyer/investor identity verification facts (PAN/GSTIN/CIN/Aadhaar) |
| **M5b** Banking/Escrow ACL (stub) | VA inflow observation (WS-5) + payout legs (WS-7), with vendor idempotency keys |
| **M5c** Signing ACL (stub) | the assignment signature session (WS-6) |
| **M5d** Notifications (stub) | OTP / ack notifications fire-and-forget |

If any handler needs a substrate that turns out missing (e.g. a generic domain maker-checker helper not
yet extracted from M4d's admin-IAM use), that extraction is its own decision-logged step **before** the
sub-slice, same as the M4c disable-cascade and the M5 `AbstractAclService` extractions.

---

## 4. Sub-slices (build in this order; each is test-first, light tier)

Each sub-slice names what it advances, the command(s), the event(s), the resulting state transition (in
**DB-enum-true** names — the V1/V2 enums, *not* the spec's idealized labels), and what it explicitly
defers. State names, CHECKs, and maker-checker columns are quoted from the migrations.

### WS-1 · Supplier active (BC8) — admin-on-behalf
- **Advances:** `sup_account.status` `created → identity_verified → kyc_submitted → kyc_approved →
  credit_reviewed → maa_signed → active`.
- **Commands (admin actor; no supplier login — DL-012):** `CreateSupplier` · `GrantAgencyConsent`
  (`sup_agency_consent.is_active=true` — every later on-behalf action references it, AC.1/G5) ·
  `RecordIdentityVerified` (← M5a) · `SubmitKyc` → **compliance auto-approve** writes
  `comp_kyc_file.status=approved` → `RecordKycApproved` · `SubmitFinancialProfile` +
  `RecordCreditReview(risk_rating, exposure_cap)` (under ₹10 Cr ⇒ no four-eyes) · `RecordMaaSigned`
  (← M5c; MAA signed personally, never delegated — AC.2) · `ActivateSupplier`.
- **Gate to active (SA8.2):** kyc approved ∧ credit review set ∧ MAA signed.
- **Defers:** suspension/blacklist/exit, consent revocation, real KYC.

### WS-2 · Buyer + acknowledgment user (BC9)
- **Advances:** `buyer_account.status` `nominated → identity_verified → credit_assessed →
  engagement_started → active` (`relationship_tier=acknowledged_buyer`, `acknowledgment_mode=per_invoice`
  — Phase-1 CHECKs).
- **Commands:** `NominateBuyer` · `RecordIdentityVerified` (← M5a) ·
  `RecordCreditAssessment(credit_limit, pricing_band_id)` (under ₹10 Cr ⇒ no four-eyes) ·
  `StartEngagement` · `DesignateAckUser` (OTP-only login, **no password, no MFA** — AU.1/DL-021;
  `buyer_ack_user`) · `ConfirmPaymentInstruction` (`buyer_payment_rule`) · `ActivateBuyer`.
- **Gate to active (BA.3):** credit assessment ∧ ≥1 active ack user ∧ payment instruction confirmed.
- **Defers:** blanket ack, anchor tier, payment-rule supersession.

### WS-3 · Investor active (BC7) — invite → active
- **Advances:** `inv_account.status` `signed_up → identity_verified → kyc_submitted →
  suitability_assessed → financial_profile_completed → kyc_approved → mia_signed → active`.
- **Commands:** `IssueInvite` (compliance reviewer; `inv_invite`, 14-day expiry — DL-008/C20) ·
  `SignUp(invite_id,…)` (consumes invite) · `RecordIdentityVerified` (PAN + Aadhaar ← M5a) · `SubmitKyc`
  → **auto-approve** → `RecordKycApproved` · `AssessSuitability(mismatch=false)` ·
  `CompleteFinancialProfile(bank_account_last4)` · `RecordMiaSigned` (← M5c) · `ActivateInvestor`.
- **Gate to active (IA.3):** kyc approved ∧ suitability set ∧ bank_account_last4 set ∧ MIA signed.
- **Defers:** suitability mismatch + override-ack, NRI/institutional, penny-drop realism, KYC refresh.

### WS-4 · Invoice listed, priced, **gone live** (BC1 + BC3 + BC4) — maker-checker + MFA gate #1
- **Pre-req (BC3, minimal):** one active `risk_pricing_policy` matching `(buyer_id, tenor_bucket)`
  (`tenor_bucket` from `tenor_days`), a buyer credit limit, a supplier exposure cap — the three values
  BC1 snapshots.
- **Advances Invoice:** `deal_invoice.status` `submitted → ops_checks_in_progress → ops_checks_passed →
  listed`.
- **Advances Listing:** `deal_listing.status` `draft → operational_checks_in_progress →
  awaiting_acknowledgment → ready_for_review → live`.
  - **Acknowledgment:** the ack user acknowledges the invoice via OTP (per-invoice) →
    `awaiting_acknowledgment` cleared.
  - **Pricing snapshot at `ready_for_review` (G20/L.3, immutable):** freeze
    `pricing_snapshot{pricing_band_id, rate_bps, fee_bps}`, `buyer_limit_headroom_snapshot`,
    `supplier_exposure_cap_snapshot`, and **`funding_target`** =
    `face_value − face_value·rate_bps/10000·tenor_days/365 − face_value·fee_bps/10000` (L.7/DL-024,
    integer paise — define the rounding rule in the WS-4 plan and pin it in a DL-BE entry).
  - **Go-live — `ApproveGoLive(checker_id, mfa_assertion_id)`** (L.4): a **maker-checker record, checker
    ∈ Treasury & Settlement, `golive_checker_id ≠ golive_maker_id`, valid fresh `mfa_assertion_id`** —
    enforced in-app *and* by `deal_listing_golive_maker_ne_checker` + `deal_listing_golive_assertion_chk`.
    Emits `Listing.GoneLive`.
- **Advances Settlement (BC4):** on `Listing.GoneLive` → `CreateVirtualAccount` →
  `cash_virtual_account.status = created`, `expected_inflow_total = funding_target`, one VA per listing
  (`cash_virtual_account_listing_uq`).
- **Defers:** ops-check *failure*, acknowledgment failure, held-for-review, funding-window expiry, the
  4-eyes >₹1 Cr go-live variant.

### WS-5 · Subscription to 100% → **fully funded** (BC2 + M5b escrow inflow) — G10
- **Advances:** `sub_subscription.status` `committed → funds_pending → funds_received → confirmed`;
  `deal_listing.status` `live → fully_funded`.
- **Commands / flow:** `CommitSubscription(amount = funding_target)` — `amount ≥ ₹10 000` (`1 000 000`
  paise, S.1) and a **coordinated commit** that atomically bumps `deal_listing.committed_total`, blocked
  at commit time by `deal_listing_committed_lte_target` so over-subscription is impossible (X1/L.2/S.5).
  Stubbed escrow observes the inflow (← M5b) → `RecordInflowReconciled` (only *reconciled*, not the
  provisional observation, advances `funds_received` — V.4) → `ConfirmSubscription`
  (`expected_inflow_amount == amount`, paise — S.3). When `committed_total == funding_target` (strict
  paise equality, L.6) → `Listing.FullyFunded`.
- **G10 invariant to assert:** `Σ (confirmed sub.amount) = deal_listing.committed_total =
  cash_virtual_account.observed_inflow_total = funding_target` — all paise-exact (X3/V.2).
- **Defers:** multi-investor allocation, partial fills, funding shortfall + refund, pre-confirmation
  cancellation, concentration warnings.

### WS-6 · Assignment requested, single leg signed → **all signed** (BC5 + M5c) — gate to disbursement
- **Advances:** `legal_assignment_set.status` `requested → in_progress → all_signed`;
  `deal_listing.all_signed = true`.
- **Commands / flow:** on `Listing.FullyFunded` → `RequestAssignmentSet` (`total_count = 1` confirmed
  sub; `sign_deadline = now + 24h`, `legal_assignment_set_listing_uq`) → `InitiateLeg` (per-investor MIA
  `legal_master_agreement` must exist) → stubbed signing session (← M5c) → `RecordLegSigned`. When
  `signed_count == total_count` → `AssignmentSet.AllSigned` → flip `deal_listing.all_signed = true`
  (the C27 gate: **every investor signs before disbursement**).
- **Defers:** multi-leg coordination, the 24 h time-box abandonment branch (Trace C), per-invoice
  stamping (AS.7/G2, parked-legal).

### WS-7 · Disbursement drafted → approved → executed → **disbursed** (BC4 + M5b) — maker-checker + MFA gate #2
- **Advances:** `cash_payout_instruction.status` `drafted → approved → sent → executed`;
  `deal_listing.status` `fully_funded → disbursed`.
- **Commands / flow:** `DraftDisbursement` — T&S **maker**; allowed only when `deal_listing.status =
  fully_funded ∧ all_signed = true` (PI.2); `kind=disbursement`, `listing_id` set, `subscription_id`
  null (`cash_payout_instruction_kind_target_chk`). The `payout_instruction_id` PK **is** the bank
  vendor idempotency key (PI.1/C9), reused verbatim on retry. → `ApprovePayout(checker_id,
  mfa_assertion_id)` — T&S **checker ≠ maker, valid fresh MFA** (PI.5; `cash_payout_instruction_maker_
  ne_checker` + `…_checker_mfa_chk`) → `DisbursementInstructed` → stubbed escrow payout leg (← M5b) →
  `RecordPayoutExecuted` → `Listing.Disbursed`.
- **Defers:** partial/failed legs + remediation case, the EoD master-statement reconciliation overlay
  (PI.7/RL.3 — skeleton recon is a single matched provisional inflow), the distribution leg (WS-8).

---

## 5. Cross-cutting invariants this skeleton must prove

| Invariant | Where it bites | Asserted in |
|---|---|---|
| **Maker-checker** (proposer ≠ approver, at the command boundary) | go-live (WS-4) · payout approve (WS-7) | per-slice reject test: approver = maker ⇒ rejected (app + DB CHECK) |
| **MFA-fresh** (checker carries a valid `mfa_assertion_id`) | same two steps | per-slice reject test: stale/missing assertion ⇒ rejected |
| **Funding equality G10** | WS-5 | `Σ confirmed = committed_total = observed_inflow_total = funding_target`, paise-exact; over-subscription blocked at commit |
| **Idempotency** | every command (`command_id` via M4a) · inflow (vendor event ref, M5b) · payout (`payout_instruction_id`) | replay each ⇒ no double effect |
| **Audit chaining** | every state change → envelope before the command returns | E2E test re-reads `sys_audit_event` for the run's `correlation_id` and asserts an unbroken `previous_envelope_hash` chain across all hops |

---

## 6. Five non-negotiables — applicability (milestone level)

Every **state-changing** command across WS-1…WS-7 is all five. The two genuine maker-checker+MFA gates
are go-live and payout-approve; the onboarding `Record*`/`Activate*` commands are single-actor
state-machine advances that are still idempotent, SoD/role-checked, and audited (they are not two-party
approvals, so "maker-checker" for them means the command-boundary authorization, not a propose→approve
pair).

| # | Control | Applies | How / where |
|---|---|---|---|
| 1 | Maker-checker | yes | propose→approve on go-live (WS-4) & payout (WS-7); command-boundary authz on the rest |
| 2 | MFA-fresh | yes | checker's `mfa_assertion_id` on the two gates (M3b freshness) |
| 3 | SoD-checked | yes | M4c policy at every command boundary (e.g. T&S vs Ops vs Credit roles) |
| 4 | Idempotent on `command_id` | yes | M4a `sys_command_log` claim on every command |
| 5 | Audit-logged | yes | M2 envelope per command; the E2E chain assert is the headline |

---

## 7. Test scenarios (write these first)

- [ ] **Capstone — `WalkingSkeletonE2ETest`:** drive WS-1→WS-7 under one `correlation_id`; assert each
      hop's state **and** the unbroken audit chain ending at `deal_listing.status='disbursed'`.
- [ ] **WS-4 go-live maker-checker:** `golive_checker_id == golive_maker_id` ⇒ rejected (app + CHECK).
- [ ] **WS-4 go-live MFA:** missing/stale `mfa_assertion_id` ⇒ rejected.
- [ ] **WS-4 pricing snapshot immutability:** a later BC3 pricing change does **not** alter the listing's
      frozen `funding_target` / `pricing_snapshot`.
- [ ] **WS-5 G10 equality:** after confirm, `Σ confirmed = committed_total = observed_inflow_total =
      funding_target` to the paise; an over-subscribing commit ⇒ rejected at commit (DB CHECK fires).
- [ ] **WS-6 gate:** disbursement draft attempted while `all_signed=false` ⇒ rejected (PI.2).
- [ ] **WS-7 payout maker-checker + MFA:** approver = maker, or stale MFA ⇒ rejected.
- [ ] **Idempotent replay:** re-issuing the go-live, the subscription commit, and the payout approve with
      the same `command_id` is a no-op (state unchanged, no second envelope).

---

## 8. Explicitly deferred to Milestone 2 (widen each module to full rigor)

All reject/alternate paths: ops-check failure, acknowledgment failure, held-for-review, funding shortfall
+ refund, pre-confirmation cancellation, the 24 h assignment time-box abandonment, partial/failed payout
legs + remediation, the EoD master-statement reconciliation overlay, four-eyes (>₹1 Cr / >₹10 Cr),
suitability mismatch + override-ack, multi-investor allocation & concentration, per-invoice stamping,
real vendor sandboxes, the maturity → distribution → closed tail (**WS-8**, same maker-checker shape as
WS-7 with `kind=distribution` + TDS snapshot, G4), the real BC11 compliance engine (replaces the
WS auto-approve stub), and supplier/buyer/investor suspension/exit lifecycles.

---

## 9. Definition of Done (milestone)

- [ ] WS-1…WS-7 each: §7 per-slice tests green; `/code-review` on the diff; findings fixed; a `DL-BE-xxx`
      entry for each non-obvious decision (funding-target rounding, the coordinated-commit mechanism, the
      go-live/payout propose→approve wiring, the compliance auto-approve stub boundary).
- [ ] **Capstone `WalkingSkeletonE2ETest` green** — `listed → disbursed`, audit chain unbroken.
- [ ] Whole suite green (no regression to the foundation's 133).
- [ ] `DL-BE-029` finalized as-built (the skeleton strategy + the cross-cutting proofs).
- [ ] This spec flipped to **Status: Done**. Milestone 1 complete → Milestone 2 (widen Wave 1).

## 10. Watch-for (carry forward)

- **`funding_target` rounding** is real money — pick the rule (truncate vs round-half-even on the
  discount and fee terms) deliberately and pin it; a paise drift breaks G10's strict equality downstream.
- **Coordinated commit (X1/G27):** the `Subscription.Committed` + `Listing.committed_total` bump is one
  local DB transaction in the Phase-1 monolith — keep it atomic; over-subscription must be impossible by
  construction, not by check-then-act.
- **Generic domain maker-checker:** M4d's engine was built for admin-IAM; WS-4/WS-7 are the first
  *domain* propose→approve uses. If a clean reuse needs an extraction, do it (decision-logged) before
  WS-4 — mirror the M5 `AbstractAclService` move.
- **`correlation_id` threading:** the E2E audit-chain assert only works if every command on the run
  carries the same correlation id; establish the threading convention in WS-1 and hold it throughout.
- **Compliance stub boundary:** the auto-approve must sit behind the *same* command (`RecordKycApproved`)
  the real BC11 engine will call at M15, so the swap is a one-place change — not scattered `if (stub)`.
```
