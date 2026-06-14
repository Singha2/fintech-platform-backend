# Step 5 Blueprint — Supplier Portal (S14) + Buyer Portal (S15)
# Fintech Platform MVP — Invoice Discounting

## How to use this file
Self-contained spec for building S14 and S15 into the existing React + Tailwind app
(skeleton Step 1, investor flow Step 2, admin console Step 4).

**Place alongside:** `STEP0_OUTPUT.md`, `STEP2_INVESTOR_BLUEPRINT.md`,
`STEP4_ADMIN_BLUEPRINT.md`, `Mock_Build_Plan.md`

**Hard limits (same as previous steps):**
- Hardcoded JSON only — no API calls, no backend.
- Use only the existing component kit (Button, Table, Card, StatusBadge, FormField, PageHeader).
- Add mock data to `mockData.js` under keys S14, S15.
- No real auth — simulate OTP login with a "Send OTP → Enter OTP → verified" mock flow.
- S14 and S15 are the thinnest screens in the app — keep each under 80 lines of JSX.

---

## Critical architectural notes before building

**S14 — Supplier has NO login in Phase 1.**
`tblSupplierAccount` intentionally has no `identity_id` column (DL-012, DL-013, A9 in auth.sql).
All supplier work is admin-on-behalf via AgencyConsent. The "Supplier portal" in the mock
is therefore the **admin's acting-as view of the supplier** — it shows what a supplier
*would* see if they had a login, but it is accessed from S3 (admin acting as supplier),
not from a separate supplier login. Render it as a read-mostly view with one write action
(invoice upload), clearly labelled "Acting as: [Supplier Name]".

**S15 — Buyer uses Email + OTP login only. No password, no MFA.**
`tblAcknowledgmentUser` login is OTP-only; no `tblCredential` row exists (AU.1 in auth.sql).
S15 has its own minimal login screen (email → OTP → dashboard). This is a completely
separate login flow from S1 — do not reuse the S1 admin login component.
Out of scope for Phase 1: payment initiation, dispute filing, statements, user management,
bulk acknowledgment, notification configuration (DL-021).

---

## Mock Data Shapes — add to `mockData.js`

```js
S14: {
  // Supplier context — accessed via admin acting-as mode from S3
  supplier: {
    supplier_id: 'sup-001',
    legal_name: 'Alpha Components Pvt Ltd',
    constitution_type: 'private_limited',
    pan: 'AABCA1234Z',
    gstin: '27AABCA1234Z1Z5',
    status: 'active',
    activated_at: '2026-04-20T09:00:00Z',
    agency_consent: {
      consent_id: 'con-001',
      scope: ['invoice_submission', 'kyc_upload', 'financial_profile'],
      granted_at: '2026-05-01T10:00:00Z',
      is_active: true,
    },
  },
  invoices: [
    {
      invoice_id: 'inv-001',
      invoice_number: 'INV-2026-0042',
      buyer_name: 'Reliance Industries Ltd',
      face_value: 5125000,        // paise → ₹51,250
      invoice_date: '2026-05-01',
      due_date: '2026-07-30',
      tenor_days: 90,
      irn: 'IRN123456789012345678901234567890123456789012345678',
      status: 'ops_checks_in_progress',
      // status values visible to supplier:
      // submitted | ops_checks_in_progress | ops_checks_failed
      // | listed | fully_funded | disbursed | matured | closed
      listing: {
        listing_id: 'lst-001',
        status: 'live',
        funding_target: 5000000,     // paise
        committed_total: 3000000,    // paise — 60% funded
        investor_count: 3,           // aggregate only — DL-017
        funding_window_close_at: '2026-05-28T18:00:00Z',
        rate_bps: 1200,
        disbursed_at: null,
        disbursement_utr: null,
      },
    },
    {
      invoice_id: 'inv-002',
      invoice_number: 'INV-2026-0039',
      buyer_name: 'Tata Steel Ltd',
      face_value: 10200000,       // paise → ₹1,02,000
      invoice_date: '2026-04-25',
      due_date: '2026-06-24',
      tenor_days: 60,
      irn: 'IRN987654321098765432109876543210987654321098765432',
      status: 'disbursed',
      listing: {
        listing_id: 'lst-002',
        status: 'disbursed',
        funding_target: 9996000,
        committed_total: 9996000,    // 100% funded
        investor_count: 5,
        funding_window_close_at: '2026-05-15T18:00:00Z',
        rate_bps: 1050,
        disbursed_at: '2026-05-16T10:00:00Z',
        disbursement_utr: 'UTR20260516000456',
      },
    },
    {
      invoice_id: 'inv-003',
      invoice_number: 'INV-2026-0051',
      buyer_name: 'Reliance Industries Ltd',
      face_value: 3000000,        // paise → ₹30,000
      invoice_date: '2026-05-18',
      due_date: '2026-08-16',
      tenor_days: 90,
      irn: null,                  // manual entry — no IRN
      status: 'submitted',
      listing: null,              // not yet listed
    },
  ],
  // New invoice upload form (draft state)
  upload_draft: {
    irn: '',
    invoice_number: '',
    buyer_id: '',
    face_value: '',
    invoice_date: '',
    tenor_days: '',
    doc_hash: null,
  },
  // Available buyers for this supplier (for invoice upload dropdown)
  available_buyers: [
    { buyer_id: 'buy-001', legal_name: 'Reliance Industries Ltd' },
    { buyer_id: 'buy-002', legal_name: 'Tata Steel Ltd' },
  ],
},

S15: {
  // Buyer OTP login state
  login: {
    email: 'procurement@reliance.com',
    otp_sent: false,
    otp_verified: false,
  },
  buyer: {
    buyer_id: 'buy-001',
    legal_name: 'Reliance Industries Ltd',
    ack_user: {
      ack_user_id: 'ack-001',
      display_name: 'Rajan Nair',
      email: 'procurement@reliance.com',
      phone: '+91 98765 00001',
    },
  },
  // Payment instructions for this buyer (one current, DL-021)
  payment_instruction: {
    instruction_id: 'pi-001',
    escrow_bank: 'RBL Bank Ltd',
    account_name: 'Platform Escrow — Reliance Industries',
    account_number: '1234567890123456',
    ifsc: 'RATN0VAAPIS',
    effective_from: '2026-04-01',
    note: 'Pay to this account for each invoice at due date. Use invoice number as payment reference.',
  },
  // Invoices pending or actioned for this buyer
  invoices: [
    {
      invoice_id: 'inv-001',
      invoice_number: 'INV-2026-0042',
      supplier_name: 'Alpha Components Pvt Ltd',
      face_value: 5125000,          // paise → ₹51,250
      invoice_date: '2026-05-01',
      due_date: '2026-07-30',
      tenor_days: 90,
      ack_status: 'pending',        // pending | acknowledged | rejected
      // pending = buyer must act; acknowledged = done; rejected = buyer refused
      ack_requested_at: '2026-05-03T09:00:00Z',
      ack_sla_deadline: '2026-05-06T09:00:00Z',  // SLA for ack (3 business days)
      acknowledged_at: null,
      noa_available: false,         // Notice of Assignment — available post-listing
    },
    {
      invoice_id: 'inv-004',
      invoice_number: 'INV-2026-0038',
      supplier_name: 'Alpha Components Pvt Ltd',
      face_value: 7500000,          // paise → ₹75,000
      invoice_date: '2026-04-20',
      due_date: '2026-07-19',
      tenor_days: 90,
      ack_status: 'acknowledged',
      ack_requested_at: '2026-04-22T09:00:00Z',
      ack_sla_deadline: '2026-04-25T09:00:00Z',
      acknowledged_at: '2026-04-23T14:30:00Z',
      noa_available: true,          // NOA available — listing went live
    },
    {
      invoice_id: 'inv-005',
      invoice_number: 'INV-2026-0035',
      supplier_name: 'Alpha Components Pvt Ltd',
      face_value: 4200000,          // paise → ₹42,000
      invoice_date: '2026-04-10',
      due_date: '2026-07-09',
      tenor_days: 90,
      ack_status: 'pending',
      ack_requested_at: '2026-04-12T09:00:00Z',
      ack_sla_deadline: '2026-04-15T09:00:00Z',  // SLA already passed — show overdue
      acknowledged_at: null,
      noa_available: false,
    },
  ],
},
```

---

## S14 — Supplier Portal

- **Persona:** Supplier (accessed via Admin acting-as from S3 — no independent supplier login in Phase 1)
- **Purpose:** Admin (acting as supplier) uploads invoices and monitors listing and funding status per invoice.
- **Entry from:** S3 (acting-as mode → "Open Supplier Portal" button on supplier detail).
- **Exits to:** S3 (back to supplier workspace).

### Layout
Persistent amber banner at top: **"Acting as: Alpha Components Pvt Ltd · Agency Consent: Active · [Exit acting-as]"**
PageHeader + two tabs: **"Invoices"** | **"Upload Invoice"**

### Tab 1 — Invoices

Invoice status table + expandable listing detail per row.

**Table columns:** Invoice # | Buyer | Face Value (₹) | Invoice Date | Due Date | Status | Listing Status | Action

**StatusBadge mapping for invoice status:**

| Invoice status | Badge |
|---------------|-------|
| submitted | gray |
| ops_checks_in_progress | amber |
| ops_checks_failed | red |
| listed / live | amber |
| fully_funded | purple |
| disbursed | green |
| matured / closed | green |

**Expandable row (click any invoice) shows Listing Detail Card:**
- Listing status badge
- Funding progress: `committed_total / funding_target` → progress bar + % (aggregate only — DL-017)
- Investor count: `investor_count` (aggregate — no investor names shown)
- Funding window closes: `funding_window_close_at` countdown
- Rate: `rate_bps / 100`% p.a.
- Disbursement: date + UTR if `disbursed_at` is set; "Pending" if not
- If `listing = null` → "Not yet listed — operational checks pending"

### Tab 2 — Upload Invoice

Card with invoice upload form. Two modes toggled by radio: **"Enter IRN"** | **"Manual Entry"**

**IRN mode:**
- IRN (FormField, 54-char) + "Fetch from GST" Button → mock auto-fills invoice_number, face_value, invoice_date, tenor_days from mock data (DL-016)
- Buyer selector (dropdown from `available_buyers`)
- Document upload (mock — label: "Invoice document PDF")
- "Submit Invoice" Button → adds new invoice to list with status `submitted`

**Manual mode (fallback — IRN = null):**
- Invoice number (FormField)
- Buyer selector (dropdown)
- Face value ₹ (FormField, numeric)
- Invoice date (FormField, date)
- Tenor days (FormField, numeric — range 1–180)
- Document upload (mock)
- "Submit Invoice" Button → same outcome

### State Variants
- `agency_consent_inactive` — red banner replacing amber: "Agency consent revoked. Cannot perform actions." All form inputs disabled.
- `ops_checks_failed` — row shows red badge + "View failure reason" expand showing which check failed.
- `funding_shortfall` — listing status: "Funding window closed — shortfall. Refund in progress."

### Rules
- DL-012 / DL-013: Admin-assisted; all actions under agency consent — amber banner always visible.
- DL-016: IRN auto-fetch preferred; manual fallback available.
- DL-017: Supplier sees aggregate funding progress only — no investor identities, no per-investor amounts.
- AC.3: Every action emits `AgencyAction.Recorded` — show action log tab (read-only table of past admin actions on this supplier).
- AC.2: MAA e-sign non-delegable — not in scope for this screen (handled in S3).

### Founder Notes
- TBD — Should the supplier see a "Disbursement received" confirmation separate from the UTR, or is the UTR sufficient?
- TBD — Action log tab: how far back should agency actions be visible — 30 days, 90 days, all time?

---

## S15 — Buyer Portal (Minimal)

- **Persona:** Buyer (AcknowledgmentUser — OTP login, no password)
- **Purpose:** Per-invoice acknowledgment and payment instructions view. Nothing else in Phase 1.
- **Entry from:** Email OTP link / direct URL (separate from admin login S1).
- **Exits to:** No navigation to other screens — self-contained portal.

### Layout
**Unauthenticated state:** Minimal centred login card — no sidebar, no top bar.
**Authenticated state:** Minimal top bar (buyer name + "Sign out") + main content area (no sidebar — buyer sees only this portal).

### Login Flow (OTP — no password)

Centred card with platform name:
1. Email (FormField) + "Send OTP" Button → mock: `otp_sent = true`
2. OTP field (6-digit FormField, label "Enter the OTP sent to your email") + "Verify" Button → mock: accept any 6 digits → `otp_verified = true` → show dashboard

**State variants during login:**
- `otp_sent` — show OTP field; hide email field; show "Resend OTP" ghost button (disabled for 30s mock).
- `otp_invalid` — inline error "Invalid OTP. Please try again."

### Dashboard (authenticated)

Two sections stacked vertically: **Invoices for Acknowledgment** + **Payment Instructions**

---

**Section 1 — Invoices for Acknowledgment**

PageHeader: "Invoices · [Buyer legal name]"

Table columns: Invoice # | Supplier | Amount (₹) | Invoice Date | Due Date | Status | SLA | Action

**StatusBadge per ack_status:**
| Status | Badge |
|--------|-------|
| pending | amber |
| acknowledged | green |
| rejected | red |

**SLA column:**
- If `ack_status = pending` AND today > `ack_sla_deadline` → show "Overdue" red badge
- If `ack_status = pending` AND today ≤ `ack_sla_deadline` → show deadline date in amber
- If `ack_status = acknowledged` → show acknowledged_at date in green

**Action column:**
- `pending` → "Acknowledge" Button (primary) — on click: show confirmation modal:
  - Modal: "Acknowledge Invoice INV-XXXX from [Supplier] for ₹[amount]?"
  - "Confirm Acknowledgment" Button → `ack_status → acknowledged`, `acknowledged_at → now()`
  - "Cancel" ghost button
- `acknowledged` → "View NOA" Button (ghost) — enabled only if `noa_available = true`; disabled with tooltip "Assignment notice issued after funding" if false
- `rejected` → read-only "Rejected" badge; no action

**Out of scope banner** (subtle info card at bottom of section):
"Bulk acknowledgment, dispute filing, and statements are not available in Phase 1."

---

**Section 2 — Payment Instructions**

Card: "Payment Instructions"
- Escrow bank: `payment_instruction.escrow_bank`
- Account name: `payment_instruction.account_name`
- Account number: `payment_instruction.account_number` (with copy button)
- IFSC: `payment_instruction.ifsc` (with copy button)
- Effective from: `payment_instruction.effective_from`
- Note: `payment_instruction.note`

Amber info banner below: "Pay the exact invoice face value to this account on or before the due date. Use the invoice number as the payment reference."

No "Pay Now" button — payment initiation is out of scope (DL-021).

### State Variants
- `no_pending_invoices` — invoices table shows "No invoices pending acknowledgment" empty state; already-acknowledged rows still visible.
- `all_overdue` — all pending rows show red "Overdue" SLA badge; no blocking — buyer can still acknowledge.
- `buyer_suspended` — after login, show full-page Card: "Your account has been temporarily suspended. Please contact [support email]." No dashboard content.

### Rules
- DL-021: Strictly minimal scope — no payment initiation, no dispute, no statements, no user management, no bulk ack.
- AU.1: OTP-only login — no password field, no MFA app.
- DL-019: Per-invoice acknowledgment only — no blanket ack option shown.
- BA.1: At least one active AcknowledgmentUser per buyer — enforced in S4 (admin), not here.
- G19 / X14: Buyer sees only their own invoices — mock enforces by filtering on `buyer_id`.

### Founder Notes
- TBD — NOA ("View NOA" button): is the Notice of Assignment downloadable as a PDF directly from this portal, or emailed to the buyer?
- TBD — When buyer acknowledges, do they receive a confirmation email immediately, or is the on-screen confirmation sufficient for Phase 1?
- TBD — Overdue acknowledgments: does the system auto-escalate to admin (Ops Executive) or does the buyer just see the overdue label?

---

## Claude Code Prompt — Copy and Paste This

```
Read STEP5_SUPPLIER_BUYER_BLUEPRINT.md, STEP4_ADMIN_BLUEPRINT.md,
STEP0_OUTPUT.md, and Mock_Build_Plan.md in this folder.

Implement Step 5 only: build S14 (Supplier Portal) and S15 (Buyer Portal)
into the existing React + Vite + Tailwind app. Use only the existing component
kit (Button, Table, Card, StatusBadge, FormField, PageHeader).

Instructions:

1. Update mockData.js: replace the empty S14 and S15 stubs with the mock
   data shapes defined in STEP5_SUPPLIER_BUYER_BLUEPRINT.md exactly.

2. S14 — Supplier Portal:
   - Render as an admin acting-as view (no separate supplier login).
   - Show a persistent amber banner at the top: "Acting as: [supplier.legal_name]
     · Agency Consent: Active · [Exit acting-as] button → navigates back to S3".
   - Two tabs: "Invoices" and "Upload Invoice".
   - Invoices tab: Table with all invoices. Each row is expandable (click to
     expand/collapse) showing the Listing Detail Card with funding progress bar,
     investor count (aggregate only — no investor names), window countdown,
     disbursement UTR if available.
   - Upload Invoice tab: toggle between IRN mode and Manual Entry mode via
     radio buttons. IRN mode: "Fetch from GST" button auto-fills mock invoice
     fields. Both modes: "Submit Invoice" button appends a new invoice with
     status "submitted" to the invoices array in state.
   - State variant switcher (dropdown or button group): normal |
     agency_consent_inactive | ops_checks_failed.

3. S15 — Buyer Portal:
   - Separate from the admin login (S1). S15 has its own minimal login card.
   - Login flow: Email field → "Send OTP" button → OTP field (6-digit) →
     "Verify" button → accept any 6 digits → show dashboard.
   - After login: minimal top bar (buyer name + "Sign out") with NO sidebar.
     Buyer only sees S15 — no navigation to other screens.
   - Dashboard has two sections:
     a) Invoices table with Acknowledge action. Pending invoices show an
        "Acknowledge" button that opens a confirmation modal. On confirm →
        update ack_status to "acknowledged" in state.
        Show SLA column: "Overdue" red badge if past sla_deadline and still
        pending; deadline date in amber if not yet due.
        "View NOA" ghost button on acknowledged rows — enabled only if
        noa_available = true; otherwise disabled with a tooltip.
     b) Payment Instructions card with copy buttons for account number and IFSC.
        Amber info banner below. No "Pay Now" button.
   - State variant switcher: normal | no_pending_invoices | buyer_suspended.

4. Wire click-paths per STEP0_OUTPUT.md:
   - S3 "Open Supplier Portal" button → S14 (in acting-as mode).
   - S14 "Exit acting-as" → S3.
   - S15 is standalone — accessible from persona switcher as "Buyer" persona;
     shows its own login screen first, then dashboard after mock OTP.

5. Sidebar behaviour:
   - When persona = Supplier → sidebar shows only S14.
   - When persona = Buyer → sidebar shows only S15; clicking S15 shows the
     OTP login card if not yet verified.

6. All amounts in paise; display as ₹ with Indian number format (divide by 100).
   Funding progress = committed_total / funding_target as a percentage bar.

7. Keep both screens thin — under 80 lines of JSX each (excluding mock data).
   No animation, no theming, no extra variants beyond what is specified.

Stop after Step 5. Do not modify S1–S13.
```
