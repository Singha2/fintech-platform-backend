# B3 — Aggregates & Invariants

*Phase 1 MVP. For every bounded context in B1, the aggregates that live inside it, the state each aggregate carries, the invariants it enforces, and the commands that drive its lifecycle. Inputs: DL-001 through DL-050; A1 constraints C1–C28; A2 integration contracts; B1 contexts BC1–BC19; B2 event catalogue and envelope; Gap Log G1–G21 (existing) and G22–G26 (proposed in B2 §6). Output: anchor for B4 (Command/Query APIs) and component decomposition.*

---

## 1. Principles

**P1. One aggregate, one consistency boundary.** Each aggregate is the unit of transactional consistency. A single command on a single aggregate is the only place where multiple state changes can be made atomic. Everything else is reconciled via events (B2 P1).

**P2. Hard invariants live inside one aggregate; cross-aggregate rules are soft by construction.** A hard invariant is enforced inside the aggregate's transactional boundary — the aggregate cannot persist in a state that violates it. A soft invariant is restored eventually by subscribers, reconciliation, or time-boxed escalation. When a rule would otherwise span two aggregates, B3 chooses one of three reductions: (a) move the rule wholly inside one aggregate by widening its state, (b) make the rule soft and name its restorer, or (c) coordinate atomically in-process under G17/G27 and document the Phase 2 saga path.

**P3. Identity is immutable and globally unique.** Every aggregate root carries a UUIDv7 identity minted at creation. Identity never changes; renames are new aggregates plus a `corrects` link (B2 §5.3, G23). Cross-context references are by identity only (B1 "B3 (Aggregates)" rule).

**P4. Small aggregates by default.** Target ≤ 7 state fields and ≤ 5 commands. Any aggregate exceeding either threshold carries an explicit justification in its row; the bar is "could this be split without forcing a cross-aggregate hard invariant?" If the answer is no, the size is structural.

**P5. State is what enforces invariants.** A field belongs in the aggregate's state if and only if (a) it is part of identity, (b) it is the lifecycle/status field, (c) it is needed to evaluate a hard invariant on a future command, or (d) it is a snapshot locked at a state transition (e.g. G20). Everything else lives in projections (the read side) and is rebuilt from the event stream.

**P6. Commands match B2 event names.** Every command names the past-tense event it emits on success. Where a command can produce one of several outcomes, all outcomes are named. Commands themselves are intentions (B2 P2) — they are not events. Failed-but-authorised commands emit `CommandRejected` per G22.

**P7. Actor types come from B2 §2.2.** No new actor type is introduced in B3. Where an admin user is the actor, the responsible role (Ops Executive, Credit Reviewer, Compliance Reviewer, Treasury & Settlement, Super Admin — Spec §3.1) is named. The role gates the command; B2 §2.2's `actor_type=admin_user` is the envelope category.

**P8. Concurrency control is optimistic on `aggregate_version`.** Every write carries the expected version (B2 §2.1); a mismatch causes the command handler to retry or fail explicitly. Producers' uniqueness on `(actor_id, command_id)` (G18) protects against double-application; `aggregate_version` protects against lost-update across concurrent commands.

---

## 2. Aggregate Catalogue

By owning bounded context, matching the BC numbering and headings of B1 §1 and B2 §3. Each aggregate is given a row of attributes (root identity, state fields, child entities, events emitted from B2) followed by an invariants table and a commands table. Cross-aggregate consequences are deferred to §3.

### 2.1 BC1 — Listing & Invoice

Two aggregates. `Invoice` is upstream (raw fact, operational checks); `Listing` is the central state machine that coordinates with six other contexts (B1 §4.2). Cross-aggregate consistency between Invoice and Listing is intra-context and resolved at draft time.

#### 2.1.1 Invoice

| | |
|---|---|
| Root identity | `invoice_id : UUIDv7` |
| State fields | `supplier_id`, `buyer_id`, `irn?:IRN`, `invoice_number`, `face_value:Money`, `tenor_days`, `due_date:BusinessDate`, `status:{submitted\|ops_checks_in_progress\|ops_checks_passed\|ops_checks_failed\|listed}`, `check_outcomes:map<check_name,outcome>` |
| Child entities | none — `check_outcomes` are value objects (see §2.1 note) |
| Events emitted | `Invoice.Submitted`, `Invoice.OperationalChecksPassed`, `Invoice.OperationalChecksFailed` (B2 §3.1) |

State-field count (9) exceeds P4's target; justification: an invoice carries authoritative source-of-truth data (DL-016, C24) that the Credit-side snapshot mechanism reads at listing draft. Splitting "invoice identity" from "operational check outcomes" would force a cross-aggregate consistency rule between two records always written together. Held as one aggregate.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| INV.1 | Hard | `(supplier_id, irn)` is unique when `irn` is present; `(supplier_id, buyer_id, invoice_number, face_value, tenor_days)` is unique when `irn` is null (manual fallback). Duplicate-check (DL-027) | DL-016, DL-027, C24 |
| INV.2 | Hard | `face_value > 0`; `tenor_days ∈ [1, 180]` (Spec §6.4 implicit upper bound) | DL-016 |
| INV.3 | Hard | `due_date == invoice_date + tenor_days` honouring shared-kernel BusinessDate (B1 §2) | DL-016 |
| INV.4 | Hard | `status` transitions follow `submitted → ops_checks_in_progress → {ops_checks_passed, ops_checks_failed}; ops_checks_passed → listed` only. Backward transitions forbidden | DL-027, Spec §6.4 step 1–2 |
| INV.5 | Hard | `status=ops_checks_passed` requires every check in the BC1-defined operational-check set has `outcome=passed` (DL-027 enumerates: IRN validity, e-way bill match, buyer-supplier relationship, duplicate check, supplier exposure cap, buyer limit headroom, document completeness) | DL-027, C24 |
| INV.6 | Soft | `supplier_id.status=active` and `buyer_id.status=active` at submission (reconciled by BC8/BC9 subscribers; if either becomes suspended after submission, ops checks fail at C24-verification step) | DL-014, DL-018 |
| INV.7 | Hard | IRN value, when present, is verified via BC17 (`verify_irn`) before `ops_checks_passed`. Self-attested IRN never accepted | C24, A2 §1.3 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `SubmitInvoice(irn? \| manual_fields, face_value, tenor_days, buyer_id, doc_hash)` | `supplier_user` or `agency` (DL-013, G5) | INV.1, INV.2, INV.3, INV.6 | `Invoice.Submitted` |
| `RecordOperationalCheckOutcome(check_name, outcome, evidence_ref)` | `admin_user`[Ops Executive] or `system_scheduler` (verification webhook subscriber) | INV.4 (transition only) | none directly; on completeness triggers `RunChecksCompletionCheck` |
| `RunChecksCompletionCheck()` | `system_scheduler` (causation: every `RecordOperationalCheckOutcome`) | INV.4, INV.5, INV.7 | `Invoice.OperationalChecksPassed` or `Invoice.OperationalChecksFailed` |

Three commands; under P4 ceiling.

#### 2.1.2 Listing

| | |
|---|---|
| Root identity | `listing_id : UUIDv7` |
| State fields | `invoice_id`, `supplier_id`, `buyer_id`, `status:enum(B1 §6.4)`, `pricing_snapshot:{pricing_band_id, rate_bps, fee_bps, snapshot_at}`, `buyer_limit_headroom_snapshot:Money`, `supplier_exposure_cap_snapshot:Money`, `funding_target:Money`, `committed_total:Money`, `funding_window_close_at:timestamp`, `va_id?`, `all_signed:bool`, `terminal_outcome?:{distributed\|funding_failed_refunded\|cancelled_pre_disbursement\|defaulted}` |
| Child entities | none — operational-check evidence lives on the upstream Invoice; assignment legs live in BC5 |
| Events emitted | `Listing.Drafted`, `…AcknowledgmentRequested`, `…AcknowledgmentReceived`, `…AcknowledgmentFailed`, `…PricingApplied`, `…SnapshotTaken`, `…ReadyForReview`, `…GoneLive`, `…FullyFunded`, `…FundingShortfallDeclared`, `…DisbursementGateOpened`, `…Disbursed`, `…Matured`, `…MaturityShortfall`, `…Distributed`, `…Closed`, `…HeldForReview`, `…CancelledPreDisbursement` (B2 §3.1) |

State-field count (13) and command count (15+) exceed P4 thresholds. Justification: `Listing` is the platform's central state-machine aggregate (Spec §6.4 has 11 nominal states plus exit/alternate branches). Each state transition is a command; each snapshot field (G20) is a hard locked-at-transition value. Decomposing `Listing` into smaller aggregates (e.g. `FundingWindow`, `DisbursementWorkflow`) would force cross-aggregate hard invariants — most critically the `committed_total ≤ funding_target` rule (L.2, G10) — which P2 forbids. The aggregate is structurally large because the domain *is* the listing lifecycle.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| L.1 | Hard | `status` transitions follow Spec §6.4 state machine. No skipping; no backward moves. Terminal states (`distributed`, `funding_failed_refunded`, `cancelled_pre_disbursement`, `defaulted`) accept no further commands except `Close` if not already closed | Spec §6.4 |
| L.2 | Hard | `committed_total ≤ funding_target` at all times. New commitments rejected if they would breach (G10 — over-subscription prevented at commit time). See §3 rule X1 on the coordinated commit | DL-017, C12, G10 |
| L.3 | Hard | Once `status` reaches `ready_for_review`, the four snapshot fields (`pricing_snapshot`, `buyer_limit_headroom_snapshot`, `supplier_exposure_cap_snapshot`, `funding_target`) are immutable for the life of the listing | G20 |
| L.4 | Hard | `GoneLive` transition requires a maker-checker record where checker ∈ Treasury & Settlement and checker ≠ maker; checker holds a valid `mfa_assertion_id` (B2 §2.2) | C4, C7, Spec §4.1 step 5 |
| L.5 | Hard | `DisbursementGateOpened` requires `all_signed = true` (set by subscriber to BC5 `AssignmentSet.AllSigned`) | C27, DL-002 |
| L.6 | Hard | `FullyFunded` transition requires `committed_total == funding_target` (strict equality on paise — G10) | DL-017, C12, G10 |
| L.7 | Hard | `funding_target = invoice.face_value − (face_value × pricing_snapshot.rate_bps / 10000 × tenor_days/365) − (face_value × pricing_snapshot.fee_bps / 10000)`, frozen at `SnapshotTaken` (rate is the discount rate, applied pro-rata over tenor; fee is flat-bps) | DL-024, G20 |
| L.8 | Hard | `funding_window_close_at = GoneLive_occurred_at + 5 business days` per shared-kernel BusinessDate (B1 §2). Funding shortfall declared by scheduler at exactly that instant | DL-017, C12 |
| L.9 | Hard | After `funding_window_close_at`, `Subscription.Commit` is rejected regardless of headroom | C12, DL-017 |
| L.10 | Soft | `pricing_snapshot.rate_bps` is within the Credit-published band for `(buyer_id, tenor_bucket)` at snapshot time. Restored by BC3 PricingBand consistency rule (§3) | DL-024, BC3 |
| L.11 | Soft | Buyer is `active` and supplier is `active` at `GoneLive`. Restored by BC8/BC9 subscribers; if either suspended in-flight, listing is held by `HoldForReview` | DL-014, DL-018 |
| L.12 | Hard | `va_id` is set exactly once (at `GoneLive`-driven BC4 `VirtualAccount.Created` subscriber), never updated, never cleared until terminal `Close` | C8, DL-043 |
| L.13 | Hard | `Listing.MaturityShortfall` is the only path that emits an inflow-less-than-expected fact; this branches to BC6 collections rather than to BC4 distribution | Spec §4.2, DL-029 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `DraftListing(invoice_id)` | `admin_user`[Ops Executive] or `system_scheduler` (subscriber to `Invoice.OperationalChecksPassed`) | INV.5 on Invoice; supplier+buyer active (L.11) | `Listing.Drafted` |
| `RequestBuyerAcknowledgment(ack_user_id, sla_hours)` | `admin_user`[Ops Executive] | L.1 (`drafted → awaiting_acknowledgment`) | `Listing.AcknowledgmentRequested` |
| `RecordBuyerAcknowledgment(ack_method, evidence_ref?)` | `buyer_ack_user` or `admin_user`[Ops Executive] (admin-captured per DL-019) | L.1 | `Listing.AcknowledgmentReceived` or `Listing.AcknowledgmentFailed` |
| `ApplyPricing()` | `system_scheduler` (causation: ack-received) | L.1; reads BC3 PricingBand by `(buyer_id, tenor_bucket)` | `Listing.PricingApplied` |
| `TakeSnapshot()` | `system_scheduler` (causation: pricing-applied) | L.1; reads BC3 BuyerCreditLimit & SupplierExposureCap | `Listing.SnapshotTaken` then `Listing.ReadyForReview` |
| `ApproveGoLive(checker_id, mfa_assertion_id)` | `admin_user`[Treasury & Settlement] | L.4, L.7, L.11 | `Listing.GoneLive` |
| `AcceptSubscriptionCommitment(subscription_id, amount)` | invoked internally by BC2 `Subscription.CommitSubscription` handler (coordinated, §3 X1) | L.2, L.9; status=`live` | none (state-only side-effect on `committed_total`); `Listing.FullyFunded` if total hits target |
| `ReleaseSubscriptionCommitment(subscription_id, amount)` | invoked internally by BC2 cancel/refund-eligible handler | status ∈ {`live`, `fully_funded`} | none directly; can cause re-`live` transition |
| `DeclareFundingShortfall()` | `system_scheduler` (deterministic at L.8 boundary) | L.1, L.8; `committed_total < funding_target` | `Listing.FundingShortfallDeclared` |
| `OpenDisbursementGate()` | subscriber to BC5 `AssignmentSet.AllSigned` | L.5 | `Listing.DisbursementGateOpened` |
| `RecordDisbursement(supplier_payout_ref, net_amount, utr)` | subscriber to BC4 `Disbursement.Executed` | L.1 (`fully_funded` → `disbursed`) | `Listing.Disbursed` |
| `RecordMaturity(buyer_payment_ref, amount)` | subscriber to BC4 `InflowReconciled` (matched to listing maturity) | L.13 (full vs partial branch) | `Listing.Matured` or `Listing.MaturityShortfall` |
| `RecordDistribution(outcome)` | subscriber to BC4 `Distribution.Completed` | L.1 | `Listing.Distributed` |
| `Close(terminal_outcome)` | `system_scheduler` (causation: distribution or default or refund cycle complete) | L.1 (terminal) | `Listing.Closed` |
| `HoldForReview(reason)` | subscriber to BC5 `AssignmentSet.Incomplete` (G13) or BC8/BC9 suspension | L.1, L.11 | `Listing.HeldForReview` |
| `CancelPreDisbursement(reason)` | `admin_user`[Credit Reviewer + Treasury & Settlement maker-checker] | L.1 (only from `fully_funded`, `held_for_review`) | `Listing.CancelledPreDisbursement` |

Sixteen commands. Per P4 justification above, the state-machine cardinality is the domain's; the count is not reducible without re-introducing cross-aggregate hard rules.

---

### 2.2 BC2 — Subscription

One aggregate per the B1 inventory; one is sufficient because the per-investor commitment has a single linear lifecycle (Spec §6.5).

#### 2.2.1 Subscription

| | |
|---|---|
| Root identity | `subscription_id : UUIDv7` |
| State fields | `listing_id`, `investor_id`, `amount:Money`, `status:{committed\|funds_pending\|funds_received\|confirmed\|assignment_executed\|distribution_received\|closed\|cancelled_by_investor\|refunded\|loss_realised}`, `expected_inflow_amount:Money`, `actual_inflow_txn_ref?:MoneyMovementRef`, `assignment_doc_hash?:bytes32`, `distribution_outcome?:{gross,tds,fee,net:Money}`, `concentration_warnings_at_commit:[warning]`, `wallet_attribution?:string` (dormant Phase 1 per B1 §5.4) |
| Child entities | none |
| Events emitted | `Subscription.Committed`, `…CancelledByInvestor`, `…FundsReceived`, `…Confirmed`, `…AssignmentExecuted`, `…RefundEligible`, `…Refunded`, `…DistributionReceived`, `…Closed`, `…LossRealised`, `…SoftConcentrationWarningRaised` (B2 §3.2) |

State-field count (11) and command count (9) exceed P4. Justification: Subscription is the per-investor lens on the full listing lifecycle. Splitting "commit/funds phase" from "post-disbursement phase" would force cross-aggregate consistency between two records describing one investor's one position. Distribution-money fields are necessary because BC12 (Tax & Reporting) reads them to issue Form 16A (DL-045).

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| S.1 | Hard | `amount ≥ ₹10,000` (paise: 1,000,000) at commit | DL-007 |
| S.2 | Hard | `status` transitions follow Spec §6.5. `cancelled_by_investor` allowed only from `committed` or `funds_pending` (i.e. before `funds_received`). `refunded` reachable from `{committed, funds_pending, funds_received, confirmed, assignment_executed}` per G3 + G13 | Spec §6.5, G3, G13 |
| S.3 | Hard | `Confirmed` requires `expected_inflow_amount == amount` (paise-equality) and `actual_inflow_txn_ref` is set and reconciled | G10, C23 |
| S.4 | Hard | At `Committed`, `investor_id.status=active` (read from BC7 at command-handler time) | DL-005, DL-008 |
| S.5 | Hard | At `Committed`, the host `Listing.status=live` and `Listing.committed_total + amount ≤ Listing.funding_target`. Enforced via the coordinated commit pattern in §3 X1 | L.2, L.9, G10 |
| S.6 | Hard | `AssignmentExecuted` requires the assignment's BC5 `AssignmentSet.AllSigned` to have fired for this listing; recorded `assignment_doc_hash` is the per-investor doc the BC19 vendor returned | C27, DL-002 |
| S.7 | Hard | `LossRealised` reachable only after BC3 `DefaultCase.Classified(outcome=defaulted)` and BC6 recovery is exhausted (no future `Recovery.Achieved` expected) | DL-029, Spec §6.5 |
| S.8 | Soft | Concentration warnings (per-buyer, per-supplier, per-invoice) computed at commit using a read-side projection of the investor's positions; recorded in `concentration_warnings_at_commit` but never block. `Subscription.SoftConcentrationWarningRaised` emitted per dimension breached | DL-011, G30 (new) |
| S.9 | Hard | `distribution_outcome.gross − distribution_outcome.tds − distribution_outcome.fee == distribution_outcome.net` (paise-equality) | DL-045, G4 |
| S.10 | Hard | `wallet_attribution` is null in Phase 1 (B1 §5.4); set only after Phase 2 wallet feature activates | DL-009, C26 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `CommitSubscription(listing_id, amount)` | `investor` | S.1, S.2, S.4, S.5 (coordinated with BC1 — §3 X1) | `Subscription.Committed`; possibly `Subscription.SoftConcentrationWarningRaised` (×N) |
| `CancelByInvestor(reason)` | `investor` | S.2 (`status ∈ {committed, funds_pending}`) | `Subscription.CancelledByInvestor`; triggers Listing release (§3 X1) |
| `RecordFundsReceived(txn_ref, amount)` | subscriber to BC4 `InflowReconciled(matched_to=this subscription)` | S.3 (amount paise-equality) | `Subscription.FundsReceived` then `Subscription.Confirmed` if all checks pass |
| `MarkRefundEligible(reason)` | subscriber to BC1 `FundingShortfallDeclared` / `CancelledPreDisbursement`, or BC5 `AssignmentSet.Incomplete` (G13) | S.2 | `Subscription.RefundEligible` |
| `RecordRefund(refund_ref, utr)` | subscriber to BC4 `Refund.Executed` | S.2 | `Subscription.Refunded` then `Subscription.Closed` |
| `RecordAssignmentExecuted(assignment_doc_hash)` | subscriber to BC5 `AssignmentSet.AllSigned` (narrowed to this investor) | S.6 | `Subscription.AssignmentExecuted` |
| `RecordDistribution(gross, tds, fee, net, utr)` | subscriber to BC4 `DistributionLeg.Executed` (narrowed to this investor) | S.9 | `Subscription.DistributionReceived` |
| `RecordLoss(loss_amount)` | subscriber to BC3 `DefaultCase.Classified(outcome=defaulted)` after recovery window exhausted | S.7 | `Subscription.LossRealised` then `Subscription.Closed` |
| `Close()` | `system_scheduler` (causation: refund, distribution, or loss terminal) | S.2 (terminal) | `Subscription.Closed` |


---

### 2.3 BC3 — Credit & Underwriting

Four aggregates per B1. Credit policy is policy-driven (DL-022, DL-024) — the aggregates here own *policy values* that other contexts snapshot at use time (G20). Hard invariants stay local (four-eyes-threshold C6, monotonicity of approvals); cross-aggregate effects on listings flow only via snapshots (§3 X4).

#### 2.3.1 BuyerCreditProfile

| | |
|---|---|
| Root identity | `buyer_id` (shared with BC9; BC3 holds the credit-side projection — see §3 note on shared identifier) |
| State fields | `sector`, `rating_source`, `rating`, `credit_limit:Money`, `tenor_cap_days`, `conditions:[…]`, `last_review_at`, `four_eyes_approval_ref?` |
| Child entities | none |
| Events emitted | `BuyerCreditProfile.Created`, `BuyerCreditLimit.Set`, `PeriodicCreditReview.Completed` (B2 §3.3) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| BCP.1 | Hard | `credit_limit > 0`; `tenor_cap_days ∈ [1, 180]` | DL-022 |
| BCP.2 | Hard | Any `BuyerCreditLimit.Set` with `new_limit > ₹1,00,00,000` requires `four_eyes_approval_ref` pointing to a `FourEyesApproval.Granted` envelope where `second_approver ≠ first_approver` | C6, DL-023 |
| BCP.3 | Hard | Limit reductions emit `BuyerLimit.Reduced` to BC9; subsequent listings snapshot the reduced value (G20); in-flight listings keep their snapshot | DL-022, G20 |
| BCP.4 | Soft | `last_review_at` ≤ 12 months ago for active buyers (BC3 scheduler reminds; non-blocking) | DL-022 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `CreateBuyerCreditProfile(buyer_id, sector, rating, …)` | `admin_user`[Credit Reviewer] | BCP.1 | `BuyerCreditProfile.Created` |
| `SetBuyerCreditLimit(new_limit, tenor_cap, conditions, four_eyes_approval_ref?)` | `admin_user`[Credit Reviewer]; if `>₹1 Cr`, also `admin_user`[Credit Reviewer or second-approver as Founder/CEO] | BCP.1, BCP.2 | `BuyerCreditLimit.Set` |
| `CompletePeriodicReview(outcome)` | `admin_user`[Credit Reviewer] | BCP.4 | `PeriodicCreditReview.Completed` |

#### 2.3.2 SupplierCreditProfile

| | |
|---|---|
| Root identity | `supplier_id` (shared with BC8) |
| State fields | `risk_rating`, `exposure_cap:Money`, `conditions:[…]`, `last_review_at`, `four_eyes_approval_ref?` |
| Child entities | none |
| Events emitted | `SupplierCreditProfile.Approved`, `SupplierExposureCap.Changed`, `PeriodicCreditReview.Completed` (B2 §3.3) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SCP.1 | Hard | `exposure_cap ≥ 0` | DL-022 |
| SCP.2 | Hard | Any `SupplierExposureCap.Changed` with `new > ₹1,00,00,000` requires `four_eyes_approval_ref` | C6, DL-023 |
| SCP.3 | Soft | Same 12-month review cadence as BCP.4 | DL-022 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `ApproveSupplierCreditProfile(risk_rating, exposure_cap, four_eyes_approval_ref?)` | `admin_user`[Credit Reviewer] (+second approver if > ₹1 Cr) | SCP.1, SCP.2 | `SupplierCreditProfile.Approved` |
| `ChangeSupplierExposureCap(new, reason, four_eyes_approval_ref?)` | `admin_user`[Credit Reviewer] (+second approver if > ₹1 Cr) | SCP.1, SCP.2 | `SupplierExposureCap.Changed` |
| `CompletePeriodicReview(outcome)` | `admin_user`[Credit Reviewer] | SCP.3 | `PeriodicCreditReview.Completed` |

#### 2.3.3 PricingPolicy (PricingBand)

| | |
|---|---|
| Root identity | `pricing_band_id : UUIDv7` |
| State fields | `buyer_id`, `tenor_bucket:{≤30d \| 31–60d \| 61–90d \| 91–180d}`, `rate_range_bps:{min,max}`, `fee_bps`, `effective_from:BusinessDate`, `superseded_by?:pricing_band_id` |
| Child entities | none |
| Events emitted | `PricingBand.Set` (B2 §3.3) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| PB.1 | Hard | `rate_range_bps.min ≤ rate_range_bps.max`; `fee_bps ≥ 0`; `rate_range_bps.min > 0` | DL-024 |
| PB.2 | Hard | For each `(buyer_id, tenor_bucket)` at any point in time, exactly one band is current (`effective_from ≤ today` and `superseded_by` is null or its effective date is in the future). Supersession is via `superseded_by` chain | DL-024 |
| PB.3 | Hard | Past `PricingBand` records are immutable. A re-pricing creates a new band and sets the prior's `superseded_by`; in-flight Listings continue using their snapshot (G20) | DL-024, G20 |
| PB.4 | Soft | When a new band is published, BC1's Listing snapshots taken before the new band's `effective_from` are unaffected (enforced by G20 in BC1) | G20 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `SetPricingBand(buyer_id, tenor_bucket, rate_range_bps, fee_bps, effective_from)` | `admin_user`[Credit Reviewer] | PB.1, PB.2, PB.3 | `PricingBand.Set` |

One command; under P4.

#### 2.3.4 DefaultCase

| | |
|---|---|
| Root identity | `case_id : UUIDv7` |
| State fields | `listing_id`, `status:{requested\|under_adjudication\|classified}`, `outcome?:{disputed\|dilution\|fraud\|defaulted\|recovered}`, `rationale_doc_hash`, `classified_at?`, `four_eyes_approval_ref?` |
| Child entities | none |
| Events emitted | `DefaultCase.Classified`, `CreditException.Adjudicated`, `FourEyesApproval.Granted`, `FourEyesApproval.Denied` (B2 §3.3) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| DC.1 | Hard | Classification requires the prior `Classification.Requested` from BC6 (causation chain) | DL-029, B2 trace D |
| DC.2 | Hard | If the listing's outstanding exposure exceeds ₹1 Cr, `four_eyes_approval_ref` must be present | C6, DL-023 |
| DC.3 | Hard | `outcome` is set exactly once; re-classification creates a new envelope with `corrects` pointing at the prior (G23) | DL-029, B2 P6, G23 |
| DC.4 | Hard | No automatic time-based default declaration — case advances only on explicit `Classify` command (DL-029) | DL-029 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `OpenCase(listing_id, prior_evidence_ref)` | subscriber to BC6 `Classification.Requested` | DC.1 | `CreditException.Adjudicated` (initial open) |
| `ProposeClassification(outcome, rationale_doc_hash)` | `admin_user`[Credit Reviewer] (maker) | DC.4 | (internal state only) |
| `Classify(outcome, four_eyes_approval_ref?)` | `admin_user`[Credit Reviewer] (checker, distinct from maker — C4) | DC.1, DC.2, DC.3, DC.4 | `DefaultCase.Classified` |
| `ReClassify(new_outcome, corrects_event_id, four_eyes_approval_ref?)` | `admin_user`[Credit Reviewer + second-approver] | DC.3, DC.2 | `DefaultCase.Classified` with `corrects` field |

Note on `FourEyesApproval`: emitted as part of any command above that crosses the ₹1 Cr threshold (and from BC3 BuyerCreditLimit / SupplierExposureCap commands). It is not a standalone aggregate — it is a workflow primitive recorded as an envelope per C6 and referenced by `four_eyes_approval_ref` in the originating command's emitted event. Hosting it as its own aggregate would force a cross-aggregate hard invariant (originating change cannot persist without the approval reference), which is correctly modelled as an *intra-command* check on the originating aggregate.

---

### 2.4 BC4 — Settlement

Three aggregates per B1 plus a fourth introduced here for the `VirtualAccount` (a BC4 view of the escrow VA, distinct from BC18's `VendorInstruction` ACL aggregate). Cross-aggregate consistency (e.g. reconciliation across the ledger and per-instruction states) is the central concern of BC4 and is documented via the soft rules in §3.

#### 2.4.1 VirtualAccount

| | |
|---|---|
| Root identity | `va_id : UUIDv7` (platform-minted; the bank-side IFSC+account_no are observed values) |
| State fields | `listing_id`, `status:{requested\|created\|closed}`, `ifsc?`, `account_no?`, `created_at?`, `closed_at?`, `expected_inflow_total:Money`, `observed_inflow_total:Money` |
| Child entities | none |
| Events emitted | `VirtualAccount.Created`, `VirtualAccount.Closed`, `InflowObserved`, `InflowReconciled`, `InflowUnmatched`, `Reconciliation.DiscrepancyDetected` (B2 §3.4) |

State-field count (8) marginally exceeds P4. Justification: `expected_inflow_total` and `observed_inflow_total` are the running ledger needed to evaluate the per-VA conservation invariant (V.2). Removing them would push the conservation rule cross-aggregate.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| V.1 | Hard | One `VirtualAccount` per `listing_id` (per-listing segregation) | C8, DL-043 |
| V.2 | Hard | `observed_inflow_total = Σ(reconciled inflows on this VA)`; `expected_inflow_total = Σ(active subscription amounts) + (post-disbursement) buyer maturity expected`. Discrepancy at EoD (G6) triggers `Reconciliation.DiscrepancyDetected` and a RemediationCase. The running ledger is updated on each reconciled inflow; mismatches are not silently dropped | C23, G6, G11 |
| V.3 | Hard | Transition `created → closed` only after listing is in a terminal state and all instructed payouts are executed or refunded | C8, DL-043 |
| V.4 | Hard | `InflowObserved` is provisional (B2 §3.4 `provisional:true`); only `InflowReconciled` advances downstream aggregate state (BC2 `Subscription.FundsReceived`, BC1 `Listing.Matured`) | C23, G6, G23 |
| V.5 | Soft | No commingling — a single VA serves exactly one listing. Restored if reconciler detects mismatched-VA inflow (rare; treated as remediation) | C8 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `RequestCreation(listing_id)` | subscriber to BC1 `Listing.GoneLive` | V.1 | (issues BC18 `instruct_va_create`) |
| `RecordCreation(ifsc, account_no)` | subscriber to BC18 `Va.LifecycleObserved(created)` | V.1 | `VirtualAccount.Created` |
| `RecordInflow(txn_ref, amount, sender_details, utr)` | subscriber to BC18 `InflowWebhookProcessed` | V.4 | `InflowObserved` |
| `Reconcile(business_date)` | `system_scheduler` (EoD batch — G6) | V.2; matches `InflowObserved` against expected subscription/maturity records | `InflowReconciled` (×N), `InflowUnmatched` (×M), `Reconciliation.DiscrepancyDetected` (if mismatch) |
| `Close()` | subscriber to listing terminal | V.3 | `VirtualAccount.Closed` (issues BC18 `instruct_va_close` first) |

#### 2.4.2 PayoutInstruction

| | |
|---|---|
| Root identity | `payout_instruction_id : UUIDv7` (mints `client_instruction_id` for BC18 — same UUID) |
| State fields | `kind:{disbursement\|distribution\|refund}`, `listing_id` or `subscription_id`, `status:{drafted\|approved\|sent\|executed\|partial\|failed\|completed}`, `payload:{legs?:[Leg], tds_snapshot?:[per_investor], total_tds?:Money, gross:Money, net:Money, fee:Money}`, `maker_id`, `checker_id?`, `four_eyes_approval_ref?` |
| Child entities | `Leg` per-investor for distribution kind: `{leg_index, investor_id, gross, tds, fee, net, status, utr?, failure_code?}` |
| Events emitted | `DisbursementInstructed`, `Disbursement.Executed`, `DistributionInstructed`, `DistributionLeg.Executed`, `DistributionLeg.Failed`, `Distribution.Completed`, `RefundInstructed`, `Refund.Executed`, `TdsChallanRecorded` (B2 §3.4) |

State-field count (9) and command count (6) exceed P4. Justification: PayoutInstruction is the platform-side record of every fund movement; the legs child collection is necessary to satisfy G11 (per-leg tracking and Manual Remediation). All idempotency on `(actor_id, command_id)` (G18) and `client_instruction_id` (C9, A2 §2.3) is keyed on this aggregate.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| PI.1 | Hard | Every fund-movement instruction carries `client_instruction_id` (idempotency key); replay of the same `(actor_id, command_id)` produces the same `payout_instruction_id` | C9, G18, A2 §2.3 |
| PI.2 | Hard | `kind=disbursement` is allowed only when `Listing.status=fully_funded` AND `Listing.all_signed=true` AND `Listing.DisbursementGateOpened` has fired | C27, L.5 |
| PI.3 | Hard | `kind=distribution` payload carries the TDS snapshot from BC12 (`Tds.Calculated`); per-leg `gross − tds − fee = net`; sum-of-legs `net + total_tds + total_fee = listing.matured_amount` (paise-equality) | DL-045, G4 |
| PI.4 | Hard | `kind=refund` is allowed when the matching `Subscription.RefundEligible` has fired (BC2) | G3, DL-017 |
| PI.5 | Hard | `status` transitions: `drafted → approved → sent → (executed \| partial \| failed) → completed`. The `approved` step requires a maker-checker pair where checker ∈ Treasury & Settlement and `checker ≠ maker` (C4); checker holds a valid MFA assertion (C7) | C4, C7, DL-030 |
| PI.6 | Hard | A `partial` status (some legs failed) cannot transition to `completed` until every failed leg has a linked `RemediationCase` and that case is resolved (G11). Failed legs are never silently dropped | G11 |
| PI.7 | Hard | Webhook-driven status changes are *provisional* until EoD reconciliation overlay confirms (`Disbursement.Executed` / `DistributionLeg.Executed` are final only after match against `MasterStatement.Fetched`) | C23, G6 |
| PI.8 | Hard | T+1 timing: `kind=disbursement` is instructed within T+1 business days of `Listing.FullyFunded`; `kind=distribution` within T+1 of `Listing.Matured`. SLA fields enforced at command-handler level using BusinessDate calculator | C11, DL-030 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `DraftDisbursement(listing_id, gross, fee, net, supplier_payout_ref)` | `admin_user`[Treasury & Settlement] (maker) | PI.1, PI.2, PI.5 (`drafted`) | (internal state only) |
| `ApprovePayout(checker_id, mfa_assertion_id)` | `admin_user`[Treasury & Settlement] (checker, ≠ maker) | PI.5 (`approved`), C4 | `DisbursementInstructed` / `DistributionInstructed` / `RefundInstructed` (per kind) |
| `DraftDistribution(listing_id, legs[], tds_snapshot, total_tds, total_fee)` | `admin_user`[Treasury & Settlement] (maker) | PI.1, PI.3, PI.5 | (internal state only) |
| `DraftRefund(subscription_id, refund_amount)` | `admin_user`[Treasury & Settlement] (maker) or `system_scheduler` (auto-queued from `RefundEligible`) | PI.1, PI.4, PI.5 | (internal state only) |
| `RecordLegOutcome(leg_index, outcome, utr?, failure_code?)` | subscriber to BC18 `PayoutLegWebhookProcessed` | PI.6, PI.7 | `DistributionLeg.Executed` or `DistributionLeg.Failed`; if all legs terminal, `Distribution.Completed` |
| `RecordExecution(utr)` | subscriber to BC18 `PayoutLegWebhookProcessed` (single-leg disbursement) or `RefundWebhookProcessed` | PI.7 | `Disbursement.Executed` or `Refund.Executed` |
| `RecordTdsChallan(challan_ref, total_tds)` | subscriber to BC18 `TdsChallanWebhookProcessed` | PI.3 | `TdsChallanRecorded` |
| `Complete()` | `system_scheduler` (causation: all legs terminal + remediation cases resolved) | PI.5, PI.6 | (internal terminal) |

Eight commands. Justified above.

#### 2.4.3 ReconciliationLedger

| | |
|---|---|
| Root identity | `business_date:BusinessDate` (one ledger per business date — natural identity) |
| State fields | `status:{open\|reconciling\|completed\|completed_with_discrepancies}`, `master_statement_hash?:bytes32`, `inflows_matched:int`, `inflows_unmatched:int`, `discrepancies:[{va_id, expected:Money, observed:Money}]`, `summary?` |
| Child entities | none (discrepancies are value objects) |
| Events emitted | `Reconciliation.Completed`, `Reconciliation.DiscrepancyDetected` (B2 §3.4); incidentally `InflowReconciled` / `InflowUnmatched` per VA |
| Refs | C23, G6 |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| RL.1 | Hard | One ledger per `business_date`. Re-runs must update the existing ledger (no parallel ledgers per day) | C23 |
| RL.2 | Hard | Cannot transition to `completed` while any discrepancy lacks a `RemediationCase` (G6, G11) | G6, G11 |
| RL.3 | Hard | The EoD overlay is authoritative over webhook state (C23). Any divergence is recorded in `discrepancies` and a corrective envelope (G23) is emitted | C23, G23 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `StartReconciliation(business_date)` | `system_scheduler` (EoD cron) | RL.1 | (internal) |
| `IngestMasterStatement(statement_hash)` | subscriber to BC18 `MasterStatement.Fetched` | RL.1 | (internal) |
| `RecordDiscrepancy(va_id, expected, observed)` | `system_scheduler` (during overlay) | RL.3 | `Reconciliation.DiscrepancyDetected` |
| `Complete()` | `system_scheduler` (causation: all discrepancies routed to remediation) | RL.1, RL.2 | `Reconciliation.Completed` |

#### 2.4.4 RemediationCase

| | |
|---|---|
| Root identity | `case_id : UUIDv7` |
| State fields | `trigger:{leg_failed\|inflow_unmatched\|discrepancy\|webhook_signature_invalid}`, `linked_aggregate_ref`, `status:{open\|in_progress\|resolved}`, `assignee_id?`, `resolution_doc_hash?`, `linked_corrective_event_id?` |
| Child entities | none |
| Events emitted | `ManualRemediation.Opened`, `ManualRemediation.Resolved` (B2 §3.4) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| RC.1 | Hard | A `RemediationCase` cannot be marked `resolved` without `linked_corrective_event_id` pointing at the envelope that fixed the underlying state (G23) | G11, G23 |
| RC.2 | Hard | Closure requires `admin_user`[Treasury & Settlement] | G11, Spec §3.1 |
| RC.3 | Hard | Failed legs (G11) cannot be silently dropped — every `DistributionLeg.Failed` envelope must produce exactly one `RemediationCase` | G11 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Open(trigger, linked_aggregate_ref, summary)` | subscriber to `DistributionLeg.Failed`, `InflowUnmatched`, `Reconciliation.DiscrepancyDetected`, `WebhookSignature.Invalid` | RC.3 | `ManualRemediation.Opened` |
| `Assign(assignee_id)` | `admin_user`[Treasury & Settlement] | — | (internal) |
| `Resolve(resolution_doc_hash, linked_corrective_event_id)` | `admin_user`[Treasury & Settlement] | RC.1, RC.2 | `ManualRemediation.Resolved` |


---

### 2.5 BC5 — Assignment & Signing

Three aggregates per B1. The aggregate-signature state (`all_signed`) is the C27 disbursement gate; the 24h time-box (G13) lives here as a hard invariant on `AssignmentSet`.

#### 2.5.1 MasterAgreement

| | |
|---|---|
| Root identity | `agreement_id : UUIDv7` |
| State fields | `party_id`, `party_type:{supplier\|investor}`, `kind:{MAA\|MIA}`, `doc_hash:bytes32`, `status:{initiated\|signed\|stamped\|failed}`, `signature_cert_serial?`, `stamp_cert_id?`, `failed_reason?` |
| Child entities | none |
| Events emitted | `MasterAgreement.Initiated`, `MasterAgreement.Signed`, `MasterAgreement.SigningFailed`, `MasterStamping.Completed` (B2 §3.5) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| MA.1 | Hard | One `MasterAgreement` per `(party_id, kind)` in `signed` or `stamped` status at any time. Re-signing (e.g. after a failure) creates a new aggregate; the failed one is retained for audit | DL-048, C1 |
| MA.2 | Hard | Status transitions: `initiated → {signed, failed}; signed → stamped`. `failed` is terminal | DL-048, A2 §3.6 |
| MA.3 | Hard | `signature_cert_serial` is set only on receipt of BC19 `SignatureCompleted`; `stamp_cert_id` only on `StampIssued` (DL-048 master-level stamping) | DL-048 |
| MA.4 | Soft | Master-level stamping completes within a reasonable window post-signing (G14 vendor-orchestrated). Failure escalates to admin alert but does not block onboarding terminal state (the MAA/MIA is legally signed; stamping is a state-duty compliance step) | DL-048, G2, G14 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Initiate(party_id, party_type, kind, doc_hash)` | `system_scheduler` (subscriber to BC7/BC8 reaching signing stage) | MA.1, MA.2 | `MasterAgreement.Initiated` (issues BC19 `init_signature`) |
| `RecordSigned(signature_cert_serial)` | subscriber to BC19 `SignatureCompleted` | MA.2, MA.3 | `MasterAgreement.Signed` |
| `RecordFailed(reason)` | subscriber to BC19 `SignatureFailed` | MA.2 | `MasterAgreement.SigningFailed` |
| `RecordStamped(stamp_cert_id)` | subscriber to BC19 `StampIssued` | MA.2, MA.3 | `MasterStamping.Completed` |

#### 2.5.2 AssignmentSet

| | |
|---|---|
| Root identity | `assignment_set_id : UUIDv7` |
| State fields | `listing_id`, `time_box_close_at:timestamp`, `status:{requested\|in_progress\|all_signed\|incomplete}`, `signed_count:int`, `unsigned_count:int`, `total_count:int` |
| Child entities | `AssignmentLeg` per investor: `{investor_id, subscription_id, allocation:Money, signature_request_id?, doc_hash?, status:{pending\|initiated\|signed\|failed}, signature_cert_serial?}` |
| Events emitted | `AssignmentSet.Requested`, `AssignmentSignature.Initiated`, `AssignmentSignature.Completed`, `AssignmentSignature.Failed`, `AssignmentSet.AllSigned`, `AssignmentSet.Incomplete`, `PerInvoiceStamp.Pending` (B2 §3.5) |

State-field count plus per-leg child collection; justification: the `all_signed` invariant (C27) is the disbursement gate and is the *exact reason* the AssignmentSet exists as a single aggregate. Splitting per-leg would re-introduce a cross-aggregate hard invariant on completeness.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AS.1 | Hard | One `AssignmentSet` per `listing_id`; `total_count = number of confirmed Subscriptions on that listing at FullyFunded time` | C27, DL-002 |
| AS.2 | Hard | `time_box_close_at = AssignmentSet.Requested_at + 24h` per G13 | G13 |
| AS.3 | Hard | `AssignmentSet.AllSigned` fires exactly when `signed_count == total_count` AND `time_box_close_at` has not yet passed | C27, G13 |
| AS.4 | Hard | `AssignmentSet.Incomplete` fires exactly when `time_box_close_at` is reached with `signed_count < total_count`. Any subsequent signing attempts on legs are rejected | G13 |
| AS.5 | Hard | `signed_count + unsigned_count = total_count` at all times (legs are accounted for monotonically; once `signed`, a leg cannot revert) | C27 |
| AS.6 | Hard | Per-leg `doc_hash` is set at `AssignmentSignature.Initiated` and unchanged thereafter (the doc the investor signs is the doc the audit log records) | C1, C2 |
| AS.7 | Soft | Per-invoice stamping is async post-event (G2); a `PerInvoiceStamp.Pending` placeholder is emitted at `AllSigned`. Stamping completion is parked-legal (DL-048) | G2, DL-048 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Request(listing_id, investors[])` | subscriber to BC1 `Listing.FullyFunded` | AS.1, AS.2 | `AssignmentSet.Requested` |
| `InitiateLeg(investor_id, doc_hash)` | `system_scheduler` (per-leg fan-out) | AS.5, AS.6 | `AssignmentSignature.Initiated` (issues BC19 `init_signature`) |
| `RecordLegSigned(investor_id, signature_cert_serial)` | subscriber to BC19 `SignatureCompleted` | AS.5; not past `time_box_close_at` | `AssignmentSignature.Completed`; on completeness, `AssignmentSet.AllSigned` |
| `RecordLegFailed(investor_id, reason)` | subscriber to BC19 `SignatureFailed` (after retries) | AS.5 | `AssignmentSignature.Failed` |
| `DeclareIncomplete()` | `system_scheduler` (deterministic at `time_box_close_at` if not `all_signed`) | AS.4 | `AssignmentSet.Incomplete`; `PerInvoiceStamp.Pending` retracted via new envelope (none in B2 — placeholder until G2 resolves) |
| `MarkStampPending()` | `system_scheduler` (causation: `AllSigned`) | AS.7 | `PerInvoiceStamp.Pending` |

Six commands; justified above (the per-leg fan-out is mandatory under C27).

#### 2.5.3 SignatureRequest

| | |
|---|---|
| Root identity | `signature_request_id : UUIDv7` (same UUID used as `client_request_id` to BC19) |
| State fields | `signer_id`, `signer_type:{supplier_signatory\|investor\|admin_proxy_for_supplier_under_agency}`, `doc_hash`, `parent_aggregate_ref:{master_agreement_id \| assignment_set_id+investor_id}`, `status:{initiated\|completed\|failed}`, `vendor_session_url?`, `retry_count:int`, `cert_serial?` |
| Child entities | none |
| Events emitted | none directly — BC19 emits `SignatureSession.Initiated`, `SignatureCompleted`, `SignatureFailed`; BC5's `MasterAgreement`/`AssignmentSet` are the listeners. SignatureRequest is the BC5-side bookkeeping record |

Note: this aggregate exists to track the platform-side identity for de-duplication and retry on the vendor call (A2 §3.3, C22). It is referenced by `MasterAgreement` and `AssignmentSet` legs but does not itself emit business events; it is more of a value-rich entity. It is listed as an aggregate per the B1 inventory but its state machine is shallow.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SR.1 | Hard | `(signer_id, doc_hash, parent_aggregate_ref)` is unique — replays produce the same `signature_request_id` | G18, A2 §3.3 |
| SR.2 | Hard | `retry_count ≤ 3` (A2 §3.6); after exhaustion, status becomes `failed` and bubbles up to parent | A2 §3.6 |
| SR.3 | Hard | `cert_serial` set exactly once, on terminal `completed` | C2 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Create(signer_id, doc_hash, parent_aggregate_ref)` | invoked by MasterAgreement/AssignmentSet handlers | SR.1 | none directly (parent emits) |
| `RecordVendorSession(vendor_session_url)` | subscriber to BC19 `SignatureSession.Initiated` | — | none directly (BC19 emits) |
| `RecordTerminal(outcome, cert_serial?)` | subscriber to BC19 `SignatureCompleted` / `SignatureFailed` | SR.3 | none directly (BC19 emits, parent reacts) |

---

### 2.6 BC6 — Collections & Recovery

Three aggregates per B1. The delay state machine is per-listing maturity tracking; classification is delegated to BC3 (DL-029); recovery flow loops back through BC4.

#### 2.6.1 MaturityCase

| | |
|---|---|
| Root identity | `maturity_case_id : UUIDv7` (one per listing) |
| State fields | `listing_id`, `maturity_date:BusinessDate`, `delay_status:{on_track\|mildly_delayed\|delayed\|seriously_delayed\|under_adjudication\|outcome}`, `days_past_due:int`, `outcome?:{disputed\|dilution\|fraud\|defaulted\|recovered}`, `panel_lawyer_assignment_id?`, `recovery_total:Money` |
| Child entities | none |
| Events emitted | `MaturityTracking.Started`, `MaturityReminder.Sent`, `DelayStatus.Progressed`, `HardCollections.Initiated`, `Classification.Requested`, `Recovery.Achieved` (B2 §3.6) |

State-field count (7) at the P4 ceiling; commands at 6; held as one aggregate because the delay state machine and recovery accumulator are inseparable for the per-listing maturity lens.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| MC.1 | Hard | One `MaturityCase` per `listing_id`, created exactly on `Listing.Disbursed` | DL-028, DL-029 |
| MC.2 | Hard | `delay_status` transitions follow the bucketed thresholds: `on_track → mildly_delayed (T+1..+7) → delayed (T+8..+15) → seriously_delayed (T+16..+30) → under_adjudication (T+31+)`. The scheduler advances on day boundaries (BusinessDate) | DL-029 |
| MC.3 | Hard | `Classification.Requested` fires exactly when `delay_status` reaches `under_adjudication`. No earlier; no automatic default declaration (DL-029) | DL-029 |
| MC.4 | Hard | `outcome` is set by subscribing to BC3 `DefaultCase.Classified` — BC6 never sets it on its own | DL-029 |
| MC.5 | Hard | `recovery_total = Σ(Recovery.Achieved amounts)`; each `Recovery.Achieved` envelope is matched against this case and triggers a partial-distribution chain via BC4 | DL-029, Spec §4.2 |
| MC.6 | Soft | `MaturityReminder.Sent` schedule: T-3, T-1, T (BusinessDate) — non-blocking dispatch via BC15 | Spec §4.1 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Start(listing_id, maturity_date)` | subscriber to BC1 `Listing.Disbursed` | MC.1 | `MaturityTracking.Started` |
| `SendReminder(reminder_type)` | `system_scheduler` (T-3/T-1/T) | MC.6 | `MaturityReminder.Sent` |
| `ProgressDelayStatus(new_status, days_past_due)` | `system_scheduler` (day-boundary tick) | MC.2 | `DelayStatus.Progressed`; on `seriously_delayed`, also `HardCollections.Initiated`; on `under_adjudication`, also `Classification.Requested` |
| `RecordClassification(outcome)` | subscriber to BC3 `DefaultCase.Classified` | MC.4 | (internal state only) |
| `RecordRecovery(amount, source)` | subscriber to BC4 inflow matched to recovery for this case | MC.5 | `Recovery.Achieved` |

#### 2.6.2 CollectionsAction

| | |
|---|---|
| Root identity | `action_id : UUIDv7` |
| State fields | `maturity_case_id`, `action_type:{phone_followup\|email_notice\|panel_lawyer_letter\|other}`, `notes_doc_hash`, `recorded_at`, `actor_id` |
| Child entities | none |
| Events emitted | `CollectionsAction.Recorded` (B2 §3.6) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| CA.1 | Hard | Linked `MaturityCase` must exist and not be in terminal-outcome state | DL-028 |
| CA.2 | Hard | `action_type=panel_lawyer_letter` only when linked case has `delay_status ∈ {seriously_delayed, under_adjudication}` | DL-028 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Record(maturity_case_id, action_type, notes_doc_hash)` | `admin_user`[Ops Executive] (soft) or `admin_user`[Credit Reviewer + Treasury & Settlement] (hard) | CA.1, CA.2 | `CollectionsAction.Recorded` |

#### 2.6.3 ClaimCase

| | |
|---|---|
| Root identity | `claim_id : UUIDv7` |
| State fields | `listing_id`, `claim_type:{dilution\|fraud\|dispute}`, `evidence_doc_hash`, `status:{raised\|under_adjudication\|resolved}`, `outcome?`, `supplier_indemnity_amount?:Money` |
| Child entities | none |
| Events emitted | `Claim.Raised`, `Claim.Resolved` (B2 §3.6) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| CC.1 | Hard | `supplier_indemnity_amount` is set only for `claim_type ∈ {dilution, fraud}` outcomes within the warranty scope (DL-015) | DL-015 |
| CC.2 | Hard | A `Claim.Raised` envelope causes BC3 to receive a `Classification.Requested` indirectly via MaturityCase, ensuring all classification flows through BC3 | DL-029 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Raise(listing_id, claim_type, evidence_doc_hash)` | `admin_user`[Credit Reviewer] | CC.2 | `Claim.Raised` |
| `Resolve(outcome, supplier_indemnity_amount?)` | subscriber to BC3 `DefaultCase.Classified` (when outcome maps to claim type) | CC.1 | `Claim.Resolved` |

---

### 2.7 BC7 — Investor Onboarding

Four aggregates per B1. The eight-stage onboarding journey is captured on `InvestorAccount` as a single state machine; KYC file approval is delegated to BC11 via subscriber pattern; signing to BC5.

#### 2.7.1 Invite

| | |
|---|---|
| Root identity | `invite_id : UUIDv7` |
| State fields | `email_hash:bytes32`, `phone_hash:bytes32`, `expiry_at:timestamp`, `referrer_id?`, `justification:string`, `status:{issued\|consumed\|expired\|revoked}`, `issued_at`, `consumed_at?`, `consumed_by_investor_account_id?` |
| Child entities | none |
| Events emitted | `Invite.Issued`, `Invite.Consumed`, `Invite.Expired`, `Invite.Revoked` (B2 §3.7) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| I.1 | Hard | `expiry_at = issued_at + 14 days` (calendar, not business) | DL-008, C20 |
| I.2 | Hard | Single-use: `status=consumed` is terminal; a second consumption attempt is rejected | DL-008, C20 |
| I.3 | Hard | `Invite.Consumed` requires the consumer's authenticated email and phone to match `email_hash` and `phone_hash` from issuance (binding per G9) | G9, C20 |
| I.4 | Hard | Only `admin_user`[Compliance Reviewer] can `Issue` (DL-036) | DL-036, Spec §3.1 |
| I.5 | Hard | `Revoke` allowed only from `issued`; not from `consumed`/`expired` | DL-008 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Issue(email, phone, referrer_id?, justification)` | `admin_user`[Compliance Reviewer] | I.1, I.4 | `Invite.Issued` |
| `Consume(investor_account_id, asserted_email, asserted_phone)` | `investor` (anonymous-until-consumed) | I.2, I.3 | `Invite.Consumed` |
| `Expire()` | `system_scheduler` (deterministic at I.1 boundary) | — | `Invite.Expired` |
| `Revoke(reason)` | `admin_user`[Compliance Reviewer] | I.5 | `Invite.Revoked` |

#### 2.7.2 InvestorAccount

| | |
|---|---|
| Root identity | `investor_account_id : UUIDv7` |
| State fields | `email`, `phone`, `pan?:PAN`, `aadhaar_last4?:AadhaarLast4`, `bank_account_last4?:string`, `nominee?`, `fatca_status?`, `sub_type:{resident_individual\|huf\|nri\|institutional\|nbfc_partner}` (Phase 1: only `resident_individual` or `huf` — DL-006), `status:enum(Spec §6.3)`, `kyc_file_status:{not_submitted\|submitted\|approved\|rejected}`, `suitability_outcome?:{matched\|mismatched_with_override}`, `activated_at?` |
| Child entities | none — KYC documents are referenced by hash and live in BC16 (Documents) |
| Events emitted | `InvestorAccount.SignedUp`, `InvestorIdentity.Verified`, `InvestorKyc.Submitted`, `InvestorSuitability.Assessed`, `InvestorSuitability.OverrideAcknowledged`, `InvestorFinancialProfile.Completed`, `InvestorKyc.Approved`, `InvestorKyc.Rejected`, `InvestorMia.Signed`, `InvestorAccount.Activated`, `InvestorKycRefresh.Due`, `InvestorAccount.Suspended`, `InvestorAccount.Dormant`, `InvestorAccount.Exited` (B2 §3.7) |

State-field count (12) and command count (12) exceed P4. Justification: the eight-stage onboarding journey (Spec §2.4) is a single linear lifecycle on a single entity; splitting it (e.g. `OnboardingFile` vs `ActiveAccount`) would create a cross-aggregate hard rule that the active account cannot exist without an approved onboarding file. The state machine is structural.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| IA.1 | Hard | `status` transitions follow Spec §6.3. Onboarding stages are strictly forward; alternates are explicit | Spec §6.3, DL-050 |
| IA.2 | Hard | `sub_type ∈ {resident_individual, huf}` in Phase 1 (DL-006); other values present in schema but rejected at command-handler | DL-006, C25, C26 |
| IA.3 | Hard | `Activated` requires: `kyc_file_status=approved`, `suitability_outcome` set (with override acknowledged if mismatched — C21), `bank_account_last4` set (penny-drop completed), MIA signed (S5 subscriber) | DL-050, C21, Spec §2.4 |
| IA.4 | Hard | `suitability_outcome=mismatched_with_override` requires an `InvestorSuitability.OverrideAcknowledged` envelope referencing an `override_text_hash` (G26) | C21, G26 |
| IA.5 | Hard | `Suspended` from `active` is reversible only via Compliance Reviewer adjudication; `Blacklisted` (from `suspended`) is terminal | Spec §6.3, DL-038 |
| IA.6 | Soft | Annual KYC refresh (`InvestorKycRefresh.Due` at +12 months from `Activated`) — non-blocking schedule | C17 |
| IA.7 | Hard | `aadhaar_last4` is the only Aadhaar field stored on the aggregate; full Aadhaar number, if stored at all, is encrypted in BC16 with strict ACL (UIDAI norms) | C15, DL-050 |
| IA.8 | Hard | `pan`, `aadhaar_last4`, `bank_account_last4` are each set exactly once at their respective verification events; subsequent changes require explicit re-verification command, not direct update | C24, DL-050 |
| IA.9 | Hard | `Exited` reachable only when investor has zero subscriptions in non-terminal status (BC2 read-side check before issuing command) | Spec §2.4, Spec §6.3 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `SignUp(invite_id, email, phone)` | `investor` | IA.1, IA.2; Invite consumption (I.2, I.3) | `InvestorAccount.SignedUp` |
| `RecordIdentityVerified(pan, aadhaar_last4)` | subscriber to BC17 verification results | IA.1, IA.7, IA.8 | `InvestorIdentity.Verified` |
| `SubmitKyc(doc_hashes)` | `investor` | IA.1 | `InvestorKyc.Submitted` |
| `AssessSuitability(profile, mismatch)` | `investor` (questionnaire submission) | IA.1 | `InvestorSuitability.Assessed` |
| `AcknowledgeSuitabilityOverride(override_text_hash)` | `investor` | IA.4 | `InvestorSuitability.OverrideAcknowledged` |
| `CompleteFinancialProfile(bank_account_last4, nominee, fatca_status)` | `investor` | IA.1, IA.8 | `InvestorFinancialProfile.Completed` |
| `RecordKycApproved(approver_id)` | subscriber to BC11 `KycFile.Approved` | IA.1 | `InvestorKyc.Approved` |
| `RecordKycRejected(reason)` | subscriber to BC11 `KycFile.Rejected` | IA.1 | `InvestorKyc.Rejected` |
| `RecordMiaSigned(agreement_id)` | subscriber to BC5 `MasterAgreement.Signed` for this investor | IA.1 | `InvestorMia.Signed` |
| `Activate()` | `system_scheduler` (causation: MIA signed + all prerequisites) | IA.3 | `InvestorAccount.Activated` |
| `Suspend(reason)` | `admin_user`[Compliance Reviewer] | IA.5 | `InvestorAccount.Suspended` |
| `Exit()` | `investor` or `admin_user`[Compliance Reviewer] | IA.9 | `InvestorAccount.Exited` |

#### 2.7.3 KycFile (BC7 view)

The BC7-side `KycFile` aggregate is the per-investor envelope of submitted KYC artefacts; BC11's view of the same concept (with the approval workflow) is a separate aggregate in BC11. Both share the same `subject_id = investor_account_id` but each owns a different lens (P3 cross-context references by identity).

| | |
|---|---|
| Root identity | `kyc_file_id : UUIDv7` |
| State fields | `subject_id` (= `investor_account_id`), `subject_type:investor`, `doc_hashes:[bytes32]`, `submitted_at`, `status:{submitted\|approved\|rejected}` |
| Child entities | none |
| Events emitted | none directly — `InvestorKyc.Submitted` is emitted on `InvestorAccount` (the BC7 owning aggregate); KycFile is BC7's reference record. Approval events are owned by BC11 |

This is a thin reference-aggregate; held as a separate aggregate per B1's listing for cleanliness, but it has no commands of its own (state is set transactionally with `InvestorAccount.SubmitKyc`).

#### 2.7.4 SuitabilityAssessment

| | |
|---|---|
| Root identity | `assessment_id : UUIDv7` |
| State fields | `investor_account_id`, `risk_profile`, `mismatch:bool`, `override_text_hash?:bytes32`, `override_acknowledged_at?:timestamp`, `questionnaire_doc_hash:bytes32` |
| Child entities | none |
| Events emitted | none directly — emitted via `InvestorAccount.AssessSuitability` and `InvestorAccount.AcknowledgeSuitabilityOverride`. Kept as a separate aggregate because the assessment is a discrete artefact with its own immutability rules |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SA.1 | Hard | Assessment is immutable after creation; a re-assessment creates a new `assessment_id` | C21 |
| SA.2 | Hard | If `mismatch=true`, the InvestorAccount can advance to `Activated` only when `override_text_hash` and `override_acknowledged_at` are set (handled at IA.4) | C21, G26 |

No standalone commands; mutated transactionally with InvestorAccount commands.

---

### 2.8 BC8 — Supplier Onboarding

Four aggregates per B1. Six-stage journey on `SupplierAccount`; agency consent is a distinct artefact (G5).

#### 2.8.1 SupplierAccount

| | |
|---|---|
| Root identity | `supplier_id : UUIDv7` |
| State fields | `legal_name`, `constitution_type:{pvt_ltd\|llp\|partnership\|proprietorship\|msme}`, `pan?:PAN`, `gstin?:GSTIN`, `cin?:CIN`, `bank_account_last4?:string`, `udyam?:UDYAM` (Phase 1: dormant per B1 §5.4), `status:enum(Spec §6.1)`, `kyc_file_status`, `financial_profile_status`, `credit_review_outcome?:{risk_rating, exposure_cap:Money}`, `activated_at?` |
| Child entities | none |
| Events emitted | `SupplierAccount.Created`, `SupplierIdentity.Verified`, `SupplierKyc.Submitted`, `SupplierFinancialProfile.Submitted`, `SupplierKyc.Approved`, `SupplierCreditReview.Outcome`, `SupplierMaa.Signed`, `SupplierAccount.Activated`, `SupplierAccount.Suspended`, `SupplierAccount.Blacklisted` (B2 §3.8) |

State-field count (12) and command count (8) exceed P4. Same justification as InvestorAccount: linear onboarding state machine on a single entity is the domain (Spec §2.2, §6.1).

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SA8.1 | Hard | `status` transitions follow Spec §6.1 | Spec §6.1, DL-012 |
| SA8.2 | Hard | `Activated` requires `kyc_file_status=approved`, `credit_review_outcome` set, MAA signed | DL-014, DL-022, DL-048 |
| SA8.3 | Hard | Identity-data fields (`pan`, `gstin`, `cin`) verified via BC17 (`verify_pan`, `verify_gstin`, `fetch_mca21`); not self-attested | C24, DL-014 |
| SA8.4 | Hard | `Voluntary exit` reachable only when supplier has zero invoices in non-terminal status (BC1 read-side check) | Spec §6.1 |
| SA8.5 | Soft | Annual KYC refresh, like IA.6 | C17 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Create(legal_name, constitution_type)` | `admin_user`[Ops Executive] (admin-assisted — DL-012) | SA8.1 | `SupplierAccount.Created` |
| `RecordIdentityVerified(pan, gstin, cin)` | subscriber to BC17 results | SA8.3 | `SupplierIdentity.Verified` |
| `SubmitKyc(doc_hashes)` | `supplier_user` or `agency` | SA8.1 | `SupplierKyc.Submitted` |
| `SubmitFinancialProfile(doc_hashes)` | `supplier_user` or `agency` | SA8.1 | `SupplierFinancialProfile.Submitted` |
| `RecordKycApproved(approver_id)` | subscriber to BC11 `KycFile.Approved` | SA8.1 | `SupplierKyc.Approved` |
| `RecordCreditReview(risk_rating, exposure_cap)` | subscriber to BC3 `SupplierCreditProfile.Approved` | SA8.1 | `SupplierCreditReview.Outcome` |
| `RecordMaaSigned(agreement_id)` | subscriber to BC5 `MasterAgreement.Signed` | SA8.1 | `SupplierMaa.Signed` |
| `Activate()` | `system_scheduler` (causation: MAA signed) | SA8.2 | `SupplierAccount.Activated` |
| `Suspend(reason)` / `Blacklist(reason)` | `admin_user`[Credit Reviewer + Compliance Reviewer maker-checker] | SA8.1 | `SupplierAccount.Suspended` / `…Blacklisted` |

#### 2.8.2 AgencyConsent

| | |
|---|---|
| Root identity | `consent_id : UUIDv7` |
| State fields | `supplier_id`, `scope:[…]`, `consent_doc_hash:bytes32`, `granted_at`, `revoked_at?`, `status:{active\|revoked}` |
| Child entities | none |
| Events emitted | `AgencyConsent.Granted`, `AgencyAction.Recorded` (B2 §3.8) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AC.1 | Hard | Every `actor.actor_type=agency` envelope in the system must reference an `active` `AgencyConsent` (B2 §2.2 `agency_consent_id`). At command-handler entry, the consent is verified | DL-013, G5, B2 §2.2 |
| AC.2 | Hard | Legal-signature commands (e-sign on MAA) are *never* delegable under agency; the supplier's authorised signatory must sign personally | DL-012, DL-048 |
| AC.3 | Hard | `AgencyAction.Recorded` envelope must be emitted for *every* admin action taken under agency, with the consent reference; failure to emit blocks the action | DL-013, C2 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Grant(supplier_id, scope, consent_doc_hash)` | `supplier_user` (click-through at onboarding) | AC.1 | `AgencyConsent.Granted` |
| `Revoke(reason)` | `supplier_user` or `admin_user`[Super Admin] | AC.1 | (issues internal envelope; future agency actions then fail AC.1) |
| `RecordAgencyAction(admin_user_id, action_summary)` | `system_scheduler` (sidecar to every agency command in BC8/BC1) | AC.3 | `AgencyAction.Recorded` |

#### 2.8.3 KycFile (BC8 view)

Symmetric to BC7's KycFile; same shape; `subject_type=supplier`. No standalone commands.

#### 2.8.4 FinancialProfile

| | |
|---|---|
| Root identity | `financial_profile_id : UUIDv7` |
| State fields | `supplier_id`, `submitted_doc_hashes:[bytes32]`, `gst_returns_ttl_until:timestamp`, `aa_bank_statement_ttl_until:timestamp`, `top_buyers:[…]`, `status:{submitted\|reviewed}` |
| Child entities | none |
| Events emitted | none directly — `SupplierFinancialProfile.Submitted` is on `SupplierAccount` |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| FP.1 | Hard | GST returns are pulled via BC17 (`fetch_gst_returns`) with TTL 90 days (A2 §1.4); AA bank statements TTL 90 days. Re-pull required on TTL expiry before credit review | A2 §1.4, C24 |
| FP.2 | Hard | All financial data is verified via BC17, not self-attested (C24) | C24, DL-026 |

No standalone commands; mutated with `SupplierAccount.SubmitFinancialProfile`.

---

### 2.9 BC9 — Buyer Management

Three aggregates per B1. The credit-side `BuyerCreditProfile` lives in BC3; this context owns the operational buyer record.

#### 2.9.1 BuyerAccount

| | |
|---|---|
| Root identity | `buyer_id : UUIDv7` |
| State fields | `legal_name`, `mca_cin`, `gstin`, `sector`, `relationship_tier:{anchor\|acknowledged_buyer\|unacknowledged_buyer}` (Phase 1: only `acknowledged_buyer` per DL-020), `acknowledgment_mode:{per_invoice\|blanket}` (Phase 1: only `per_invoice` per DL-019), `status:enum(Spec §6.2)`, `credit_assessment_outcome?:{limit:Money, pricing_band_id, conditions}`, `activated_at?` |
| Child entities | none |
| Events emitted | `BuyerAccount.Nominated`, `BuyerIdentity.Verified`, `BuyerCreditAssessment.Outcome`, `BuyerEngagement.Started`, `PaymentInstructions.Confirmed`, `BuyerAccount.Activated`, `BuyerAccount.Suspended`, `BuyerLimit.Reduced` (B2 §3.9) |

State-field count (9) at P4 ceiling; command count (7) at ceiling. Justification matches IA/SA8 — linear onboarding state machine.

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| BA.1 | Hard | `status` transitions follow Spec §6.2 | Spec §6.2, DL-018 |
| BA.2 | Hard | `relationship_tier=acknowledged_buyer` and `acknowledgment_mode=per_invoice` in Phase 1; schema permits other values but command-handler rejects (C25, C26) | DL-019, DL-020, C25, C26 |
| BA.3 | Hard | `Activated` requires `credit_assessment_outcome` set (from BC3) AND at least one `AcknowledgmentUser` designated AND `PaymentInstructions` confirmed | DL-018, DL-021 |
| BA.4 | Hard | Buyer identity (CIN, GSTIN) verified via BC17 (`fetch_mca21`, `verify_gstin`); not self-attested | C24, DL-018 |
| BA.5 | Soft | `BuyerLimit.Reduced` envelope from BC3 is reflected in `credit_assessment_outcome.limit`; in-flight listings use their snapshot (G20) | G20, DL-022 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Nominate(legal_name, source)` | `admin_user`[Credit Reviewer] | BA.1 | `BuyerAccount.Nominated` |
| `RecordIdentityVerified(cin, gstin)` | subscriber to BC17 | BA.4 | `BuyerIdentity.Verified` |
| `RecordCreditAssessment(limit, pricing_band_id, conditions)` | subscriber to BC3 `BuyerCreditLimit.Set` | BA.1 | `BuyerCreditAssessment.Outcome` |
| `StartEngagement()` | `admin_user`[Ops Executive] | BA.1 | `BuyerEngagement.Started` |
| `ConfirmPaymentInstructions(doc_hash)` | `admin_user`[Ops Executive] | BA.1 | `PaymentInstructions.Confirmed` |
| `Activate()` | `system_scheduler` (causation: engagement complete) | BA.3 | `BuyerAccount.Activated` |
| `Suspend(reason)` | `admin_user`[Credit Reviewer + Treasury & Settlement maker-checker] | BA.1 | `BuyerAccount.Suspended` |
| `RecordLimitReduced(new_limit)` | subscriber to BC3 `BuyerCreditLimit.Set` (downward) | BA.5 | `BuyerLimit.Reduced` |

#### 2.9.2 AcknowledgmentUser

| | |
|---|---|
| Root identity | `ack_user_id : UUIDv7` |
| State fields | `buyer_id`, `email`, `phone`, `name`, `status:{active\|inactive}`, `designated_at` |
| Child entities | none |
| Events emitted | `AcknowledgmentUser.Designated` (B2 §3.9) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AU.1 | Hard | `email` is unique per `buyer_id` (no two ack users on the same email at one buyer) | DL-021 |
| AU.2 | Hard | At least one `active` AcknowledgmentUser per `buyer_id` for the buyer to be `active` (BA.3) | DL-021 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Designate(buyer_id, email, phone, name)` | `admin_user`[Ops Executive] | AU.1 | `AcknowledgmentUser.Designated` |
| `Deactivate()` | `admin_user`[Ops Executive] | AU.2 (must not leave buyer with zero) | (internal) |

#### 2.9.3 PaymentInstruction

| | |
|---|---|
| Root identity | `instruction_id : UUIDv7` |
| State fields | `buyer_id`, `instruction_doc_hash:bytes32`, `effective_from:BusinessDate`, `superseded_by?` |
| Child entities | none |
| Events emitted | `PaymentInstructions.Confirmed` (B2 §3.9) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| PI9.1 | Hard | Only one `PaymentInstruction` is current per `buyer_id` at a time; supersession via `superseded_by` (parallel to PricingBand) | Spec §2.3 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Confirm(buyer_id, instruction_doc_hash)` | `admin_user`[Ops Executive] | PI9.1 | `PaymentInstructions.Confirmed` |


---

### 2.10 BC10 — Admin IAM

Four aggregates per B1. Three are entity-shaped (`AdminUser`, `RoleAssignment`, `DeviationEntry`); `SodPolicy` is a policy aggregate (rules-as-data, like PricingBand). Maker-checker is a primitive consumed across other contexts but its envelopes (`MakerChecker.Blocked`, `MakerChecker.Approved`) are owned here.

#### 2.10.1 AdminUser

| | |
|---|---|
| Root identity | `admin_user_id : UUIDv7` |
| State fields | `email`, `display_name`, `mfa_factors:[{factor_type:{totp\|sms_otp}, enrolled_at}]`, `status:{invited\|active\|disabled}`, `tenant_claims:[…]` |
| Child entities | none — `RoleAssignment` is a separate aggregate; `mfa_factors` is a value-collection inside this aggregate (challenges are ephemeral and not stored as aggregate state) |
| Events emitted | `AdminUser.Created`, `Mfa.Enrolled`, `Mfa.ChallengeIssued`, `Mfa.ChallengeSucceeded`, `Mfa.ChallengeFailed`, `TenantClaim.Issued` (B2 §3.10) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AU10.1 | Hard | `email` is globally unique | DL-031 |
| AU10.2 | Hard | At least one MFA factor must be enrolled before `status=active` | C7, DL-035 |
| AU10.3 | Hard | Sensitive admin actions (every state-changing command in every context where actor is `admin_user`) require a fresh `Mfa.ChallengeSucceeded` envelope whose `assertion_id` is referenced via `actor.mfa_assertion_id` (B2 §2.2). The assertion has a bounded TTL set by Architect (G19 territory) | C7, B2 §2.2 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Create(email, display_name)` | `admin_user`[Super Admin] | AU10.1 | `AdminUser.Created` |
| `EnrolMfa(factor_type)` | the admin user themselves | — | `Mfa.Enrolled` |
| `IssueMfaChallenge(factor_type)` | the admin user (login or sensitive-action) | — | `Mfa.ChallengeIssued` |
| `VerifyMfaChallenge(challenge_id, code)` | the admin user | — | `Mfa.ChallengeSucceeded` or `Mfa.ChallengeFailed` |
| `EstablishSession()` | the admin user (post-MFA) | AU10.2, AU10.3 | `TenantClaim.Issued` |
| `Disable(reason)` | `admin_user`[Super Admin] | — | (internal) |

#### 2.10.2 RoleAssignment

| | |
|---|---|
| Root identity | `(admin_user_id, role)` composite — natural identity. A user holding three roles has three RoleAssignment aggregates |
| State fields | `assigned_at`, `assigned_by`, `revoked_at?`, `status:{active\|revoked}`, `sod_warning_acknowledged_at?`, `override_reason?`, `deviation_register_entry_id?` |
| Child entities | none |
| Events emitted | `Role.Assigned`, `Role.Revoked`, `SodSoftDeviation.Logged` (B2 §3.10) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| RA.1 | Hard | Strict SoD pairs are blocked at command-handler: `Credit Reviewer ⊕ Treasury & Settlement` cannot both be active for the same user (C5 strict tier) | C5, DL-033 |
| RA.2 | Hard | Soft SoD pairs (Super Admin + Compliance Reviewer; Ops Executive + Treasury & Settlement; Credit Reviewer + Compliance Reviewer) issue a warning and require `override_reason`; a `DeviationEntry` is created (RA.3) | C5, DL-033 |
| RA.3 | Hard | When RA.2 is triggered, exactly one corresponding `DeviationEntry` (BC10's DeviationRegister aggregate) is created and `deviation_register_entry_id` is stored | C5, DL-033 |
| RA.4 | Hard | Auditor accounts (BC13) cannot hold any operational role (account-level SoD per C19) — enforced at assign time | C19, DL-039 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Assign(admin_user_id, role, override_reason?)` | `admin_user`[Super Admin] | RA.1, RA.2, RA.3, RA.4 | `Role.Assigned`; if soft-SoD, also `SodSoftDeviation.Logged` |
| `Revoke()` | `admin_user`[Super Admin] | — | `Role.Revoked` |

#### 2.10.3 DeviationEntry (Managed Deviation Register)

| | |
|---|---|
| Root identity | `deviation_register_entry_id : UUIDv7` |
| State fields | `admin_user_id`, `combo:[role,role]`, `reason`, `created_at`, `quarterly_review_status:{pending\|reviewed}`, `review_decision?`, `reviewed_at?` |
| Child entities | none |
| Events emitted | `DeviationRegister.EntryReviewed` (B2 §3.10) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| DE.1 | Hard | Entry is immutable except for the review fields, set exactly once at quarterly review | DL-033, C5 |
| DE.2 | Soft | Quarterly review cadence — scheduler reminds; non-blocking | DL-033 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Review(decision)` | `admin_user`[Super Admin] (quarterly) | DE.1, DE.2 | `DeviationRegister.EntryReviewed` |

#### 2.10.4 SodPolicy

| | |
|---|---|
| Root identity | `sod_policy_id : UUIDv7` (one current at a time; supersession pattern) |
| State fields | `strict_pairs:[[role,role]]`, `soft_pairs:[[role,role]]`, `effective_from`, `superseded_by?` |
| Child entities | none |
| Events emitted | none in B2 §3.10 explicitly — `Role.Assigned` envelopes carry the pair classification at assignment time. The aggregate is policy-as-data; changes are administrative |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SP.1 | Hard | At any time exactly one current SodPolicy; supersession via `superseded_by` chain | C5, DL-033 |
| SP.2 | Hard | The Phase 1 policy is fixed at: strict = `{(Credit Reviewer, Treasury & Settlement)}`; soft = `{(Super Admin, Compliance Reviewer), (Ops Executive, Treasury & Settlement), (Credit Reviewer, Compliance Reviewer)}` | C5, DL-033 |

One command (`PublishPolicy`); below P4. The MakerChecker primitive itself is not a separate aggregate — it is a cross-cutting check on every command that requires it (C4), enforced at command-handler entry by reading from the originating aggregate's history. Envelopes (`MakerChecker.Blocked`, `MakerChecker.Approved`) are emitted as sidecars; under P3 they reference the originating aggregate's identity, not an IAM aggregate.

---

### 2.11 BC11 — Compliance

Four aggregates per B1. AML screening is one-time at onboarding (DL-037); re-screening scheduler is dormant (B1 §5.4).

#### 2.11.1 AmlScreening

| | |
|---|---|
| Root identity | `screening_id : UUIDv7` |
| State fields | `subject_id`, `subject_type:{investor\|supplier\|signatory\|ubo}`, `status:{initiated\|completed\|adjudicated}`, `match_score?`, `hits?:[…]`, `adjudication_decision?:{cleared\|sar_opened\|suspended}`, `rationale?` |
| Child entities | none |
| Events emitted | `AmlScreening.Initiated`, `AmlScreening.Completed`, `AmlHit.Adjudicated` (B2 §3.11) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AS11.1 | Hard | Adjudication is required if `hits` is non-empty; if empty, screening auto-resolves `cleared` | DL-037, DL-038 |
| AS11.2 | Hard | `adjudication_decision=sar_opened` requires a corresponding `SarCase` to be created | DL-038 |
| AS11.3 | Hard | Decision is by `admin_user`[Compliance Reviewer]; maker-checker not required for AML decisions (single-approver per role definition) | DL-038, Spec §3.1 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Initiate(subject_id, subject_type)` | subscriber to BC7 `InvestorIdentity.Verified` or BC8 `SupplierIdentity.Verified` | AS11.1 | `AmlScreening.Initiated` |
| `RecordCompletion(match_score, hits)` | subscriber to BC17 `screen_aml_pep` result | — | `AmlScreening.Completed` |
| `Adjudicate(decision, rationale)` | `admin_user`[Compliance Reviewer] | AS11.1, AS11.2 | `AmlHit.Adjudicated` |

#### 2.11.2 SarCase

| | |
|---|---|
| Root identity | `sar_id : UUIDv7` |
| State fields | `subject_id`, `summary_doc_hash`, `status:{opened\|documented}`, `internal_only:bool` (= true in Phase 1 per DL-038) |
| Child entities | none |
| Events emitted | `Sar.Opened`, `Sar.Documented` (B2 §3.11) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| SAR.1 | Hard | `internal_only=true` in Phase 1; external FIU-IND filing is dormant (DL-038, B1 §5.4) | DL-038 |
| SAR.2 | Hard | SarCase is immutable once `documented` (audit-trail integrity) | C1, DL-038 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Open(subject_id, summary_doc_hash)` | `admin_user`[Compliance Reviewer] | SAR.1 | `Sar.Opened` |
| `Document(doc_hash)` | `admin_user`[Compliance Reviewer] | SAR.2 | `Sar.Documented` |

#### 2.11.3 KycRefreshSchedule

| | |
|---|---|
| Root identity | `(subject_id, due_at)` composite |
| State fields | `subject_type`, `status:{scheduled\|due\|completed\|missed}`, `window_close_at` |
| Child entities | none |
| Events emitted | `KycRefresh.Due`, `KycRefresh.Completed` (B2 §3.11) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| KR.1 | Hard | `due_at = activated_at + 12 months` per C17 | C17 |
| KR.2 | Soft | Missed refresh does not auto-suspend (Phase 1 — DL-037); Compliance Reviewer adjudicates | DL-037 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Schedule(subject_id, due_at)` | subscriber to BC7/BC8 `Activated` | KR.1 | (internal) |
| `Fire()` | `system_scheduler` | — | `KycRefresh.Due` |
| `RecordCompletion()` | subscriber to refreshed KycFile approval | — | `KycRefresh.Completed` |

#### 2.11.4 SpotCheck

| | |
|---|---|
| Root identity | `spot_check_id : UUIDv7` |
| State fields | `period`, `scope`, `findings_doc_hash`, `completed_at` |
| Child entities | none |
| Events emitted | `SpotCheck.Completed` (B2 §3.11) |

**Invariants** — none material at aggregate level; spot-checks are findings records. C1 immutability applies.

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Complete(period, scope, findings_doc_hash)` | `admin_user`[Compliance Reviewer] | — | `SpotCheck.Completed` |

#### 2.11.5 Note on KycFile approval (BC11 view)

The BC11-side `KycFile` is the *approval* lens — BC11 owns the approve/reject decision and emits `KycFile.Approved` / `KycFile.Rejected` (B2 §3.11). The BC7/BC8 view is the submission lens. Both share `(subject_id, subject_type)` as the natural identifier. This is the cleanest split: approval is a Compliance authority, not an onboarding-context concern.

| | |
|---|---|
| Root identity | `kyc_file_id : UUIDv7` (one per subject, latest version) |
| State fields | `subject_id`, `subject_type`, `doc_hashes:[bytes32]`, `status:{in_review\|approved\|rejected}`, `approver_id?`, `decided_at?`, `rejection_reason?` |
| Events emitted | `KycFile.Approved`, `KycFile.Rejected` |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| KF.1 | Hard | Approval is by `admin_user`[Compliance Reviewer]; cannot be the same individual who submitted on behalf of supplier (record-level maker-checker — C4) | C4, DL-050 |
| KF.2 | Hard | `KycFile.Approved` is the only path that allows the corresponding BC7/BC8 account to advance to MAA/MIA signing stage | DL-050, C24 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Approve()` | `admin_user`[Compliance Reviewer] | KF.1 | `KycFile.Approved` |
| `Reject(reason)` | `admin_user`[Compliance Reviewer] | KF.1 | `KycFile.Rejected` |

---

### 2.12 BC12 — Tax & Reporting

Three aggregates per B1.

#### 2.12.1 TaxYearProfile

| | |
|---|---|
| Root identity | `fy_code` (e.g. `FY2026`) — natural identity |
| State fields | `tds_rate_table:[{investor_subtype, pan_status, rate_bps}]`, `effective_from:BusinessDate`, `closed_at?` |
| Child entities | none |
| Events emitted | `TdsRate.Resolved` (per-investor lookup, technically a derived envelope), `TaxYear.Closed` (B2 §3.12) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| TYP.1 | Hard | Exactly one TaxYearProfile per FY; rate table is immutable after `effective_from` | DL-045 |
| TYP.2 | Hard | The TDS rate snapshot used in a distribution is the one current at the moment of `Tds.Calculated`, which is distribution-instruction time (G4) | DL-045, G4 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Publish(fy_code, rate_table, effective_from)` | `admin_user`[Credit Reviewer] (proxy for policy author; could be Compliance — single-approver) | TYP.1 | (internal) |
| `ResolveTdsRate(investor_id, listing_id)` | subscriber to BC1 `Listing.Matured` | TYP.2 | `TdsRate.Resolved` |
| `Close()` | `system_scheduler` (year-end) | — | `TaxYear.Closed` |

#### 2.12.2 TdsDeduction

| | |
|---|---|
| Root identity | `(investor_id, listing_id)` composite |
| State fields | `gross:Money`, `tds:Money`, `rate_bps`, `challan_ref?`, `status:{calculated\|recorded}` |
| Child entities | none |
| Events emitted | `Tds.Calculated`, `TdsDeduction.Recorded` (B2 §3.12) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| TD.1 | Hard | `tds = gross × rate_bps / 10000` (paise-integer rounding rule fixed at half-up for INR — implementation detail held at command-handler) | DL-045, G4 |
| TD.2 | Hard | The `Tds.Calculated` envelope is what BC4 reads when building the `DistributionInstructed` payload (snapshot at instruction time — G4) | G4, DL-045 |
| TD.3 | Hard | `challan_ref` is set only on receipt of BC4 `TdsChallanRecorded` (which is a subscriber to BC18 webhook) | DL-045 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Calculate(investor_id, listing_id, gross)` | subscriber to BC1 `Listing.Matured` | TD.1, TD.2 | `Tds.Calculated` |
| `RecordChallan(challan_ref)` | subscriber to BC4 `TdsChallanRecorded` | TD.3 | `TdsDeduction.Recorded` |

#### 2.12.3 InvestorStatement

| | |
|---|---|
| Root identity | `(investor_id, period)` composite (period = month or FY) |
| State fields | `kind:{monthly_portfolio\|annual_form_16a}`, `doc_hash`, `generated_at`, `total_tds?:Money` (Form 16A only) |
| Child entities | none |
| Events emitted | `Statement.Generated`, `Form16A.Issued` (B2 §3.12) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| IS.1 | Hard | One statement per `(investor_id, period, kind)`; idempotent on re-generation | DL-045, Spec §2.4 |
| IS.2 | Hard | Form 16A uses challan refs accumulated via TdsDeduction.RecordChallan during the FY; missing challans block issuance (G12 fallback path) | G12, DL-045 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `GenerateMonthly(investor_id, period)` | `system_scheduler` (month-end) | IS.1 | `Statement.Generated` |
| `IssueForm16A(investor_id, fy)` | `system_scheduler` (FY-end) | IS.1, IS.2 | `Form16A.Issued` |

---

### 2.13 BC13 — Auditor Access

Two aggregates per B1.

#### 2.13.1 AuditorAccount

| | |
|---|---|
| Root identity | `auditor_account_id : UUIDv7` |
| State fields | `email`, `scope_id`, `validity_window:{start:BusinessDate, end:BusinessDate}`, `status:{proposed\|approved\|activated\|auto_disabled}`, `proposed_by`, `approved_by?`, `activated_at?` |
| Child entities | none |
| Events emitted | `AuditorAccount.Proposed`, `AuditorAccount.Approved`, `AuditorAccount.Activated`, `AuditorAccount.AutoDisabled`, `AuditorRead.Performed`, `AuditorExport.Performed`, `AuditorRateLimit.Triggered` (B2 §3.13) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AA13.1 | Hard | `Activated` requires `Approved`; `proposed_by ≠ approved_by` (record-level maker-checker per G21) | G21, C4, C19 |
| AA13.2 | Hard | `Approved` requires `admin_user`[Compliance Reviewer] (G21); `Proposed` is by `admin_user`[Super Admin] | G21 |
| AA13.3 | Hard | Auditor accounts cannot hold any operational role (account-level SoD — C19; enforced at BC10 RoleAssignment) | C19, DL-039 |
| AA13.4 | Hard | `AutoDisabled` fires deterministically at `validity_window.end` (scheduler); status becomes terminal | C19 |
| AA13.5 | Hard | Every auditor read and export emits an `AuditorRead.Performed` / `AuditorExport.Performed` envelope before returning data to the auditor — audit-the-auditor (C3) | C3, DL-039 |
| AA13.6 | Hard | Export volume is bounded; exceeding the rate limit emits `AuditorRateLimit.Triggered` and blocks the export | C19 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Propose(email, scope_id, validity_window)` | `admin_user`[Super Admin] | AA13.2 | `AuditorAccount.Proposed` |
| `Approve()` | `admin_user`[Compliance Reviewer] (≠ proposer) | AA13.1, AA13.2 | `AuditorAccount.Approved` |
| `Activate()` | `system_scheduler` (causation: approval) | AA13.3 | `AuditorAccount.Activated` |
| `RecordRead(target_aggregate_type, target_aggregate_id)` | sidecar to every auditor query (P-pattern) | AA13.5 | `AuditorRead.Performed` |
| `RecordExport(scope, row_count, byte_count)` | sidecar to every auditor export | AA13.5, AA13.6 | `AuditorExport.Performed` or `AuditorRateLimit.Triggered` |
| `AutoDisable()` | `system_scheduler` (deterministic at `validity_window.end`) | AA13.4 | `AuditorAccount.AutoDisabled` |

#### 2.13.2 AccessScope

| | |
|---|---|
| Root identity | `scope_id : UUIDv7` |
| State fields | `date_range:{start, end}`, `entity_types:[…]`, `sensitivity_levels:[…]` |
| Child entities | none |
| Events emitted | none directly (referenced by AuditorAccount lifecycle envelopes) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| ASC.1 | Hard | `date_range.start ≤ date_range.end` and within retention window | C19 |
| ASC.2 | Hard | Scope is immutable after first use (referenced by an active AuditorAccount); changes require a new scope | C19 |

**Commands**: `Define(date_range, entity_types, sensitivity_levels)` by `admin_user`[Super Admin].

---

### 2.14 BC14 — Audit Log

One write-only aggregate per B1.

#### 2.14.1 AuditEvent

| | |
|---|---|
| Root identity | `event_id : UUIDv7` (the envelope's own id from B2 §2.1) |
| State fields | the full envelope is the state (B2 §2.1) — append-only |
| Child entities | none |
| Events emitted | none — BC14 is a sink, not a producer (B2 §3.14) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| AE.1 | Hard | Append-only: no update, no delete, by any role, including Super Admin | C1, DL-040 |
| AE.2 | Hard | `previous_envelope_hash` chains all envelopes in arrival order (per-shard, G25); `envelope_hash` is computed over canonical encoding minus `envelope_hash` itself (B2 §2.1) | C1, G7, G25 |
| AE.3 | Hard | Retention 10 years from `recorded_at` (C1) | C1 |
| AE.4 | Hard | Every state-changing command, sensitive read, approval/override, role change, agency action, fund-movement instruction, webhook event, and auditor activity is appended exactly once (publish-before-success per B2 P3) | C1, C2, C3 |

**Commands** — only `Append(envelope)`, invoked by every other context's command-handler post-state-mutation. There is no UPDATE or DELETE command. No actor type can issue a destructive command — the API surface is structurally narrow.

---

### 2.15 BC15 — Notifications

One aggregate per B1.

#### 2.15.1 NotificationDispatch

| | |
|---|---|
| Root identity | `dispatch_id : UUIDv7` |
| State fields | `channel:{email\|sms}`, `recipient_hash:bytes32`, `template_id`, `related_aggregate_ref`, `status:{dispatched\|delivery_failed}`, `vendor_message_id?`, `retry_count:int` |
| Child entities | none |
| Events emitted | `Notification.Dispatched`, `Notification.DispatchFailed` (B2 §3.15) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| ND.1 | Hard | Dispatch is fire-and-forget — delivery failure does NOT roll back business state (B1 §4.4) | B1 §4.4, DL-049 |
| ND.2 | Hard | Recipient PII is hashed at envelope level (`recipient_hash`); raw email/phone never appears in envelope payload | C14, C15 |
| ND.3 | Soft | Failed dispatches retry per vendor policy; `retry_count` capped | DL-049 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Dispatch(channel, recipient, template_id, related_aggregate_ref)` | invoked by any context's subscriber | ND.1, ND.2 | `Notification.Dispatched` |
| `RecordFailure(reason)` | subscriber to vendor failure webhook | ND.3 | `Notification.DispatchFailed` |

---

### 2.16 BC16 — Documents

One aggregate per B1.

#### 2.16.1 DocumentObject

| | |
|---|---|
| Root identity | `doc_hash:bytes32` (SHA-256 — natural identity, content-addressed) |
| State fields | `content_type`, `originating_context`, `originating_aggregate_ref`, `stored_at`, `byte_size`, `encryption_key_ref` |
| Child entities | none |
| Events emitted | `Document.Stored`, `Document.SensitiveReadPerformed`, `SignedUrl.Issued` (B2 §3.16) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| DO.1 | Hard | Identity is the hash; storage is idempotent — re-`Store` of identical content returns the same `doc_hash`, no new envelope | C14 |
| DO.2 | Hard | All stored content is encrypted at rest with per-tenant key (C14); the binary never inlines into Audit Log envelopes (only the hash is referenced) | C14, B1 §4.4 |
| DO.3 | Hard | Every sensitive read (KYC docs, financial statements, signed PDFs) emits `Document.SensitiveReadPerformed` before returning the data (C2) | C2, Spec §7.1 |
| DO.4 | Hard | India-resident storage (C13); 10-year retention aligned with C1 (G15) | C13, G15 |
| DO.5 | Hard | `SignedUrl.Issued` envelopes carry the `expires_at`; URLs are time-bound and not re-issuable for the same `(doc_hash, reader_id)` pair within a cooldown window | Spec §9 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Store(content, content_type, originating_ref)` | any context | DO.1, DO.2 | `Document.Stored` (only if new) |
| `RecordSensitiveRead(doc_hash, reader_id, purpose)` | any context (sidecar to actual read) | DO.3 | `Document.SensitiveReadPerformed` |
| `IssueSignedUrl(doc_hash, reader_id, ttl)` | any context | DO.5 | `SignedUrl.Issued` |

---

### 2.17 BC17 — Verification (Aggregator ACL)

One aggregate per B1 — a `Verification` record per outbound API call.

#### 2.17.1 Verification

| | |
|---|---|
| Root identity | `verification_id : UUIDv7` (= the `client_request_id` to the vendor) |
| State fields | `subject_id`, `api_name:enum`, `status:{requested\|completed\|failed\|stale\|manual_fallback}`, `vendor_payload_hash?`, `extracted_fields?`, `ttl_until?:timestamp`, `failure_class?`, `signature_verified_at?` |
| Child entities | none |
| Events emitted | `Verification.Requested`, `Verification.Completed`, `Verification.Failed`, `Verification.Stale`, `AggregatorOutage.Declared`, `ManualFallback.Invoked`, `WebhookSignature.Invalid` (B2 §3.17) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| V17.1 | Hard | Idempotency on `(subject_id, api_name, client_request_id)` — replays return the stored response within 24h (A2 §1.3) | A2 §1.3, C9, G18 |
| V17.2 | Hard | Vendor payloads stored verbatim alongside extracted fields (`vendor_payload_hash` references BC16 stored body) — evidence per C24 | C24, A2 §1.3 |
| V17.3 | Hard | `ttl_until` set per data type from A2 §1.4 (PAN 12m, MCA 18m or next FY filing, bureau 30d, GST returns 90d, AA bank statement 90d, penny-drop 12m, Aadhaar e-KYC 12m). Stale verifications cannot satisfy invariants on downstream commands | A2 §1.4, C24 |
| V17.4 | Hard | HMAC signature verification on every inbound webhook; invalid signature emits `WebhookSignature.Invalid` and the envelope is dropped from the business stream (alerted to BC15) | C10 |
| V17.5 | Soft | Aggregator outage detected by healthcheck/timeout threshold (G8); `ManualFallback.Invoked` permits Ops Executive + Compliance Reviewer co-signed manual capture | G8, A2 §1.5 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `Request(subject_id, api_name, client_request_id)` | any context | V17.1 | `Verification.Requested` |
| `RecordCompletion(vendor_payload_hash, extracted_fields, ttl_until)` | subscriber to vendor webhook (after HMAC) | V17.2, V17.3 | `Verification.Completed` |
| `RecordFailure(failure_class)` | subscriber to vendor error or timeout | — | `Verification.Failed` |
| `MarkStale(verification_id)` | `system_scheduler` (TTL check at use time) | V17.3 | `Verification.Stale` |
| `DeclareOutage(suspected_api)` | `system_scheduler` (healthcheck threshold) | V17.5 | `AggregatorOutage.Declared` |
| `InvokeManualFallback(verification_id, captured_by, co_signer)` | `admin_user`[Ops Executive] + `admin_user`[Compliance Reviewer] (co-signed) | V17.5 | `ManualFallback.Invoked` |
| `RejectInvalidWebhook(raw_payload_hash)` | subscriber to vendor webhook (HMAC failure) | V17.4 | `WebhookSignature.Invalid` |

Seven commands. Justification: BC17 wraps eleven vendor APIs (B1 BC17 list); the command set is the minimum lifecycle per verification record plus the outage and manual-fallback escape hatches.

---

### 2.18 BC18 — Banking (Escrow ACL)

One aggregate per B1 — a `VendorInstruction` per outbound call to escrow, plus the inbound webhook handlers (which are sidecars, not aggregates).

#### 2.18.1 VendorInstruction (Escrow)

| | |
|---|---|
| Root identity | `client_instruction_id : UUIDv7` |
| State fields | `instruction_type:{create_va\|close_va\|payout_single\|payout_multi_leg\|refund\|fetch_statement}`, `status:{sent\|acknowledged\|webhook_received\|reconciled\|failed}`, `originating_aggregate_ref` (PayoutInstruction or VirtualAccount in BC4), `vendor_payload_hash?`, `webhook_event_ids:[…]`, `signature_verified_at?` |
| Child entities | none |
| Events emitted | `Va.LifecycleObserved`, `InflowWebhookProcessed`, `PayoutLegWebhookProcessed`, `RefundWebhookProcessed`, `TdsChallanWebhookProcessed`, `MasterStatement.Fetched`, `WebhookSignature.Invalid`, `Webhook.DuplicateDropped` (B2 §3.18) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| VI.1 | Hard | Idempotent on `client_instruction_id` — vendor must not execute twice; on replay, the same `vendor_payload_hash` is returned (A2 §2.3) | C9, A2 §2.3 |
| VI.2 | Hard | HMAC signature on every webhook; invalid → `WebhookSignature.Invalid`, payload dropped from business stream | C10 |
| VI.3 | Hard | Webhook dedupe on `vendor_event_id` (first-write-wins per B2 §2.4); duplicates emit `Webhook.DuplicateDropped` for visibility | C22, B2 §2.4 |
| VI.4 | Hard | Webhook-driven state changes are provisional (per V.4 on BC4); the EoD `MasterStatement.Fetched` is authoritative (C23, G6) | C23, G6 |
| VI.5 | Hard | Failed-leg envelopes are emitted exactly once per failure; never silently dropped — they route to BC4 RemediationCase (G11) | G11 |
| VI.6 | Hard | TDS challan webhook produces `TdsChallanWebhookProcessed`; that envelope is what BC4 uses to record the challan ref (DL-045 chain) | DL-045 |

**Commands** (the ACL-internal command surface — invoked from BC4):

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `IssueInstruction(instruction_type, payload, client_instruction_id)` | invoked by BC4 PayoutInstruction handlers | VI.1 | (issues outbound HTTPS to vendor) |
| `ProcessInboundWebhook(vendor_event)` | `vendor_escrow` (actor_type) | VI.2, VI.3 | one of the *WebhookProcessed events, or `WebhookSignature.Invalid` / `Webhook.DuplicateDropped` |
| `FetchMasterStatement(business_date)` | `system_scheduler` (EoD) | — | `MasterStatement.Fetched` |

---

### 2.19 BC19 — Signing (e-Sign / e-Stamp ACL)

One aggregate per B1.

#### 2.19.1 VendorSignatureRequest

| | |
|---|---|
| Root identity | `signature_request_id : UUIDv7` (= the BC5 SignatureRequest id; ACL stores it for dedupe) |
| State fields | `signer_id`, `doc_hash`, `vendor_session_url?`, `status:{initiated\|completed\|failed\|expired}`, `signed_pdf_hash?`, `cert_serial?`, `signature_verified_at?` |
| Child entities | none |
| Events emitted | `SignatureSession.Initiated`, `SignatureCompleted`, `SignatureFailed`, `StampIssued`, `StampFailed`, `WebhookSignature.Invalid` (B2 §3.19) |

**Invariants**

| # | Hard/Soft | Rule | Refs |
|---|---|---|---|
| VSR.1 | Hard | Idempotency on `client_request_id` (A2 §3.3) | C9, G18, A2 §3.3 |
| VSR.2 | Hard | HMAC signature verification on every webhook (parallel to VI.2) | C10 |
| VSR.3 | Hard | `cert_serial` and `signed_pdf_hash` captured from vendor webhook; the signed PDF body lives in BC16, referenced by hash | C2, C14 |
| VSR.4 | Soft | UIDAI-outage degradation pauses Aadhaar-OTP flow; DSC path remains; no Phase 1 fallback to liveness check (V-CIP deferred per DL-050) | A2 §3.6, DL-050 |

**Commands**

| Command | Actor | Invariants checked | Events emitted |
|---|---|---|---|
| `InitiateSession(signer_id, doc_hash, client_request_id)` | invoked by BC5 | VSR.1 | `SignatureSession.Initiated` |
| `ProcessSignatureWebhook(vendor_event)` | `vendor_signing` | VSR.2, VSR.3 | `SignatureCompleted` / `SignatureFailed` / `WebhookSignature.Invalid` |
| `RequestStamp(parent_agreement_id, value, state)` | invoked by BC5 (master-level) | — | (issues outbound) |
| `ProcessStampWebhook(vendor_event)` | `vendor_signing` | VSR.2 | `StampIssued` / `StampFailed` |

---

## 3. Cross-Aggregate Consistency Rules

Each rule below names a consistency property whose enforcement spans two or more aggregates. Per P2, none of these is a hard invariant — every one is restored by an explicit mechanism: a subscriber, the reconciler, a time-box, or a coordinated in-process commit (G17 monolith).

| # | Rule | Aggregates involved | Enforcement | Refs |
|---|---|---|---|---|
| X1 | **Listing commit conservation: `Σ confirmed Subscription.amount ≤ Listing.funding_target`.** New `Subscription.CommitSubscription` must atomically update `Listing.committed_total`. | BC2 Subscription, BC1 Listing | **Coordinated commit** under G17 monolith (in-process pub/sub): both aggregates write in one local transaction; in Phase 2 broker world becomes a saga with a reservation hold + confirm (G27 — new). Over-subscription prevented at commit time, not inflow time (G10). | DL-017, C12, G10, G18, G27 |
| X2 | **DisbursementGate: BC1 `Listing.DisbursementGateOpened` fires when BC5 `AssignmentSet.AllSigned` has arrived AND `Listing.fully_funded=true`.** | BC1 Listing, BC5 AssignmentSet | **Subscriber** in BC1 reacts to BC5 envelope; idempotent on `command_id`. If `Listing` is not yet `fully_funded` (race), subscriber retries on next aggregate write (deferred state machine) | C27, L.5, AS.3 |
| X3 | **Confirmed funds = Listing committed total** at all times (modulo provisional reconciliation window). | BC2 Subscription, BC1 Listing, BC4 VirtualAccount | **Reconciler** (BC4 ReconciliationLedger EoD): if `Σ Subscription.confirmed.amount ≠ Listing.committed_total` or ≠ `VirtualAccount.observed_inflow_total` (modulo refunds), discrepancy is opened as a RemediationCase | C23, G6, G23 |
| X4 | **Pricing band in-flight invariance.** Changes to BC3 PricingBand do not affect BC1 Listings already past `SnapshotTaken`. | BC3 PricingPolicy, BC1 Listing | **Snapshot at transition** — Listing copies the band values at `SnapshotTaken` and never re-reads BC3 thereafter (G20). The "rule" is the absence of a subscriber. | G20, DL-024 |
| X5 | **Buyer/supplier active at GoneLive.** | BC1 Listing, BC8 SupplierAccount, BC9 BuyerAccount | **Subscriber** check at maker-checker approval; if a suspension envelope arrived between snapshot and approval, the approval handler rejects. Once `GoneLive` has fired, in-flight listing is unaffected by subsequent suspension (re-emits `HoldForReview` via X7 path) | DL-014, DL-018, L.11 |
| X6 | **Refund eligibility.** Every `Subscription` whose listing ends in `funding_failed_refunded`, `cancelled_pre_disbursement`, or fails `AssignmentSet.AllSigned` must be refunded. | BC1 Listing, BC2 Subscription, BC5 AssignmentSet, BC4 PayoutInstruction | **Subscriber chain**: BC1 / BC5 envelope → BC2 `MarkRefundEligible` → BC4 `DraftRefund`. Time-box: refund within T+1 (G3). Reconciler audits any subscription stuck in `RefundEligible` > T+1 → admin alert. | DL-017, G3, G13 |
| X7 | **Held-for-review on suspension.** A buyer or supplier suspension *after* a listing has gone live triggers `Listing.HeldForReview`. | BC8 / BC9, BC1 Listing | **Subscriber** in BC1 reacts to `BuyerAccount.Suspended` / `SupplierAccount.Suspended`; for in-flight listings, emits `Listing.HeldForReview` (Credit Reviewer queue). | L.11, Spec §6.2 |
| X8 | **Per-investor distribution conservation.** `Σ DistributionLeg.net + Σ tds + Σ fee = Listing.matured_amount` (paise-equality). | BC4 PayoutInstruction, BC1 Listing, BC12 TdsDeduction | **Reconciler** in BC4 at `Distribution.Completed`; mismatch opens RemediationCase. Sum is verified at instruction-payload-build time (hard, on PayoutInstruction PI.3) and re-verified at execution (soft, via reconciler). | DL-045, G4, PI.3 |
| X9 | **TDS challan completeness.** Every distribution leg has a corresponding TDS challan ref before Form 16A issuance (FY-end). | BC4 PayoutInstruction, BC12 InvestorStatement | **Reconciler** at FY-end: missing challans block Form 16A and trigger panel-CA fallback (G12). | DL-045, G12, IS.2 |
| X10 | **Default classification triggers loss realisation.** A `DefaultCase.Classified(outcome=defaulted)` requires all related Subscriptions to receive `LossRealised` once recovery window is closed. | BC3 DefaultCase, BC2 Subscription, BC6 MaturityCase | **Subscriber chain** with **time-box**: BC2 sets `loss_pending` on receipt of `DefaultCase.Classified`; a recovery window timer (Architect-set, default 12 months) drains; on expiry, BC2 emits `Subscription.LossRealised`. Each `Recovery.Achieved` during the window emits a partial distribution per X8 and reduces the eventual loss accordingly. | DL-029, S.7, MC.5 |
| X11 | **Maker-checker is record-level.** Every command that requires it must verify maker ≠ checker on the same `originating_aggregate_id`. | BC10 RoleAssignment (the SoD policy), every BC | **Command-handler precondition** — read the originating aggregate's `Listing.Drafted` (or equivalent maker envelope) and reject the checker command if `actor.actor_id == maker.actor_id` on the same record. `MakerChecker.Blocked` envelope on rejection. | C4, DL-033 |
| X12 | **Agency provenance.** Every envelope with `actor.actor_type=agency` references an active `AgencyConsent`. | BC8 AgencyConsent, every BC where agency acts | **Command-handler precondition** in each affected context: load the consent referenced in `actor.agency_consent_id`; reject if not active. `AgencyAction.Recorded` sidecar emitted in BC8. | DL-013, G5, AC.1, AC.3 |
| X13 | **Audit publish-before-success.** No command returns success until the corresponding envelope has been appended to BC14. | every aggregate, BC14 | **In-process transactional outbox** (G17 monolith). Producer writes aggregate state and envelope in one local transaction; outbox flushes to BC14 before returning success. Phase 2 broker world replaces outbox with broker publish + sync acknowledgement (G27 — new). | C1, C2, C3, AE.4, G17, G27 |
| X14 | **Tenant isolation.** Every read from a per-tenant context filters on the actor's tenant claims. | BC10 AdminUser session, every BC's repository layer | **Repository-layer enforcement** (G19) — claims injected at session establishment; queries that omit tenant filter are rejected at framework level, not at handler. Never UI-only. | C16, G19 |
| X15 | **Reconciliation correction is a new envelope.** A provisional inflow proved wrong at EoD emits a new corrective `InflowReconciled` with `corrects` set; subscribers in BC2 update their projection; the original envelope is preserved (G23). | BC4 ReconciliationLedger, BC2 Subscription | **Subscriber on corrective envelope** — BC2 treats `corrects`-bearing envelope as authoritative; the previous `Subscription.FundsReceived` envelope is not deleted; projections are recomputed. | B2 P6, G23, C23 |
| X16 | **Auditor account SoD.** An AuditorAccount cannot share user with any operational-role-holder. | BC13 AuditorAccount, BC10 RoleAssignment | **Subscriber** at `AuditorAccount.Approved` checks BC10 for any RoleAssignment on the same user-identity; rejects with `CommandRejected` envelope (G22). | C19, AA13.3, G21 |
| X17 | **Concentration warnings are read-side.** Soft warnings (S.8) read a projection of the investor's positions but never block. Restoration of the warning state across distributions is a projection refresh, not an aggregate operation. | BC2 Subscription, projection layer | **Projection** — no aggregate state involved; warnings recorded on `Subscription.Committed` envelope payload (`concentration_warnings_at_commit`) for audit; not enforced. | DL-011, G30 (new) |

---

## 4. Aggregate ⇄ Decision/Constraint/Gap Traceability

A condensed cross-reference. Every aggregate row in §2 cites at least one DL, Cxx, Gxx, or BCxx; every invariant cites at least one. The matrix below is the inverse view — for the most-referenced constraints, which aggregates enforce them.

| Source | Enforcing aggregates / invariants |
|---|---|
| **C1** Immutable audit log | BC14 AuditEvent (AE.1–4); referenced by every aggregate's command set via X13 |
| **C2** State-changing actions audit-logged | Every aggregate via X13; BC16 DocumentObject DO.3 for reads |
| **C3** Audit-the-auditor | BC13 AuditorAccount AA13.5 |
| **C4** Record-level maker-checker | BC1 Listing L.4, BC3 DefaultCase DC, BC4 PayoutInstruction PI.5, BC8/BC9 Suspend commands, BC11 KycFile KF.1, BC13 AuditorAccount AA13.1, X11 |
| **C5** Role SoD two-tier | BC10 RoleAssignment RA.1–3, SodPolicy SP |
| **C6** Threshold four-eyes | BC3 BuyerCreditProfile BCP.2, SupplierCreditProfile SCP.2, DefaultCase DC.2 |
| **C7** MFA mandatory admin | BC10 AdminUser AU10.2–3 |
| **C8** Per-listing VA segregation | BC4 VirtualAccount V.1, V.5 |
| **C9** Idempotent fund-movement | BC4 PayoutInstruction PI.1, BC18 VendorInstruction VI.1, BC17 V17.1, BC19 VSR.1 |
| **C10** Webhook integrity | BC17 V17.4, BC18 VI.2, BC19 VSR.2 |
| **C11** T+1 disbursement/distribution | BC4 PayoutInstruction PI.8 |
| **C12** Listing funding invariants | BC1 Listing L.2, L.6, L.8, L.9; BC2 Subscription S.5; X1 |
| **C16** Tenant isolation | BC10 AdminUser TenantClaim; X14 (repository layer) |
| **C17** Annual KYC refresh | BC11 KycRefreshSchedule KR.1; BC7 IA.6; BC8 SA8.5 |
| **C19** Auditor JIT, scoped, account-SoD | BC13 AuditorAccount AA13.1–6; X16 |
| **C20** Invite-gated investor onboarding | BC7 Invite I.1–4 |
| **C21** Suitability override-ack | BC7 InvestorAccount IA.4; SuitabilityAssessment SA.2 |
| **C23** Reconciliation authoritative | BC4 VirtualAccount V.2, V.4; ReconciliationLedger RL.3; PayoutInstruction PI.7; BC18 VI.4; X3, X15 |
| **C24** Source-of-truth verification | BC1 Invoice INV.7; BC7 IA.8; BC8 SA8.3; BC9 BA.4; BC17 V17.2–3 |
| **C27** Direct assignment, no NBFC | BC1 Listing L.5; BC5 AssignmentSet AS.3 |
| **DL-002** Direct assignment | BC1 Listing L.5; BC5 AssignmentSet |
| **DL-007** ₹10K minimum ticket | BC2 Subscription S.1 |
| **DL-008** Invite gating | BC7 Invite I.1–5 |
| **DL-011** Soft concentration | BC2 Subscription S.8; X17 |
| **DL-013** Agency transparency | BC8 AgencyConsent AC.1–3; X12 |
| **DL-017** 100% / 5-day / refund | BC1 Listing L.2, L.6–9; BC2 S.2; X1, X6 |
| **DL-019** Per-invoice acknowledgment | BC1 Listing AcknowledgmentRequested/Received commands; BC9 BA.2 |
| **DL-022** Credit policy | BC3 all aggregates |
| **DL-023** Threshold four-eyes | BC3 BCP.2, SCP.2, DC.2 |
| **DL-024** Policy-driven pricing | BC3 PricingPolicy PB.1–4; BC1 Listing L.7, L.10 |
| **DL-029** Case-by-case default classification | BC3 DefaultCase DC.1–4; BC6 MaturityCase MC.2–4 |
| **DL-030** T+1 timing | BC4 PayoutInstruction PI.8 |
| **DL-040** Immutable audit log | BC14 AuditEvent |
| **DL-043** Per-listing VA | BC4 VirtualAccount V.1; BC18 VendorInstruction |
| **DL-045** Platform calculates TDS | BC12 TaxYearProfile, TdsDeduction; BC4 PayoutInstruction PI.3 |
| **DL-048** e-Sign + master stamping | BC5 MasterAgreement MA.1–3; BC19 VSR |
| **DL-050** KYC stack | BC7 IA.3, IA.7–8; BC8 SA8.3; BC11 KycFile KF |
| **G3** Refund SLA | BC2 S.2; X6 |
| **G4** TDS at distribution instruction | BC12 TdsDeduction TD.2; BC4 PayoutInstruction PI.3 |
| **G5** Agency consent artefact | BC8 AgencyConsent |
| **G6** Reconciliation engine | BC4 ReconciliationLedger RL; X3, X15 |
| **G10** "100% funded" semantics | BC1 Listing L.2, L.6; BC2 S.3; X1 |
| **G11** Multi-leg payout atomicity | BC4 PayoutInstruction PI.6; RemediationCase RC.1–3; BC18 VI.5 |
| **G13** Per-investor assignment time-box | BC5 AssignmentSet AS.2–4; X6 |
| **G18** Cross-context command idempotency | every aggregate's command surface (`(actor_id, command_id)` dedupe) |
| **G19** Tenant isolation layer | BC10 TenantClaim; X14 |
| **G20** Pricing band in-flight invariance | BC1 Listing L.3, L.7, L.10; BC3 PricingPolicy PB.3–4; X4 |
| **G21** Auditor account provisioning | BC13 AA13.1–2; X16 |
| **G22** CommandRejected envelopes | every aggregate (P6); referenced X11, X16 |
| **G23** Reconciliation correction shape | BC4 RL.3; BC2 (subscriber treats corrective as authoritative); X15 |
| **G26** Suitability override hash | BC7 IA.4; SuitabilityAssessment SA.2 |

---

## 5. Phase 2 Aggregate Hooks

Per C25/C26, aggregates carry dormant fields. Summarised here for completeness; details are at the BC level in B1 §5.4.

- **BC2 Subscription** — `wallet_attribution` field present, dormant (S.10).
- **BC7 InvestorAccount** — `sub_type` field accepts only `{resident_individual, huf}` in Phase 1 (IA.2); NRI/institutional/NBFC-Partner values rejected at command-handler.
- **BC8 SupplierAccount** — `udyam` field dormant.
- **BC9 BuyerAccount** — `relationship_tier`, `acknowledgment_mode` fields accept only `acknowledged_buyer` / `per_invoice` in Phase 1 (BA.2).
- **BC13 AuditorAccount** — regulator-inspection sub-type schema-supported, command-handler rejects in Phase 1 (DL-042).
- **BC4 / BC18** — pull-mode mandate-management aggregate stubbed; not wired (no command surface in Phase 1).
- **BC17 Verification** — Video-KYC / V-CIP `api_name` reserved but command-handler rejects (DL-050, G16).

---

## 6. Gap Log Additions from B3

Four new working assumptions surface at the aggregate-design layer. Proposed entries for the Gap Log:

| # | Gap | Working Assumption | Status | Resolve By | Blocks |
|---|---|---|---|---|---|
| G27 | Cross-aggregate atomicity in monolith vs broker | In Phase 1 monolith (G17), cross-aggregate "coordinated commits" (X1 Listing+Subscription, X13 audit publish-before-success) use a local DB transaction with an outbox flush. In Phase 2 broker world, X1 becomes a saga with a reservation hold + confirm/release; X13 becomes broker-publish-with-sync-ack. The aggregate boundaries themselves do not change; the mechanism does | Assumed | Architect | Subscription commit handler, Listing committed_total update, audit outbox |
| G28 | Recovery window for loss realisation | After `DefaultCase.Classified(defaulted)`, a 12-month recovery window is opened; on expiry, `Subscription.LossRealised` fires for any non-zero residual. Window may be extended once by Credit Reviewer + Founder. Each `Recovery.Achieved` during window emits a partial distribution and reduces eventual loss | Assumed | Founder + Credit Lead | BC2 Subscription S.7, BC6 MaturityCase MC.5, X10 |
| G29 | Maker-checker on cross-aggregate commands | When a command touches two aggregates (e.g. CancelPreDisbursement updates Listing and triggers subscription refunds), maker-checker is evaluated on the *initiating* aggregate (Listing). The downstream effects inherit the same maker-checker decision via causation; no second pair-check on the dependent aggregates | Assumed | Architect | BC1 Listing CancelPreDisbursement, BC4 RemediationCase Resolve, X11 |
| G30 | Concentration projection refresh cadence | Soft concentration warnings (S.8) are computed from a read-side projection of the investor's positions. The projection is refreshed on every `Subscription.Committed`, `Subscription.Refunded`, `Subscription.Closed`. There is no separate "concentration aggregate"; the rule is read-side and warning-only (DL-011). If a stale projection misses a warning at commit time, no business consequence — the warning is advisory | Assumed | Founder | BC2 S.8, X17 |

These become G27–G30 in the project Gap Log, joining G22–G26 proposed in B2 §6.

---

## How to use this document

- **B4 (Command/Query APIs).** Each command in §2 becomes a command endpoint with the named actor as the authorised caller and the named events as the success outputs. Failed-but-authorised commands return a `*.CommandRejected` envelope (G22) with the proposed `after_state` and the failing invariant.
- **B4 (Query side).** Read-model projections are built from the envelope streams. The concentration warnings (X17), the per-investor portfolio (S.8 inputs), and the reconciliation summary (X3) are projection responsibilities, not aggregate state.
- **Architect (deployment).** Aggregate boundaries here are the irreducible consistency boundaries; module/service decomposition may consolidate aggregates within one context but never split one aggregate across deployment units. Cross-aggregate rules in §3 inform Phase 1 monolith transaction scope (G17) and Phase 2 saga design (G27 — new).

Cross-references:
- **Decision Log:** DL-001 through DL-050.
- **Constraints:** C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11, C12, C13, C14, C15, C16, C17, C18, C19, C20, C21, C22, C23, C24, C25, C26, C27, C28.
- **Contexts:** BC1–BC19. Every aggregate listed in B1 §1 is given a row here.
- **Events:** every aggregate's command set names the B2 §3 events it emits on success.
- **Gaps used:** G1, G2, G3, G4, G5, G6, G7, G8, G9, G10, G11, G12, G13, G14, G15, G16, G17, G18, G19, G20, G21, G22, G23, G24, G25, G26.
- **Gaps proposed:** G27, G28, G29, G30.
