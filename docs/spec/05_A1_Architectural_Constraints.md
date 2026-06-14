# A1 — Architectural Constraints & Binding NFRs

*Phase 1 MVP. Non-negotiable inputs to all later phases. Every design choice is checked against this list.*

---

## 1. Audit & Immutability

**C1. Immutable audit log, 10-year retention.**
Append-only at storage layer. No role — including Super Admin — can modify or delete entries. Cryptographic chaining or WORM substrate required.
*Source: DL-040, Spec §3.4, §7.1*

**C2. Every state-changing action is audit-logged.**
User, timestamp, action, target record, before/after state. Includes admin "acting on behalf of supplier" actions, invite issuance, role changes, sensitive reads, fund movements, auditor reads/exports.
*Source: Spec §7.1*

**C3. Audit-the-auditor.**
Auditor reads and exports are themselves audit events.
*Source: DL-039, Spec §2.7*

---

## 2. Segregation of Duties

**C4. Record-level maker-checker is strict and system-enforced.**
The same individual cannot be maker and checker on the same record, regardless of roles held. Enforced at command-handler level, not UI level.
*Source: DL-033, Spec §3.2*

**C5. Role-level SoD has two tiers.**
Strict (system blocks): Credit Reviewer ⊕ Treasury & Settlement. Soft (warns, logged in Managed Deviation Register, override-with-reason): Super Admin + Compliance Reviewer, Ops Executive + Treasury & Settlement, Credit Reviewer + Compliance Reviewer.
*Source: DL-033*

**C6. Threshold-based four-eyes on credit decisions.**
Buyer credit limit and supplier credit decisions ≤ ₹1 crore: single approver (Credit Reviewer). > ₹1 crore: second approver required. Architecturally a workflow primitive, not a hardcoded rule.
*Source: DL-023*

**C7. MFA mandatory for all admin users.**
TOTP preferred, SMS OTP fallback. No exceptions.
*Source: DL-035*

---

## 3. Money Movement & Segregation

**C8. Per-listing fund segregation via virtual sub-accounts.**
Every listing has a dedicated virtual sub-account under master escrow. No commingling across listings.
*Source: DL-043*

**C9. Idempotent fund-movement instructions.**
All disbursement, distribution, and refund instructions to escrow carry idempotency keys. Replays are safe.
*Source: Spec §9, architecturally binding*

**C10. Webhook integrity.**
HMAC-signed callbacks from escrow and aggregator. Unsigned or invalid-signature webhooks rejected and alerted.
*Source: Spec §7.3*

**C11. T+1 disbursement and distribution.**
Operational SLA; design must accommodate NEFT/RTGS cutoffs and escrow settlement windows.
*Source: DL-030*

**C12. Listing funding invariants.**
100% funding required for disbursement; 5-day funding window; partial funding triggers full refund. These are aggregate invariants, not configurable rules.
*Source: DL-017*

---

## 4. Data Protection & Residency

**C13. India data residency.**
All personal and financial data stored in India. Aligns with RBI sectoral norms and DPDP Act.
*Source: Spec §7.4*

**C14. Encryption at rest and in transit.**
Sensitive data (KYC docs, financials, bank details) encrypted at rest. TLS for all platform traffic.
*Source: Spec §7.3*

**C15. Aadhaar masked storage per UIDAI norms.**
Last 4 digits visible. Full number either not stored or encrypted with strict access controls.
*Source: Spec §7.3*

**C16. Tenant isolation across personas.**
Investors cannot see other investors' subscriptions. Suppliers cannot see other suppliers' invoices or investor identities. Buyers see only their own assigned invoices. *Exception:* buyer identity is intentionally disclosed to investors on listings (DL-010); supplier identity is also disclosed.
*Source: Spec §7.3, DL-010, DL-017*

**C17. Annual KYC refresh for active investors and suppliers.**
Scheduled compliance process; binding for Phase 1.
*Source: Spec §7.2*

---

## 5. Identity & Access

**C18. RBAC with composable role assignment.**
A user account can hold multiple roles. Effective permissions = union. SoD enforced independently.
*Source: DL-031, DL-032*

**C19. Auditor accounts are just-in-time and scoped.**
Created only when engagement is active, time-bound auto-disable, scoped by date range/entity type/sensitivity level, export rate limits to prevent bulk exfiltration. Account-level SoD: auditor accounts cannot be combined with any operational role.
*Source: DL-039, Spec §2.7*

**C20. Invite-gated investor onboarding.**
Public site has no investor CTA. Onboarding URL non-discoverable. Single-use 14-day invite codes tied to invitee email + phone at issuance. Cold sign-ups route to waitlist.
*Source: DL-008*

**C21. Investor suitability with override-acknowledgment.**
Suitability assessment is mandatory at onboarding. Mismatch between investor profile and product profile is permitted but requires explicit acknowledged override, recorded in audit log.
*Source: Spec §2.4*

---

## 6. Reliability & Operability

**C22. Webhook reliability.**
At-least-once delivery assumed. Platform handles deduplication via idempotency keys. Dead-letter and manual replay capability required.
*Source: Spec §9*

**C23. Reconciliation is authoritative.**
Webhook-driven state changes are provisional until reconciled against escrow statements. End-of-day batch reconciliation overlays real-time updates.
*Source: Spec §9*

**C24. Source-of-truth verification.**
Supplier/buyer/invoice data verified against authoritative sources (MCA21, GST Network, Aadhaar, IRN, bank penny-drop) — not self-attested.
*Source: DL-014, DL-016, DL-026*

---

## 7. Phase 2 Readiness

**C25. Schema superset-readiness.**
Entity model accommodates Phase 2 expansions without migration: MSME/Udyam fields, additional investor sub-types, anchor relationships, blanket acknowledgment, wallet model, hard concentration limits, regulator inspection.
*Source: DL-001, Spec §1.3, §8*

**C26. Inactive-but-schema-present pattern.**
Fields and relationships for deferred features exist in schema but are unused/dormant in Phase 1. No feature-flag gymnastics; behaviour is policy-driven.
*Source: Spec §8*

---

## 8. Legal Structure Constraints

**C27. Direct assignment, no NBFC.**
Investors are legal claimants on buyer payment via direct assignment. No NBFC in legal or operational chain in Phase 1. Per-investor assignment documentation generated and e-signed on funding completion, before disbursement.
*Source: DL-002, DL-003*

**C28. Limited recourse.**
Supplier liable for fraud and dilution only. Buyer credit default is investor risk. No PG, no FLDG, no upfront margin.
*Source: DL-015*

---

## How to use this document

Every later artefact (bounded contexts, events, aggregates, APIs, components) is checked against these 28 constraints. If a design choice violates one, either the choice changes or the constraint is explicitly revisited via a new Decision Log entry.

Constraints are numbered (C1–C28) for cross-reference. Later artefacts cite them inline.
