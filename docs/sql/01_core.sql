-- =============================================================================
-- 01_core.sql
-- Fintech Invoice Discounting Platform — Phase 1 MVP
-- Core Domain: BC1 Listing & Invoice, BC2 Subscription, BC3 Credit & Underwriting,
--              BC4 Settlement, BC5 Assignment & Signing, BC6 Collections & Recovery
--
-- Design decisions (locked — see Decision Log):
--   * All PKs are UUIDv7, stored as UUID; generation in app layer.
--   * Money stored as BIGINT in paise (smallest INR unit). Never FLOAT.
--   * BusinessDate stored as DATE. Timestamps as TIMESTAMPTZ.
--   * aggregate_version INT on every aggregate for optimistic concurrency
--     (app layer checks expected version on every write).
--   * Refs: DL-007, DL-016, DL-017, DL-019, DL-022, DL-023, DL-024, DL-029,
--           DL-030, DL-043, DL-044, DL-045, DL-048, DL-002, C4, C6, C8, C9,
--           C11, C12, C23, C27, G4, G6, G10, G11, G13, G20.
-- =============================================================================

-- =============================================================================
-- SECTION 0A: EXTENSIONS
-- Registered here (first migration) so all subsequent files can rely on them.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS citext;   -- case-insensitive text; used by auth_identity.email (03_auth.sql)

-- =============================================================================
-- SECTION 0: SHARED DOMAIN VALUE-OBJECT TYPES  (Shared Kernel — B1 §2)
-- Defined here (first migration) so every subsequent file can use them.
-- Moved from 04_generic_acl.sql where they were unreachable by earlier tables.
-- =============================================================================

-- PAN: 10-character alphanumeric per Income Tax Dept format.
CREATE DOMAIN pan_type AS TEXT
    CHECK (VALUE ~ '^[A-Z]{5}[0-9]{4}[A-Z]$');

-- GSTIN: 15-character GST Identification Number.
-- Format: 2-digit state code + 10-char PAN + 1-char entity + Z + 1-char checksum.
CREATE DOMAIN gstin_type AS TEXT
    CHECK (VALUE ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$');

-- IFSC: 11-character RBI branch code. 4 alpha (bank) + 0 + 6 alphanumeric (branch).
CREATE DOMAIN ifsc_type AS TEXT
    CHECK (VALUE ~ '^[A-Z]{4}0[A-Z0-9]{6}$');

-- IRN: Invoice Reference Number issued by IRP — always exactly 64 hex characters.
CREATE DOMAIN irn_type AS TEXT
    CHECK (char_length(VALUE) = 64);

-- AadhaarLast4: Only the last 4 digits of Aadhaar are stored (UIDAI norms, C15).
-- Full Aadhaar number must never be persisted — DL-050, A1 C15.
CREATE DOMAIN aadhaar_last4_type AS CHAR(4)
    CHECK (VALUE ~ '^[0-9]{4}$');

-- money_paise: Non-negative BIGINT paise (>= 0). Use for accumulators, fees, and
-- optional caps where a zero value is semantically valid (e.g. zero TDS deducted).
CREATE DOMAIN money_paise AS BIGINT
    CHECK (VALUE >= 0);

-- positive_money_paise: Strictly positive BIGINT paise (> 0). Use for amounts that
-- must represent a real economic transfer — gross payouts, net payouts, credit limits.
-- A zero here is always an accounting error, not a valid state.
CREATE DOMAIN positive_money_paise AS BIGINT
    CHECK (VALUE > 0);

-- bps_type: Basis-points integer (0–100 000 = 0%–1000%). Used for rates and fees.
CREATE DOMAIN bps_type AS INT
    CHECK (VALUE >= 0 AND VALUE <= 100000);


-- =============================================================================
-- SECTION 1: ENUM TYPES
-- =============================================================================

-- ---------------------------------------------------------------------------
-- BC1 — Listing & Invoice
-- ---------------------------------------------------------------------------

CREATE TYPE deal_invoice_status AS ENUM (
    'submitted',
    'ops_checks_in_progress',
    'ops_checks_passed',
    'ops_checks_failed',
    'listed'
);

-- Spec §6.4: 11 nominal states + exit/alternate branches.
CREATE TYPE deal_listing_status AS ENUM (
    'draft',
    'operational_checks_in_progress',
    'awaiting_acknowledgment',
    'ready_for_review',
    'live',
    'fully_funded',
    'disbursed',
    'in_repayment',
    'matured_payment_received',
    'distributed',
    'closed',
    -- exit / alternate branches
    'rejected_operational',
    'acknowledgment_failed',
    'funding_failed_refunded',
    'cancelled_pre_disbursement',
    'held_for_review',
    -- delay sub-states (from in_repayment)
    'mildly_delayed',
    'delayed',
    'seriously_delayed',
    'under_adjudication',
    -- outcome states (aligned with risk_default_outcome and col_maturity_outcome)
    'disputed',
    'dilution',
    'fraud',
    'defaulted',
    'recovered'
);

CREATE TYPE deal_terminal_outcome AS ENUM (
    'distributed',
    'funding_failed_refunded',
    'cancelled_pre_disbursement',
    'defaulted'
);

-- ---------------------------------------------------------------------------
-- BC2 — Subscription
-- ---------------------------------------------------------------------------

CREATE TYPE sub_subscription_status AS ENUM (
    'committed',
    'funds_pending',
    'funds_received',
    'confirmed',
    'assignment_executed',
    'distribution_received',
    'closed',
    'cancelled_by_investor',
    'refunded',
    'loss_realised'
);

-- ---------------------------------------------------------------------------
-- BC3 — Credit & Underwriting
-- ---------------------------------------------------------------------------

CREATE TYPE risk_tenor_bucket AS ENUM (
    'lte_30d',
    '31_60d',
    '61_90d',
    '91_180d'
);

CREATE TYPE risk_default_case_status AS ENUM (
    'requested',
    'under_adjudication',
    'classified'
);

CREATE TYPE risk_default_outcome AS ENUM (
    'disputed',
    'dilution',
    'fraud',
    'defaulted',
    'recovered'
);

-- ---------------------------------------------------------------------------
-- BC4 — Settlement
-- ---------------------------------------------------------------------------

CREATE TYPE cash_virtual_account_status AS ENUM (
    'requested',
    'created',
    'closed'
);

CREATE TYPE cash_payout_kind AS ENUM (
    'disbursement',
    'distribution',
    'refund'
);

CREATE TYPE cash_payout_status AS ENUM (
    'drafted',
    'approved',
    'sent',
    'executed',
    'partial',
    'failed',
    'completed'
);

CREATE TYPE cash_recon_ledger_status AS ENUM (
    'open',
    'reconciling',
    'completed',
    'completed_with_discrepancies'
);

CREATE TYPE cash_remediation_trigger AS ENUM (
    'leg_failed',
    'inflow_unmatched',
    'discrepancy',
    'webhook_signature_invalid'
);

CREATE TYPE cash_remediation_status AS ENUM (
    'open',
    'in_progress',
    'resolved'
);

-- ---------------------------------------------------------------------------
-- BC5 — Assignment & Signing
-- ---------------------------------------------------------------------------

CREATE TYPE legal_master_agreement_party_type AS ENUM (
    'supplier',
    'investor'
);

CREATE TYPE legal_master_agreement_kind AS ENUM (
    'MAA',
    'MIA'
);

CREATE TYPE legal_master_agreement_status AS ENUM (
    'initiated',
    'signed',
    'stamped',
    'failed'
);

CREATE TYPE legal_assignment_set_status AS ENUM (
    'requested',
    'in_progress',
    'all_signed',
    'incomplete'
);

CREATE TYPE legal_assignment_leg_status AS ENUM (
    'pending',
    'initiated',
    'signed',
    'failed'
);

CREATE TYPE legal_signature_request_status AS ENUM (
    'initiated',
    'completed',
    'failed'
);

CREATE TYPE legal_signer_type AS ENUM (
    'supplier_signatory',
    'investor',
    'admin_proxy_for_supplier_under_agency'
);

-- ---------------------------------------------------------------------------
-- BC6 — Collections & Recovery
-- ---------------------------------------------------------------------------

CREATE TYPE col_delay_status AS ENUM (
    'on_track',
    'mildly_delayed',
    'delayed',
    'seriously_delayed',
    'under_adjudication',
    'outcome'
);

CREATE TYPE col_maturity_outcome AS ENUM (
    'disputed',
    'dilution',
    'fraud',
    'defaulted',
    'recovered'
);

CREATE TYPE col_action_type AS ENUM (
    'phone_followup',
    'email_notice',
    'panel_lawyer_letter',
    'other'
);

CREATE TYPE col_claim_type AS ENUM (
    'dilution',
    'fraud',
    'dispute'
);

CREATE TYPE col_claim_status AS ENUM (
    'raised',
    'under_adjudication',
    'resolved'
);


-- =============================================================================
-- SECTION 2: TABLES
-- =============================================================================

-- =============================================================================
-- BC1 — LISTING & INVOICE
-- =============================================================================

-- ---------------------------------------------------------------------------
-- deal_invoice
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: deal_invoice
--   INV.1  (supplier_id, irn) must be unique when irn IS NOT NULL.
--          (supplier_id, buyer_id, invoice_number, face_value, tenor_days)
--          must be unique when irn IS NULL (manual fallback).
--          Both uniqueness rules are composite partial-unique constraints
--          that span nullable columns; enforce in app layer and via partial
--          unique indexes below.
--   INV.2  status transitions follow: submitted → ops_checks_in_progress →
--          {ops_checks_passed | ops_checks_failed}. ops_checks_passed → listed.
--          No backward moves; no skipping.
--   INV.3  face_value > 0; tenor_days ∈ [1, 180].
--   INV.4  due_date = invoice_date + tenor_days (BusinessDate arithmetic).
--   INV.5  An invoice can only advance to 'listed' once an associated Listing
--          is drafted (BC1 Listing creation sets status = 'listed').
--   INV.6  check_outcomes is populated by the operational-checks subsystem;
--          structure is map<check_name, outcome>. Schema does not enforce
--          individual check semantics.
--   INV.7  Source-of-truth for IRN data comes from BC17 (GST Network API);
--          manually-entered data is flagged with irn IS NULL. (C24)
-- ---------------------------------------------------------------------------

CREATE TABLE deal_invoice (
    invoice_id          UUID            NOT NULL,
    supplier_id         UUID            NOT NULL,
    buyer_id            UUID            NOT NULL,
    irn                 irn_type,
    invoice_number      TEXT            NOT NULL,
    -- face_value stored in paise (BIGINT). Never FLOAT. (DL-016)
    face_value          BIGINT          NOT NULL,
    invoice_date        DATE            NOT NULL,
    tenor_days          SMALLINT        NOT NULL,
    -- due_date = invoice_date + tenor_days (INV.4). App layer enforces on insert.
    due_date            DATE            NOT NULL,
    status              deal_invoice_status NOT NULL DEFAULT 'submitted',
    -- check_outcomes: map<check_name → {outcome, detail, checked_at}> (DL-027, C24)
    check_outcomes      JSONB           NOT NULL DEFAULT '{}',
    aggregate_version   INT             NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT deal_invoice_pk PRIMARY KEY (invoice_id),
    CONSTRAINT deal_invoice_face_value_positive CHECK (face_value > 0),
    CONSTRAINT deal_invoice_tenor_range CHECK (tenor_days BETWEEN 1 AND 180)
);

COMMENT ON TABLE deal_invoice IS
    'BC1 Invoice aggregate. Represents a raw supplier invoice submitted for '
    'funding. Owns operational-check outcomes. Source of truth per DL-016, C24.';

COMMENT ON COLUMN deal_invoice.invoice_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN deal_invoice.irn IS
    'GST Invoice Reference Number (irn_type domain: 64 hex chars). NULL when '
    'invoice is entered manually (manual fallback path per DL-016). Partial '
    'unique index enforces uniqueness per supplier when NOT NULL.';

COMMENT ON COLUMN deal_invoice.invoice_date IS
    'BusinessDate on which the invoice was raised. Stored for due_date derivation '
    'and age validation. App layer enforces due_date = invoice_date + tenor_days (INV.4).';
COMMENT ON COLUMN deal_invoice.face_value IS
    'Invoice face value in paise (smallest INR unit). Never FLOAT.';
COMMENT ON COLUMN deal_invoice.tenor_days IS
    'Financing tenor in calendar days. Must be in [1, 180] per DL-022.';
COMMENT ON COLUMN deal_invoice.due_date IS
    'BusinessDate on which buyer payment is due. '
    'Computed as invoice_date + tenor_days and validated by app layer (INV.4).';
COMMENT ON COLUMN deal_invoice.check_outcomes IS
    'JSONB map of operational check name → {outcome, detail, checked_at}. '
    'Populated by the ops-checks subsystem (DL-027).';
COMMENT ON COLUMN deal_invoice.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write '
    'and rejects if expected version mismatches.';

-- Partial unique index: IRN uniqueness per supplier (INV.1, C24)
CREATE UNIQUE INDEX uidx_deal_invoice_irn_supplier
    ON deal_invoice (supplier_id, irn)
    WHERE irn IS NOT NULL;

-- Partial unique index: manual-entry uniqueness (INV.1 fallback)
CREATE UNIQUE INDEX uidx_deal_invoice_manual
    ON deal_invoice (supplier_id, buyer_id, invoice_number, face_value, tenor_days)
    WHERE irn IS NULL;

CREATE INDEX idx_deal_invoice_supplier ON deal_invoice (supplier_id);
CREATE INDEX idx_deal_invoice_buyer    ON deal_invoice (buyer_id);
CREATE INDEX idx_deal_invoice_status   ON deal_invoice (status);


-- ---------------------------------------------------------------------------
-- deal_listing
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: deal_listing
--   L.1   status transitions follow Spec §6.4 state machine exactly.
--         No skipping; no backward moves. Terminal states accept no further
--         commands except Close.
--   L.2   committed_total ≤ funding_target at all times.
--         New commitments rejected if they would breach (G10). The CHECK
--         constraint enforces the invariant at the DB layer as a last-resort
--         guard; the primary enforcement is in the app command handler.
--   L.3   Once status reaches 'ready_for_review', the four snapshot fields
--         (pricing_snapshot, buyer_limit_headroom_snapshot,
--         supplier_exposure_cap_snapshot, funding_target) are immutable
--         for the life of the listing. (G20)
--         App layer must refuse any update to these columns after that point.
--   L.4   GoneLive requires a maker-checker pair: checker ∈ Treasury &
--         Settlement AND checker_id ≠ maker_id AND checker holds a valid
--         mfa_assertion_id. (C4, C7)
--   L.5   DisbursementGateOpened requires all_signed = TRUE, set by subscriber
--         to BC5 AssignmentSet.AllSigned. (C27, DL-002)
--   L.6   FullyFunded requires committed_total = funding_target (paise
--         equality). (DL-017, C12, G10)
--   L.7   funding_target = face_value − discount − fee, frozen at SnapshotTaken.
--         Discount = face_value × rate_bps / 10000 × tenor_days / 365.
--         Fee = face_value × fee_bps / 10000. (DL-024, G20)
--   L.8   funding_window_close_at = GoneLive timestamp + 5 business days
--         (NOT configurable). Funding shortfall declared by scheduler exactly
--         at that instant. (DL-017, C12)
--   L.9   After funding_window_close_at, Subscription.Commit is rejected
--         regardless of remaining headroom. (C12, DL-017)
--   L.10  pricing_snapshot.rate_bps must be within the BC3 Credit-published
--         band for (buyer_id, tenor_bucket) at snapshot time. (DL-024, G20)
--   L.11  Buyer is 'active' and supplier is 'active' at GoneLive. If either
--         is suspended in-flight, emit Listing.HeldForReview. (DL-014, DL-018)
--   L.12  va_id is set exactly once (at GoneLive-driven BC4 cash_virtual_account
--         creation). Never updated or cleared until terminal Close. (C8, DL-043)
--   L.13  Listing.MaturityShortfall is the only path for inflow < expected.
--         Branch routes to BC6 Collections, not BC4 Distribution. (Spec §4.2)
-- ---------------------------------------------------------------------------

CREATE TABLE deal_listing (
    listing_id                          UUID                    NOT NULL,
    invoice_id                          UUID                    NOT NULL,
    supplier_id                         UUID                    NOT NULL,
    buyer_id                            UUID                    NOT NULL,
    status                              deal_listing_status     NOT NULL DEFAULT 'draft',

    -- Snapshot fields: immutable after status = 'ready_for_review' (L.3, G20)
    -- pricing_snapshot: {pricing_band_id, rate_bps, fee_bps, snapshot_at}
    pricing_snapshot                    JSONB,
    buyer_limit_headroom_snapshot       BIGINT,             -- paise
    supplier_exposure_cap_snapshot      BIGINT,             -- paise
    funding_target                      BIGINT,             -- paise; immutable after ready_for_review

    -- Funding state (DL-017, C12, G10)
    committed_total                     BIGINT              NOT NULL DEFAULT 0,
    funding_window_close_at             TIMESTAMPTZ,

    -- VA reference (set once at GoneLive; FK to cash_virtual_account) (C8, DL-043)
    va_id                               UUID,

    -- Disbursement gate (C27, DL-002)
    all_signed                          BOOLEAN             NOT NULL DEFAULT FALSE,

    -- Terminal metadata
    terminal_outcome                    deal_terminal_outcome,

    -- Maker-checker audit for GoneLive (C4, C7)
    golive_maker_id                     UUID,
    golive_checker_id                   UUID,
    golive_mfa_assertion_id             TEXT,

    aggregate_version                   INT                 NOT NULL DEFAULT 1,
    created_at                          TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at                          TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT deal_listing_pk PRIMARY KEY (listing_id),
    CONSTRAINT deal_listing_invoice_fk
        FOREIGN KEY (invoice_id) REFERENCES deal_invoice (invoice_id),
    CONSTRAINT deal_listing_committed_lte_target
        CHECK (committed_total <= COALESCE(funding_target, committed_total)),
    CONSTRAINT deal_listing_funding_target_positive
        CHECK (funding_target IS NULL OR funding_target > 0),
    CONSTRAINT deal_listing_committed_nonneg
        CHECK (committed_total >= 0),
    CONSTRAINT deal_listing_golive_maker_ne_checker
        CHECK (golive_maker_id IS NULL
               OR golive_checker_id IS NULL
               OR golive_maker_id <> golive_checker_id)
);

COMMENT ON TABLE deal_listing IS
    'BC1 Listing aggregate. Central state machine for the entire invoice '
    'discounting lifecycle from draft → closed. Coordinates BC2 Subscription, '
    'BC4 Settlement, BC5 Assignment, BC6 Collections. (Spec §6.4, DL-017)';

COMMENT ON COLUMN deal_listing.listing_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN deal_listing.pricing_snapshot IS
    'JSONB: {pricing_band_id, rate_bps, fee_bps, snapshot_at}. '
    'Immutable once status reaches ready_for_review (G20, L.3). '
    'App layer must reject any write to this column after that status.';
COMMENT ON COLUMN deal_listing.buyer_limit_headroom_snapshot IS
    'Buyer credit limit headroom in paise at SnapshotTaken. '
    'Immutable after ready_for_review (G20, L.3).';
COMMENT ON COLUMN deal_listing.supplier_exposure_cap_snapshot IS
    'Supplier exposure cap in paise at SnapshotTaken. '
    'Immutable after ready_for_review (G20, L.3).';
COMMENT ON COLUMN deal_listing.funding_target IS
    'Net disbursement target in paise. Calculated at SnapshotTaken and '
    'immutable thereafter (L.3, L.7). '
    'Formula: face_value − discount − fee.';
COMMENT ON COLUMN deal_listing.committed_total IS
    'Running sum of active Subscription amounts in paise. '
    'Must always be ≤ funding_target (L.2, G10, C12). '
    'FullyFunded fires when committed_total = funding_target exactly.';
COMMENT ON COLUMN deal_listing.funding_window_close_at IS
    'Timestamp at which the 5-business-day funding window closes. '
    'Set at GoneLive as GoneLive_at + 5 business days (NOT configurable). '
    '(DL-017, C12, L.8)';
COMMENT ON COLUMN deal_listing.va_id IS
    'Reference to cash_virtual_account. Set exactly once at GoneLive. '
    'Never cleared until terminal Close. (C8, DL-043, L.12)';
COMMENT ON COLUMN deal_listing.all_signed IS
    'Set to TRUE by subscriber to BC5 AssignmentSet.AllSigned. '
    'Gates DisbursementGateOpened (C27, DL-002, L.5).';
COMMENT ON COLUMN deal_listing.terminal_outcome IS
    'Populated only on Close. Identifies terminal path taken.';
COMMENT ON COLUMN deal_listing.golive_maker_id IS
    'Admin user who prepared the GoneLive action (maker in maker-checker). (C4)';
COMMENT ON COLUMN deal_listing.golive_checker_id IS
    'Admin user (Treasury & Settlement role) who approved GoneLive. '
    'Must differ from golive_maker_id. (C4, C7, L.4)';
COMMENT ON COLUMN deal_listing.golive_mfa_assertion_id IS
    'MFA assertion ref for golive_checker_id at approval time. (C7, L.4)';
COMMENT ON COLUMN deal_listing.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_deal_listing_status       ON deal_listing (status);
CREATE INDEX idx_deal_listing_supplier     ON deal_listing (supplier_id);
CREATE INDEX idx_deal_listing_buyer        ON deal_listing (buyer_id);
CREATE INDEX idx_deal_listing_invoice      ON deal_listing (invoice_id);
CREATE INDEX idx_deal_listing_window_close ON deal_listing (funding_window_close_at)
    WHERE status = 'live';


-- =============================================================================
-- BC2 — SUBSCRIPTION
-- =============================================================================

-- ---------------------------------------------------------------------------
-- sub_subscription
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: sub_subscription
--   S.1   amount >= 1_000_000 paise (Rs 10,000 minimum). (DL-007)
--         Enforced as CHECK constraint below.
--   S.2   status transitions follow Spec §6.5.
--         cancelled_by_investor only from {committed, funds_pending}.
--         refunded reachable from {committed, funds_pending, funds_received,
--         confirmed, assignment_executed} per G3 + G13.
--   S.3   Confirmed requires expected_inflow_amount = amount (paise equality)
--         AND actual_inflow_txn_ref IS SET AND reconciled. (G10, C23)
--   S.4   At Committed, investor_id.status = 'active' (verified at BC7 at
--         command-handler time). (DL-005, DL-008)
--   S.5   At Committed, host Listing.status = 'live' AND
--         Listing.committed_total + amount ≤ Listing.funding_target. (C12)
--   S.6   (listing_id, investor_id) must be unique — see UNIQUE constraint.
--   S.7   distribution_outcome: gross − tds − fee = net enforced as CHECK
--         constraint on the JSONB extracted fields below.
--         App layer also validates before writing.
--   S.8   Soft concentration warnings are recorded as JSONB on
--         concentration_warnings_at_commit for audit; NOT enforced as a hard
--         block. (DL-011)
--   S.9   wallet_attribution is a Phase 2 dormant field. App layer ignores it
--         in Phase 1 command handlers. (C25, C26)
-- ---------------------------------------------------------------------------

CREATE TABLE sub_subscription (
    subscription_id                 UUID                        NOT NULL,
    listing_id                      UUID                        NOT NULL,
    investor_id                     UUID                        NOT NULL,
    -- amount in paise; minimum 1,000,000 (Rs 10,000). (DL-007, S.1)
    amount                          BIGINT                      NOT NULL,
    status                          sub_subscription_status     NOT NULL DEFAULT 'committed',

    -- Inflow tracking (G10, C23, S.3)
    expected_inflow_amount          BIGINT                      NOT NULL,
    actual_inflow_txn_ref           TEXT,

    -- Set when BC5 AssignmentSet.AllSigned fires for this investor (C27)
    assignment_doc_hash             TEXT,

    -- Distribution outcome: {gross, tds, fee, net} all in paise. (DL-045, G4)
    -- gross - tds - fee = net enforced as CHECK below.
    distribution_outcome            JSONB,

    -- Soft concentration warnings recorded at commit time for audit. (DL-011, S.8)
    concentration_warnings_at_commit JSONB                      NOT NULL DEFAULT '[]',

    -- Phase 2 dormant field — not used in Phase 1 command handlers. (C25, C26)
    wallet_attribution              TEXT,

    aggregate_version               INT                         NOT NULL DEFAULT 1,
    created_at                      TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT sub_subscription_pk PRIMARY KEY (subscription_id),
    CONSTRAINT sub_subscription_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),

    -- S.1: minimum ticket Rs 10,000 = 1,000,000 paise (DL-007)
    CONSTRAINT sub_subscription_min_amount
        CHECK (amount >= 1000000),
    CONSTRAINT sub_subscription_amount_positive
        CHECK (amount > 0),
    CONSTRAINT sub_subscription_expected_inflow_positive
        CHECK (expected_inflow_amount > 0),

    -- S.6: one subscription per (listing_id, investor_id)
    CONSTRAINT sub_subscription_listing_investor_uq
        UNIQUE (listing_id, investor_id),

    -- S.7: distribution_outcome gross - tds - fee = net (paise equality).
    -- Extracted from JSONB; NULL when distribution_outcome not yet set.
    CONSTRAINT sub_subscription_distribution_net_check
        CHECK (
            distribution_outcome IS NULL
            OR (
                (distribution_outcome->>'gross')::BIGINT
                - (distribution_outcome->>'tds')::BIGINT
                - (distribution_outcome->>'fee')::BIGINT
                = (distribution_outcome->>'net')::BIGINT
            )
        )
);

COMMENT ON TABLE sub_subscription IS
    'BC2 Subscription aggregate. Per-investor commitment to a listing, '
    'tracking the full lifecycle from committed → closed. '
    'Enforces Rs 10,000 minimum ticket (DL-007) and paise-equality '
    'on distribution amounts (DL-045, G4).';

COMMENT ON COLUMN sub_subscription.subscription_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN sub_subscription.amount IS
    'Committed investment amount in paise. Minimum 1,000,000 (Rs 10,000). '
    '(DL-007, S.1)';
COMMENT ON COLUMN sub_subscription.expected_inflow_amount IS
    'Amount expected to arrive in the listing VA. Set equal to amount at '
    'commit time; used for paise-equality check at Confirmed (S.3, G10).';
COMMENT ON COLUMN sub_subscription.actual_inflow_txn_ref IS
    'Reference to the reconciled inflow transaction (from BC4 InflowReconciled). '
    'Must be set before status can advance to confirmed. (C23, G10)';
COMMENT ON COLUMN sub_subscription.assignment_doc_hash IS
    'Hash of the assignment document signed by this investor. '
    'Set by subscriber to BC5 AssignmentSignature.Completed. (C27, DL-002)';
COMMENT ON COLUMN sub_subscription.distribution_outcome IS
    'JSONB: {gross, tds, fee, net} all in paise. '
    'gross - tds - fee = net enforced by CHECK constraint. '
    'Set by BC4 DistributionLeg.Executed subscriber. (DL-045, G4)';
COMMENT ON COLUMN sub_subscription.concentration_warnings_at_commit IS
    'JSONB array of soft concentration warnings recorded at commit time. '
    'Audit record only — not a hard block. (DL-011, S.8)';
COMMENT ON COLUMN sub_subscription.wallet_attribution IS
    'DORMANT in Phase 1. Reserved for Phase 2 wallet/source-of-funds '
    'attribution. App layer must not set this in Phase 1. (C25, C26)';
COMMENT ON COLUMN sub_subscription.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_sub_subscription_listing  ON sub_subscription (listing_id);
CREATE INDEX idx_sub_subscription_investor ON sub_subscription (investor_id);
CREATE INDEX idx_sub_subscription_status   ON sub_subscription (status);


-- =============================================================================
-- BC3 — CREDIT & UNDERWRITING
-- =============================================================================

-- ---------------------------------------------------------------------------
-- risk_buyer_profile
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: risk_buyer_profile
--   BCP.1  credit_limit > 0; tenor_cap_days ∈ [1, 180]. (DL-022)
--   BCP.2  Any SetBuyerCreditLimit with new_limit > 10,000,000,000 paise
--          (Rs 1 Cr) requires second_approver_id ≠ first_approver_id.
--          second_approver_id is the four-eyes approval reference. (C6, DL-023)
--          App layer MUST refuse if second_approver_id IS NULL when
--          credit_limit > 10,000,000,000.
--   BCP.3  Limit reductions emit BuyerLimit.Reduced; in-flight Listings
--          keep their snapshot (G20). App layer subscriber updates this
--          record; Listings are unaffected by design.
--   BCP.4  (Soft) last_review_at should be ≤ 12 months ago for active
--          buyers. Scheduler sends reminder; non-blocking.
-- ---------------------------------------------------------------------------

CREATE TABLE risk_buyer_profile (
    buyer_id                UUID        NOT NULL,
    sector                  TEXT        NOT NULL,
    rating_source           TEXT        NOT NULL,
    rating                  TEXT        NOT NULL,
    -- credit_limit in paise. Must be > 0. (DL-022, BCP.1)
    credit_limit            BIGINT      NOT NULL,
    -- tenor_cap_days ∈ [1, 180]. (DL-022, BCP.1)
    tenor_cap_days          SMALLINT    NOT NULL,
    conditions              JSONB       NOT NULL DEFAULT '[]',
    last_review_at          TIMESTAMPTZ,
    -- four_eyes_approval_ref required when credit_limit > 10,000,000,000 paise.
    -- App layer enforces: if credit_limit > 10_000_000_000 then this must be set.
    -- (C6, DL-023, BCP.2)
    four_eyes_approval_ref  TEXT,
    second_approver_id      UUID,

    aggregate_version       INT         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT risk_buyer_profile_pk PRIMARY KEY (buyer_id),
    CONSTRAINT risk_buyer_profile_credit_limit_positive CHECK (credit_limit > 0),
    CONSTRAINT risk_buyer_profile_tenor_cap_range CHECK (tenor_cap_days BETWEEN 1 AND 180)
);

COMMENT ON TABLE risk_buyer_profile IS
    'BC3 BuyerCreditProfile aggregate. Holds credit policy values for a buyer: '
    'limit, tenor cap, pricing band reference. Published to BC1 at snapshot time. '
    '(DL-022, DL-023, G20)';

COMMENT ON COLUMN risk_buyer_profile.buyer_id IS
    'UUIDv7. Shared identity with BC9 buyer_account. BC3 holds the credit-side '
    'projection only. Cross-context reference by identity.';
COMMENT ON COLUMN risk_buyer_profile.credit_limit IS
    'Buyer aggregate credit limit in paise. Must be > 0. (DL-022)';
COMMENT ON COLUMN risk_buyer_profile.tenor_cap_days IS
    'Maximum allowable tenor in days for this buyer. Range [1, 180]. (DL-022)';
COMMENT ON COLUMN risk_buyer_profile.four_eyes_approval_ref IS
    'Reference to the FourEyesApproval envelope when credit_limit > Rs 1 Cr '
    '(> 10,000,000,000 paise). App layer MUST require this when threshold is '
    'crossed. (C6, DL-023, BCP.2)';
COMMENT ON COLUMN risk_buyer_profile.second_approver_id IS
    'UUID of second approver (Credit Reviewer or Founder/CEO). '
    'Required when credit_limit > 10,000,000,000 paise (Rs 1 Cr). '
    'App layer must enforce second_approver_id ≠ the first approver. (C6, BCP.2)';
COMMENT ON COLUMN risk_buyer_profile.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_risk_buyer_profile_sector ON risk_buyer_profile (sector);


-- ---------------------------------------------------------------------------
-- risk_supplier_profile
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: risk_supplier_profile
--   SCP.1  exposure_cap >= 0. (DL-022)
--   SCP.2  Any ChangeSupplierExposureCap with new_cap > 10,000,000,000 paise
--          (Rs 1 Cr) requires second_approver_id ≠ first_approver_id. (C6, DL-023)
--          App layer MUST refuse if second_approver_id IS NULL when
--          exposure_cap > 10,000,000,000.
--   SCP.3  (Soft) 12-month review cadence. Scheduler reminds; non-blocking.
-- ---------------------------------------------------------------------------

CREATE TABLE risk_supplier_profile (
    supplier_id             UUID        NOT NULL,
    risk_rating             TEXT        NOT NULL,
    -- exposure_cap in paise. >= 0. (DL-022, SCP.1)
    exposure_cap            BIGINT      NOT NULL,
    conditions              JSONB       NOT NULL DEFAULT '[]',
    last_review_at          TIMESTAMPTZ,
    -- Required when exposure_cap > 10,000,000,000 paise. (C6, DL-023, SCP.2)
    four_eyes_approval_ref  TEXT,
    second_approver_id      UUID,

    aggregate_version       INT         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT risk_supplier_profile_pk PRIMARY KEY (supplier_id),
    CONSTRAINT risk_supplier_profile_exposure_cap_nonneg CHECK (exposure_cap >= 0)
);

COMMENT ON TABLE risk_supplier_profile IS
    'BC3 SupplierCreditProfile aggregate. Risk rating and exposure cap for a '
    'supplier. Four-eyes required when cap > Rs 1 Cr. (DL-022, DL-023, C6)';

COMMENT ON COLUMN risk_supplier_profile.supplier_id IS
    'UUIDv7. Shared identity with BC8 sup_account. Cross-context by identity.';
COMMENT ON COLUMN risk_supplier_profile.exposure_cap IS
    'Aggregate supplier exposure cap in paise. Zero means no new listings '
    'permitted. (DL-022, SCP.1)';
COMMENT ON COLUMN risk_supplier_profile.second_approver_id IS
    'Required when exposure_cap > 10,000,000,000 paise (Rs 1 Cr). '
    'App layer must enforce second_approver_id ≠ first approver. (C6, SCP.2)';
COMMENT ON COLUMN risk_supplier_profile.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';


-- ---------------------------------------------------------------------------
-- risk_pricing_policy
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: risk_pricing_policy
--   PB.1  rate_range_min_bps > 0; rate_range_min_bps ≤ rate_range_max_bps;
--         fee_bps >= 0. (DL-024)
--   PB.2  For each (buyer_id, tenor_bucket), exactly one band is current
--         (superseded_by IS NULL) at any point in time.
--         Enforced by partial unique index below.
--   PB.3  Past risk_pricing_policy records are immutable. A re-pricing creates a
--         new row and sets the prior's superseded_by. In-flight Listings
--         keep their snapshot (G20). App layer must not update rate/fee
--         fields on an existing row; only superseded_by may be set.
--   PB.4  (Soft) When a new band is published, BC1 snapshots taken before the
--         new band's effective_from are unaffected by design (G20).
-- ---------------------------------------------------------------------------

CREATE TABLE risk_pricing_policy (
    pricing_band_id         UUID                NOT NULL,
    buyer_id                UUID                NOT NULL,
    tenor_bucket            risk_tenor_bucket   NOT NULL,
    -- Rate range in basis points (bps). min > 0; min ≤ max. (DL-024, PB.1)
    rate_range_min_bps      INT                 NOT NULL,
    rate_range_max_bps      INT                 NOT NULL,
    -- Platform fee in basis points. >= 0. (DL-024, PB.1)
    fee_bps                 INT                 NOT NULL,
    effective_from          DATE                NOT NULL,
    -- Self-reference to next band; NULL means this band is currently active.
    -- Partial unique index on (buyer_id, tenor_bucket) WHERE superseded_by IS NULL
    -- enforces at most one active band per (buyer_id, tenor_bucket).
    superseded_by           UUID,

    aggregate_version       INT                 NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT risk_pricing_policy_pk PRIMARY KEY (pricing_band_id),
    CONSTRAINT risk_pricing_policy_superseded_fk
        FOREIGN KEY (superseded_by) REFERENCES risk_pricing_policy (pricing_band_id),
    CONSTRAINT risk_pricing_policy_rate_min_positive CHECK (rate_range_min_bps > 0),
    CONSTRAINT risk_pricing_policy_rate_range_valid
        CHECK (rate_range_min_bps <= rate_range_max_bps),
    CONSTRAINT risk_pricing_policy_fee_nonneg CHECK (fee_bps >= 0)
);

COMMENT ON TABLE risk_pricing_policy IS
    'BC3 PricingPolicy (PricingBand) aggregate. One active band per '
    '(buyer_id, tenor_bucket) enforced via partial unique index on '
    'superseded_by IS NULL. Past bands are immutable; re-pricing creates '
    'a new row. (DL-024, G20)';

COMMENT ON COLUMN risk_pricing_policy.pricing_band_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN risk_pricing_policy.rate_range_min_bps IS
    'Minimum discount rate in basis points (bps). Must be > 0. (DL-024)';
COMMENT ON COLUMN risk_pricing_policy.rate_range_max_bps IS
    'Maximum discount rate in basis points. Must be ≥ rate_range_min_bps. (DL-024)';
COMMENT ON COLUMN risk_pricing_policy.fee_bps IS
    'Platform fee in basis points applied flat on face value. Must be ≥ 0. (DL-024)';
COMMENT ON COLUMN risk_pricing_policy.effective_from IS
    'BusinessDate from which this band is effective.';
COMMENT ON COLUMN risk_pricing_policy.superseded_by IS
    'Self-reference to the pricing_band_id that replaced this band. '
    'NULL means this is the currently active band for (buyer_id, tenor_bucket). '
    'Once set, this record is immutable. (DL-024, G20, PB.3)';
COMMENT ON COLUMN risk_pricing_policy.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

-- PB.2: one active band per (buyer_id, tenor_bucket). (DL-024)
CREATE UNIQUE INDEX uidx_risk_pricing_policy_active
    ON risk_pricing_policy (buyer_id, tenor_bucket)
    WHERE superseded_by IS NULL;

CREATE INDEX idx_risk_pricing_policy_buyer ON risk_pricing_policy (buyer_id);


-- ---------------------------------------------------------------------------
-- risk_default_case
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: risk_default_case
--   DC.1  Opening a DefaultCase requires a prior Classification.Requested
--         from BC6 (causation chain). App layer must verify this causal link
--         before creating the row. (DL-029)
--   DC.2  If the listing's outstanding exposure > 10,000,000,000 paise (Rs 1 Cr),
--         four_eyes_approval_ref MUST be present and second_approver_id must
--         differ from first_approver_id. (C6, DL-023)
--   DC.3  outcome is set exactly once. Re-classification creates a new row
--         with corrects_case_id pointing at the prior. (DL-029, G23)
--   DC.4  No automatic time-based default declaration. Case advances only
--         on explicit Classify command. (DL-029)
-- ---------------------------------------------------------------------------

CREATE TABLE risk_default_case (
    case_id                 UUID                        NOT NULL,
    listing_id              UUID                        NOT NULL,
    status                  risk_default_case_status    NOT NULL DEFAULT 'requested',
    outcome                 risk_default_outcome,
    -- Immutable document hash for the rationale document. (C1)
    rationale_doc_hash      TEXT,
    classified_at           TIMESTAMPTZ,
    -- Four-eyes approval reference when exposure > Rs 1 Cr. (C6, DL-023, DC.2)
    four_eyes_approval_ref  TEXT,
    second_approver_id      UUID,
    -- For re-classification: points to the prior case_id. (DC.3, G23)
    corrects_case_id        UUID,
    -- Maker-checker for classification (C4)
    maker_id                UUID,
    checker_id              UUID,

    aggregate_version       INT                         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT risk_default_case_pk PRIMARY KEY (case_id),
    CONSTRAINT risk_default_case_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    CONSTRAINT risk_default_case_corrects_fk
        FOREIGN KEY (corrects_case_id) REFERENCES risk_default_case (case_id),
    CONSTRAINT risk_default_case_maker_ne_checker
        CHECK (maker_id IS NULL OR checker_id IS NULL OR maker_id <> checker_id)
);

COMMENT ON TABLE risk_default_case IS
    'BC3 DefaultCase aggregate. Created by subscriber to BC6 Classification.Requested. '
    'outcome set only by explicit Classify command — never automatic. '
    'Re-classification creates a new row (corrects_case_id chain). (DL-029, DC.1–DC.4)';

COMMENT ON COLUMN risk_default_case.case_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN risk_default_case.outcome IS
    'Set exactly once on Classify command. NULL until classified. '
    'Re-classification requires a new row (DC.3, G23).';
COMMENT ON COLUMN risk_default_case.four_eyes_approval_ref IS
    'Required when listing exposure > 10,000,000,000 paise (Rs 1 Cr). '
    'References a FourEyesApproval envelope. (C6, DL-023, DC.2)';
COMMENT ON COLUMN risk_default_case.corrects_case_id IS
    'Points to prior case_id when this row is a re-classification. '
    'NULL on initial case. (DC.3, G23)';
COMMENT ON COLUMN risk_default_case.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_risk_default_case_listing ON risk_default_case (listing_id);
CREATE INDEX idx_risk_default_case_status  ON risk_default_case (status);


-- =============================================================================
-- BC4 — SETTLEMENT
-- =============================================================================

-- ---------------------------------------------------------------------------
-- cash_virtual_account
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: cash_virtual_account
--   V.1  One VirtualAccount per listing_id. Enforced by UNIQUE constraint.
--        (C8, DL-043)
--   V.2  observed_inflow_total = Σ(reconciled inflows on this VA).
--        expected_inflow_total is set at creation and updated as subscriptions
--        are confirmed. Discrepancy at EoD triggers cash_remediation_case. (C23, G6)
--   V.3  Transition to 'closed' only after listing is terminal AND all
--        instructed payouts are executed or refunded. (C8, DL-043)
--   V.4  InflowObserved is provisional; only InflowReconciled advances
--        downstream aggregate state. (C23, G6, G23)
--   V.5  (Soft) No commingling — one VA serves exactly one listing. (C8)
-- ---------------------------------------------------------------------------

CREATE TABLE cash_virtual_account (
    va_id                       UUID                        NOT NULL,
    listing_id                  UUID                        NOT NULL,
    status                      cash_virtual_account_status NOT NULL DEFAULT 'requested',
    -- Bank-side coordinates (observed from BC18 webhook)
    ifsc                        TEXT,
    account_no                  TEXT,
    created_at_bank             TIMESTAMPTZ,
    closed_at_bank              TIMESTAMPTZ,
    -- Running inflow ledger in paise. (V.2, C23, G6)
    expected_inflow_total       BIGINT                      NOT NULL DEFAULT 0,
    observed_inflow_total       BIGINT                      NOT NULL DEFAULT 0,

    aggregate_version           INT                         NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT cash_virtual_account_pk PRIMARY KEY (va_id),
    CONSTRAINT cash_virtual_account_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    -- V.1: one VA per listing (C8, DL-043)
    CONSTRAINT cash_virtual_account_listing_uq UNIQUE (listing_id),
    CONSTRAINT cash_virtual_account_expected_inflow_nonneg CHECK (expected_inflow_total >= 0),
    CONSTRAINT cash_virtual_account_observed_inflow_nonneg CHECK (observed_inflow_total >= 0)
);

COMMENT ON TABLE cash_virtual_account IS
    'BC4 VirtualAccount aggregate. Platform-minted escrow virtual account, '
    'one per listing. Tracks expected vs observed inflows for reconciliation. '
    '(C8, DL-043, G6, C23)';

COMMENT ON COLUMN cash_virtual_account.va_id IS
    'UUIDv7 primary key (platform-minted). Bank-side IFSC/account_no are '
    'observed values set on webhook receipt from BC18.';
COMMENT ON COLUMN cash_virtual_account.ifsc IS
    'Bank IFSC code of the virtual account. Set on BC18 Va.LifecycleObserved.';
COMMENT ON COLUMN cash_virtual_account.account_no IS
    'Virtual account number. Set on BC18 Va.LifecycleObserved.';
COMMENT ON COLUMN cash_virtual_account.expected_inflow_total IS
    'Sum of active subscription amounts + buyer maturity expected, in paise. '
    'Updated as subscriptions are confirmed. (V.2, C23)';
COMMENT ON COLUMN cash_virtual_account.observed_inflow_total IS
    'Sum of reconciled inflows on this VA, in paise. '
    'Updated by EoD reconciler. Discrepancy vs expected triggers cash_remediation_case. '
    '(V.2, C23, G6)';
COMMENT ON COLUMN cash_virtual_account.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_cash_virtual_account_status ON cash_virtual_account (status);


-- ---------------------------------------------------------------------------
-- cash_payout_instruction
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: cash_payout_instruction
--   PI.1  payout_instruction_id IS ALSO the idempotency key (client_instruction_id)
--         sent to the banking vendor (BC18). Same UUID reused. (C9, G18, A2 §2.3)
--   PI.2  kind='disbursement' allowed only when Listing.status='fully_funded'
--         AND Listing.all_signed=TRUE AND Listing.DisbursementGateOpened has
--         fired. (C27, L.5)
--   PI.3  kind='distribution' payload must carry TDS snapshot from BC12.
--         Per-leg: gross − tds − fee = net.
--         Sum-of-legs: net + total_tds + total_fee = listing.matured_amount
--         (paise equality). (DL-045, G4, PI.3)
--   PI.4  kind='refund' allowed only when Subscription.RefundEligible has
--         fired (BC2). (G3, DL-017)
--   PI.5  status transitions: drafted → approved → sent → (executed | partial |
--         failed) → completed. Approved step requires checker_id ∈ Treasury &
--         Settlement AND checker_id ≠ maker_id AND checker holds valid MFA
--         assertion. (C4, C7, DL-030)
--         maker_id ≠ checker_id enforced as CHECK constraint.
--   PI.6  'partial' cannot → 'completed' until every failed leg has a linked
--         cash_remediation_case that is resolved. (G11)
--   PI.7  Webhook-driven status changes are provisional until EoD
--         reconciliation overlay confirms. (C23, G6)
--   PI.8  T+1 timing: 'disbursement' instructed within T+1 business days of
--         Listing.FullyFunded; 'distribution' within T+1 of Listing.Matured.
--         Enforced at command-handler level using BusinessDate calculator. (C11)
--   PI.tds_immutable: tds_snapshot inside payload is immutable after
--         checker_id is set. App layer must refuse any modification to
--         tds_snapshot once the row has checker_id IS NOT NULL.
-- ---------------------------------------------------------------------------

CREATE TABLE cash_payout_instruction (
    payout_instruction_id       UUID                    NOT NULL,
    kind                        cash_payout_kind        NOT NULL,
    -- listing_id for disbursement/distribution; subscription_id for refund.
    listing_id                  UUID,
    subscription_id             UUID,
    status                      cash_payout_status      NOT NULL DEFAULT 'drafted',
    -- payload: {legs?, tds_snapshot?, total_tds?, gross, net, fee} all paise.
    -- tds_snapshot is IMMUTABLE after checker_id is set (PI.tds_immutable).
    payload                     JSONB                   NOT NULL DEFAULT '{}',
    gross_amount                BIGINT                  NOT NULL,
    net_amount                  BIGINT                  NOT NULL,
    fee_amount                  BIGINT                  NOT NULL,
    total_tds_amount            BIGINT,
    -- Maker-checker (C4, PI.5). maker_id ≠ checker_id enforced as CHECK.
    maker_id                    UUID                    NOT NULL,
    checker_id                  UUID,
    checker_mfa_assertion_id    TEXT,
    four_eyes_approval_ref      TEXT,
    -- T+1 SLA reference date. (C11, PI.8)
    instruction_sla_date        DATE,

    aggregate_version           INT                     NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ             NOT NULL DEFAULT now(),

    CONSTRAINT cash_payout_instruction_pk PRIMARY KEY (payout_instruction_id),
    CONSTRAINT cash_payout_instruction_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    CONSTRAINT cash_payout_instruction_subscription_fk
        FOREIGN KEY (subscription_id) REFERENCES sub_subscription (subscription_id),
    -- PI.5: maker ≠ checker (C4)
    CONSTRAINT cash_payout_instruction_maker_ne_checker
        CHECK (checker_id IS NULL OR maker_id <> checker_id),
    CONSTRAINT cash_payout_instruction_gross_positive CHECK (gross_amount > 0),
    CONSTRAINT cash_payout_instruction_net_positive   CHECK (net_amount > 0),
    CONSTRAINT cash_payout_instruction_fee_nonneg     CHECK (fee_amount >= 0),
    CONSTRAINT cash_payout_instruction_tds_nonneg     CHECK (total_tds_amount IS NULL OR total_tds_amount >= 0)
);

COMMENT ON TABLE cash_payout_instruction IS
    'BC4 PayoutInstruction aggregate. Platform-side record of every fund '
    'movement. payout_instruction_id doubles as the idempotency key '
    '(client_instruction_id) sent to the banking vendor (C9, PI.1). '
    'Maker-checker enforced with maker_id ≠ checker_id CHECK. '
    'tds_snapshot inside payload is IMMUTABLE after checker_id is set. (DL-045, G4)';

COMMENT ON COLUMN cash_payout_instruction.payout_instruction_id IS
    'UUIDv7 primary key. Also used as client_instruction_id for BC18 — '
    'same UUID value. (C9, G18, A2 §2.3, PI.1)';
COMMENT ON COLUMN cash_payout_instruction.kind IS
    'Type of fund movement: disbursement (to supplier), distribution '
    '(to investors), or refund. (PI.2, PI.3, PI.4)';
COMMENT ON COLUMN cash_payout_instruction.listing_id IS
    'Set for kind ∈ {disbursement, distribution}. NULL for refund kind.';
COMMENT ON COLUMN cash_payout_instruction.subscription_id IS
    'Set for kind = refund. NULL for disbursement / distribution.';
COMMENT ON COLUMN cash_payout_instruction.payload IS
    'JSONB containing legs (for distribution), tds_snapshot, and other '
    'instruction details. tds_snapshot is IMMUTABLE after checker_id is set — '
    'app layer must refuse any update to this field once checker_id IS NOT NULL. '
    '(DL-045, G4, PI.tds_immutable)';
COMMENT ON COLUMN cash_payout_instruction.gross_amount IS
    'Total gross amount in paise before TDS and fee deductions.';
COMMENT ON COLUMN cash_payout_instruction.net_amount IS
    'Net amount in paise after TDS and fee (for distribution = Σ leg net).';
COMMENT ON COLUMN cash_payout_instruction.fee_amount IS
    'Platform fee in paise deducted from gross.';
COMMENT ON COLUMN cash_payout_instruction.total_tds_amount IS
    'Total TDS in paise (distribution kind only). (DL-045, G4)';
COMMENT ON COLUMN cash_payout_instruction.maker_id IS
    'Admin user (Treasury & Settlement) who drafted the instruction. (C4)';
COMMENT ON COLUMN cash_payout_instruction.checker_id IS
    'Admin user (Treasury & Settlement) who approved the instruction. '
    'Must differ from maker_id (CHECK constraint). (C4, C7, PI.5)';
COMMENT ON COLUMN cash_payout_instruction.checker_mfa_assertion_id IS
    'MFA assertion reference for checker at approval time. (C7, PI.5)';
COMMENT ON COLUMN cash_payout_instruction.instruction_sla_date IS
    'BusinessDate by which instruction must be sent (T+1 SLA). (C11, PI.8)';
COMMENT ON COLUMN cash_payout_instruction.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_cash_payout_instruction_listing      ON cash_payout_instruction (listing_id);
CREATE INDEX idx_cash_payout_instruction_subscription ON cash_payout_instruction (subscription_id);
CREATE INDEX idx_cash_payout_instruction_status       ON cash_payout_instruction (status);
CREATE INDEX idx_cash_payout_instruction_kind         ON cash_payout_instruction (kind);


-- ---------------------------------------------------------------------------
-- cash_recon_ledger
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: cash_recon_ledger
--   RL.1  One ledger per business_date. Re-runs update the existing row.
--         No parallel ledgers per day. (C23)
--   RL.2  Cannot transition to 'completed' while any discrepancy lacks a
--         linked cash_remediation_case. (G6, G11)
--   RL.3  EoD master-statement overlay is authoritative over webhook state.
--         Any divergence recorded in discrepancies JSONB; corrective envelope
--         emitted. (C23, G23)
-- ---------------------------------------------------------------------------

CREATE TABLE cash_recon_ledger (
    -- Natural identity: one ledger per business date. (RL.1, C23)
    business_date           DATE                        NOT NULL,
    status                  cash_recon_ledger_status    NOT NULL DEFAULT 'open',
    -- Hash of the master bank statement fetched at EoD. (C23, G6, RL.3)
    master_statement_hash   TEXT,
    inflows_matched         INT                         NOT NULL DEFAULT 0,
    inflows_unmatched       INT                         NOT NULL DEFAULT 0,
    -- discrepancies: [{va_id, expected:BIGINT, observed:BIGINT}]
    discrepancies           JSONB                       NOT NULL DEFAULT '[]',
    summary                 JSONB,

    aggregate_version       INT                         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT cash_recon_ledger_pk PRIMARY KEY (business_date),
    CONSTRAINT cash_recon_ledger_inflows_matched_nonneg   CHECK (inflows_matched >= 0),
    CONSTRAINT cash_recon_ledger_inflows_unmatched_nonneg CHECK (inflows_unmatched >= 0)
);

COMMENT ON TABLE cash_recon_ledger IS
    'BC4 ReconciliationLedger aggregate. One row per business date. '
    'EoD master-statement overlay is authoritative over webhooks (C23, G6). '
    'Cannot complete while discrepancies lack cash_remediation_case rows. (G6, G11)';

COMMENT ON COLUMN cash_recon_ledger.business_date IS
    'Natural primary key. One ledger per business day (RL.1, C23).';
COMMENT ON COLUMN cash_recon_ledger.master_statement_hash IS
    'SHA-256 (or equivalent) hash of the master bank statement fetched at EoD. '
    'Authoritative per C23, G6. Set on receipt of BC18 MasterStatement.Fetched.';
COMMENT ON COLUMN cash_recon_ledger.discrepancies IS
    'JSONB array of {va_id, expected_paise, observed_paise} discrepancy records. '
    'Each entry must have a linked cash_remediation_case before ledger can complete. (G11)';
COMMENT ON COLUMN cash_recon_ledger.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_cash_recon_ledger_status ON cash_recon_ledger (status);


-- ---------------------------------------------------------------------------
-- cash_remediation_case
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: cash_remediation_case
--   RC.1  Cannot be marked 'resolved' without linked_corrective_event_id
--         pointing at the envelope that fixed the underlying state. (G11, G23)
--   RC.2  Closure requires admin_user with Treasury & Settlement role. (G11)
--   RC.3  Every DistributionLeg.Failed envelope must produce exactly one
--         cash_remediation_case — failed legs cannot be silently dropped. (G11)
-- ---------------------------------------------------------------------------

CREATE TABLE cash_remediation_case (
    case_id                     UUID                        NOT NULL,
    trigger                     cash_remediation_trigger    NOT NULL,
    -- Freeform reference to the triggering aggregate (e.g., payout_instruction_id
    -- or va_id). Stored as TEXT to allow cross-aggregate references.
    linked_aggregate_ref        TEXT                        NOT NULL,
    status                      cash_remediation_status     NOT NULL DEFAULT 'open',
    assignee_id                 UUID,
    -- Set on resolution; hash of the resolution document. (RC.1, G23)
    resolution_doc_hash         TEXT,
    -- ID of the corrective event envelope that fixed the underlying state. (RC.1)
    linked_corrective_event_id  TEXT,

    aggregate_version           INT                         NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT cash_remediation_case_pk PRIMARY KEY (case_id)
);

COMMENT ON TABLE cash_remediation_case IS
    'BC4 RemediationCase aggregate. Created for every failed payout leg, '
    'unmatched inflow, reconciliation discrepancy, or invalid webhook. '
    'Cannot be resolved without a linked corrective event. (G11, G23)';

COMMENT ON COLUMN cash_remediation_case.case_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN cash_remediation_case.trigger IS
    'What caused this case to be opened. (G11, RC.3)';
COMMENT ON COLUMN cash_remediation_case.linked_aggregate_ref IS
    'Reference to the aggregate that triggered this case '
    '(e.g., payout_instruction_id, va_id). Free-form TEXT for cross-aggregate '
    'references; app layer resolves the type from trigger field.';
COMMENT ON COLUMN cash_remediation_case.linked_corrective_event_id IS
    'ID of the corrective event envelope that resolved the underlying issue. '
    'MUST be set before status can transition to resolved. (RC.1, G11, G23)';
COMMENT ON COLUMN cash_remediation_case.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_cash_remediation_case_status  ON cash_remediation_case (status);
CREATE INDEX idx_cash_remediation_case_trigger ON cash_remediation_case (trigger);


-- =============================================================================
-- BC5 — ASSIGNMENT & SIGNING
-- =============================================================================

-- ---------------------------------------------------------------------------
-- legal_master_agreement
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: legal_master_agreement
--   MA.1  One MasterAgreement per (party_id, kind) in {signed, stamped} status
--         at any time. A failed signing creates a new row; the failed one is
--         retained for audit. App layer enforces this before Initiate. (DL-048, C1)
--   MA.2  Status transitions: initiated → {signed | failed}; signed → stamped.
--         'failed' is terminal. (DL-048, A2 §3.6)
--   MA.3  signature_cert_serial set only on receipt of BC19 SignatureCompleted.
--         stamp_cert_id set only on BC19 StampIssued. (DL-048)
--   MA.4  (Soft) Stamping completes within vendor SLA post-signing. Failure
--         escalates to admin alert but does not block onboarding. (DL-048, G2)
-- ---------------------------------------------------------------------------

CREATE TABLE legal_master_agreement (
    agreement_id            UUID                                    NOT NULL,
    party_id                UUID                                    NOT NULL,
    party_type              legal_master_agreement_party_type       NOT NULL,
    kind                    legal_master_agreement_kind             NOT NULL,
    doc_hash                TEXT                                    NOT NULL,
    status                  legal_master_agreement_status           NOT NULL DEFAULT 'initiated',
    -- Set only on BC19 SignatureCompleted. (MA.3, DL-048)
    signature_cert_serial   TEXT,
    -- Set only on BC19 StampIssued. (MA.3, DL-048)
    stamp_cert_id           TEXT,
    failed_reason           TEXT,

    aggregate_version       INT                                     NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ                             NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                             NOT NULL DEFAULT now(),

    CONSTRAINT legal_master_agreement_pk PRIMARY KEY (agreement_id)
);

COMMENT ON TABLE legal_master_agreement IS
    'BC5 MasterAgreement aggregate. MAA for suppliers, MIA for investors. '
    'One active (signed/stamped) agreement per (party_id, kind) at any time; '
    'app layer enforces before Initiate. Failed rows retained for audit. '
    '(DL-048, C1, C2)';

COMMENT ON COLUMN legal_master_agreement.agreement_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN legal_master_agreement.party_id IS
    'UUID of the signing party (supplier_id or investor_id depending on kind).';
COMMENT ON COLUMN legal_master_agreement.doc_hash IS
    'Hash of the document sent to the signing vendor. Immutable once set. (C1)';
COMMENT ON COLUMN legal_master_agreement.signature_cert_serial IS
    'Certificate serial number from e-sign vendor. Set exactly once on '
    'BC19 SignatureCompleted. (MA.3, DL-048, C2)';
COMMENT ON COLUMN legal_master_agreement.stamp_cert_id IS
    'Stamping certificate ID from e-stamp vendor. Set exactly once on '
    'BC19 StampIssued. (MA.3, DL-048)';
COMMENT ON COLUMN legal_master_agreement.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_legal_master_agreement_party  ON legal_master_agreement (party_id, kind);
CREATE INDEX idx_legal_master_agreement_status ON legal_master_agreement (status);


-- ---------------------------------------------------------------------------
-- legal_assignment_set
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: legal_assignment_set
--   AS.1  One AssignmentSet per listing_id. Enforced by UNIQUE constraint.
--         total_count = number of confirmed Subscriptions at FullyFunded time.
--         (C27, DL-002)
--   AS.2  sign_deadline = created_at + INTERVAL '24 hours'. (G13)
--         Stored as computed value; app layer sets it on creation.
--   AS.3  AllSigned fires exactly when signed_count = total_count AND
--         sign_deadline has not yet passed. (C27, G13)
--   AS.4  AssignmentSet.Incomplete fires exactly at sign_deadline if
--         signed_count < total_count. Further signing attempts rejected. (G13)
--   AS.5  signed_count + unsigned_count = total_count at all times.
--         Enforced as CHECK constraint.
--   AS.6  Per-leg doc_hash set at AssignmentSignature.Initiated and
--         unchanged thereafter. App layer must refuse updates. (C1, C2)
--   AS.7  (Soft) PerInvoiceStamp.Pending emitted at AllSigned. Stamping
--         strategy parked for legal resolution. (G2, DL-048)
--
-- legs column stores the per-investor assignment legs as JSONB array:
-- [{investor_id, subscription_id, allocation_paise, signature_request_id?,
--   doc_hash?, status, signature_cert_serial?}]
-- ---------------------------------------------------------------------------

CREATE TABLE legal_assignment_set (
    assignment_set_id       UUID                        NOT NULL,
    listing_id              UUID                        NOT NULL,
    -- sign_deadline = created_at + INTERVAL '24 hours'. (G13, AS.2)
    sign_deadline           TIMESTAMPTZ                 NOT NULL,
    status                  legal_assignment_set_status NOT NULL DEFAULT 'requested',
    signed_count            INT                         NOT NULL DEFAULT 0,
    unsigned_count          INT                         NOT NULL DEFAULT 0,
    total_count             INT                         NOT NULL DEFAULT 0,
    -- legs: JSONB array of AssignmentLeg value objects. (AS.6)
    legs                    JSONB                       NOT NULL DEFAULT '[]',

    aggregate_version       INT                         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                 NOT NULL DEFAULT now(),

    CONSTRAINT legal_assignment_set_pk PRIMARY KEY (assignment_set_id),
    CONSTRAINT legal_assignment_set_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    -- AS.1: one AssignmentSet per listing. (C27, DL-002)
    CONSTRAINT legal_assignment_set_listing_uq UNIQUE (listing_id),
    -- AS.5: signed_count + unsigned_count = total_count
    CONSTRAINT legal_assignment_set_counts_balance
        CHECK (signed_count + unsigned_count = total_count),
    CONSTRAINT legal_assignment_set_counts_nonneg
        CHECK (signed_count >= 0 AND unsigned_count >= 0 AND total_count >= 0)
);

COMMENT ON TABLE legal_assignment_set IS
    'BC5 AssignmentSet aggregate. One per listing. Orchestrates per-investor '
    'assignment document signing. all_signed gate for disbursement (C27, DL-002). '
    'sign_deadline = created_at + 24h (G13, AS.2). '
    'signed_count + unsigned_count = total_count enforced by CHECK.';

COMMENT ON COLUMN legal_assignment_set.assignment_set_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN legal_assignment_set.sign_deadline IS
    'Timestamp after which signing attempts are rejected. '
    'Set by app layer as created_at + INTERVAL 24 hours. (G13, AS.2)';
COMMENT ON COLUMN legal_assignment_set.legs IS
    'JSONB array of per-investor assignment legs. Each element: '
    '{investor_id, subscription_id, allocation_paise, signature_request_id?, '
    'doc_hash?, status: legal_assignment_leg_status, signature_cert_serial?}. '
    'doc_hash immutable once set at AssignmentSignature.Initiated. (C1, C2, AS.6)';
COMMENT ON COLUMN legal_assignment_set.signed_count IS
    'Count of legs with status = signed. Incremented on each RecordLegSigned.';
COMMENT ON COLUMN legal_assignment_set.unsigned_count IS
    'Count of legs with status ∈ {pending, initiated, failed}. '
    'signed_count + unsigned_count = total_count at all times. (AS.5)';
COMMENT ON COLUMN legal_assignment_set.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_legal_assignment_set_status ON legal_assignment_set (status);


-- ---------------------------------------------------------------------------
-- legal_signature_request
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: legal_signature_request
--   SR.1  (signer_id, doc_hash, parent_aggregate_ref) is unique — replays
--         produce the same signature_request_id. (G18, A2 §3.3)
--   SR.2  retry_count ≤ 3. After exhaustion, status = 'failed'. (A2 §3.6)
--         Enforced as CHECK constraint.
--   SR.3  cert_serial set exactly once on terminal 'completed'. (C2)
-- ---------------------------------------------------------------------------

CREATE TABLE legal_signature_request (
    signature_request_id    UUID                                NOT NULL,
    signer_id               UUID                                NOT NULL,
    signer_type             legal_signer_type                   NOT NULL,
    doc_hash                TEXT                                NOT NULL,
    -- Freeform reference to owning aggregate (agreement_id or
    -- assignment_set_id:investor_id composite). App layer encodes convention.
    parent_aggregate_ref    TEXT                                NOT NULL,
    status                  legal_signature_request_status      NOT NULL DEFAULT 'initiated',
    vendor_session_url      TEXT,
    -- retry_count ≤ 3. (SR.2, A2 §3.6)
    retry_count             SMALLINT                            NOT NULL DEFAULT 0,
    -- Set exactly once on completed. (SR.3, C2)
    cert_serial             TEXT,

    aggregate_version       INT                                 NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ                         NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ                         NOT NULL DEFAULT now(),

    CONSTRAINT legal_signature_request_pk PRIMARY KEY (signature_request_id),
    -- SR.2: retry_count ≤ 3 (A2 §3.6)
    CONSTRAINT legal_signature_request_retry_max
        CHECK (retry_count <= 3),
    CONSTRAINT legal_signature_request_retry_nonneg
        CHECK (retry_count >= 0)
);

COMMENT ON TABLE legal_signature_request IS
    'BC5 SignatureRequest aggregate. Platform-side bookkeeping for each '
    'e-sign vendor session. signature_request_id = client_request_id to BC19. '
    'retry_count ≤ 3 enforced by CHECK (SR.2, A2 §3.6). '
    'cert_serial set exactly once on completion. (SR.3, C2)';

COMMENT ON COLUMN legal_signature_request.signature_request_id IS
    'UUIDv7 primary key. Also used as client_request_id to BC19 signing vendor. '
    'Replays for the same (signer_id, doc_hash, parent_aggregate_ref) must '
    'produce the same UUID (SR.1, G18, A2 §3.3).';
COMMENT ON COLUMN legal_signature_request.parent_aggregate_ref IS
    'Free-form reference to owning aggregate. Convention (app-layer): '
    'for MAA/MIA use agreement_id; for AssignmentSet use '
    'assignment_set_id::investor_id.';
COMMENT ON COLUMN legal_signature_request.retry_count IS
    'Number of retry attempts made. Must not exceed 3. (SR.2, A2 §3.6) '
    'When retry_count reaches 3 and status is still not completed, '
    'app layer must set status = failed and bubble up to parent.';
COMMENT ON COLUMN legal_signature_request.cert_serial IS
    'Certificate serial from signing vendor. Set exactly once on terminal '
    'completed status. (SR.3, C2)';
COMMENT ON COLUMN legal_signature_request.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

-- SR.1: uniqueness for idempotent replay (G18, A2 §3.3)
CREATE UNIQUE INDEX uidx_legal_signature_request_idempotency
    ON legal_signature_request (signer_id, doc_hash, parent_aggregate_ref);

CREATE INDEX idx_legal_signature_request_status ON legal_signature_request (status);


-- =============================================================================
-- BC6 — COLLECTIONS & RECOVERY
-- =============================================================================

-- ---------------------------------------------------------------------------
-- col_maturity_case
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: col_maturity_case
--   MC.1  One MaturityCase per listing_id. Created exactly on
--         BC1 Listing.Disbursed event. Enforced by UNIQUE constraint.
--         (DL-028, DL-029)
--   MC.2  delay_status transitions follow bucketed thresholds:
--         on_track → mildly_delayed (DPD 1–7) → delayed (DPD 8–15)
--         → seriously_delayed (DPD 16–30) → under_adjudication (DPD 31+).
--         Scheduler advances on day boundaries. (DL-029)
--   MC.3  Classification.Requested fires exactly when delay_status reaches
--         under_adjudication. No earlier; no automatic default. (DL-029)
--   MC.4  outcome is set ONLY by subscribing to BC3 DefaultCase.Classified.
--         BC6 NEVER sets outcome on its own. There is NO DB DEFAULT for outcome.
--         App layer must refuse any direct outcome write; it must arrive via
--         the BC3 subscriber. (DL-029)
--   MC.5  recovery_total = Σ(Recovery.Achieved amounts). Each Recovery.Achieved
--         event increments recovery_total and triggers a partial-distribution
--         chain via BC4. (DL-029, Spec §4.2)
--   MC.6  (Soft) MaturityReminder.Sent schedule: T-3, T-1, T (BusinessDate).
--         Non-blocking dispatch via BC15. (Spec §4.1)
-- ---------------------------------------------------------------------------

CREATE TABLE col_maturity_case (
    maturity_case_id            UUID                NOT NULL,
    listing_id                  UUID                NOT NULL,
    maturity_date               DATE                NOT NULL,
    delay_status                col_delay_status    NOT NULL DEFAULT 'on_track',
    days_past_due               INT                 NOT NULL DEFAULT 0,
    -- outcome set ONLY via BC3 DefaultCase.Classified subscriber.
    -- NO DB DEFAULT. App layer must refuse direct writes. (MC.4, DL-029)
    outcome                     col_maturity_outcome,
    -- Set when delay_status reaches 'seriously_delayed'. (DL-028)
    panel_lawyer_assignment_id  UUID,
    -- Running recovery total in paise. (MC.5, DL-029)
    recovery_total              BIGINT              NOT NULL DEFAULT 0,

    aggregate_version           INT                 NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT col_maturity_case_pk PRIMARY KEY (maturity_case_id),
    CONSTRAINT col_maturity_case_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    -- MC.1: one MaturityCase per listing. (DL-028, DL-029)
    CONSTRAINT col_maturity_case_listing_uq UNIQUE (listing_id),
    CONSTRAINT col_maturity_case_dpd_nonneg CHECK (days_past_due >= 0),
    CONSTRAINT col_maturity_case_recovery_nonneg CHECK (recovery_total >= 0)
);

COMMENT ON TABLE col_maturity_case IS
    'BC6 MaturityCase aggregate. One per listing; created on Listing.Disbursed. '
    'Tracks delay status from on_track through to outcome states. '
    'outcome column MUST be set only by subscriber to BC3 DefaultCase.Classified '
    '— never set directly by BC6 or with a DB DEFAULT (MC.4, DL-029). '
    'recovery_total accumulates from BC4 inflow recovery events. (MC.5)';

COMMENT ON COLUMN col_maturity_case.maturity_case_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN col_maturity_case.maturity_date IS
    'BusinessDate on which buyer payment is due per the original invoice.';
COMMENT ON COLUMN col_maturity_case.delay_status IS
    'Current delay classification. Advances via scheduler on BusinessDate '
    'boundaries per DPD thresholds. (DL-029, MC.2)';
COMMENT ON COLUMN col_maturity_case.days_past_due IS
    'Calendar days past maturity_date with no full payment. '
    'Set by scheduler on each day-boundary tick. (DL-029)';
COMMENT ON COLUMN col_maturity_case.outcome IS
    'Terminal outcome. NO DB DEFAULT. Set EXCLUSIVELY by subscriber to '
    'BC3 DefaultCase.Classified. App layer must reject any other write path. '
    '(MC.4, DL-029)';
COMMENT ON COLUMN col_maturity_case.panel_lawyer_assignment_id IS
    'Reference to assigned panel lawyer. Set when delay_status transitions '
    'to seriously_delayed (DPD 16+). (DL-028)';
COMMENT ON COLUMN col_maturity_case.recovery_total IS
    'Cumulative amount recovered in paise. Incremented on each Recovery.Achieved '
    'event from BC4 inflow matching. Triggers partial-distribution chain. (MC.5)';
COMMENT ON COLUMN col_maturity_case.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_col_maturity_case_delay_status ON col_maturity_case (delay_status);
CREATE INDEX idx_col_maturity_case_maturity_date ON col_maturity_case (maturity_date);


-- ---------------------------------------------------------------------------
-- col_action_log
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: col_action_log
--   CA.1  Linked MaturityCase must exist and not be in a terminal-outcome
--         state. App layer checks before Record command. (DL-028)
--   CA.2  action_type = 'panel_lawyer_letter' only when linked case has
--         delay_status ∈ {seriously_delayed, under_adjudication}. (DL-028)
-- ---------------------------------------------------------------------------

CREATE TABLE col_action_log (
    action_id               UUID                NOT NULL,
    maturity_case_id        UUID                NOT NULL,
    action_type             col_action_type     NOT NULL,
    -- Immutable hash of the notes/evidence document. (C1, C2)
    notes_doc_hash          TEXT,
    recorded_at             TIMESTAMPTZ         NOT NULL DEFAULT now(),
    -- Admin user who recorded the action. (DL-028)
    actor_id                UUID                NOT NULL,

    CONSTRAINT col_action_log_pk PRIMARY KEY (action_id),
    CONSTRAINT col_action_log_case_fk
        FOREIGN KEY (maturity_case_id) REFERENCES col_maturity_case (maturity_case_id)
);

COMMENT ON TABLE col_action_log IS
    'BC6 CollectionsAction. Immutable record of each manual collections activity '
    'against a col_maturity_case. panel_lawyer_letter only when case is in '
    'seriously_delayed or under_adjudication. (DL-028, CA.1, CA.2)';

COMMENT ON COLUMN col_action_log.action_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN col_action_log.notes_doc_hash IS
    'Hash of the notes or evidence document attached to this action. '
    'Immutable once written. (C1, C2)';
COMMENT ON COLUMN col_action_log.actor_id IS
    'Admin user (Ops Executive or Credit Reviewer) who recorded the action.';

CREATE INDEX idx_col_action_log_case ON col_action_log (maturity_case_id);
CREATE INDEX idx_col_action_log_type ON col_action_log (action_type);


-- ---------------------------------------------------------------------------
-- col_claim_case
-- ---------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS: col_claim_case
--   CCL.1  Created only when a dilution or fraud signal is detected against
--          an active MaturityCase. App layer verifies causal link. (DL-015)
--   CCL.2  outcome set by BC3 DefaultCase.Classified subscriber (routed via
--          BC6 → BC3 Classification.Requested chain). No DB DEFAULT. (DL-029)
--   CCL.3  supplier_indemnity_amount applicable only for dilution/fraud claims.
--          App layer enforces. (Spec §2.2 — limited recourse)
-- ---------------------------------------------------------------------------

CREATE TABLE col_claim_case (
    claim_id                    UUID                NOT NULL,
    listing_id                  UUID                NOT NULL,
    maturity_case_id            UUID                NOT NULL,
    claim_type                  col_claim_type      NOT NULL,
    -- Immutable evidence document hash. (C1)
    evidence_doc_hash           TEXT                NOT NULL,
    status                      col_claim_status    NOT NULL DEFAULT 'raised',
    -- outcome set only via BC3 classification chain. NO DB DEFAULT. (CCL.2)
    outcome                     col_maturity_outcome,
    -- Supplier indemnity amount in paise (dilution/fraud only). (CCL.3)
    supplier_indemnity_amount   BIGINT,

    aggregate_version           INT                 NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ         NOT NULL DEFAULT now(),

    CONSTRAINT col_claim_case_pk PRIMARY KEY (claim_id),
    CONSTRAINT col_claim_case_listing_fk
        FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id),
    CONSTRAINT col_claim_case_maturity_fk
        FOREIGN KEY (maturity_case_id) REFERENCES col_maturity_case (maturity_case_id),
    CONSTRAINT col_claim_case_indemnity_nonneg
        CHECK (supplier_indemnity_amount IS NULL OR supplier_indemnity_amount >= 0)
);

COMMENT ON TABLE col_claim_case IS
    'BC6 ClaimCase aggregate. Raised on dilution, fraud, or dispute signals. '
    'Triggers BC3 classification request. outcome set ONLY via BC3 '
    'DefaultCase.Classified subscriber — no DB DEFAULT. (DL-015, DL-029, CCL.1–CCL.3)';

COMMENT ON COLUMN col_claim_case.claim_id IS
    'UUIDv7 primary key. Generated in app layer.';
COMMENT ON COLUMN col_claim_case.evidence_doc_hash IS
    'Hash of the evidence document submitted with the claim. '
    'Immutable once written. (C1)';
COMMENT ON COLUMN col_claim_case.outcome IS
    'Terminal outcome (col_maturity_outcome enum). NO DB DEFAULT. Set exclusively '
    'via BC3 DefaultCase.Classified subscriber. App layer must reject direct writes. '
    '(CCL.2, DL-029)';
COMMENT ON COLUMN col_claim_case.supplier_indemnity_amount IS
    'Supplier indemnity amount in paise for dilution or fraud claims. '
    'NULL for pure dispute claims. (CCL.3, Spec §2.2)';
COMMENT ON COLUMN col_claim_case.aggregate_version IS
    'Optimistic concurrency version. App layer increments on every write.';

CREATE INDEX idx_col_claim_case_listing      ON col_claim_case (listing_id);
CREATE INDEX idx_col_claim_case_maturity     ON col_claim_case (maturity_case_id);
CREATE INDEX idx_col_claim_case_status       ON col_claim_case (status);
CREATE INDEX idx_col_claim_case_claim_type   ON col_claim_case (claim_type);


-- =============================================================================
-- END OF FILE: 01_core.sql
-- =============================================================================
