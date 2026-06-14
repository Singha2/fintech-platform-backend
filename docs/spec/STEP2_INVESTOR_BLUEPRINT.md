# Step 2 Blueprint — Investor Flow (S10–S13)
# Fintech Platform MVP — Invoice Discounting

## How to use this file
This file is the complete, self-contained spec for building the four investor screens
into the existing React + Tailwind skeleton (built in Step 1).

**What to do:**
1. Place this file alongside `STEP0_OUTPUT.md` and `Mock_Build_Plan.md` in your project folder.
2. Use the Claude Code prompt at the bottom of this file.
3. Claude Code reads this file and builds all four screens into the existing app.

**Hard limits for the mock:**
- Hardcoded JSON only — no API calls, no backend.
- Use only the component kit already built (Button, Table, Card, StatusBadge, FormField, PageHeader).
- Add mock data to `mockData.js` under keys S10, S11, S12, S13.
- No real auth, no real file upload, no real e-sign — simulate with state transitions.
- One variant per state — use StatusBadge and conditional rendering to show state variants.

---

## Component Kit Reference (already built in Step 1)

| Component | Usage |
|-----------|-------|
| `<Button>` | Primary action (CommitSubscription, Submit, Download) |
| `<Button ghost>` | Secondary / cancel actions |
| `<Table columns={[]} rows={[]} />` | Positions table, statements list, listings list |
| `<Card title subtitle>` | Section containers, summary panels |
| `<StatusBadge status="active\|pending\|rejected\|invited\|draft" />` | Account status, subscription status, listing status |
| `<FormField label placeholder />` | Amount input, questionnaire fields |
| `<PageHeader title subtitle />` | Top of every screen |

---

## Mock Data Shapes — add to `mockData.js`

```js
// mockData.js additions — replace empty stubs with these shapes

S10: {
  invite: {
    invite_id: 'inv-001',
    expiry_at: '2026-06-01T00:00:00Z',
    status: 'pending', // pending | consumed | expired
  },
  investor: {
    investor_id: 'inv-acct-001',
    sub_type: 'resident_individual', // resident_individual | huf
    status: 'kyc_submitted',
    // status flow: signed_up → identity_verified → kyc_submitted
    //              → kyc_in_review → kyc_approved → mia_pending → active
    pan: 'ABCDE1234F',
    aadhaar_last4: '7890',
    bank_account_last4: '4321',
    fatca_status: 'not_us_person',
    kyc_approved_by: null,
    kyc_approved_at: null,
    mia_signed_at: null,
    activated_at: null,
  },
  suitability: {
    assessment_id: 'suit-001',
    mismatch: false,          // set true to show override-ack variant
    override_text_hash: null, // set when investor acknowledges mismatch
  },
  // Wizard stage derived from investor.status:
  // signed_up          → Stage 1: Sign Up
  // identity_verified  → Stage 2: KYC Upload
  // kyc_submitted      → Stage 3: Financial Profile & Suitability
  // kyc_in_review      → Stage 4: Approval Pending (read-only hold)
  // kyc_approved       → Stage 5: MIA e-Sign
  // mia_pending        → Stage 5: MIA e-Sign
  // active             → Redirect to S11
},

S11: {
  listings: [
    {
      listing_id: 'lst-001',
      buyer_name: 'Reliance Industries Ltd',
      buyer_sector: 'Energy',
      supplier_name: 'Alpha Components Pvt Ltd',
      funding_target: 5000000,        // paise → ₹50,000
      committed_total: 3000000,       // paise → ₹30,000 (60%)
      funding_window_close_at: '2026-05-28T18:00:00Z',
      rate_bps: 1200,                 // 12% p.a.
      tenor_days: 90,
      due_date: '2026-08-20',
      status: 'live',                 // live | fully_funded
      investor_subscribed: false,     // true if this investor already committed
    },
    {
      listing_id: 'lst-002',
      buyer_name: 'Tata Steel Ltd',
      buyer_sector: 'Manufacturing',
      supplier_name: 'Beta Metals Pvt Ltd',
      funding_target: 10000000,       // paise → ₹1,00,000
      committed_total: 10000000,      // paise → fully funded
      funding_window_close_at: '2026-05-25T18:00:00Z',
      rate_bps: 1050,                 // 10.5% p.a.
      tenor_days: 60,
      due_date: '2026-07-25',
      status: 'fully_funded',
      investor_subscribed: false,
    },
    {
      listing_id: 'lst-003',
      buyer_name: 'Infosys Ltd',
      buyer_sector: 'Technology',
      supplier_name: 'Gamma Tech Services',
      funding_target: 8000000,        // ₹80,000
      committed_total: 1600000,       // 20%
      funding_window_close_at: '2026-05-30T18:00:00Z',
      rate_bps: 1100,                 // 11% p.a.
      tenor_days: 75,
      due_date: '2026-08-12',
      status: 'live',
      investor_subscribed: true,      // show "Invested" badge
    },
  ],
},

S12: {
  listing: {
    listing_id: 'lst-001',
    status: 'live',
    funding_target: 5000000,
    committed_total: 3000000,
    funding_window_close_at: '2026-05-28T18:00:00Z',
    pricing_snapshot: {
      rate_bps: 1200,
      fee_bps: 50,
      snapshot_at: '2026-05-01T10:00:00Z',
    },
    va_id: 'va-001',
    virtual_account_number: '9234567890123456', // shown post-commit only
    virtual_account_ifsc: 'RATN0VAAPIS',
  },
  invoice: {
    invoice_number: 'INV-2026-0042',
    face_value: 5125000,    // paise → ₹51,250 (funding_target = face − discount − fee)
    tenor_days: 90,
    due_date: '2026-08-20',
    invoice_date: '2026-05-01',
    irn: 'IRN123456789012345678901234567890123456789012345678', // 54-char GST IRN
    check_outcomes: {
      irn_verified:   { outcome: 'pass', detail: 'IRN active on GST portal', checked_at: '2026-05-02T09:00:00Z' },
      buyer_ack:      { outcome: 'pass', detail: 'Acknowledged by buyer signatory', checked_at: '2026-05-02T10:00:00Z' },
      duplicate_check:{ outcome: 'pass', detail: 'No duplicate found', checked_at: '2026-05-02T09:05:00Z' },
    },
  },
  buyer: {
    name: 'Reliance Industries Ltd',
    sector: 'Energy',
    rating: 'AA+',
    rating_source: 'CRISIL',
  },
  supplier: {
    name: 'Alpha Components Pvt Ltd',
    constitution_type: 'private_limited',
  },
  subscription: null, // null = not yet subscribed; object = existing subscription
  // Subscription shape when committed:
  // {
  //   subscription_id: 'sub-001',
  //   amount: 1000000,          // ₹10,000
  //   status: 'committed',      // committed | funds_pending | funds_received
  //                             // | confirmed | assignment_executed | closed
  //   expected_inflow_amount: 1000000,
  //   actual_inflow_txn_ref: null,
  //   assignment_doc_hash: null,
  //   distribution_outcome: null,
  //   concentration_warnings_at_commit: [],
  // },
},

S13: {
  investor: {
    investor_id: 'inv-acct-001',
    sub_type: 'resident_individual',
    pan: 'ABCDE1234F',
    aadhaar_last4: '7890',
    bank_account_last4: '4321',
    activated_at: '2026-04-01T00:00:00Z',
    kyc_refresh_due_at: '2027-04-01T00:00:00Z',
    status: 'active',
  },
  summary: {
    total_deployed_paise: 3000000,    // ₹30,000
    total_returned_paise: 1050000,    // ₹10,500
    active_positions: 2,
    matured_positions: 1,
  },
  subscriptions: [
    {
      subscription_id: 'sub-001',
      listing_id: 'lst-003',
      buyer_name: 'Infosys Ltd',
      supplier_name: 'Gamma Tech Services',
      amount: 1000000,                // ₹10,000
      status: 'confirmed',
      due_date: '2026-08-12',
      assignment_doc_hash: null,
      distribution_outcome: null,
      concentration_warnings_at_commit: [],
    },
    {
      subscription_id: 'sub-002',
      listing_id: 'lst-004',
      buyer_name: 'HCL Technologies Ltd',
      supplier_name: 'Delta IT Supplies',
      amount: 2000000,                // ₹20,000
      status: 'closed',
      due_date: '2026-04-10',
      assignment_doc_hash: 'abc123hash',
      distribution_outcome: {
        gross: 2058333,               // principal + return
        tds:   8333,                  // 10% TDS on return
        fee:   1000,
        net:   2049000,
      },
      concentration_warnings_at_commit: [],
    },
  ],
  tds: [
    {
      tds_deduction_id: 'tds-001',
      listing_id: 'lst-004',
      buyer_name: 'HCL Technologies Ltd',
      fy_code: 'FY2025-26',
      gross_paise: 2058333,
      tds_amount_paise: 8333,
      fee_paise: 1000,
      net_paise: 2049000,
      challan_ref: 'CHL20260410001',
    },
  ],
  statements: [
    {
      period: '2026-04',
      kind: 'monthly_portfolio',
      doc_hash: 'stmthash001',
      generated_at: '2026-05-01T06:00:00Z',
    },
  ],
},
```

---

## S10 — Investor Onboarding

- **Persona:** Investor
- **Purpose:** Eight-stage wizard: invite consumption → sign-up → identity verification → KYC document upload → financial profile & suitability → bank & tax → admin approval wait → MIA e-sign → activated.
- **Entry from:** Single-use invite URL (email link from S8). Cold sign-ups without valid code show a "waitlist" dead-end — not this flow.
- **Exits to:** S11 on `status = active`.

### Layout
Multi-step wizard. Show a stage progress bar at the top (8 stages). Active stage = form. Completed stages = tick. Pending stages = locked.

### Stages & Fields to Show

| Stage | Shown when `investor.status =` | Fields / UI |
|-------|-------------------------------|-------------|
| 1. Sign Up | `signed_up` (initial) | Email (pre-filled from invite), Phone, Sub-type selector (resident_individual / huf only) |
| 2. Identity Verification | `signed_up` → verifying | PAN input (10 char), Aadhaar OTP trigger (show "Send OTP" button → mock success) |
| 3. KYC Upload | `identity_verified` | Address proof upload (mock), Photo upload (mock), Signature upload (mock). Show "Submit KYC" button → status → `kyc_submitted` |
| 4. Financial Profile & Suitability | `kyc_submitted` | Declared annual income (FormField), Source of funds (FormField), Investment experience (dropdown), Risk tolerance questionnaire (3 mock questions). If `suitability.mismatch = true` → show override-ack block (yellow warning Card + "I acknowledge" Button) before proceeding |
| 5. Bank & Tax | `kyc_submitted` (after suitability) | Bank account number (last 4 shown after penny-drop mock), Nominee name (FormField), FATCA declaration checkbox |
| 6. Approval Pending | `kyc_in_review` | Read-only holding Card: "Your file is under review by our Compliance team." No actions. |
| 7. MIA e-Sign | `kyc_approved` | Card showing "Master Investment Agreement ready". Button: "Review & e-Sign" → mock click → status → `mia_pending` → `active` |
| 8. Active | `active` | Success Card. Button: "Browse Listings" → navigate to S11 |

### State Variants (use a dropdown / toggle in mock for switching)
- `invite_expired` — show expiry error Card before wizard loads; no proceed.
- `mismatch_pending_override` — Stage 4 blocked until override-ack clicked (C21, G26).
- `kyc_rejected` — show rejection reason Card with "Re-submit KYC" button → back to Stage 3.

### Rules (display in a small footnote or tooltip in mock)
- C20, DL-008: Invite-gated; single-use code; 14-day validity.
- DL-050: Full KYC stack before activation.
- C15: Only `aadhaar_last4` stored — never show full Aadhaar.
- IA.3: Activation = KYC approved + MIA signed + suitability resolved.
- C21, G26: Suitability override requires explicit acknowledgment.

### Founder Notes (show as `// TODO` comments in mock)
- TBD — MIA e-sign: embedded iframe vs redirect to vendor e-sign page?
- TBD — What is shown in the approval-pending stage? SLA estimate? Contact email?

---

## S11 — Listing Marketplace

- **Persona:** Investor
- **Purpose:** Filterable index of all `live` listings the investor can subscribe to.
- **Entry from:** S10 (on activation), S13 (back to browse), S12 (back navigation).
- **Exits to:** S12 (click any listing).

### Layout
PageHeader + filter bar + listing cards (or Table). Each card/row is clickable.

### Fields to Show per Listing
| Field | Source | Display |
|-------|--------|---------|
| Buyer name | `buyer_name` | Bold, prominent |
| Buyer sector | `buyer_sector` | Badge / tag |
| Supplier name | `supplier_name` | Secondary text |
| Face value | `funding_target` (paise) | ₹ formatted |
| Funding progress | `committed_total / funding_target` | Progress bar % |
| Return rate | `rate_bps / 100` | "12.00% p.a." |
| Tenor | `tenor_days` | "90 days" |
| Due date | `due_date` | Date |
| Window closes | `funding_window_close_at` | Countdown / date |
| Listing status | `status` | StatusBadge: live=green, fully_funded=gray |
| Already invested | `investor_subscribed` | "Invested" purple badge if true |

### Filter Bar (mock — no real filter logic needed, just render the controls)
- Sector: dropdown (All / Energy / Manufacturing / Technology)
- Tenor: dropdown (All / <30d / 30–60d / 60–90d / 90d+)
- Status: toggle (Live only / Show fully funded)

### State Variants
- `fully_funded` listing — grey card, no click-through to S12 subscribe action.
- `empty_marketplace` — no listings; show "No live listings available" Card.
- `investor_suspended` — show suspension banner at top; hide listing cards.

### Rules
- DL-010: Buyer name fully disclosed on card.
- DL-011: Soft concentration warning advisory (show as info banner if applicable).
- L.9 / C12: Listings past `funding_window_close_at` must not appear subscribable.
- X14 / G19: Tenant isolation (mock: all listings visible to all investors in mock).

### Founder Notes
- TBD — Should `fully_funded` listings be visible (social proof) or filtered out entirely?
- TBD — Is IRN verification status shown on the card or only on S12 detail?

---

## S12 — Listing Detail + Subscribe

- **Persona:** Investor
- **Purpose:** Full diligence view of one listing plus the subscribe action.
- **Entry from:** S11 (click a listing card).
- **Exits to:** S11 (back / cancel), S13 (after successful CommitSubscription).

### Layout
Two-column layout: left = listing/invoice detail; right = subscription action panel.

### Left Column — Listing & Invoice Detail

**Listing Header Card**
- Status badge, Funding target (₹), Committed total (₹), Funding % progress bar
- Funding window countdown (`funding_window_close_at`)
- Pricing: Rate `rate_bps/100`% p.a., Fee `fee_bps/100`%, Snapshot date

**Invoice Card**
- Invoice number, Face value (₹), Tenor (days), Invoice date, Due date
- IRN (truncated with copy button)
- Ops checks: render `check_outcomes` as a small table — check name | outcome (pass/fail badge) | detail

**Buyer Card**
- Buyer name (bold), Sector, Rating + Source — always disclosed (DL-010)

**Supplier Card**
- Supplier name, Constitution type

### Right Column — Subscription Panel

**When `subscription = null` (not yet subscribed):**
- FormField: "Investment Amount (₹)" — input with ₹10,000 minimum label (DL-007)
- Return illustration (computed display only, not stored):
  - Gross return = `amount × rate_bps / 10000 × tenor_days / 365`
  - TDS (10% on return) = `gross_return × 0.10`
  - Net = gross_return − TDS
- Concentration warning Card (amber) if applicable (DL-011) — non-blocking
- Button: "Commit Subscription" → on click → update mock subscription status to `committed`
- Button ghost: "Back to Listings" → S11

**When `subscription.status = committed` or `funds_pending`:**
- StatusBadge: "Committed"
- Virtual Account details Card (shown post-commit — DL-009):
  - VA number: `virtual_account_number`
  - IFSC: `virtual_account_ifsc`
  - "Transfer ₹X from your bank to this account"
  - Copy button for VA number
- Button ghost: "Cancel Subscription" → update status to `cancelled_by_investor`

**When `subscription.status = funds_received / confirmed / assignment_executed`:**
- Read-only status Card. No cancel.

### State Variants
- `not_yet_subscribed` — subscribe panel open.
- `committed_awaiting_funds` — VA details shown.
- `funding_window_closed` — subscribe panel replaced by "Funding window closed" Card.
- `fully_funded_no_headroom` — "No capacity remaining" Card.
- `concentration_warning` — amber warning inline before commit button.

### Rules
- DL-007 / S.1: ₹10,000 minimum ticket — enforce in FormField validation.
- DL-009: No platform wallet — investor pushes to VA; show VA details post-commit.
- DL-010: Buyer always disclosed.
- DL-011 / S.8: Concentration warnings non-blocking.
- L.9 / C12: Block CommitSubscription if past `funding_window_close_at`.
- G10 / L.2: Block if `committed_total + amount > funding_target`.

### Founder Notes
- TBD — Are `check_outcomes` shown to investor in full or summarised as "Verified" badge?
- TBD — TDS estimate shown pre-commit or only on statements post-distribution?

---

## S13 — Investor Portfolio + Statements

- **Persona:** Investor
- **Purpose:** Dashboard of all positions (active and historical) plus downloadable statements.
- **Entry from:** S12 (post-commit redirect), sidebar nav.
- **Exits to:** S11 (browse more), S12 (click a position row).

### Layout
PageHeader + Summary Cards row + Positions Table + TDS section + Statements section + Account Details section.

### Summary Cards Row (4 cards)
| Card | Value |
|------|-------|
| Total Deployed | `summary.total_deployed_paise` → ₹ |
| Total Returned | `summary.total_returned_paise` → ₹ |
| Active Positions | `summary.active_positions` |
| Matured Positions | `summary.matured_positions` |

### Positions Table
Columns: Listing (Buyer / Supplier) | Amount (₹) | Status | Due Date | Distribution | Action

Per row from `subscriptions[]`:
- Buyer name + Supplier name (two lines)
- Amount in ₹
- StatusBadge: committed=amber, confirmed=amber, closed with distribution=green, closed with loss=red
- Due date
- Distribution outcome: if `distribution_outcome` set → show net (₹), gross, TDS, fee on hover/expand; else "—"
- Action: "View" button → navigate to S12 for that listing

### TDS Section
Table: FY | Listing (Buyer) | Gross (₹) | TDS (₹) | Fee (₹) | Net (₹) | Challan Ref
From `tds[]`. Show "Download Form 16A" button per row (mock — disabled if `challan_ref` null).

### Statements Section
Table: Period | Type | Generated | Action
From `statements[]`. "Download" button per row (mock — always enabled).
- `kind = monthly_portfolio` → label "Monthly Statement"
- `kind = form_16a` → label "Form 16A"

### Account Details Section (collapsed Card)
- Sub-type, PAN, Aadhaar last 4, Bank account last 4, KYC refresh due date
- Show amber banner if `kyc_refresh_due_at` within 30 days (C17)

### State Variants
- `empty_portfolio` — no subscriptions; CTA Card → S11.
- `pending_distribution` — status=`assignment_executed`; show "Awaiting buyer payment" label.
- `loss_realised` — show loss amount in red; no distribution_outcome net (G28).
- `kyc_refresh_due` — amber banner at page top (C17, DL-037 — non-blocking in Phase 1).
- `investor_suspended` — red banner; all positions read-only.

### Rules
- DL-045 / G4: gross − tds − fee = net paise equality — show all four in distribution breakdown.
- DL-030: T+1 distribution timing — display label only.
- C17: KYC refresh banner at 12-month mark.
- G19 / X14: Investor sees only their own subscriptions.
- DL-011: Concentration warnings on committed positions shown as audit trail label.

### Founder Notes
- TBD — Form 16A download: available from this screen directly or emailed only?
- TBD — Loss-realised positions: show recovery timeline or just final loss amount? (G28 unresolved)

---

## Claude Code Prompt — Copy and Paste This

```
Read STEP2_INVESTOR_BLUEPRINT.md, STEP0_OUTPUT.md, and Mock_Build_Plan.md in this folder.

Implement Step 2 only: build the four investor screens (S10, S11, S12, S13) into
the existing React + Vite + Tailwind app from Step 1. Use only the component kit
already built (Button, Table, Card, StatusBadge, FormField, PageHeader).

Instructions:
1. Replace the S10, S11, S12, S13 placeholder routes with real screen components
   in src/screens/ (or src/pages/ — match the existing folder structure).
2. Update mockData.js: replace the empty S10–S13 stubs with the mock data shapes
   defined in STEP2_INVESTOR_BLUEPRINT.md exactly.
3. S10 — multi-step wizard. Stage derived from investor.status. Show one stage
   at a time. Include a state-variant switcher (dropdown or button group) so the
   founder can toggle between: normal flow | invite_expired | mismatch_pending_override | kyc_rejected.
4. S11 — listing cards or table. Each live listing is clickable → S12.
   Fully-funded listings greyed out and non-clickable.
5. S12 — two-column layout: detail left, subscription panel right. Subscription
   panel changes based on subscription status. Include a state-variant switcher
   for: not_subscribed | committed | funding_window_closed | fully_funded_no_headroom.
6. S13 — summary cards + positions table + TDS section + statements section +
   account details. Include empty_portfolio state variant.
7. Wire click-paths per STEP0_OUTPUT.md: S10 → S11, S11 → S12, S12 → S13 (post-commit),
   S13 → S11, S12 → S11 (back).
8. All amounts stored as paise (integer); display as ₹ formatted (divide by 100,
   two decimal places, Indian number format).
9. No backend, no API calls, no real file upload. Simulate state transitions
   with React useState.

Stop after Step 2. Do not build any other screens.
```
