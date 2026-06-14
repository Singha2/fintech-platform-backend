# Step 4 Blueprint — Admin Console (S1–S9)
# Fintech Platform MVP — Invoice Discounting

## How to use this file
Self-contained spec for building the eight admin screens into the existing
React + Tailwind app (skeleton from Step 1, investor flow from Step 2).

**Place alongside:** `STEP0_OUTPUT.md`, `STEP2_INVESTOR_BLUEPRINT.md`, `Mock_Build_Plan.md`

**Hard limits (same as Step 2):**
- Hardcoded JSON only — no API calls, no backend.
- Use only the existing component kit (Button, Table, Card, StatusBadge, FormField, PageHeader).
- Add mock data to `mockData.js` under keys S1–S9.
- Simulate state transitions with React useState — no real auth, no real MFA.
- Maker-checker SoD: simulate with a second "approver" dropdown in affected screens.

---

## Roles Reference

| Role | Key screens | SoD notes |
|------|-------------|-----------|
| Ops Executive | S2, S3, S5 | Cannot approve listings (that's Treasury) |
| Credit Reviewer | S2, S4 | Cannot hold Treasury & Settlement (strict block) |
| Compliance Reviewer | S2, S8 | Issues invite codes; approves KYC |
| Treasury & Settlement | S2, S5 (checker), S6, S7 | Cannot be maker on same record as checker |
| Super Admin | S2 (all queues) | User management only; cannot transact |
| Auditor | S9 only | Separate login; no operational access |

**Phase 1 team (mock as selectable personas):**
- Founder/CEO → Super Admin + Compliance Reviewer
- Ops Lead → Ops Executive + Treasury & Settlement
- Credit Lead → Credit Reviewer

---

## Mock Data Shapes — add to `mockData.js`

```js
S1: {
  // Mock login — no real auth. Simulate by clicking "Login" → sets persona in state.
  credentials: { email: 'ops@platform.com', password: '••••••••' },
  mfa: { type: 'totp', code: '123456' },
  personas: [
    { id: 'founder',    label: 'Founder / CEO',   roles: ['super_admin', 'compliance_reviewer'] },
    { id: 'ops_lead',   label: 'Ops Lead',         roles: ['ops_executive', 'treasury_and_settlement'] },
    { id: 'credit_lead',label: 'Credit Lead',      roles: ['credit_reviewer'] },
    { id: 'auditor',    label: 'Auditor',          roles: ['auditor'] },
  ],
},

S2: {
  // Each queue is filtered by current persona's roles
  queues: {
    ops_executive: [
      { id: 'q1', type: 'supplier_onboarding', label: 'Alpha Components — KYC pending', status: 'pending', age_days: 2 },
      { id: 'q2', type: 'invoice_check',       label: 'INV-2026-0042 — checks in progress', status: 'in_progress', age_days: 0 },
    ],
    credit_reviewer: [
      { id: 'q3', type: 'buyer_credit',        label: 'Reliance Industries — credit review', status: 'pending', age_days: 3 },
      { id: 'q4', type: 'supplier_credit',     label: 'Beta Metals — exposure cap review', status: 'pending', age_days: 1 },
    ],
    compliance_reviewer: [
      { id: 'q5', type: 'kyc_approval',        label: 'Investor Rahul Mehta — KYC submitted', status: 'pending', age_days: 1 },
      { id: 'q6', type: 'invite_issuance',     label: '2 pending invite requests', status: 'pending', age_days: 0 },
    ],
    treasury_and_settlement: [
      { id: 'q7', type: 'listing_golive',      label: 'LST-001 — ready for go-live approval', status: 'ready', age_days: 0 },
      { id: 'q8', type: 'disbursement',        label: 'LST-002 — disbursement pending', status: 'pending', age_days: 1 },
    ],
    super_admin: [
      { id: 'q9', type: 'user_management',     label: '1 new admin user invite pending', status: 'pending', age_days: 0 },
    ],
  },
  stats: {
    active_listings: 3,
    total_deployed_paise: 25000000,   // ₹2,50,000
    investors_active: 8,
    suppliers_active: 4,
    pending_disbursements: 1,
  },
},

S3: {
  suppliers: [
    {
      supplier_id: 'sup-001',
      legal_name: 'Alpha Components Pvt Ltd',
      constitution_type: 'private_limited',
      pan: 'AABCA1234Z',
      gstin: '27AABCA1234Z1Z5',
      cin: 'U74999MH2015PTC123456',
      status: 'kyc_submitted',
      // status flow: created → identity_verified → kyc_submitted
      //              → kyc_in_review → kyc_approved → credit_reviewed
      //              → maa_pending → active
      kyc_approved_by: null,
      kyc_approved_at: null,
      credit_exposure_cap_paise: null,
      credit_risk_rating: null,
      maa_agreement_id: null,
      activated_at: null,
      agency_consent: {
        consent_id: 'con-001',
        scope: ['invoice_submission', 'kyc_upload', 'financial_profile'],
        granted_at: '2026-05-01T10:00:00Z',
        is_active: true,
      },
    },
    {
      supplier_id: 'sup-002',
      legal_name: 'Beta Metals Pvt Ltd',
      constitution_type: 'private_limited',
      pan: 'AABCB5678Z',
      gstin: '27AABCB5678Z1Z2',
      cin: 'U27100MH2010PTC654321',
      status: 'active',
      kyc_approved_by: 'admin-001',
      kyc_approved_at: '2026-04-15T14:00:00Z',
      credit_exposure_cap_paise: 20000000,  // ₹2,00,000
      credit_risk_rating: 'A',
      maa_agreement_id: 'maa-001',
      activated_at: '2026-04-20T09:00:00Z',
      agency_consent: {
        consent_id: 'con-002',
        scope: ['invoice_submission', 'kyc_upload', 'financial_profile'],
        granted_at: '2026-04-01T10:00:00Z',
        is_active: true,
      },
    },
  ],
  // Acting-as mode: set when admin selects a supplier to act on behalf of
  acting_as: null, // supplier_id when active; shows persistent banner
},

S4: {
  buyers: [
    {
      buyer_id: 'buy-001',
      legal_name: 'Reliance Industries Ltd',
      mca_cin: 'L17110MH1973PLC019786',
      gstin: '27AAACR5055K1ZT',
      sector: 'Energy',
      relationship_tier: 'acknowledged_buyer',
      acknowledgment_mode: 'per_invoice',
      status: 'active',
      credit_limit_paise: 100000000,  // ₹10,00,000
      pricing_band_id: 'band-001',
      rating: 'AA+',
      rating_source: 'CRISIL',
      tenor_cap_days: 90,
      last_review_at: '2026-03-01T00:00:00Z',
    },
    {
      buyer_id: 'buy-002',
      legal_name: 'Tata Steel Ltd',
      mca_cin: 'L27100MH1907PLC000260',
      gstin: '27AAACT2727Q1ZX',
      sector: 'Manufacturing',
      relationship_tier: 'acknowledged_buyer',
      acknowledgment_mode: 'per_invoice',
      status: 'under_credit_review',
      credit_limit_paise: null,
      pricing_band_id: null,
      rating: 'AA',
      rating_source: 'ICRA',
      tenor_cap_days: null,
      last_review_at: null,
    },
  ],
  pricing_bands: [
    { band_id: 'band-001', buyer_id: 'buy-001', tenor_bucket: '61-90d', rate_bps: 1200, fee_bps: 50 },
    { band_id: 'band-002', buyer_id: 'buy-001', tenor_bucket: '31-60d', rate_bps: 1050, fee_bps: 50 },
  ],
},

S5: {
  invoices: [
    {
      invoice_id: 'inv-001',
      invoice_number: 'INV-2026-0042',
      supplier_name: 'Alpha Components Pvt Ltd',
      buyer_name: 'Reliance Industries Ltd',
      face_value: 5125000,     // paise → ₹51,250
      tenor_days: 90,
      invoice_date: '2026-05-01',
      due_date: '2026-07-30',
      irn: 'IRN123456789012345678901234567890123456789012345678',
      status: 'ops_checks_in_progress',
      // status flow: submitted → ops_checks_in_progress →
      //   ops_checks_passed → listed (→ ready_for_review → live)
      check_outcomes: {
        irn_verified:     { outcome: 'pass',    detail: 'Active on GST portal',         checked_at: '2026-05-02T09:00:00Z' },
        eway_bill_match:  { outcome: 'pass',    detail: 'E-way bill matches invoice',   checked_at: '2026-05-02T09:02:00Z' },
        buyer_supplier_rel:{ outcome: 'pass',   detail: 'Relationship validated',       checked_at: '2026-05-02T09:04:00Z' },
        duplicate_check:  { outcome: 'pass',    detail: 'No duplicate found',           checked_at: '2026-05-02T09:05:00Z' },
        exposure_cap:     { outcome: 'pass',    detail: 'Within supplier cap',          checked_at: '2026-05-02T09:06:00Z' },
        buyer_limit:      { outcome: 'pass',    detail: 'Headroom ₹9,48,750 remaining', checked_at: '2026-05-02T09:07:00Z' },
        doc_completeness: { outcome: 'pending', detail: 'Document upload awaited',      checked_at: null },
        buyer_ack:        { outcome: 'pending', detail: 'Acknowledgment request sent',  checked_at: null },
      },
    },
    {
      invoice_id: 'inv-002',
      invoice_number: 'INV-2026-0039',
      supplier_name: 'Beta Metals Pvt Ltd',
      buyer_name: 'Tata Steel Ltd',
      face_value: 10200000,    // ₹1,02,000
      tenor_days: 60,
      invoice_date: '2026-04-25',
      due_date: '2026-06-24',
      irn: 'IRN987654321098765432109876543210987654321098765432',
      status: 'ops_checks_passed',
      check_outcomes: {
        irn_verified:      { outcome: 'pass', detail: 'Active', checked_at: '2026-04-26T09:00:00Z' },
        eway_bill_match:   { outcome: 'pass', detail: 'Match',  checked_at: '2026-04-26T09:01:00Z' },
        buyer_supplier_rel:{ outcome: 'pass', detail: 'OK',     checked_at: '2026-04-26T09:02:00Z' },
        duplicate_check:   { outcome: 'pass', detail: 'Clean',  checked_at: '2026-04-26T09:03:00Z' },
        exposure_cap:      { outcome: 'pass', detail: 'Within', checked_at: '2026-04-26T09:04:00Z' },
        buyer_limit:       { outcome: 'pass', detail: 'OK',     checked_at: '2026-04-26T09:05:00Z' },
        doc_completeness:  { outcome: 'pass', detail: 'All docs present', checked_at: '2026-04-26T09:06:00Z' },
        buyer_ack:         { outcome: 'pass', detail: 'Acknowledged by portal', checked_at: '2026-04-26T09:10:00Z' },
      },
    },
  ],
  // Listings ready for go-live approval (maker-checker)
  listings_for_approval: [
    {
      listing_id: 'lst-005',
      invoice_number: 'INV-2026-0039',
      supplier_name: 'Beta Metals Pvt Ltd',
      buyer_name: 'Tata Steel Ltd',
      funding_target: 9996000,   // paise after discount+fee
      rate_bps: 1050,
      tenor_days: 60,
      maker_id: 'admin-ops',
      maker_name: 'Ops Lead',
      status: 'ready_for_review',
    },
  ],
},

S6: {
  disbursements: [
    {
      disbursement_id: 'disb-001',
      listing_id: 'lst-002',
      supplier_name: 'Beta Metals Pvt Ltd',
      buyer_name: 'Tata Steel Ltd',
      net_amount_paise: 9996000,    // ₹99,960
      status: 'pending_approval',
      // status: pending_approval | approved | executed | failed
      all_signed: true,
      funding_completed_at: '2026-05-20T15:00:00Z',
      due_disbursement_date: '2026-05-21',  // T+1 (DL-030)
      maker_id: 'admin-ops',
      maker_name: 'Ops Lead',
      checker_id: null,  // Treasury & Settlement approver — different person
    },
    {
      disbursement_id: 'disb-002',
      listing_id: 'lst-003',
      supplier_name: 'Gamma Tech Services',
      buyer_name: 'Infosys Ltd',
      net_amount_paise: 7840000,    // ₹78,400
      status: 'executed',
      all_signed: true,
      funding_completed_at: '2026-05-10T12:00:00Z',
      due_disbursement_date: '2026-05-11',
      maker_id: 'admin-ops',
      maker_name: 'Ops Lead',
      checker_id: 'admin-treasury',
      checker_name: 'Ops Lead (Treasury role)',
      executed_at: '2026-05-11T10:00:00Z',
      utr: 'UTR20260511000123',
    },
  ],
},

S7: {
  distributions: [
    {
      distribution_id: 'dist-001',
      listing_id: 'lst-004',
      buyer_name: 'HCL Technologies Ltd',
      maturity_date: '2026-05-15',
      buyer_payment_ref: 'NEFT20260515ABC',
      buyer_payment_amount_paise: 10400000,
      status: 'distribution_pending',
      // status: awaiting_maturity | distribution_pending | executed | shortfall
      investors: [
        { investor_name: 'Rahul Mehta',  amount_paise: 2000000, gross_paise: 2058333, tds_paise: 8333, fee_paise: 1000, net_paise: 2049000, utr: null },
        { investor_name: 'Priya Shah',   amount_paise: 3000000, gross_paise: 3087500, tds_paise: 12500, fee_paise: 1500, net_paise: 3073500, utr: null },
        { investor_name: 'Amit Joshi',   amount_paise: 5000000, gross_paise: 5145833, tds_paise: 20833, fee_paise: 2500, net_paise: 5122500, utr: null },
      ],
    },
  ],
  reconciliation: [
    {
      rec_id: 'rec-001',
      listing_id: 'lst-004',
      buyer_name: 'HCL Technologies Ltd',
      expected_paise: 10400000,
      actual_paise: 10400000,
      status: 'matched',    // matched | partial | unmatched
      reconciled_at: '2026-05-15T14:30:00Z',
      txn_ref: 'NEFT20260515ABC',
    },
    {
      rec_id: 'rec-002',
      listing_id: 'lst-006',
      buyer_name: 'Wipro Ltd',
      expected_paise: 5000000,
      actual_paise: 4800000,
      status: 'partial',
      reconciled_at: '2026-05-18T11:00:00Z',
      txn_ref: 'NEFT20260518XYZ',
    },
  ],
},

S8: {
  invites: [
    {
      invite_id: 'inv-i-001',
      email_display: 'r.mehta@example.com',   // display only — actual stored as hash
      phone_display: '+91 98765 43210',        // display only
      issued_by: 'Founder / CEO',
      issued_at: '2026-05-01T10:00:00Z',
      expiry_at: '2026-05-15T10:00:00Z',
      status: 'consumed',
      consumed_at: '2026-05-03T09:00:00Z',
      justification: 'Personal network — known HNI',
    },
    {
      invite_id: 'inv-i-002',
      email_display: 'p.shah@example.com',
      phone_display: '+91 91234 56789',
      issued_by: 'Founder / CEO',
      issued_at: '2026-05-18T14:00:00Z',
      expiry_at: '2026-06-01T14:00:00Z',
      status: 'pending',
      consumed_at: null,
      justification: 'Referral from Rahul Mehta',
    },
    {
      invite_id: 'inv-i-003',
      email_display: 'a.kumar@example.com',
      phone_display: '+91 99887 76655',
      issued_by: 'Founder / CEO',
      issued_at: '2026-04-10T09:00:00Z',
      expiry_at: '2026-04-24T09:00:00Z',
      status: 'expired',
      consumed_at: null,
      justification: 'Angel network contact',
    },
  ],
},

S9: {
  // Read-only. Auditor sees all events across the platform.
  events: [
    { event_id: 'evt-001', event_type: 'Listing.GoneLive',              actor: 'Ops Lead (Treasury role)', target: 'LST-002', recorded_at: '2026-05-10T09:00:00Z', sensitivity: 'standard' },
    { event_id: 'evt-002', event_type: 'InvestorAccount.Activated',     actor: 'System',                   target: 'INV-001', recorded_at: '2026-05-03T10:00:00Z', sensitivity: 'sensitive' },
    { event_id: 'evt-003', event_type: 'Subscription.Committed',        actor: 'Investor: Rahul Mehta',    target: 'SUB-001', recorded_at: '2026-05-11T11:00:00Z', sensitivity: 'standard' },
    { event_id: 'evt-004', event_type: 'AgencyAction.Recorded',         actor: 'Ops Lead',                 target: 'SUP-001', recorded_at: '2026-05-05T14:00:00Z', sensitivity: 'standard' },
    { event_id: 'evt-005', event_type: 'Invite.Issued',                 actor: 'Founder / CEO',            target: 'INV-I-002', recorded_at: '2026-05-18T14:00:00Z', sensitivity: 'sensitive' },
    { event_id: 'evt-006', event_type: 'KycFile.Approved',              actor: 'Founder / CEO',            target: 'INV-001', recorded_at: '2026-05-02T16:00:00Z', sensitivity: 'sensitive' },
    { event_id: 'evt-007', event_type: 'Listing.Disbursed',             actor: 'System (BC4)',             target: 'LST-003', recorded_at: '2026-05-11T10:30:00Z', sensitivity: 'standard' },
    { event_id: 'evt-008', event_type: 'BuyerAccount.Nominated',        actor: 'Credit Lead',              target: 'BUY-002', recorded_at: '2026-05-15T09:00:00Z', sensitivity: 'standard' },
  ],
  // Auditor scope (set at account provisioning)
  scope: {
    scope_id: 'scope-001',
    date_range: { from: '2026-04-01', to: '2026-06-30' },
    entity_types: ['Listing', 'Invoice', 'Subscription', 'InvestorAccount', 'SupplierAccount'],
    sensitivity_level: 'sensitive',
  },
},
```

---

## S1 — Login + MFA

- **Persona:** All admin (Ops Executive, Credit Reviewer, Compliance Reviewer, Treasury & Settlement, Super Admin) + Auditor (separate path)
- **Purpose:** Authenticate user, collect TOTP/SMS OTP, establish session with role claims.
- **Entry from:** Direct URL / app open.
- **Exits to:** S2 (all admin roles); S9 (Auditor role).

### Layout
Centred card. No sidebar. No top bar. Platform name + logo top.

### Fields
- Email (FormField)
- Password (FormField, type=password)
- "Login" Button → shows MFA step
- MFA step: 6-digit TOTP code (FormField) + "Verify" Button
- Below MFA: persona selector dropdown (mock only — real app derives from DB roles)

### Mock behaviour
On "Verify" click → set selected persona in app state → navigate to S2 (or S9 if Auditor).

### State Variants
- `mfa_failed` — show inline error "Invalid code. 2 attempts remaining."
- `account_disabled` — show "Account disabled. Contact Super Admin."

### Rules
- DL-035: TOTP mandatory; SMS OTP shown as fallback option.
- AU10.2: Every subsequent admin action requires valid `mfa_assertion_id` — show MFA-freshness banner in S2–S8 if mock assertion is "stale" (>30 min).
- C7: Sensitive actions (go-live approval, disbursement) re-prompt MFA — simulate with a modal.

### Founder Notes
- TBD — Is there a "Forgot password" flow in Phase 1 or is reset done by Super Admin only?

---

## S2 — Admin Dashboard (Role-Scoped Work Queues)

- **Persona:** All admin
- **Purpose:** Role-filtered work queue showing items needing attention, plus platform-wide stats.
- **Entry from:** S1 (post-login).
- **Exits to:** S3 (Ops queue item → supplier), S4 (credit queue), S5 (invoice/listing queue), S6 (disbursement queue), S7 (distribution), S8 (invite queue).

### Layout
PageHeader ("Good morning, [Name] · [Roles]") + Stats row + Work queue table filtered by current persona's roles.

### Stats Row (4 Cards — all personas see these)
| Card | Value |
|------|-------|
| Active Listings | `stats.active_listings` |
| Total Deployed | `stats.total_deployed_paise` → ₹ |
| Active Investors | `stats.investors_active` |
| Pending Disbursements | `stats.pending_disbursements` |

### Work Queue Table
Columns: Type | Description | Status | Age | Action

Filtered by role — show only queues relevant to current persona:
- **Ops Executive** → supplier_onboarding, invoice_check items
- **Credit Reviewer** → buyer_credit, supplier_credit items
- **Compliance Reviewer** → kyc_approval, invite_issuance items
- **Treasury & Settlement** → listing_golive, disbursement items
- **Super Admin** → user_management items + all of the above (read-only)

Action column: "Review" Button → navigates to relevant screen.

### State Variants
- `empty_queue` — "No items pending" per section.
- `mfa_stale_banner` — amber banner: "MFA assertion expires in 5 min. Re-verify for sensitive actions."

### Rules
- G19 / X14: Tenant isolation — queue items scoped to current admin's tenant claims.
- C4: Maker-checker items clearly labelled with maker name so checker can verify they are different people.

### Founder Notes
- TBD — Should Super Admin see a merged queue of all roles, or separate tabs per role?

---

## S3 — Supplier Onboarding Workspace ("Acting-As" Mode)

- **Persona:** Ops Executive
- **Purpose:** Admin completes supplier onboarding on behalf of supplier; persistent "Acting as Supplier X" banner enforces agency transparency.
- **Entry from:** S2 (ops queue → supplier onboarding item).
- **Exits to:** S2 (back to queue).

### Layout
**When no supplier selected:** supplier list table + "Create New Supplier" Button.
**When supplier selected (acting-as active):** persistent amber banner at top ("Acting as: [Supplier Name] · Agency Consent: Active") + supplier detail wizard.

### Supplier List Table
Columns: Legal Name | PAN | Constitution | Status | Agency Consent | Action

StatusBadge values: created=gray, identity_verified=amber, kyc_submitted=amber, kyc_in_review=amber, kyc_approved=amber, credit_reviewed=amber, maa_pending=amber, active=green, suspended=red

Action: "Open" Button → enters acting-as mode for that supplier.

### Supplier Detail Wizard (acting-as active)
Six stages — one active at a time based on `supplier.status`:

| Stage | Active when status = | Fields / Actions |
|-------|---------------------|------------------|
| 1. Create | `created` | Legal name, Constitution type (dropdown), PAN, GSTIN, CIN — FormFields. "Submit" → triggers identity verification mock |
| 2. Identity Verification | `created` → verifying | Show PAN/GSTIN/CIN verification results (pass/fail badges). "Confirm" → `identity_verified` |
| 3. KYC Upload | `identity_verified` | Constitution docs, Signatory KYC, UBO details — mock upload fields. "Submit KYC" → `kyc_submitted` |
| 4. Financial Profile | `kyc_submitted` | GST returns TTL (mock date), AA bank statement (mock), Top buyers list (FormField). "Submit" → routes to credit review queue |
| 5. Credit Review Outcome | `kyc_approved` | Show exposure cap (₹, FormField), risk rating (dropdown: AAA/AA/A/BBB). "Record Outcome" → `credit_reviewed` |
| 6. MAA e-Sign | `credit_reviewed` | "Initiate MAA e-Sign" Button → mock → `maa_pending` → `active` |

### State Variants
- `agency_consent_missing` — show "No active agency consent" error; block all actions (AC.1).
- `kyc_rejected` — show rejection reason; re-submit KYC button.
- `supplier_suspended` — read-only; suspend/blacklist actions available to Compliance Reviewer.

### Rules
- DL-012: Admin-assisted; all actions under agency consent.
- DL-013 / AC.3: Every action emits `AgencyAction.Recorded` — show action log tab on supplier detail.
- AC.2: MAA e-sign is non-delegable; supplier's authorised signatory must sign — show note.
- C24: Identity data verified via aggregator, not self-attested — show "Verified via GST/MCA" label.

### Founder Notes
- TBD — Acting-as banner: should it appear on every admin screen or only in S3?
- TBD — Agency consent: is it captured as a click-through in S3 or does the supplier do it via a separate email link before S3 begins?

---

## S4 — Buyer Management + Credit Review

- **Persona:** Credit Reviewer
- **Purpose:** Nominate and manage buyer entities; set credit limits and pricing bands.
- **Entry from:** S2 (credit queue item).
- **Exits to:** S2 (back).

### Layout
PageHeader + buyer list table + side panel (buyer detail + credit form) on row click.

### Buyer List Table
Columns: Legal Name | Sector | Rating | Credit Limit (₹) | Status | Last Review | Action

StatusBadge: nominated=gray, identity_verified=amber, under_credit_review=amber, approved_with_limit=amber, active=green, suspended=red

### Buyer Detail Side Panel

**Identity Card**
- Legal name, MCA CIN, GSTIN, Sector, Rating + Source, Relationship tier (acknowledged_buyer — Phase 1 only)

**Credit Profile Card**
- Credit limit (₹) — FormField (editable)
- Tenor cap days — FormField
- Pricing bands table: tenor bucket | rate_bps | fee_bps | Action (edit row)
- Last review date
- "Set / Update Credit Limit" Button → maker-checker: shows "Checker approval required" if limit > ₹1 Cr (C6, DL-023)

**Onboarding Stage Actions**
| Stage | Action |
|-------|--------|
| nominated | "Trigger Identity Verification" Button → `identity_verified` |
| identity_verified | "Start Credit Assessment" Button → `under_credit_review` |
| under_credit_review | Credit limit + pricing band form → "Approve Credit" Button → `approved_with_limit` |
| approved_with_limit | "Mark Active" Button → `active` (after NOA + ack user setup) |

### State Variants
- `four_eyes_required` — credit limit > ₹1 Cr; second approver dropdown shown (C6, DL-023).
- `buyer_suspended` — read-only; "Reinstate" Button for Credit Reviewer.

### Rules
- DL-022: Credit policy owner; pricing bands per buyer/tenor.
- DL-023 / C6: Four-eyes for limits > ₹1 Cr.
- DL-019: Per-invoice acknowledgment mode only in Phase 1.
- DL-020: Only `acknowledged_buyer` tier active in Phase 1.
- G20: Once a listing is live, credit limit changes do not affect in-flight listing — show note.

### Founder Notes
- TBD — Does the buyer nomination originate from S4 (Credit Reviewer creates it) or from a request submitted by Ops and reviewed here?

---

## S5 — Invoice Operational Checks + Listing Approval

- **Persona:** Ops Executive (checks), Treasury & Settlement (go-live approval — checker)
- **Purpose:** Run/review operational checks per invoice; approve listing go-live via maker-checker.
- **Entry from:** S2 (ops queue → invoice_check or listing_golive item).
- **Exits to:** S2 (back), S6 (after go-live → disbursement queue).

### Layout
Two tabs: **"Invoice Checks"** | **"Listing Approval"**

### Tab 1 — Invoice Checks

Invoice list table: Invoice # | Supplier | Buyer | Face Value (₹) | Tenor | Status | Action

On row click → Invoice detail panel:
- Invoice header: number, IRN (truncated + copy), face value, tenor, dates
- Ops checks table: Check Name | Outcome (StatusBadge pass=green/fail=red/pending=amber) | Detail | Checked At
- "Record Check Outcome" Button (per check) → outcome dropdown (pass/fail) + detail FormField
- "Send Buyer Acknowledgment Request" Button → `buyer_ack` check moves to pending → mock sends request
- "Capture Manual Ack" Button → mark buyer_ack as pass manually (DL-019 fallback)

### Tab 2 — Listing Approval (Maker-Checker)

Listings ready for go-live: Listing ID | Supplier | Buyer | Funding Target (₹) | Rate | Maker | Status | Action

On row click → Listing detail panel:
- Pricing snapshot Card: rate_bps, fee_bps, snapshot_at (immutable — G20)
- Funding target (₹)
- Maker name (cannot be same person as approver — C4)
- "Approve Go-Live" Button — only enabled for Treasury & Settlement role AND persona ≠ maker (SoD check)
- MFA re-prompt modal on click (simulate — C7, sensitive action)
- On approval → listing status → `live` → navigate to S6

### State Variants
- `checks_failed` — one or more checks failed; list blocked from progressing.
- `maker_checker_violation` — approver = maker; button disabled + warning shown.
- `mfa_required` — go-live button triggers MFA modal (C7).

### Rules
- DL-027: Ops owns checks — IRN, e-way bill, relationship, duplicate, cap, headroom, docs, ack.
- DL-019: Buyer acknowledgment per-invoice; admin can capture manually.
- C4: Maker ≠ checker on go-live.
- C7: MFA freshness required for go-live approval (sensitive action).
- INV.5 / INV.7: IRN verified via aggregator, not self-attested — show "GST Verified" label.

### Founder Notes
- TBD — Who is the maker for go-live: Ops Executive who ran checks, or a separate Treasury prep step?

---

## S6 — Disbursement Approval Queue

- **Persona:** Treasury & Settlement
- **Purpose:** Approve disbursement of funds to supplier once 100% funded and all assignments signed.
- **Entry from:** S2 (disbursement queue item), S5 (after listing go-live → fully funded → assignments complete).
- **Exits to:** S2, S7 (after disbursement → distribution tracking).

### Layout
PageHeader + disbursements table + detail panel on row click.

### Disbursements Table
Columns: Listing | Supplier | Net Amount (₹) | Due Date | All Signed | Status | Action

StatusBadge: pending_approval=amber, approved=amber, executed=green, failed=red

`all_signed` shown as tick/cross badge (C27 — disbursement gate requires all investor assignments signed).

### Disbursement Detail Panel
- Listing summary: listing_id, buyer, supplier, net_amount
- Funding completed at (timestamp)
- Due disbursement date (T+1 per DL-030)
- All signed status: ✓ / ✗ + "Assignments pending" note if false
- Maker: name (read-only — from S5 go-live)
- Checker: "Approve Disbursement" Button → only enabled if `all_signed = true` AND persona = Treasury & Settlement AND not same as maker
- MFA re-prompt on approve (C7)
- On approval → status → `approved` → mock → `executed` with UTR displayed

### State Variants
- `assignments_incomplete` — `all_signed = false`; approve button disabled + "Awaiting investor e-signatures" note (C27).
- `maker_checker_violation` — same person; button disabled.
- `executed` — read-only; UTR shown.

### Rules
- DL-030: T+1 disbursement timing — show due date prominently.
- C27 / L.5: Disbursement gate requires `all_signed = true`.
- C4 / C7: Maker-checker + MFA on disbursement approval.
- DL-043: Per-listing VA — show VA reference used for this disbursement.

### Founder Notes
- TBD — Failed disbursements: is the remediation case (BC4 RemediationCase) visible in this screen or a separate queue?

---

## S7 — Distribution + Reconciliation View

- **Persona:** Treasury & Settlement
- **Purpose:** Track investor distributions at maturity; view reconciliation status of buyer payments.
- **Entry from:** S2 (distribution/reconciliation item), S6 (after disbursement executed).
- **Exits to:** S2.

### Layout
Two tabs: **"Distributions"** | **"Reconciliation"**

### Tab 1 — Distributions

Distribution list table: Listing | Buyer | Maturity Date | Buyer Payment Ref | Status | Action

On row click → Distribution detail panel:
- Buyer payment received: amount (₹), txn ref, reconciled_at
- Per-investor payout table:
  - Investor Name | Amount (₹) | Gross (₹) | TDS (₹) | Fee (₹) | Net (₹) | UTR
  - Net formula: gross − tds − fee (DL-045, G4) — show formula label
- "Execute Distributions" Button → mock all UTRs populated → status → `executed`
- Total gross / total TDS / total net summary row

### Tab 2 — Reconciliation

Reconciliation table: Listing | Buyer | Expected (₹) | Actual (₹) | Status | Reconciled At | Txn Ref

StatusBadge: matched=green, partial=amber, unmatched=red

On `partial` / `unmatched` row → show "Raise Shortfall" Button → mock routes to collections (BC6).

### State Variants
- `shortfall` — actual < expected; amber/red badge + "Raise Shortfall" action shown.
- `distribution_executed` — all UTRs populated; read-only.

### Rules
- DL-030: T+1 distribution after buyer payment received.
- DL-045 / G4: TDS deducted at distribution instruction time; gross−tds−fee=net shown.
- C23: Reconciliation authoritative — actual inflow must match before distribution executes.
- G6: EoD master-statement overlay (show "Last reconciled: [timestamp]" label).

### Founder Notes
- TBD — Is the collections escalation (shortfall → BC6) visible in this screen or a separate S7 sub-tab?

---

## S8 — Investor Invite Issuance

- **Persona:** Compliance Reviewer
- **Purpose:** Issue single-use 14-day invite codes tied to invitee email + phone; view invite log.
- **Entry from:** S2 (invite_issuance queue item).
- **Exits to:** S2.

### Layout
PageHeader + "Issue New Invite" form Card + Invites log table.

### Issue New Invite Form Card
- Email (FormField) — stored as hash; display input only
- Phone (FormField, E.164) — stored as hash
- Justification (FormField, textarea) — required; logged (DL-036)
- Referrer (FormField, optional) — name of existing investor who referred
- "Issue Invite" Button → creates invite with 14-day expiry → appears in log

### Invites Log Table
Columns: Email (display) | Phone (display) | Issued By | Issued At | Expires At | Status | Consumed At

StatusBadge: pending=amber, consumed=green, expired=gray

- "Revoke" Button per pending invite → status → revoked (show revoke reason modal)

### State Variants
- `invite_issued` — success banner: "Invite sent to [email]. Expires [date]."
- `already_consumed` — show "Consumed by [identity]" label; no revoke.
- `expired` — gray row; no actions.

### Rules
- DL-036 / I.1: Only Compliance Reviewer can issue; enforced at command handler — mock: disable button if current persona ≠ compliance_reviewer.
- DL-008 / I.3: 14-day validity; expiry_at = issued_at + 14 days.
- C20: Every issuance logged with email_hash, phone_hash, justification, referrer.
- G9: Invite tied to invitee's email + phone at issuance time — consumed only if identity email matches.

### Founder Notes
- TBD — Should the invite be dispatched automatically (email + SMS) by the platform, or does the Compliance Reviewer copy and share the link manually?

---

## S9 — Audit Log (Auditor Read-Only View)

- **Persona:** Auditor (separate login path from S1)
- **Purpose:** Read-only, scoped view of all platform events within the auditor's engagement window.
- **Entry from:** S1 (Auditor persona → bypasses S2 → lands directly here).
- **Exits to:** None (read-only; no navigation to operational screens).

### Layout
PageHeader ("Audit Log · Scope: [date range] · Sensitivity: [level]") + scope summary Card + filter bar + events table.

### Scope Summary Card (top, read-only)
- Date range: `scope.date_range.from` to `scope.date_range.to`
- Entity types in scope: `scope.entity_types` (comma list)
- Sensitivity level: `scope.sensitivity_level` — StatusBadge
- Scope defined by: Super Admin name

### Filter Bar
- Event type: dropdown (all types from `events[].event_type`)
- Actor: text search
- Date range: date pickers (within scope window)
- Sensitivity: dropdown (standard / sensitive / restricted)

### Events Table
Columns: Timestamp | Event Type | Actor | Target | Sensitivity

- All rows read-only — no buttons, no actions.
- Click row → expand detail panel showing full event payload (JSON display, read-only).
- "Export CSV" Button — mock: shows "Export requested. Rate limit: 1000 rows/export." (C19 / DL-041)

### State Variants
- `outside_scope` — event outside date range or entity type; greyed row + "Out of scope" label.
- `rate_limit_triggered` — export button disabled after mock export; "Rate limit reached" badge.
- `scope_expired` — if today > scope.date_range.to; show "Engagement period ended" banner; log still visible, no new exports.

### Rules
- DL-039: Just-in-time, scoped, time-bound account — show validity window prominently.
- DL-040: Audit log immutable — no edit/delete controls anywhere.
- DL-041: Export rate limit — mock enforces after first export click.
- C3: Auditor reads are themselves audit events — show "Your activity is logged" notice.
- C19 / X16: Auditor account cannot hold operational roles — sidebar shows S9 only.

### Founder Notes
- TBD — Does the Auditor see investor PII (name, PAN) or only investor_id + masked fields at `standard` sensitivity?
- TBD — Is `restricted` sensitivity level visible in Phase 1 or deferred?

---

## Claude Code Prompt — Copy and Paste This

```
Read STEP4_ADMIN_BLUEPRINT.md, STEP2_INVESTOR_BLUEPRINT.md, STEP0_OUTPUT.md,
and Mock_Build_Plan.md in this folder.

Implement Step 4 only: build the eight admin screens (S1–S9) into the existing
React + Vite + Tailwind app. Use only the component kit already built
(Button, Table, Card, StatusBadge, FormField, PageHeader).

Instructions:

1. Replace placeholder routes S1–S9 with real screen components in src/screens/
   (match the existing folder structure from Steps 1 and 2).

2. Update mockData.js: replace the empty S1–S9 stubs with the mock data shapes
   defined in STEP4_ADMIN_BLUEPRINT.md exactly.

3. S1 — Login screen. Centred card, no sidebar. Email + password fields, then
   TOTP field on submit. Persona selector dropdown (mock only). On verify →
   set persona in app state → navigate to S2 (or S9 for Auditor).

4. S2 — Admin dashboard. Role-scoped work queue: filter mockData.S2.queues by
   current persona's roles and show only relevant items. Stats row (4 cards).
   Queue table with "Review" button per item navigating to the relevant screen.

5. S3 — Supplier onboarding workspace. Supplier list table. On row click →
   acting-as mode with persistent amber banner. Six-stage wizard driven by
   supplier.status. Include state-variant switcher for: normal | agency_consent_missing | kyc_rejected.

6. S4 — Buyer management. Buyer list table. Side panel on row click with
   identity card, credit profile card, and stage-appropriate actions.
   Show four-eyes UI when credit limit > ₹1 Cr (100,000,000 paise).

7. S5 — Two tabs: Invoice Checks and Listing Approval. Invoice checks tab shows
   check outcomes table with record-outcome actions. Listing approval tab shows
   maker-checker UI — disable "Approve Go-Live" if current persona = maker, with
   MFA re-prompt modal on click.

8. S6 — Disbursement queue. Table + detail panel. Approve button disabled if
   all_signed = false or maker = current persona. MFA modal on approve.

9. S7 — Two tabs: Distributions and Reconciliation. Distributions tab shows
   per-investor payout breakdown with gross/tds/fee/net. Reconciliation tab
   shows matched/partial/unmatched status with shortfall action.

10. S8 — Invite issuance form + invites log table. Issue invite creates a new
    row in the log with 14-day expiry. Revoke button on pending invites.
    Disable issue button if current persona ≠ compliance_reviewer.

11. S9 — Read-only audit log. No sidebar navigation to operational screens for
    Auditor persona. Scope summary card. Filterable events table. Row expand
    for event detail. Export CSV button (mock — disable after first click).

12. Wire all click-paths per STEP0_OUTPUT.md:
    S1 → S2 (admin) or S9 (auditor)
    S2 → S3, S4, S5, S6, S7, S8 (via queue items)
    S5 → S6 (after go-live approval)
    S6 → S7 (after disbursement)
    All screens → S2 (back button)

13. All amounts in paise (BIGINT); display as ₹ with Indian number format.
    Maker-checker: simulate with persona awareness — current persona cannot
    approve items they made.

14. MFA re-prompt: simulate with a modal containing a 6-digit input field on
    sensitive actions (go-live, disbursement approval). Accept any 6-digit code.

Stop after Step 4. Do not build S14, S15, or modify S10–S13.
```
