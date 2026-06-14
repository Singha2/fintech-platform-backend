# Bounded Contexts Reference — Fintech Invoice Discounting Platform

**Bounded Context (BC)** — a slice of the domain with its own language, rules, data, and lifecycle. The platform is partitioned into 19 contexts. Each context owns specific aggregates and tables (prefixed `bcN_` in the schema).

The context map is deployment-agnostic: each BC may be a module in a monolith or a standalone service. For Phase 1 MVP, all 19 live in a single Spring monolith, enforcing context boundaries in code (ArchUnit).

---

## Quick Reference Table

| # | Context | Owner | Key Aggregates | SQL Prefix | Core Refs |
|---|---|---|---|---|---|
| **BC1** | Listing & Invoice | Invoice intake → listing lifecycle | Invoice, Listing | `bc1_` | DL-016, DL-017, DL-024, C12, C24 |
| **BC2** | Subscription | Investor commitment to listing | Subscription | `bc2_` | DL-007, DL-009, DL-011, C12, C16 |
| **BC3** | Credit & Underwriting | Pricing, buyer limits, default classification | BuyerCreditProfile, SupplierCreditProfile, PricingPolicy, DefaultCase | `bc3_` | DL-022, DL-023, DL-024, DL-025, DL-029, C6 |
| **BC4** | Settlement | All money movement — disbursement, distribution, reconciliation | PayoutInstruction, ReconciliationLedger, RemediationCase, VirtualAccount | `bc4_` | DL-030, DL-043, DL-044, DL-045, C8, C9, C11, C12, C22, C23 |
| **BC5** | Assignment & Signing | Legal assignment + e-sign workflow | MasterAgreement, AssignmentSet, SignatureRequest | `bc5_` | DL-002, DL-048, C27 |
| **BC6** | Collections & Recovery | Post-disbursement maturity tracking, default handling, claim adjudication | MaturityCase, CollectionsAction, ClaimCase | `bc6_` | DL-025, DL-029, DL-046 |
| **BC7** | Investor Onboarding | Invite → KYC → suitability assessment → active | Invite, InvestorAccount, SuitabilityAssessment | `tblInvestor*` (counterparty) | DL-008, DL-010, DL-050, C7, C16, C20, C21 |
| **BC8** | Supplier Onboarding | Supplier KYC, agency consent (admin acts on behalf) | SupplierAccount, AgencyConsent, SupplierFinancialProfile | `tblSupplier*` (counterparty) | DL-012, DL-013, DL-050, C16 |
| **BC9** | Buyer Management | Buyer onboarding, acknowledgment user management | BuyerAccount, AcknowledgmentUser | `tblBuyer*`, `tblAcknowledgment*` (counterparty) | DL-014, DL-019, DL-021, C16 |
| **BC10** | Admin IAM | Admin users, role assignment, SoD enforcement, maker-checker | AdminUser, RoleAssignment, SodPolicy | `tblAdminUser`, `tblRole*` (counterparty) | DL-033, DL-035, DL-040, C4, C5, C7, C19 |
| **BC11** | Compliance | KYC file approval/rejection, AML screening, SAR case management | KycFile, AmlScreening, SarCase | `tblKyc*`, `tblAml*` (counterparty) | DL-037, DL-038, DL-050, C14, C15 |
| **BC12** | Tax & Reporting | TDS calculation, investor tax statements | TaxYearProfile, TdsDeduction | `tblTax*`, `tblTds*` (counterparty) | DL-045, G4 |
| **BC13** | Auditor Access | Time-bound auditor account provisioning, read-only scoped access | AuditorAccount, AccessScope | `tblAuditor*` (counterparty) | DL-039, DL-042, C16, C20, G21 |
| **BC14** | Audit Log | Immutable cryptographically-chained event envelope store | AuditEvent | `tblAuditEvent` (generic_acl) | DL-040, C1, C2 |
| **BC15** | Notifications | Transactional email + SMS dispatch | NotificationDispatch | `tblNotificationDispatch` (generic_acl) | DL-049 |
| **BC16** | DevOps / Config | Managed deviation register for exception tracking | DeviationEntry | `tblDeviationEntry` (counterparty) | DL-033 |
| **BC17** | Verification ACL | Aggregator integration (PAN, Aadhaar, GST, IRN, bureau) | Verification, VendorInstruction | `tblVerification`, `tblVendorInstruction` (generic_acl) | A2 §1, DL-016, DL-050 |
| **BC18** | Banking ACL | Escrow provider integration (VA creation, payouts, inflow reconciliation, webhooks) | VendorInstruction, InflowObservation | `tblVendorInstruction`, `tblInflowObservation` (generic_acl) | A2 §2, DL-030, DL-043, G6, G11 |
| **BC19** | e-Sign ACL | E-sign vendor integration (master agreement signature, webhook receipt) | VendorSignatureRequest | `tblVendorSignatureRequest` (generic_acl) | A2 §3, DL-048 |

---

## Core Domain (BC1–BC6)

### BC1 — Listing & Invoice

**Responsibility:** invoice intake through listing lifecycle end-to-end.

- Supplier submits invoice (IRN auto-fetch or manual entry)
- Operational checks: IRN validity, buyer acknowledgment requirement, buyer/supplier active status
- Listing state machine: `draft` → `ops_checks_in_progress` → `ops_checks_passed` → `acknowledged` → `priced` → `ready_for_review` → `live` → `fully_funded` → `disbursement_gate_ready` → `disbursed` → `matured` → `closed` (or alternate paths: `ops_checks_failed`, `acknowledgment_failed`, `funding_shortfall`, `cancelled`)
- Pricing application from BC3 snapshot
- Buyer acknowledgment coordination with BC9
- Funding window open/close signals to BC2
- 100% funding threshold + refund eligibility (DL-017)
- Emits `Listing.GoneLive` → BC4 creates VA, BC2 opens subscription window

**Aggregates:** Invoice, Listing  
**Key Invariants:** operational checks pass before listing can proceed (C24); pricing snapshotted at go-live, never re-read (G20); 100% funded equality across BC2, BC1, BC4 (G10)  
**Subscribers:** BC2 (funding window), BC4 (disbursement gate), BC5 (assignments), BC6 (maturity), BC9 (ack), BC15 (notify)

---

### BC2 — Subscription

**Responsibility:** per-investor commitment to a listing, full lifecycle.

- Investor subscribes to a live listing (post-go-live signal from BC1)
- States: `committed` → `funds_pending` → `funds_received` → `confirmed` → `assignment_executed` → `assignment_received` → `distributed` → `closed` (or `cancelled_pre_disbursement`, `refunded`)
- Enforces ₹10K minimum ticket (DL-007)
- Soft concentration warnings (DL-011) — no hard block Phase 1
- Pre-confirmation cancellation allowed
- Tracks investor funds inflow, refund eligibility on shortfall (DL-017, G3)
- Rupee equality with listing VA balance at confirmation (G10)
- Coordinated commit with BC1 under monolith (G17 / in-process pub/sub)

**Aggregates:** Subscription  
**Key Invariants:** minimum ₹10K; at confirmation, `Σ Subscription.confirmed = Listing.committed_total = VirtualAccount.observed_inflow` (modulo refunds, G10, G23); pre-cancellation window enforced  
**Upstream:** BC1 (Listing.GoneLive signals window open), BC4 (ReconciliationLedger corrects funds_received state)  
**Downstream:** BC4 (refund instructions), BC5 (per-investor assignment targets)

---

### BC3 — Credit & Underwriting

**Responsibility:** credit policy — pricing bands, buyer limits, supplier risk, default classification.

- Buyer credit profile: per-buyer credit limit, concentration defaults, tenor bands
- Supplier credit profile: supplier risk tier, exposure cap
- Pricing policy: rate-of-discount (RoD) bands, fee bps, per buyer + tenor
- Default case adjudication: classifies listing outcome (full recovery, partial, total loss)
- Threshold four-eyes approval (≤ ₹1 Cr single reviewer; > ₹1 Cr second approver, DL-023, C6)
- Publishes effective pricing band + buyer-limit headroom + supplier exposure cap to BC1 at snapshot time (G20)
- Changes post-go-live do NOT affect in-flight listings (G20)

**Aggregates:** BuyerCreditProfile, SupplierCreditProfile, PricingPolicy, DefaultCase  
**Key Invariants:** pricing band in-flight invariance (G20); four-eyes on override decisions (C6); default classification is final and immutable  
**Upstream:** BC8 (supplier profile trigger), BC9 (buyer credit limit assignment)  
**Downstream:** BC1 (snapshot read-only), BC6 (default classification for claims)

---

### BC4 — Settlement

**Responsibility:** platform-side all money movement — disbursement, distribution, reconciliation, TDS deduction.

- Virtual account (VA) creation per listing (DL-043)
- Disbursement instruction to escrow: date, supplier bank, amount (T+1, DL-030)
- Distribution instruction to escrow: per-investor, net + TDS (T+1, DL-030)
- Reconciliation engine: real-time per-webhook, EoD master-statement overlay (G6)
- Remediation queue for failed payout legs (G11)
- TDS snapshot into instruction payload at instruction time (G4)
- Manual refund instruction on shortfall (DL-017)
- Treasury & Settlement role gates every instruction (C8, C9, C11, C12, C22, C23)

**Aggregates:** VirtualAccount, PayoutInstruction, ReconciliationLedger, RemediationCase  
**Key Invariants:** reconciliation authoritative (C23); every payout instruction idempotent on command_id (G18); TDS snapshotted at instruction time, not at distribution (G4); multi-leg payout atomicity enforced (G11)  
**Upstream:** BC1 (go-live triggers VA), BC2 (subscription confirmed → distribution target), BC5 (`all_signed` triggers disbursement gate), BC12 (TDS rate + amount)  
**Downstream:** BC2 (funds_received state transition), BC6 (maturity inflow tracking), BC18 (banking ACL webhook)

---

### BC5 — Assignment & Signing

**Responsibility:** legal assignment of invoices to investors + e-sign workflow.

- Assignment set creation on 100%-funded (BC1 event)
- Per-investor master agreement + schedule
- e-Sign request to vendor (DL-048)
- Signature receipt and validation
- `AllSigned` event triggers BC1 disbursement gate
- Direct assignment, no NBFC intermediary (DL-002, C27)

**Aggregates:** MasterAgreement, AssignmentSet, SignatureRequest  
**Key Invariants:** direct assignment (no NBFC, C27); all investors must sign before disbursement (C27); assignment time-box (G13)  
**Upstream:** BC1 (100%-funded event)  
**Downstream:** BC4 (all_signed → disbursement gate), BC1 (completes listing state machine)

---

### BC6 — Collections & Recovery

**Responsibility:** post-disbursement maturity tracking, buyer payment inflow, default handling, claim adjudication.

- Maturity case: tracks listing maturity date, buyer payment expected, actual received
- Inflow reconciliation with BC4
- Collections action: escalation, recovery attempt
- Claim case: when maturity shortfall or default, classify via BC3 DefaultCase
- Recovered fund distribution

**Aggregates:** MaturityCase, CollectionsAction, ClaimCase  
**Key Invariants:** maturity shortfall triggers refund eligibility (DL-017); claim classification immutable (DL-029)  
**Upstream:** BC1 (disbursed event), BC4 (reconciliation inflow), BC3 (default classification)  
**Downstream:** BC4 (recovery payout instruction)

---

## Counterparty Domain (BC7–BC9)

### BC7 — Investor Onboarding

**Responsibility:** invite-gated investor lifecycle — signup, KYC, suitability, activation.

- Invite issuance (Compliance Reviewer, DL-008)
- Investor signup via invite code (DL-008)
- Identity verification (tblIdentity in auth layer)
- KYC submission + file routing to BC11
- Suitability assessment (questionnaire + override ack, C21)
- Financial profile capture
- Activation (status=active, eligible to subscribe)
- MFA enrollment (2 auth layer, DL-035)

**Aggregates:** Invite, InvestorAccount, SuitabilityAssessment  
**Key Invariants:** invite-gated (DL-008, C20); suitability override requires ack signature (C21); MFA required for login (C7, DL-035); tenant isolation (C16); KYC re-screening on periodic refresh (DL-050)  
**Upstream:** BC11 (KYC approval)  
**Downstream:** BC2 (subscription eligibility)

---

### BC8 — Supplier Onboarding

**Responsibility:** supplier KYC + agency consent (admin acts on behalf, Phase 1).

- Supplier account creation (admin-initiated, DL-012)
- Agency consent artefact (admin acts as supplier, DL-013)
- Supplier KYC file submission + routing to BC11
- Supplier financial profile (PAN, bank account, GST)
- Activation (status=active, eligible to list invoices)
- In Phase 1, supplier has no login (admin-only work, DL-012)

**Aggregates:** SupplierAccount, AgencyConsent, SupplierFinancialProfile  
**Key Invariants:** no supplier login Phase 1 (DL-012); agency consent artefact present (G5, DL-013); tenant isolation (C16); KYC re-screening on periodic refresh (DL-050)  
**Upstream:** BC11 (KYC approval)  
**Downstream:** BC1 (listing eligibility check)

---

### BC9 — Buyer Management

**Responsibility:** buyer onboarding + acknowledgment user provisioning (per-invoice ack requirement, DL-019).

- Buyer account creation (admin-initiated)
- Buyer KYC file routing to BC11
- Per-invoice acknowledgment user provisioning (email + OTP login, DL-021)
- Acknowledgment submission (portal or admin-captured, DL-019)
- Buyer suspension → BC1 listing hold (X7)

**Aggregates:** BuyerAccount, AcknowledgmentUser  
**Key Invariants:** per-invoice acknowledgment required (DL-019); ack user is OTP-only login, no password (DL-021); tenant isolation (C16)  
**Upstream:** BC11 (KYC approval), BC1 (acknowledgment request)  
**Downstream:** BC1 (ack received → listing proceeds)

---

## Platform & Oversight (BC10–BC13)

### BC10 — Admin IAM

**Responsibility:** admin user account, role assignment, SoD enforcement, maker-checker policy.

- Admin account creation (Super Admin only, DL-035)
- Role assignment: Ops Executive, Credit Reviewer, Treasury & Settlement, Compliance Reviewer, Super Admin (5 roles)
- SoD enforcement: strict blocks (e.g., Credit Reviewer ⊕ Treasury & Settlement), soft overrides (DL-033, C5)
- Maker-checker for all platform state-changing commands (C4)
- MFA enrollment + assertion freshness validation (2 auth layer, C7, DL-035)
- Session management + tenant claims issuance (G19)

**Aggregates:** AdminUser, RoleAssignment, SodPolicy  
**Key Invariants:** strict SoD blocks (DL-033, C5); maker-checker on all commands (C4); MFA required (DL-035, C7); idempotent on (actor_id, command_id) (G18); tenant claims snapshot at session time (G19)  
**Upstream:** auth layer (identity, session, MFA)  
**Downstream:** every other context (subject to maker-checker / SoD at command time)

---

### BC11 — Compliance

**Responsibility:** KYC file approval/rejection, AML screening, SAR case management.

- KYC file review (investor, supplier, buyer, auditor)
- Approval/rejection → signals onboarding contexts (BC7, BC8, BC9)
- AML screening (name-matching against sanctions lists)
- SAR case identification (suspicious activity report, Phase 1 internal only, DL-038)
- KYC refresh scheduling (periodic re-screening, DL-050)

**Aggregates:** KycFile, AmlScreening, SarCase  
**Key Invariants:** KYC approval gates onboarding state machine (C15, DL-050); AML screening before activation (C14, DL-050); SAR internal-only Phase 1 (DL-038); re-screening on schedule (DL-050)  
**Upstream:** BC7, BC8, BC9 (KYC file submission)  
**Downstream:** BC7, BC8, BC9 (approval/rejection), BC11 scheduler (re-screening trigger)

---

### BC12 — Tax & Reporting

**Responsibility:** TDS calculation + investor tax statement generation.

- TDS rate determination per investor (income slab, treaty status)
- TDS amount calculation per distribution (paise-accurate)
- Investor statement generation (annual tax year)
- TDS reconciliation with BC4 (G4)

**Aggregates:** TaxYearProfile, TdsDeduction  
**Key Invariants:** TDS snapshotted into BC4 instruction payload at distribution time, not at distribution execution (G4, DL-045); paise-accurate calculation (C23)  
**Upstream:** BC4 (distribution instruction), BC2 (investor identity)  
**Downstream:** BC4 (TDS rate + amount feed)

---

### BC13 — Auditor Access

**Responsibility:** time-bound auditor account provisioning + read-only scoped access.

- Auditor account creation (Super Admin proposes, Compliance Reviewer approves, DL-039)
- Time-bound validity (valid_from / valid_until, auto-disable at expiry, DL-039, G21)
- Read-only access scopes (e.g., view listings, view subscription, audit log)
- SoD: auditor role bars combination with operational roles (C19)

**Aggregates:** AuditorAccount, AccessScope  
**Key Invariants:** time-bound with auto-disable scheduler (DL-039, G21); read-only (no state mutations); SoD bars operational role combination (C19); tenant isolation (C16)  
**Upstream:** auth layer (identity, session, time-bound validity check)  
**Downstream:** query APIs (subject to access scope filter)

---

## Generic / Cross-Cutting (BC14–BC16)

### BC14 — Audit Log

**Responsibility:** immutable cryptographically-chained event envelope store.

- Every state-changing command → `AuditEventEnvelope` appended
- Envelope structure: actor, timestamp, aggregate_id, command_type, outcome (accepted/rejected), payload, cryptographic hash of prior envelope
- 10-year retention (C1, DL-040)
- WORM storage (GCS Object Lock or equivalent, C1)
- Audit-logged audit access itself (audit log viewing is an action)

**Aggregates:** AuditEvent  
**Key Invariants:** immutable + cryptographically chained (C1, DL-040); append-only (no deletions); 10-year retention (C1); WORM at storage layer (C1); every read of audit log is itself logged (C2)  
**Upstream:** every context (all commands)  
**Downstream:** regulatory, forensic review, auditor access (BC13)

---

### BC15 — Notifications

**Responsibility:** transactional email + SMS dispatch.

- Email templates: invite codes, KYC status, listing status, subscription confirmation, payout receipt, etc.
- SMS: OTP, critical alerts
- Dispatch queue, retry logic, delivery tracking
- DND (do-not-disturb) list management

**Aggregates:** NotificationDispatch  
**Key Invariants:** email + SMS (DL-049); OTP delivery (C7); idempotent on (recipient, notification_type, reference_id)  
**Upstream:** BC1, BC2, BC7, BC8, BC9, BC10, BC11, BC12, BC4 (notification triggers)  
**Downstream:** external email/SMS provider (via banking ACL or direct SES/SNS)

---

### BC16 — DevOps / Config

**Responsibility:** managed deviation register + platform configuration.

- Deviation entry: exception to SoD, credit limit, KYC refresh, etc.
- Deviation register: who approved, when, expiry, reason
- Configuration: feature flags, rate limits, thresholds

**Aggregates:** DeviationEntry  
**Key Invariants:** every deviation recorded with approver + expiry (DL-033); audit-logged (C1, C2)  
**Upstream:** BC10 (deviation approval), BC3 (policy overrides)  
**Downstream:** BC10 (SoD bypass enforcement), every context (feature flags)

---

## Integration ACLs (BC17–BC19)

### BC17 — Verification ACL

**Responsibility:** aggregator integration — PAN, Aadhaar, GST, IRN, bureau verification.

- PAN verification: name + PAN match
- Aadhaar verification: UIDAI API (with consent, C15, DL-050)
- GST verification: GST number + business name match
- IRN verification: IRN validity, e-way-bill status
- Bureau verification: credit bureau score (investor / buyer)
- Vendor instruction dispatch → vendor webhook response

**Aggregates:** Verification, VendorInstruction  
**Key Invariants:** consent-driven (C15, DL-050); response enveloped + cryptographically signed (A2 §1); idempotent on (aggregate_id, instruction_id, vendor_id) (G18, C24)  
**Upstream:** BC1 (IRN verify), BC7 (identity verify), BC8 (GST verify), BC9 (buyer verify), BC11 (KYC depth verify)  
**Downstream:** upstream contexts (verification outcome)

---

### BC18 — Banking ACL

**Responsibility:** escrow provider integration — VA creation, payouts, inflow reconciliation, webhooks.

- Virtual account (VA) creation (per listing, DL-043)
- Payout instruction dispatch (disbursement, distribution, refund)
- Inflow observation: webhook receipt of incoming funds to VA
- Reconciliation: BC4 matches instruction status + observed inflow (G6, G23)
- Manual remediation: webhook failure → BC4 RemediationCase

**Aggregates:** VendorInstruction, InflowObservation  
**Key Invariants:** VA unique per listing (DL-043); payout instruction signed (C8, C23); inflow envelope signed (A2 §2, C23); idempotent on webhook receipt (G18)  
**Upstream:** BC4 (VA create, payout dispatch, inflow subscribe), BC1 (go-live triggers VA)  
**Downstream:** BC4 (inflow webhook → reconciliation state), BC2 (funds_received state)

---

### BC19 — e-Sign ACL

**Responsibility:** e-sign vendor integration — master agreement signature, signature receipt.

- Master agreement (docusign / equivalent): investor signature required
- Signature request dispatch to vendor
- Signature receipt: webhook confirms all signatures collected
- Public-site stamp (DL-048)

**Aggregates:** VendorSignatureRequest  
**Key Invariants:** all investors must sign before `AllSigned` event (C27); signature validation (A2 §3); idempotent on webhook receipt (G18)  
**Upstream:** BC5 (signature request dispatch)  
**Downstream:** BC5 (`AllSigned` event → BC1 disbursement gate)

---

## How to Use This Document

- **For schema review:** find your BC in the Quick Reference table, note the SQL prefix (`bc1_`, `bc2_`, etc.) or table names, and verify the tables match.
- **For API design:** each BC owns a set of commands (state-changing) and queries (read-only). Enforce BC boundaries in code — no cross-BC table joins in SQL; only event-driven, identity-based references.
- **For onboarding scenarios:** trace the journey: Investor Onboarding (BC7) → Subscription (BC2) → Settlement (BC4) → Collections (BC6). Each context owns one piece; they coordinate via events.
- **For compliance:** BC14 (Audit Log) captures every state change. BC10 (Admin IAM) enforces SoD and maker-checker. BC11 (Compliance) gates onboarding. BC16 (DevOps) logs deviations.
- **For integration:** BC17, BC18, BC19 are your external vendor points. Their vendor instructions are idempotent and enveloped.

---

## Cross-Context Coordination Patterns

### Pattern 1: Sequential gating (BC7 → BC2 → BC4)
Investor activates in BC7 → can subscribe in BC2 → receives funds from BC4. Each gate is enforced at the context boundary, not pre-emptively.

### Pattern 2: Event-driven workflows (BC1 → BC2 → BC5 → BC4 → BC6)
Listing go-live (BC1) → subscription window opens (BC2) → 100% funded (BC1) → assignments (BC5) → disbursement (BC4) → maturity tracking (BC6). Each context reacts to the prior one's event and owns its state machine.

### Pattern 3: Snapshot invariance (BC3 → BC1)
BC3 publishes effective pricing band at a moment in time. BC1 snapshots it at go-live. Subsequent BC3 changes do NOT affect in-flight BC1 listings (G20).

### Pattern 4: Reconciliation authority (BC4 → BC2)
BC4 runs reconciliation, observes actual inflows. BC4 corrects BC2's `funds_received` state (not the other way around). BC4 is authoritative on settlement facts (C23).

### Pattern 5: Maker-checker (BC10 → all)
Every context's commands are gated by BC10 maker-checker (C4). No state change without approval from a second role.

---

## References

- **B1 — Bounded Contexts & Context Map** (design artefact)
- **B2 — Event Model** (event ownership + subscribers)
- **B3 — Aggregates** (aggregate design per BC)
- **B4 — API Conventions** (command/query surface per BC)
- **Decision Log (DL-001 through DL-050)** — policy decisions that shape each BC
- **Constraints (C1–C28)** — regulatory & architectural constraints enforced per BC
- **Gap Log (G1–G21)** — working assumptions, many per-BC
