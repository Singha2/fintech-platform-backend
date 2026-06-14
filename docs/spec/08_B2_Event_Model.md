# B2 — Domain Event Model

*Phase 1 MVP. The platform's published language. Every state-changing action surfaces as a typed domain event owned by exactly one bounded context. Subscribers in other contexts react by emitting their own events and mutating their own state. The Audit Log is the universal sink. Inputs: DL-001 through DL-050; A1 constraints C1–C28; A2 integration contracts; B1 contexts BC1–BC19 and Gap Log G1–G21. Output: anchor for B3 (Aggregates) and the API/component design that follows.*

---

## 1. Principles

**P1. One event, one owner.** Every event has exactly one owning bounded context. Only that context mutates state in response to producing it. Cross-context effects happen via *subscribers* who, on consumption, emit their own events in their own context.

**P2. Events describe facts, not intentions.** Past tense, immutable. `BuyerAcknowledgmentReceived`, not `AcknowledgeBuyer`. Commands and queries are not events.

**P3. The envelope is universal.** Every event in the system — domain event, sensitive read, agency action, role change, fund-movement instruction, vendor webhook, auditor activity — is wrapped in the same `AuditEventEnvelope` (B1 §2) and written to the Audit Log (BC14) before the producer returns success (C1, C2, C3).

**P4. At-least-once everywhere.** No exactly-once delivery is assumed inside or outside the platform. Subscribers dedupe on `event_id`. Producers dedupe on `(actor_id, command_id)` (G18).

**P5. Ordering is local.** Total order within an aggregate; causal order within a context via `causation_id`; correlation across contexts via `correlation_id`. No global ordering guarantee, and none required (§5.1).

**P6. Retro corrections are new events.** Past envelopes are immutable (C1). A reconciliation correction, a reversed approval, or a re-classified default emits a new event that references the original via `causation_id` and carries a `corrects` field. The Audit Log is grown, not edited.

---

## 2. AuditEventEnvelope

Concrete wire format. Every context conforms; no exceptions.

### 2.1 Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `event_id` | UUIDv7 | yes | Unique per event. UUIDv7 gives lexicographic time order for tie-breaking; not a substitute for `occurred_at`. |
| `event_type` | string | yes | Fully-qualified: `<context>.<aggregate>.<verb-past-tense>` e.g. `listing.Listing.GoneLive`. Stable identifier. |
| `event_version` | int | yes | Schema version of `payload`. Starts at 1. Bumped on any backward-incompatible payload change. See §5.5. |
| `schema_uri` | string | yes | Pointer to the schema document for `(event_type, event_version)`. Resolvable in dev/staging; archival reference in prod. |
| `occurred_at` | timestamp (UTC, µs) | yes | When the fact happened in the producer's clock. |
| `recorded_at` | timestamp (UTC, µs) | yes | When Audit Log received the envelope. `recorded_at ≥ occurred_at` always. Gap is producer-to-log latency. |
| `context` | enum BC1..BC19 | yes | Owning bounded context (B1 §1). |
| `aggregate_type` | string | yes | e.g. `Listing`, `Subscription`, `InvestorAccount`. From B1 §1. |
| `aggregate_id` | string | yes | Identity of the aggregate root being written. |
| `aggregate_version` | int | yes | Monotonic per-aggregate sequence. First event = 1. |
| `actor` | object | yes | See §2.2. |
| `correlation_id` | UUID | yes | Stable across all events in a logical transaction (one user click, one webhook, one cron tick). Producer of the root event mints it; every downstream event copies it forward. |
| `causation_id` | UUID | yes | `event_id` of the immediately preceding event that caused this one. Null only for root events (user-initiated commands, scheduled jobs, inbound webhooks at the ACL edge). |
| `command_id` | UUID | conditional | Present on every event produced from a command handler. Idempotency key for the command (G18). Null on webhook-driven or scheduler-driven events (which use `external_ref` instead). |
| `external_ref` | object | conditional | For webhook-originated events: `{vendor, vendor_event_id, signature_verified_at}`. Null otherwise. Banking ACL and Verification ACL use this for at-least-once dedupe (G1, A2 §1.2, §2.2). |
| `payload` | object | yes | Event-type-specific. Schema lives at `schema_uri`. Money fields use the shared kernel `Money` type (paise integer + INR). Indian identifiers (PAN, GSTIN, IFSC, IRN) typed per B1 §2. |
| `before_state` | object | conditional | Required on state-transition events. Snapshot of the affected fields before the transition. |
| `after_state` | object | conditional | Required on state-transition events. Snapshot after the transition. |
| `corrects` | object | conditional | For retro corrections: `{original_event_id, reason}`. See §5.3. |
| `previous_envelope_hash` | bytes32 | yes | SHA-256 of the immediately previous envelope in the Audit Log's append-only chain. Cryptographic chaining (G7). |
| `envelope_hash` | bytes32 | yes | SHA-256 over canonical encoding of this envelope minus `envelope_hash` itself. Tamper-evidence. |

### 2.2 Actor sub-shape

```
actor: {
  actor_type: enum {
    admin_user,
    investor,
    supplier_user,
    buyer_ack_user,
    auditor,
    system_scheduler,
    vendor_aggregator,
    vendor_escrow,
    vendor_signing,
    agency  // admin acting on behalf of supplier — DL-013, G5
  },
  actor_id: string,         // user id, system component name, or vendor name
  session_id: string,       // null for non-user actors
  agency_principal_id: string,  // present iff actor_type=agency; the supplier on whose behalf
  agency_consent_id: string,    // reference to AgencyConsent artefact (G5)
  mfa_assertion_id: string  // null for non-admin actors; required for admin actors (C7)
}
```

The agency sub-fields make every "acting on behalf of supplier" action distinguishable in the audit log without any further bookkeeping (DL-013, BC8).

### 2.3 Correlation and causation conventions

- **Correlation** is set once at the root of a logical transaction and propagated to every descendant event, regardless of context. A trace is the set of all envelopes sharing one `correlation_id`.
- **Causation** is local: each event's `causation_id` points to the single envelope that directly caused it. Walking causation backwards from any event reconstructs its lineage; walking forward from a root reconstructs the tree.
- **Roots** of correlation chains: user commands (UI click, API call), inbound webhooks at the ACL edge, scheduled-job firings, and admin maker-checker approvals.

### 2.4 Idempotency placement

- **Command-originated events** carry `command_id`. Producer dedupes the command on `(actor_id, command_id)` before mutating state (G18). The resulting event's `command_id` is the same value.
- **Webhook-originated events** carry `external_ref.vendor_event_id`. The ACL (BC17/BC18/BC19) dedupes on it (first-write-wins). Downstream events lose `external_ref` but retain `correlation_id` and chain via `causation_id`.
- **Scheduler-originated events** carry `command_id = sha256(job_name || scheduled_at)` — deterministic so retried job runs at the same instant produce the same id.

### 2.5 Versioning

`event_version` is an integer per `event_type`. Backward-compatible payload additions (new optional field) do not bump the version. Any rename, removal, type change, or semantic shift bumps it. Old and new versions coexist; consumers handle the versions they understand and ignore the rest. `schema_uri` is the authoritative shape.

### 2.6 What an envelope is NOT

- Not a command. Commands are inputs; envelopes record outcomes. A command may produce zero, one, or many envelopes.
- Not a query. Queries do not mutate state and do not write envelopes — *except* sensitive reads (C2), which write a `*.SensitiveReadPerformed` envelope with empty `before_state`/`after_state`.
- Not a read-model projection. Projections are internal materialisations of envelope streams into the query layer; they do not themselves emit envelopes.

---

## 3. Event Catalogue

By owning bounded context. Each row: name, trigger, key payload fields, principal subscribers, and the DL/Cxx/Gxx/BCxx reference it implements. Names are shortened in the table; the fully-qualified `event_type` is `<context>.<aggregate>.<Name>`.

### 3.1 BC1 — Listing & Invoice

| Event | Trigger | Payload (key fields) | Subscribers | Refs |
|---|---|---|---|---|
| `Invoice.Submitted` | Supplier (or agency) submits IRN | `invoice_id, supplier_id, irn, face_value:Money, tenor_days` | BC17 (kicks IRN+e-way-bill verify) | DL-016, BC8 |
| `Invoice.OperationalChecksPassed` | All checks green | `invoice_id, checks:[name→outcome]` | BC1 (advances Listing) | DL-027, C24 |
| `Invoice.OperationalChecksFailed` | Any check failed | `invoice_id, failed_checks` | BC15 (notify supplier+admin) | DL-027 |
| `Listing.Drafted` | Listing created from approved invoice | `listing_id, invoice_id, supplier_id, buyer_id` | none directly | Spec §6.4 |
| `Listing.AcknowledgmentRequested` | After ops checks pass | `listing_id, buyer_id, ack_user_id, sla_deadline` | BC9, BC15 | DL-019 |
| `Listing.AcknowledgmentReceived` | Buyer user ack | `listing_id, ack_user_id, ack_method:{portal\|admin_captured}` | BC1 | DL-019, BC9 |
| `Listing.AcknowledgmentFailed` | SLA expired or buyer refused | `listing_id, reason` | BC15, BC3 (escalation) | Spec §4.2 |
| `Listing.PricingApplied` | Auto-applied from snapshot | `listing_id, pricing_band_id, rate_bps, fee_bps` | none directly | DL-024 |
| `Listing.SnapshotTaken` | Before ready_for_review | `listing_id, pricing_band:{snapshot}, buyer_limit_headroom:Money, supplier_exposure_cap:Money` | BC1 (locks invariance) | G20 |
| `Listing.ReadyForReview` | Snapshot complete | `listing_id` | BC10 (maker-checker queue) | C4 |
| `Listing.GoneLive` | T&S approval | `listing_id, va_id, funding_window_close_at` | BC4 (VA create), BC2 (window open), BC15 | DL-017, DL-030, C12 |
| `Listing.FullyFunded` | Funded total == face-value-less-discount-less-fee | `listing_id, total_committed:Money, investor_count` | BC5 (assignments), BC4 (disbursement prep) | DL-017, C12 |
| `Listing.FundingShortfallDeclared` | Window closed without 100% | `listing_id, total_committed, shortfall:Money` | BC2 (per-subscription refund eligibility), BC4 (refund prep) | DL-017, G3 |
| `Listing.DisbursementGateOpened` | `all_signed` from BC5 received | `listing_id` | BC4 (T&S approval queue) | C27 |
| `Listing.Disbursed` | Settlement confirms supplier credit | `listing_id, supplier_payout_ref, net_amount:Money` | BC6 (maturity tracking starts) | DL-030 |
| `Listing.Matured` | Buyer-payment inflow reconciled (full) | `listing_id, buyer_payment_ref, amount:Money, maturity_date` | BC4 (distribution prep), BC12 (TDS calc) | DL-030 |
| `Listing.MaturityShortfall` | Buyer paid less than expected | `listing_id, expected:Money, received:Money` | BC6 (raise as delay or claim) | Spec §4.2 |
| `Listing.Distributed` | Settlement confirms all distribution legs (or terminal failure) | `listing_id, distribution_outcome` | BC2 | DL-030 |
| `Listing.Closed` | Terminal — distributed, defaulted, or shortfall-refunded | `listing_id, terminal_state` | BC15 (statement triggers) | Spec §6.4 |
| `Listing.HeldForReview` | Assignment time-box expired (G13) | `listing_id, reason` | BC3 (Credit Reviewer queue) | G13 |
| `Listing.CancelledPreDisbursement` | Founder/Credit kills funded-but-undisbursed listing | `listing_id, reason` | BC2 (refunds), BC4 | Spec §6.4 |

### 3.2 BC2 — Subscription

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Subscription.Committed` | Investor commits ₹X to a live listing | `subscription_id, listing_id, investor_id, amount:Money, ticket_min_check:passed, concentration_warnings:[…]` | BC4 (expect inflow), BC15 (push instructions) | DL-007, DL-009, DL-011, C12, C16 |
| `Subscription.CancelledByInvestor` | Investor cancels before `funds_received` | `subscription_id, reason` | BC4 (cancel expected-inflow record) | Spec §6.5 |
| `Subscription.FundsReceived` | Reacting to BC4 `InflowReconciled` matched to this subscription | `subscription_id, va_id, txn_ref, amount:Money` | BC1 (toward fully-funded check) | DL-044, C9 |
| `Subscription.Confirmed` | Funds-received plus invariants (rupee equality, ticket min) | `subscription_id` | BC5 (per-investor assignment cohort) | DL-007, G10 |
| `Subscription.AssignmentExecuted` | Reacting to BC5 `AssignmentSet.AllSigned`, narrowed to this investor's doc | `subscription_id, assignment_doc_hash` | BC4 (disbursement gate participant) | C27 |
| `Subscription.RefundEligible` | Reacting to BC1 `FundingShortfallDeclared` or `CancelledPreDisbursement` or BC5 `AssignmentSet.Incomplete` | `subscription_id, refund_amount:Money` | BC4 (refund queue) | DL-017, G3, G13 |
| `Subscription.Refunded` | Reacting to BC4 `RefundExecuted` | `subscription_id, refund_ref` | BC15 | DL-017, G3 |
| `Subscription.DistributionReceived` | Reacting to BC4 `DistributionLegExecuted` | `subscription_id, gross:Money, tds:Money, fee:Money, net:Money` | BC12 (statement input) | DL-045 |
| `Subscription.Closed` | Distribution received, refund executed, or loss realised | `subscription_id, terminal_state` | none | Spec §6.5 |
| `Subscription.LossRealised` | Reacting to BC3 `DefaultClassified` (and recovery exhausted) | `subscription_id, loss:Money` | BC12 | DL-029, Spec §6.5 |
| `Subscription.SoftConcentrationWarningRaised` | At commit time | `subscription_id, dimension:{buyer\|supplier\|invoice}, current, threshold` | none (audit only) | DL-011 |

### 3.3 BC3 — Credit & Underwriting

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `BuyerCreditProfile.Created` | Credit Reviewer creates profile | `buyer_id, sector, rating_source, rating` | BC9 | DL-022 |
| `BuyerCreditLimit.Set` | Limit set/changed; honours four-eyes | `buyer_id, limit:Money, four_eyes_approval_ref` | BC9, BC1 (snapshot input) | DL-023, C6 |
| `PricingBand.Set` | Band published | `pricing_band_id, buyer_id, tenor_bucket, rate_range_bps, fee_bps` | BC1 (snapshot input) | DL-024 |
| `SupplierCreditProfile.Approved` | Supplier underwriting outcome | `supplier_id, risk_rating, exposure_cap:Money, four_eyes_approval_ref` | BC8, BC1 (snapshot input) | DL-022, DL-023, C6 |
| `SupplierExposureCap.Changed` | Periodic review or exception | `supplier_id, old:Money, new:Money, reason` | BC1 (future snapshots) | DL-022 |
| `FourEyesApproval.Granted` | Second approver consent on > ₹1 Cr | `proposal_id, first_approver, second_approver, decision:approved` | originating context | DL-023, C6 |
| `FourEyesApproval.Denied` | Second approver dissent | `proposal_id, reason` | originating context | DL-023 |
| `CreditException.Adjudicated` | Listing-level exception decision | `listing_id, decision, conditions` | BC1 | DL-022 |
| `DefaultCase.Classified` | Final classification on `under_adjudication` case | `case_id, listing_id, outcome:{disputed\|dilution\|fraud\|defaulted\|recovered}, rationale` | BC6, BC2, BC4 | DL-029 |
| `PeriodicCreditReview.Completed` | Quarterly/annual review on buyer or supplier | `entity_id, entity_type, outcome` | BC9, BC8 | DL-022 |

### 3.4 BC4 — Settlement

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `VirtualAccount.Created` | Reacting to `Listing.GoneLive`, after Banking ACL confirms | `va_id, listing_id, ifsc, account_no` | BC1, BC15 | DL-043, C8 |
| `VirtualAccount.Closed` | Listing terminal or refund complete | `va_id, listing_id, reason` | BC1 | DL-043 |
| `InflowObserved` | Reacting to BC18 `InflowWebhookProcessed` | `va_id, txn_ref, amount:Money, sender_bank_details, provisional:true` | BC4 (reconciler) | DL-044, C23 |
| `InflowReconciled` | EoD reconciliation overlay or match to expected-inflow record | `va_id, txn_ref, amount:Money, matched_to:{subscription_id\|maturity}` | BC2 or BC1 | C23, G6 |
| `InflowUnmatched` | EoD shows no match | `va_id, txn_ref, amount:Money` | BC4 (RemediationCase) | G6 |
| `DisbursementInstructed` | T&S approves; sent to BC18 | `listing_id, supplier_payout_ref, gross:Money, fee:Money, net:Money, client_instruction_id` | BC18 | DL-030, C9, C11 |
| `Disbursement.Executed` | Provider confirmed via webhook + EoD overlay | `listing_id, supplier_payout_ref, utr` | BC1 (`Listing.Disbursed`) | DL-030, C23 |
| `DistributionInstructed` | T&S approves; per-investor multi-leg payload built with TDS snapshot | `listing_id, legs:[{investor_id, gross, tds, fee, net}], total_tds:Money, client_instruction_id` | BC18 | DL-045, G4 |
| `DistributionLeg.Executed` | Per-leg success (provider webhook + EoD overlay) | `listing_id, leg_index, investor_id, utr, net:Money` | BC2 | DL-030, G11 |
| `DistributionLeg.Failed` | Per-leg failure | `listing_id, leg_index, investor_id, failure_code` | BC4 (RemediationCase) | G11 |
| `Distribution.Completed` | All legs terminal | `listing_id, success_count, failure_count` | BC1 | DL-030 |
| `RefundInstructed` | Listing failed-to-fund / cancelled / assignment-incomplete | `subscription_id, refund_amount:Money, client_instruction_id` | BC18 | DL-017, G3, G13 |
| `Refund.Executed` | Provider confirmed | `subscription_id, refund_ref, utr` | BC2 | G3 |
| `TdsChallanRecorded` | Reacting to BC18 `TdsChallanWebhookProcessed` | `listing_id, challan_ref, total_tds:Money, period` | BC12 | DL-045 |
| `Reconciliation.DiscrepancyDetected` | EoD overlay flags a mismatch | `va_id, expected, observed` | BC4 (RemediationCase), BC15 (admin alert) | C23, G6 |
| `Reconciliation.Completed` | EoD batch run finished | `business_date, summary` | BC14 only (audit) | C23 |
| `ManualRemediation.Opened` | Auto-routed from failure | `case_id, trigger, summary` | BC15 | G11 |
| `ManualRemediation.Resolved` | T&S closes the case | `case_id, resolution, linked_corrective_event_id` | BC4 | G11 |

### 3.5 BC5 — Assignment & Signing

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `MasterAgreement.Initiated` | Supplier/investor reaches signing stage of onboarding | `agreement_id, party_id, party_type, doc_hash` | BC19 | DL-048 |
| `MasterAgreement.Signed` | Reacting to BC19 `SignatureCompleted` for the MAA/MIA | `agreement_id, party_id, doc_hash, signature_cert_serial` | BC7 or BC8 | DL-048, C2 |
| `MasterAgreement.SigningFailed` | Reacting to BC19 `SignatureFailed`/`Expired` | `agreement_id, party_id, reason` | BC7 or BC8 (rollback to prior state) | A2 §3.6 |
| `MasterStamping.Completed` | Reacting to BC19 `StampIssued` for master-level | `agreement_id, stamp_cert_id` | BC7 or BC8 | DL-048 |
| `AssignmentSet.Requested` | Reacting to BC1 `Listing.FullyFunded` | `assignment_set_id, listing_id, investors:[{investor_id, subscription_id, allocation:Money}], time_box_close_at` | BC5 (issues per-investor signature requests), BC15 | DL-002, C27, G13 |
| `AssignmentSignature.Initiated` | Per-investor doc generated, vendor session opened | `assignment_set_id, investor_id, doc_hash, signature_request_id` | BC19, BC15 | C27 |
| `AssignmentSignature.Completed` | Reacting to BC19 `SignatureCompleted` | `assignment_set_id, investor_id, doc_hash, signature_cert_serial` | BC2 (`Subscription.AssignmentExecuted` if all_signed) | C27, DL-002 |
| `AssignmentSignature.Failed` | Reacting to BC19 `SignatureFailed`/`Expired` after retries | `assignment_set_id, investor_id, reason` | BC5 (aggregate check) | A2 §3.6 |
| `AssignmentSet.AllSigned` | Last per-investor signature lands within time-box | `assignment_set_id, listing_id` | BC1 (`DisbursementGateOpened`), BC4 | C27 |
| `AssignmentSet.Incomplete` | Time-box expired with unsigned legs | `assignment_set_id, listing_id, signed_count, unsigned_count` | BC1 (`Listing.HeldForReview`), BC3 (Credit Reviewer escalation) | G13 |
| `PerInvoiceStamp.Pending` | Per-invoice stamping placeholder (PARKED-LEGAL) | `assignment_set_id, status:pending_legal_resolution` | none | DL-048, G2, A2 §3.6 |

### 3.6 BC6 — Collections & Recovery

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `MaturityTracking.Started` | Reacting to BC1 `Listing.Disbursed` | `listing_id, maturity_date, reminder_schedule` | BC15 (T-3, T-1, T reminders) | DL-029 |
| `MaturityReminder.Sent` | Scheduled fire | `listing_id, reminder_type, dispatch_ref` | none | Spec §4.1 |
| `DelayStatus.Progressed` | Days-past-due crosses threshold | `listing_id, old_status, new_status, days_past_due` | BC1 (visibility), BC15 (investor disclosure when `delayed`) | DL-029 |
| `HardCollections.Initiated` | Status reaches `seriously_delayed` | `listing_id, panel_lawyer_assignment_id` | BC15 | DL-028 |
| `CollectionsAction.Recorded` | Manual collections action by Ops | `listing_id, action_type, notes_doc_hash` | none | DL-028 |
| `Claim.Raised` | Dilution or fraud signal | `claim_id, listing_id, claim_type, evidence_doc_hash` | BC3 (classification request) | DL-015 |
| `Classification.Requested` | Status reaches `under_adjudication` | `case_id, listing_id, days_past_due, prior_evidence` | BC3 | DL-029 |
| `Recovery.Achieved` | Inflow matched against a defaulted/disputed case | `case_id, recovered:Money, source` | BC2 (partial distribution), BC4 | DL-029 |
| `Claim.Resolved` | Final resolution of dilution/fraud claim | `claim_id, outcome, supplier_indemnity_amount:Money` | BC4 (if indemnity), BC8 | DL-015 |

### 3.7 BC7 — Investor Onboarding

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Invite.Issued` | Compliance Reviewer issues code | `invite_id, email_hash, phone_hash, expiry_at, referrer_id, justification` | BC15 (dispatch via email+SMS) | DL-008, DL-036, C20, G9 |
| `Invite.Consumed` | Invitee opens onboarding URL with valid code | `invite_id, investor_account_id` | BC7 | DL-008 |
| `Invite.Expired` | 14d window lapsed unused | `invite_id` | none | DL-008 |
| `Invite.Revoked` | Compliance Reviewer revokes | `invite_id, reason` | none | DL-008 |
| `InvestorAccount.SignedUp` | Email+phone OTP verified | `investor_account_id, email, phone` | BC17 (kick PAN+Aadhaar verify) | Spec §2.4 |
| `InvestorIdentity.Verified` | PAN + Aadhaar e-KYC complete | `investor_account_id, pan, aadhaar_last4` | BC11 (AML screen kickoff) | DL-050, C15 |
| `InvestorKyc.Submitted` | All KYC docs uploaded | `investor_account_id, doc_hashes:[…]` | BC11 (review queue) | DL-050 |
| `InvestorSuitability.Assessed` | Questionnaire complete | `investor_account_id, profile, mismatch:bool` | none | Spec §2.4, C21 |
| `InvestorSuitability.OverrideAcknowledged` | Investor explicitly proceeds despite mismatch | `investor_account_id, override_text_hash` | none | C21 |
| `InvestorFinancialProfile.Completed` | Penny-drop + nominee + FATCA captured | `investor_account_id, bank_account_last4` | BC11 | Spec §2.4 |
| `InvestorKyc.Approved` | Reacting to BC11 `KycFile.Approved` for an investor | `investor_account_id, approver_id` | BC5 (MIA initiation) | DL-050 |
| `InvestorKyc.Rejected` | Reacting to BC11 `KycFile.Rejected` | `investor_account_id, reason` | BC15 | DL-050 |
| `InvestorMia.Signed` | Reacting to BC5 `MasterAgreement.Signed` for an investor | `investor_account_id, agreement_id` | BC7 (activation) | DL-048 |
| `InvestorAccount.Activated` | Terminal stage of onboarding | `investor_account_id, activated_at` | BC2 (now subscription-eligible), BC11 (refresh schedule) | DL-050 |
| `InvestorKycRefresh.Due` | Annual refresh fires | `investor_account_id, refresh_window_close_at` | BC15 | C17 |
| `InvestorAccount.Suspended` | AML hit or fraud signal | `investor_account_id, reason` | BC2 | DL-050 |
| `InvestorAccount.Dormant` | Inactivity threshold | `investor_account_id` | none | Spec §6.3 |
| `InvestorAccount.Exited` | Investor exits with no live positions | `investor_account_id` | none | Spec §6.3 |

### 3.8 BC8 — Supplier Onboarding

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `SupplierAccount.Created` | Admin initiates supplier onboarding | `supplier_id, legal_name, constitution_type` | BC17 (verify PAN/GSTIN/CIN) | DL-012 |
| `AgencyConsent.Granted` | Supplier click-through e-consent (G5) | `supplier_id, consent_id, scope, consent_doc_hash` | BC8 (subsequent agency actions reference this) | DL-013, G5 |
| `SupplierIdentity.Verified` | PAN+GSTIN+CIN cleared via BC17 | `supplier_id` | BC11 | DL-014, C24 |
| `SupplierKyc.Submitted` | Constitution docs, signatory KYC, UBO captured | `supplier_id, doc_hashes:[…]` | BC11 | Spec §2.2 |
| `SupplierFinancialProfile.Submitted` | Financials, GST returns, AA bank, top buyers captured | `supplier_id, profile_doc_hashes` | BC3 (credit review queue) | DL-014 |
| `SupplierKyc.Approved` | Reacting to BC11 `KycFile.Approved` | `supplier_id, approver_id` | BC3 | DL-014 |
| `SupplierCreditReview.Outcome` | Reacting to BC3 `SupplierCreditProfile.Approved` | `supplier_id, exposure_cap:Money, risk_rating` | BC8 (advance to MAA stage) | DL-022 |
| `SupplierMaa.Signed` | Reacting to BC5 `MasterAgreement.Signed` | `supplier_id, agreement_id` | BC8 (activation) | DL-048 |
| `SupplierAccount.Activated` | Terminal stage | `supplier_id, activated_at` | BC1 (now listing-eligible), BC11 | Spec §2.2 |
| `AgencyAction.Recorded` | Every admin "acting on behalf" action | `supplier_id, action, consent_id, admin_user_id` | none (audit only — but BC8 may attach to relevant aggregate) | DL-013, G5 |
| `SupplierAccount.Suspended` | Fraud or dilution claim crossed threshold | `supplier_id, reason` | BC1 | Spec §6.1 |
| `SupplierAccount.Blacklisted` | Terminal sanction | `supplier_id, reason` | BC1 | Spec §6.1 |

### 3.9 BC9 — Buyer Management

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `BuyerAccount.Nominated` | Credit Reviewer initiates | `buyer_id, source` | BC17 (verify) | DL-018 |
| `BuyerIdentity.Verified` | MCA+GSTIN cleared via BC17 | `buyer_id` | BC3 (credit assessment) | DL-018, C24 |
| `BuyerCreditAssessment.Outcome` | Reacting to BC3 `BuyerCreditLimit.Set` | `buyer_id, limit:Money, pricing_band_id, conditions` | BC9 (engagement stage) | DL-022 |
| `BuyerEngagement.Started` | NOA sent | `buyer_id` | BC15 | Spec §2.3 |
| `AcknowledgmentUser.Designated` | Buyer's authorised user registered | `buyer_id, ack_user_id, email, phone` | BC1 (lookup target for ack requests) | DL-021 |
| `PaymentInstructions.Confirmed` | Escrow remittance instructions confirmed | `buyer_id, instruction_doc_hash` | BC4 | Spec §2.3 |
| `BuyerAccount.Activated` | Engagement complete | `buyer_id, activated_at` | BC1 (now eligible for listings against this buyer) | DL-018 |
| `BuyerAccount.Suspended` | Risk signal | `buyer_id, reason` | BC1 | Spec §6.2 |
| `BuyerLimit.Reduced` | Reacting to BC3 `BuyerCreditLimit.Set` (downward) | `buyer_id, new_limit:Money` | BC1 (future snapshots) | DL-022 |

### 3.10 BC10 — Admin IAM

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `AdminUser.Created` | Super Admin creates account | `admin_user_id, email` | BC15 | DL-031 |
| `Role.Assigned` | Role added to admin user | `admin_user_id, role, sod_warning?, override_reason?` | none (audit) | DL-032, C5 |
| `Role.Revoked` | Role removed | `admin_user_id, role` | none | DL-032 |
| `SodSoftDeviation.Logged` | Role combo triggers soft warn; admin overrides with reason | `admin_user_id, combo, reason, deviation_register_entry_id` | BC11 (quarterly review queue) | DL-033, C5 |
| `DeviationRegister.EntryReviewed` | Quarterly review | `deviation_register_entry_id, decision` | none | DL-033 |
| `Mfa.Enrolled` | TOTP/SMS enrolled | `admin_user_id, factor_type` | none | DL-035, C7 |
| `Mfa.ChallengeIssued` | Sign-in or sensitive action | `admin_user_id, challenge_id, factor_type` | none | C7 |
| `Mfa.ChallengeSucceeded` | Factor verified | `admin_user_id, challenge_id, assertion_id` | (downstream events reference assertion_id) | C7 |
| `Mfa.ChallengeFailed` | Wrong/expired code | `admin_user_id, challenge_id` | BC15 (alert on N failures) | C7 |
| `MakerChecker.Blocked` | Same individual attempted to check own maker action | `record_id, admin_user_id, action` | BC15 (admin alert) | C4, DL-033 |
| `MakerChecker.Approved` | Distinct individual approved | `record_id, maker_user_id, checker_user_id, decision` | originating context | C4 |
| `TenantClaim.Issued` | At session establishment | `admin_user_id, session_id, claims` | none (audit only) | C16, G19 |

### 3.11 BC11 — Compliance

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `AmlScreening.Initiated` | Investor/supplier identity verified | `subject_id, subject_type` | BC17 (kick `screen_aml_pep`) | DL-037, Spec §7.2 |
| `AmlScreening.Completed` | Reacting to BC17 verification result | `subject_id, match_score, hits:[…]` | BC11 (adjudication if hits) | DL-037 |
| `AmlHit.Adjudicated` | Compliance Reviewer decision on a hit | `subject_id, decision, rationale, sar_opened:bool` | BC7 or BC8 (suspend if needed) | DL-038 |
| `Sar.Opened` | Internal SAR raised | `sar_id, subject_id, summary_doc_hash` | none | DL-038 |
| `Sar.Documented` | SAR contents updated | `sar_id, doc_hash` | none | DL-038 |
| `KycFile.Approved` | Compliance Reviewer approves | `subject_id, subject_type, approver_id` | BC7 or BC8 | DL-050, C21 |
| `KycFile.Rejected` | Compliance Reviewer rejects | `subject_id, subject_type, reason` | BC7 or BC8 | DL-050 |
| `KycRefresh.Due` | Annual scheduler fires | `subject_id, subject_type, window_close_at` | BC7 or BC8 | C17 |
| `KycRefresh.Completed` | Refresh accepted | `subject_id, subject_type` | none | C17 |
| `SpotCheck.Completed` | Audit-trail spot check | `period, scope, findings_doc_hash` | none | Spec §7.1 |

### 3.12 BC12 — Tax & Reporting

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `TdsRate.Resolved` | Per-investor at distribution time | `investor_id, listing_id, rate_bps, basis` | none | DL-045, G4 |
| `Tds.Calculated` | Per-investor per-distribution | `investor_id, listing_id, gross:Money, tds:Money` | BC4 (input to instruction payload) | DL-045, G4 |
| `TdsDeduction.Recorded` | Reacting to BC4 `TdsChallanRecorded` | `investor_id, listing_id, tds:Money, challan_ref` | BC12 (Form 16A pipeline) | DL-045 |
| `Statement.Generated` | Monthly cycle per investor | `investor_id, period, doc_hash` | BC15 | Spec §2.4 |
| `Form16A.Issued` | Annual cycle | `investor_id, fy, doc_hash, total_tds:Money` | BC15 | DL-045, G12 |
| `TaxYear.Closed` | FY rollover bookkeeping | `fy` | none | Spec §2.4 |

### 3.13 BC13 — Auditor Access

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `AuditorAccount.Proposed` | Super Admin proposes account | `auditor_account_id, scope, validity_window` | BC10 (maker-checker queue), BC11 (approver) | G21, C19 |
| `AuditorAccount.Approved` | Compliance Reviewer approves | `auditor_account_id, approver_id` | BC13 (activation) | G21, C19 |
| `AuditorAccount.Activated` | Account credentials issued | `auditor_account_id, scope` | BC15 | C19 |
| `AuditorAccount.AutoDisabled` | End-date reached | `auditor_account_id` | none | C19 |
| `AuditorRead.Performed` | Every auditor read (UI or API) | `auditor_account_id, target_aggregate_type, target_aggregate_id, scope_check:passed` | none (audit only — C3) | C3, DL-039 |
| `AuditorExport.Performed` | Bulk export | `auditor_account_id, scope, row_count, byte_count` | none | C3, DL-041 |
| `AuditorRateLimit.Triggered` | Export cap exceeded | `auditor_account_id, limit_type` | BC15 (admin alert) | C19 |

### 3.14 BC14 — Audit Log

Owns no events of its own. Subscribes to every envelope emitted anywhere in the system and is the universal sink. Maintains the cryptographic chain (G7) and the 10-year WORM retention (C1).

### 3.15 BC15 — Notifications

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Notification.Dispatched` | Outbound email or SMS sent | `dispatch_id, channel, recipient_hash, template_id, related_aggregate_ref` | none | DL-049 |
| `Notification.DispatchFailed` | Vendor reported failure | `dispatch_id, reason` | BC15 (retry policy) | DL-049 |

Notifications are fire-and-forget; delivery failure does not roll back business state (B1 §4.4) but is itself an envelope.

### 3.16 BC16 — Documents

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Document.Stored` | New artefact written | `doc_hash, content_type, originating_context, originating_aggregate_ref` | none | C13, C14 |
| `Document.SensitiveReadPerformed` | Read of KYC or financial doc | `doc_hash, reader_id, purpose` | none | C2, Spec §7.1 |
| `SignedUrl.Issued` | Time-bound signed URL minted | `doc_hash, reader_id, expires_at` | none | Spec §9 |

### 3.17 BC17 — Verification (Aggregator ACL)

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Verification.Requested` | Domain context invokes ACL | `verification_id, subject_id, api_name, client_request_id` | BC17 itself (vendor call) | A2 §1.3, C24 |
| `Verification.Completed` | Vendor response received and parsed | `verification_id, api_name, result, vendor_payload_hash, ttl_until` | originating domain context | A2 §1.3, C24 |
| `Verification.Failed` | 5xx, timeout, or business mismatch after retries | `verification_id, api_name, failure_class` | originating context, BC15 | A2 §1.5, G8 |
| `Verification.Stale` | TTL hit at use-time | `verification_id, api_name, original_completed_at` | originating context (re-verify) | A2 §1.4 |
| `AggregatorOutage.Declared` | Healthcheck/timeout pattern threshold crossed | `from_at, suspected_api` | BC15 (admin banner), originating contexts | A2 §1.5, G8 |
| `ManualFallback.Invoked` | Ops captures manually with elevated approval | `verification_id, captured_by, co_signer` | originating context | A2 §1.5, G8 |
| `WebhookSignature.Invalid` | HMAC verification failed | `vendor, raw_payload_hash` | BC15 (alert) | C10 |

### 3.18 BC18 — Banking (Escrow ACL)

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `Va.LifecycleObserved` | Webhook on VA create/close | `va_id, lifecycle:created\|closed` | BC4 | DL-043 |
| `InflowWebhookProcessed` | Inflow webhook signature-verified and deduped | `va_id, vendor_event_id, txn_ref, amount:Money, sender_details, utr` | BC4 | DL-044, C10, C22 |
| `PayoutLegWebhookProcessed` | Per-leg outcome webhook | `client_instruction_id, leg_index, outcome:success\|failure, utr?, failure_code?` | BC4 | DL-030, G11, C10, C22 |
| `RefundWebhookProcessed` | Refund outcome | `client_instruction_id, outcome, utr?` | BC4 | G3 |
| `TdsChallanWebhookProcessed` | Challan generation event | `client_instruction_id, total_tds:Money, challan_ref` | BC4 | DL-045 |
| `MasterStatement.Fetched` | EoD pull (or push) of master statement | `business_date, statement_hash` | BC4 (reconciler input) | C23, G6 |
| `WebhookSignature.Invalid` | HMAC verification failed | `vendor_event_hint, raw_payload_hash` | BC15 (alert) | C10 |
| `Webhook.DuplicateDropped` | `vendor_event_id` already seen | `vendor_event_id` | none | C22, §2.4 |

### 3.19 BC19 — Signing ACL

| Event | Trigger | Payload | Subscribers | Refs |
|---|---|---|---|---|
| `SignatureSession.Initiated` | BC5 calls ACL | `signature_request_id, client_request_id, signer_id, doc_hash, vendor_session_url` | BC5 | A2 §3.2 |
| `SignatureCompleted` | Vendor webhook | `signature_request_id, signer_id, doc_hash, cert_serial, ip, signed_pdf_hash` | BC5 | A2 §3.3, C2 |
| `SignatureFailed` | Vendor webhook (failure or expiry after retries) | `signature_request_id, reason` | BC5 | A2 §3.6 |
| `StampIssued` | Stamp webhook | `stamp_request_id, stamp_cert_id, value, state` | BC5 | DL-048 |
| `StampFailed` | Stamp webhook | `stamp_request_id, reason` | BC5 | DL-048 |
| `WebhookSignature.Invalid` | HMAC failure | `vendor_event_hint` | BC15 | C10 |

---

## 4. End-to-End Event Traces

Each trace is a single `correlation_id` (root supplied below). Envelopes listed in causal order. Vendor ACL hops shown where they materially shape the chain; auditor reads and notification dispatches omitted unless they alter business state.

### 4.1 Trace A — Happy-path invoice lifecycle

Root: supplier (via agency) submits IRN. One investor, ₹3 lakh listing, single subscriber, sunny weather.

```
listing.Invoice.Submitted                        (root, command_id=C1)
  → verification.Verification.Requested          (api=verify_irn)
  → verification.Verification.Completed          (irn valid)
  → verification.Verification.Requested          (api=verify_eway_bill)
  → verification.Verification.Completed
listing.Invoice.OperationalChecksPassed
listing.Listing.Drafted                          (aggregate_id=L1)
listing.Listing.AcknowledgmentRequested
  ↪ buyer.AcknowledgmentUser.Designated (already exists; lookup)
  ↪ notifications.Notification.Dispatched (email+SMS to ack user)
listing.Listing.AcknowledgmentReceived           (root2, buyer ack user action)
listing.Listing.PricingApplied                   (from snapshot)
listing.Listing.SnapshotTaken                    (G20: invariance lock)
listing.Listing.ReadyForReview
admin_iam.MakerChecker.Approved                  (T&S approves go-live)
listing.Listing.GoneLive
  → banking.Va.LifecycleObserved (created)
  → settlement.VirtualAccount.Created
investor pushes ₹3L into VA
  → banking.InflowWebhookProcessed
  → settlement.InflowObserved                    (provisional)
  → subscription.Subscription.Committed          (caused by prior commit; reconcile matches here)
  → settlement.InflowReconciled                  (matched to subscription)
  → subscription.Subscription.FundsReceived
  → subscription.Subscription.Confirmed
listing.Listing.FullyFunded
  → assignment.AssignmentSet.Requested
    → assignment.AssignmentSignature.Initiated
      → signing.SignatureSession.Initiated
      → signing.SignatureCompleted
    → assignment.AssignmentSignature.Completed
  → assignment.AssignmentSet.AllSigned
listing.Listing.DisbursementGateOpened
admin_iam.MakerChecker.Approved                  (T&S approves disbursement)
settlement.DisbursementInstructed
  → banking.PayoutLegWebhookProcessed (success)
settlement.Disbursement.Executed
listing.Listing.Disbursed
collections.MaturityTracking.Started
[time passes; reminders sent at T-3, T-1, T]
buyer pays maturity amount into VA
  → banking.InflowWebhookProcessed
  → settlement.InflowObserved
  → settlement.InflowReconciled                  (matched to listing maturity)
listing.Listing.Matured
tax.TdsRate.Resolved
tax.Tds.Calculated
settlement.DistributionInstructed                (carries TDS snapshot — G4)
  → banking.PayoutLegWebhookProcessed (success)
  → banking.TdsChallanWebhookProcessed
settlement.DistributionLeg.Executed
settlement.TdsChallanRecorded
tax.TdsDeduction.Recorded
settlement.Distribution.Completed
subscription.Subscription.DistributionReceived
subscription.Subscription.Closed
listing.Listing.Distributed
listing.Listing.Closed
tax.Statement.Generated (next month-end)
```

### 4.2 Trace B — Funding shortfall + refund

Same listing L1, two subscribers committing ₹1L and ₹1.5L respectively against a ₹3L need. Window expires.

```
[…listing reaches GoneLive as in §4.1…]
subscription.Subscription.Committed             (sub-A, ₹1L)
banking.InflowWebhookProcessed                  (₹1L)
settlement.InflowReconciled                     (matched)
subscription.Subscription.FundsReceived
subscription.Subscription.Confirmed
subscription.Subscription.Committed             (sub-B, ₹1.5L)
banking.InflowWebhookProcessed                  (₹1.5L)
settlement.InflowReconciled
subscription.Subscription.FundsReceived
subscription.Subscription.Confirmed
[total ₹2.5L; window closes; T+5 scheduler fires]
listing.Listing.FundingShortfallDeclared        (command_id=deterministic from job)
  → subscription.Subscription.RefundEligible    (sub-A)
  → subscription.Subscription.RefundEligible    (sub-B)
  → settlement.RefundInstructed                 (sub-A)
  → settlement.RefundInstructed                 (sub-B)
  → banking.RefundWebhookProcessed (×2)
  → settlement.Refund.Executed (×2)
  → subscription.Subscription.Refunded (×2)
  → subscription.Subscription.Closed (×2)
listing.Listing.Closed                           (terminal: funding_failed_refunded)
settlement.VirtualAccount.Closed
```

### 4.3 Trace C — Per-investor assignment, one signer abandons

Listing L2 fully funds with five investors, ₹50K each. Investor #4 abandons signing flow after the session opens; SMS retry exhausted; 24h time-box (G13) expires.

```
listing.Listing.FullyFunded
assignment.AssignmentSet.Requested              (5 legs, time_box_close_at = now+24h)
  → assignment.AssignmentSignature.Initiated   (×5)
    → signing.SignatureSession.Initiated (×5)
  → signing.SignatureCompleted (×4, for investors 1,2,3,5)
  → assignment.AssignmentSignature.Completed (×4)
  → signing.SignatureSession.Initiated         (retry attempt 2 for investor 4)
  → signing.SignatureSession.Initiated         (retry attempt 3 for investor 4)
  → signing.SignatureFailed                    (investor 4, after 3 attempts)
  → assignment.AssignmentSignature.Failed      (investor 4)
[24h scheduler tick after AssignmentSet.Requested]
assignment.AssignmentSet.Incomplete             (signed=4, unsigned=1)
listing.Listing.HeldForReview                   (Credit Reviewer queue — G13)
admin_iam.MakerChecker.Approved                 (Credit Reviewer decides to abort)
listing.Listing.CancelledPreDisbursement
  → subscription.Subscription.RefundEligible   (×5 — all 5 are refunded per G13)
  → settlement.RefundInstructed (×5)
  → banking.RefundWebhookProcessed (×5)
  → settlement.Refund.Executed (×5)
  → subscription.Subscription.Refunded (×5)
  → subscription.Subscription.Closed (×5)
listing.Listing.Closed
settlement.VirtualAccount.Closed
```

The 4 investors who *did* sign are still refunded: their assignment never crystallised because `AssignmentSet.AllSigned` never fired and disbursement was gated on it (C27). The signed-but-unused per-investor docs remain in the Documents context, hashed in the audit log; no further action.

### 4.4 Trace D — Delayed payment progressing to default classification

Listing L3 disbursed; buyer fails to pay at maturity. Default declared after adjudication.

```
listing.Listing.Disbursed                        (T0)
collections.MaturityTracking.Started             (maturity_date=Tm)
[Tm reached; no inflow]
collections.DelayStatus.Progressed               (on_track → mildly_delayed)   [Tm+1, scheduler]
collections.MaturityReminder.Sent                (multiple, over Tm+1..Tm+7)
collections.DelayStatus.Progressed               (mildly_delayed → delayed)    [Tm+8]
[investor visibility opens per DL-029]
collections.DelayStatus.Progressed               (delayed → seriously_delayed) [Tm+16]
collections.HardCollections.Initiated            (panel-lawyer engaged)
collections.CollectionsAction.Recorded           (notice issued)
collections.DelayStatus.Progressed               (seriously_delayed → under_adjudication) [Tm+31]
collections.Classification.Requested
credit.FourEyesApproval.Granted                  (if exposure > ₹1 Cr — C6)
credit.DefaultCase.Classified                    (outcome=defaulted)
  → subscription.Subscription.LossRealised (×N)
  → subscription.Subscription.Closed (×N)
listing.Listing.Closed                           (terminal: defaulted)
settlement.VirtualAccount.Closed
[future] collections.Recovery.Achieved           (if and when something recovers)
  → settlement.DistributionInstructed (partial)
  → […distribution chain…]
  → subscription.Subscription.DistributionReceived (correction event — §5.3)
```

The recovery branch is open-ended: a single `DefaultCase` can produce zero, one, or many later `Recovery.Achieved` events, each driving a partial distribution. Each partial distribution emits *new* envelopes; no past envelope is rewritten.

### 4.5 Trace E — Investor KYC: invite → active

Compliance Reviewer issues invite to a known referral. Investor goes through the eight-stage onboarding.

```
investor_onboarding.Invite.Issued                (root, by Compliance Reviewer)
  → notifications.Notification.Dispatched (email)
  → notifications.Notification.Dispatched (sms)
[invitee opens link, OTP-verifies email and phone]
investor_onboarding.Invite.Consumed
investor_onboarding.InvestorAccount.SignedUp
  → verification.Verification.Requested (verify_pan)
  → verification.Verification.Completed
  → verification.Verification.Requested (verify_aadhaar_ekyc)
  → verification.Verification.Completed
investor_onboarding.InvestorIdentity.Verified
  → documents.Document.Stored (×3: address proof, photo, signature)
investor_onboarding.InvestorKyc.Submitted
  → compliance.AmlScreening.Initiated
    → verification.Verification.Requested (screen_aml_pep)
    → verification.Verification.Completed (no hits)
  → compliance.AmlScreening.Completed
investor_onboarding.InvestorSuitability.Assessed (no mismatch)
  → verification.Verification.Requested (verify_penny_drop)
  → verification.Verification.Completed
investor_onboarding.InvestorFinancialProfile.Completed
[Compliance Reviewer reviews and approves]
compliance.KycFile.Approved
investor_onboarding.InvestorKyc.Approved
  → assignment.MasterAgreement.Initiated         (MIA + POA + risk disclosure)
    → signing.SignatureSession.Initiated
    → signing.SignatureCompleted
  → assignment.MasterAgreement.Signed
  → signing.StampIssued                          (master-level — DL-048)
  → assignment.MasterStamping.Completed
investor_onboarding.InvestorMia.Signed
investor_onboarding.InvestorAccount.Activated
  → compliance.KycRefresh.Due (scheduled at +12 months — C17)
```

The investor is now subscription-eligible (BC2 subscribes to `InvestorAccount.Activated`).

---

## 5. Cross-cutting Conventions

### 5.1 Ordering guarantees

| Scope | Guarantee | Mechanism |
|---|---|---|
| Within an aggregate | Total order | `aggregate_version` is monotonic; producer holds an exclusive write on the aggregate |
| Within a context, across aggregates | Causal | `causation_id` chain; subscribers walk it backwards as needed |
| Across contexts | Causal via `correlation_id`; no global timestamp ordering | Producers preserve `correlation_id` end-to-end; subscribers do not assume wall-clock ordering |
| In the Audit Log | Arrival order | `previous_envelope_hash` chains all envelopes in arrival order; this defends against tampering, not against reorderings |

Consumers that need a deterministic ordering across contexts (e.g. the reconciler) build it from `correlation_id` + `aggregate_version`, not from `recorded_at`.

### 5.2 At-least-once semantics

- Every published envelope is delivered at least once to every subscriber.
- Subscribers dedupe on `event_id` (in-context replay) or `correlation_id`+`aggregate_version` tuple (cross-context replay).
- Webhook fan-in dedupes at the ACL boundary on `external_ref.vendor_event_id` (G1, A2 §2.2). Banking ACL's `Webhook.DuplicateDropped` envelope records the discarded duplicate for audit (C2) but does not propagate further.
- Producers dedupe commands on `(actor_id, command_id)` (G18). If a command handler is invoked twice with the same `command_id`, the same envelope chain is produced; downstream subscribers — being idempotent — converge to the same state.

### 5.3 Retro events

Past envelopes are immutable (C1). Corrections happen by emitting a new envelope with:
- `corrects.original_event_id` pointing at the envelope being corrected,
- `corrects.reason` describing why,
- `causation_id` pointing at whatever triggered the correction (e.g. an EoD reconciliation tick).

Typical retro shapes:
- `settlement.Reconciliation.DiscrepancyDetected` is a retro hint; it does not modify any prior event but signals that a corrective envelope is incoming.
- `settlement.InflowReconciled` with `corrects` set, used when a provisional reconciliation is reversed at EoD.
- `credit.DefaultCase.Classified` with `corrects` set, used when a classification is changed (defaulted → recovered, etc.).
- `tax.Form16A.Issued` with `corrects` set, on reissue.

Subscribers treat a corrective envelope as authoritative for state but preserve the original in their own projections for audit.

### 5.4 What is NOT an event

| Concept | Why not |
|---|---|
| Commands (e.g. "submit invoice") | Inputs, not facts. They may produce zero, one, or many envelopes. Commands are deduped via `command_id` independently of envelopes. |
| Queries / sensitive reads of non-sensitive data | No state change. Sensitive reads of KYC/financial data *do* write envelopes (C2). |
| Read-model projections | Internal materialisations of envelope streams. They mutate query stores; they do not emit envelopes. |
| UI state | Browser-side only; never trusted, never logged. |
| Cron job firing without effect | If the job runs and there's nothing to do, no envelope. The cron tick itself is not an event. |

### 5.5 Versioning and evolution

- `event_version` starts at 1 per `event_type`.
- Adding an optional payload field is non-breaking; version unchanged.
- Renaming, removing, type-changing, or shifting the semantic of a field bumps the version.
- A context may emit both v1 and v2 of the same `event_type` during a migration window. Subscribers handle the versions they understand; envelopes they don't recognise are written to Audit Log unchanged and ignored otherwise.
- Old envelopes in storage are never rewritten on a version bump. Schemas at `schema_uri` are kept indefinitely (retention aligned with C1 — 10 years).

### 5.6 Envelope authoring discipline

- Every command handler that succeeds emits at least one envelope.
- Every command handler that fails *visibly* (i.e. the actor was authenticated and authorised but the business operation rejected) emits a `*.CommandRejected` envelope with `before_state`, the proposed `after_state`, and the rejection reason. This is the *only* exception to the past-tense rule — and even here, the fact recorded is "the command was rejected", which is itself a fact (G22 — new).
- Webhook handlers always emit envelopes, even on duplicates (`Webhook.DuplicateDropped`) — visibility is the point.
- Schedulers emit envelopes only when they take action; an idle tick is not logged.
- Notifications dispatches always emit envelopes; delivery success and failure are both facts.

---

## 6. Gap Log Additions from B2

Four new working assumptions surface at the event-model layer. Proposed entries for the Gap Log:

| # | Gap | Working Assumption | Status | Resolve By | Blocks |
|---|---|---|---|---|---|
| G22 | `CommandRejected` envelope semantics | Failed-but-authorised commands emit a `*.CommandRejected` envelope with proposed `after_state` and reason. Used for audit of attempted-but-blocked actions (e.g. MakerChecker.Blocked is a specialisation). Quietly-failed commands at network/auth layer do NOT emit; those live in infrastructure logs | Assumed | Architect | B3 aggregate API, audit completeness |
| G23 | Reconciliation correction event shape | Provisional inflow reversals and EoD overlay corrections emit `InflowReconciled` (or sibling) with `corrects` set. Subscribers treat corrective envelope as authoritative; projections preserve original for audit. No envelope is ever rewritten | Assumed | Architect | Settlement reconciler, BC2 projection design |
| G24 | Correlation propagation across vendor boundary | `correlation_id` does not survive a round-trip through an external vendor. Banking ACL re-establishes `correlation_id` from the platform-side `client_instruction_id` it stored when issuing the outbound call; webhook envelopes inherit it. No vendor receives or returns our `correlation_id` | Assumed | Architect | Banking/Verification/Signing ACL implementations |
| G25 | Cryptographic chain segmentation | Per-shard chains for the Audit Log substrate (G7) — global single chain unworkable at throughput. Shard key = `(context, business_date)` or similar. Each shard's chain is independently verifiable; cross-shard ordering is via `recorded_at` and `correlation_id`, not the chain | Assumed | Architect | Audit Log substrate selection (G7) |
| G26 | Suitability override-acknowledgment payload | The override text shown to the investor at suitability mismatch is itself a versioned artefact; the envelope stores `override_text_hash` and the version, not the text. Documents context stores the canonical text by hash. Allows the displayed disclosure to evolve without losing audit fidelity | Assumed | Founder + counsel | BC7 investor onboarding flow, disclosure artefact lifecycle |

---

## How to use this document

- **B3 (Aggregates).** Every aggregate listed in B1 §1 emits a defined subset of these events. B3 fixes which fields are state vs. derived and how invariants are checked.
- **B4 (APIs).** Command endpoints accept `command_id` headers; the envelopes they produce are observable via Audit Log queries and via per-aggregate event streams.
- **Architect (deployment).** Substrate choices for envelope publication (in-process pub/sub vs broker), Audit Log storage (G7, G25), and reconciliation engine (G6) are made against this contract. The contract holds across either choice.

Cross-references:
- **Decision Log:** DL-001 through DL-050.
- **Constraints:** C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11, C12, C13, C14, C15, C16, C17, C18, C19, C20, C21, C22, C23, C24, C27.
- **Contexts:** BC1–BC19 (every context owns at least one event row or — for BC14 — is a universal subscriber).
- **A2 Integration Contracts:** §1 → BC17 events, §2 → BC18 events, §3 → BC19 events.
- **Gaps used:** G1, G2, G3, G4, G5, G6, G7, G8, G9, G10, G11, G12, G13, G18, G19, G20, G21.
- **Gaps proposed:** G22, G23, G24, G25, G26.
