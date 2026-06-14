-- =============================================================================
-- 02_counterparty_platform.sql
-- Fintech Invoice Discounting Platform — Phase 1 MVP
-- Covers: BC7 Investor Onboarding, BC8 Supplier Onboarding,
--         BC9 Buyer Management, BC10 Admin IAM, BC11 Compliance,
--         BC12 Tax & Reporting, BC13 Auditor Access
--
-- Design invariants (locked — do not re-discuss):
--   • All PKs are UUIDv7 stored as UUID.
--   • Money amounts use money_paise (>= 0) or positive_money_paise (> 0) domains.
--     Currency is always INR. Raw BIGINT must not be used for monetary columns.
--   • All timestamps stored as TIMESTAMPTZ.
--   • Every aggregate table carries aggregate_version INT NOT NULL DEFAULT 1.
--   • Assumes auth_identity(identity_id UUID PK, email TEXT UNIQUE, …) and
--     auth_session(session_id UUID PK, identity_id UUID, mfa_assertion_id UUID, …)
--     exist in a previously-run shared-kernel migration.
-- =============================================================================
-- FIX (schema-fix session): auth_mfa_factor (admin_user_id-keyed) dropped from
--   BC10. Admin MFA is owned by auth.auth_mfa_factor (identity_id-keyed, auth.sql).
--   mfa_factor_type ENUM removed; auth.sql uses mfa_factor_kind_enum.
--   auth.sql ALTER TABLE admin_user must only ADD the FK constraint;
--   identity_id column + admin_user_identity_uq UNIQUE already defined here.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- ENUM TYPES
-- ---------------------------------------------------------------------------

-- BC7 -----------------------------------------------------------------------

CREATE TYPE inv_sub_type AS ENUM (
    'resident_individual',  -- Active Phase 1.
    'huf',                  -- Active Phase 1.
    'nri',                  -- Phase 2: application blocks this value in Phase 1.
    'institutional'         -- Phase 2: application blocks this value in Phase 1.
);

CREATE TYPE inv_account_status AS ENUM (
    'signed_up',
    'identity_verified',
    'kyc_submitted',
    'suitability_assessed',
    'financial_profile_completed',
    'kyc_approved',
    'mia_signed',
    'active',
    'suspended',
    'exited'
);

CREATE TYPE inv_fatca_status AS ENUM (
    'us_person',
    'non_us_person',
    'pending'
);

-- BC8 -----------------------------------------------------------------------

CREATE TYPE sup_constitution_type AS ENUM (
    'private_limited',
    'public_limited',
    'llp',
    'partnership',
    'proprietorship',
    'trust',
    'society'
);

CREATE TYPE sup_account_status AS ENUM (
    'created',
    'identity_verified',
    'kyc_submitted',
    'kyc_approved',
    'credit_reviewed',
    'maa_signed',
    'active',
    'suspended',
    'blacklisted',
    'voluntarily_exited'
);

-- BC9 -----------------------------------------------------------------------

CREATE TYPE buyer_relationship_tier AS ENUM (
    'acknowledged_buyer', -- Active Phase 1 only.
    'anchor',             -- Phase 2.
    'unacknowledged_buyer'-- Phase 2.
);

CREATE TYPE buyer_ack_mode AS ENUM (
    'per_invoice', -- Active Phase 1 only.
    'blanket'      -- Phase 2.
);

CREATE TYPE buyer_account_status AS ENUM (
    'nominated',
    'identity_verified',
    'credit_assessed',
    'engagement_started',
    'active',
    'suspended'
);

-- BC10 ----------------------------------------------------------------------

CREATE TYPE admin_role AS ENUM (
    'ops_executive',
    'credit_reviewer',
    'compliance_reviewer',
    'treasury_and_settlement',
    'super_admin'
);

CREATE TYPE admin_user_status AS ENUM (
    'invited',
    'active',
    'disabled'
);

-- mfa_factor_type ENUM removed — auth layer uses mfa_factor_kind_enum
-- ('totp' | 'sms_otp') in auth.sql for the same purpose.

CREATE TYPE admin_role_assignment_status AS ENUM (
    'active',
    'revoked'
);

CREATE TYPE admin_sod_review_status AS ENUM (
    'pending',
    'reviewed'
);

CREATE TYPE admin_sod_enforcement_tier AS ENUM (
    'strict_block',        -- System-blocks the role combination outright.
    'soft_warn_with_log'   -- System warns; override is logged in deviation register.
);

-- BC11 ----------------------------------------------------------------------

CREATE TYPE comp_aml_subject_type AS ENUM (
    'investor',
    'supplier',
    'signatory',
    'ubo'
);

CREATE TYPE comp_aml_screening_status AS ENUM (
    'initiated',
    'completed',
    'adjudicated'
);

CREATE TYPE comp_sar_status AS ENUM (
    'internal'
    -- 'escalated_to_fiu_ind' is a Phase 2 value; application blocks it in Phase 1.
);

CREATE TYPE comp_kyc_file_status AS ENUM (
    'in_review',
    'approved',
    'rejected'
);

CREATE TYPE comp_kyc_subject_type AS ENUM (
    'investor',
    'supplier'
);

CREATE TYPE comp_kyc_refresh_status AS ENUM (
    'scheduled',
    'due',
    'completed',
    'missed'
);

-- BC12 ----------------------------------------------------------------------

CREATE TYPE tax_investor_statement_kind AS ENUM (
    'monthly_portfolio',
    'form_16a'
);

-- BC13 ----------------------------------------------------------------------

CREATE TYPE audit_account_status AS ENUM (
    'proposed',
    'approved',
    'activated',
    'auto_disabled'
);

CREATE TYPE audit_sensitivity_level AS ENUM (
    'standard',
    'sensitive',
    'restricted'
);

-- =============================================================================
-- BC7 — INVESTOR ONBOARDING
-- =============================================================================

-- ---------------------------------------------------------------------------
-- inv_invite
-- Represents a single-use invite code issued exclusively by the Compliance
-- Reviewer role (DL-036, C20).
-- ---------------------------------------------------------------------------
CREATE TABLE inv_invite (
    invite_id           UUID            NOT NULL,
    -- SHA-256 hash of the invitee e-mail. BYTEA, not plain text (privacy).
    email_hash          BYTEA           NOT NULL,
    -- SHA-256 hash of the invitee phone. BYTEA, not plain text (privacy).
    phone_hash          BYTEA           NOT NULL,
    issued_by           UUID            NOT NULL,   -- FK → admin_user; must hold compliance_reviewer role.
    issued_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    -- Invite expires 14 days after issuance. Stored as a computed value set by
    -- the application at insert time as: issued_at + INTERVAL '14 days'.
    -- Not a SQL GENERATED column because TIMESTAMPTZ generation requires an
    -- immutable function; instead the application layer is responsible for
    -- computing and inserting this value correctly.
    expiry_at           TIMESTAMPTZ     NOT NULL,
    CONSTRAINT inv_invite_expiry_coherence_chk
        CHECK (expiry_at > issued_at),
    status              TEXT            NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'consumed', 'expired')),
    consumed_at         TIMESTAMPTZ,
    -- identity_id of the investor who consumed this invite (set on consumption).
    consumed_by_identity_id UUID,
    aggregate_version   INT             NOT NULL DEFAULT 1,

    CONSTRAINT inv_invite_pk           PRIMARY KEY (invite_id),
    CONSTRAINT inv_invite_consumed_at_chk
        CHECK (
            (status = 'consumed' AND consumed_at IS NOT NULL AND consumed_by_identity_id IS NOT NULL)
            OR (status <> 'consumed' AND consumed_at IS NULL AND consumed_by_identity_id IS NULL)
        )
);

COMMENT ON TABLE inv_invite IS
    'BC7. Single-use investor invite issued only by a Compliance Reviewer admin '
    'user. Once status=consumed the record is terminal and cannot be reused. '
    'Refs: DL-036, C20, DL-008.';

COMMENT ON COLUMN inv_invite.email_hash IS
    'SHA-256 of the invitee email address stored as BYTEA (privacy). '
    'Consumption validates against auth_identity.email (not oauth_email) — '
    'application-layer invariant.';
COMMENT ON COLUMN inv_invite.phone_hash IS
    'SHA-256 of the invitee phone number stored as BYTEA (privacy).';
COMMENT ON COLUMN inv_invite.expiry_at IS
    'Computed: issued_at + 14 days. Application must reject consumption after this timestamp.';
COMMENT ON COLUMN inv_invite.status IS
    'Terminal states: consumed, expired. Once consumed no further transitions allowed.';

-- APPLICATION-LAYER INVARIANTS: inv_invite
-- I.1  Only an admin_user whose current admin_role_assignment for compliance_reviewer
--      is status=active may insert a row into this table.
-- I.2  On consumption, the application MUST validate the consuming identity
--      against auth_identity.email (the verified canonical email), NOT against
--      any oauth_email field. Mismatch → reject.
-- I.3  Consumption is rejected if now() > expiry_at or status ≠ 'pending'.
-- I.4  Status transition pending→consumed is terminal; no further update
--      permitted.

CREATE INDEX idx_inv_invite_issued_by ON inv_invite (issued_by);
CREATE INDEX idx_inv_invite_status ON inv_invite (status) WHERE status = 'pending';

-- ---------------------------------------------------------------------------
-- inv_account
-- Aggregate root for an investor in the platform. Carries identity_id FK to
-- the shared-kernel auth_identity. One investor per identity.
-- ---------------------------------------------------------------------------
CREATE TABLE inv_account (
    investor_id             UUID                    NOT NULL,
    -- FK to shared-kernel auth_identity. One-to-one (UNIQUE).
    identity_id             UUID                    NOT NULL,
    invite_id               UUID                    NOT NULL,  -- FK → inv_invite
    sub_type                inv_sub_type            NOT NULL,
    status                  inv_account_status      NOT NULL DEFAULT 'signed_up',
    -- PAN: set exactly once at identity-verification time.
    pan                     pan_type,
    -- Only last 4 Aadhaar digits stored (UIDAI norms). Set exactly once.
    aadhaar_last4           aadhaar_last4_type,
    -- Last 4 of bank account number for penny-drop verification. Set exactly once.
    bank_account_last4      CHAR(4),
    fatca_status            inv_fatca_status,
    -- nominee_doc_hash: SHA-256 reference to sys_document_object (nominee form).
    nominee_doc_hash        BYTEA,
    -- kyc_approved_by: FK → admin_user; must hold compliance_reviewer role.
    kyc_approved_by         UUID,
    kyc_approved_at         TIMESTAMPTZ,
    -- mia_agreement_id: FK → BC5 legal_master_agreement (cross-context by identity).
    mia_agreement_id        UUID,
    mia_signed_at           TIMESTAMPTZ,
    activated_at            TIMESTAMPTZ,
    -- kyc_refresh_due_at: activated_at + 12 months (C17). Stored computed value.
    kyc_refresh_due_at      TIMESTAMPTZ,
    suspended_at            TIMESTAMPTZ,
    suspension_reason       TEXT,
    exited_at               TIMESTAMPTZ,
    aggregate_version       INT                     NOT NULL DEFAULT 1,

    CONSTRAINT inv_account_pk            PRIMARY KEY (investor_id),
    CONSTRAINT inv_account_identity_uq   UNIQUE (identity_id),
    CONSTRAINT inv_account_invite_uq     UNIQUE (invite_id),
    CONSTRAINT inv_account_invite_fk
        FOREIGN KEY (invite_id) REFERENCES inv_invite (invite_id),
    CONSTRAINT inv_account_bank_len_chk  CHECK (bank_account_last4 IS NULL OR char_length(bank_account_last4) = 4),
    CONSTRAINT inv_account_activated_refresh_chk
        CHECK (
            activated_at IS NULL
            OR (kyc_refresh_due_at IS NOT NULL
                AND kyc_refresh_due_at = activated_at + INTERVAL '12 months')
        ),
    CONSTRAINT inv_account_sub_type_phase1_chk
        -- NRI and institutional are enum values but blocked in Phase 1 (Phase 2 hook).
        CHECK (sub_type IN ('resident_individual', 'huf'))
);

COMMENT ON TABLE inv_account IS
    'BC7. Aggregate root for an investor. Carries identity_id FK (one-to-one). '
    'Status progresses from signed_up to active through the onboarding pipeline. '
    'Refs: DL-005, DL-006, DL-050, C17, C20, C21.';

COMMENT ON COLUMN inv_account.sub_type IS
    'Phase 1 active values: resident_individual, huf. '
    'nri and institutional exist as enum values but the application blocks them '
    'in Phase 1 (Phase 2 hook per DL-005).';
COMMENT ON COLUMN inv_account.pan IS
    'PAN (10-char alphanumeric). Set exactly once at identity verification — '
    'application invariant. Subsequent changes require explicit re-verification.';
COMMENT ON COLUMN inv_account.aadhaar_last4 IS
    'Last 4 digits of Aadhaar only. Full Aadhaar number is never stored '
    '(UIDAI norms, C15). Set exactly once.';
COMMENT ON COLUMN inv_account.bank_account_last4 IS
    'Last 4 digits of the verified bank account (penny-drop). Set exactly once.';
COMMENT ON COLUMN inv_account.kyc_refresh_due_at IS
    'Stored computed value: activated_at + 12 months (C17, DL-037). '
    'Scheduler fires KycRefresh.Due at this timestamp.';

-- APPLICATION-LAYER INVARIANTS: inv_account
-- IA.1  pan, aadhaar_last4, bank_account_last4 are each set exactly once
--       at the event that verifies them.  No UPDATE on those columns permitted
--       after initial set; re-verification is a new command with a new event.
-- IA.2  sub_type nri and institutional must be rejected by the application
--       command handler with a clear "Phase 2 only" error until unlocked.
-- IA.3  status=active requires: identity verified, KYC approved by Compliance
--       Reviewer, MIA signed, and a completed inv_suitability where either
--       mismatch=false OR override_text_hash IS NOT NULL.
-- IA.4  If inv_suitability.mismatch=true, override_text_hash must be set
--       before the investor may be activated (C21, G26).
-- IA.5  Annual KYC refresh (C17): scheduler checks kyc_refresh_due_at and emits
--       KycRefresh.Due; missed refresh does not auto-suspend in Phase 1 (DL-037).
-- IA.6  status=exited only when investor has zero subscriptions in non-terminal
--       status (BC2 read-side check before issuing Exit command).

CREATE INDEX idx_inv_account_identity ON inv_account (identity_id);
CREATE INDEX idx_inv_account_status   ON inv_account (status);
CREATE INDEX idx_inv_account_kyc_refresh_due
    ON inv_account (kyc_refresh_due_at)
    WHERE status = 'active';

-- ---------------------------------------------------------------------------
-- inv_suitability
-- Immutable after creation. If mismatch=true, override_text_hash must be set
-- before inv_account can be activated (C21, G26).
-- ---------------------------------------------------------------------------
CREATE TABLE inv_suitability (
    assessment_id       UUID        NOT NULL,
    investor_id         UUID        NOT NULL,  -- FK → inv_account
    -- questionnaire_doc_hash: SHA-256 reference into sys_document_object for raw answers.
    questionnaire_doc_hash BYTEA    NOT NULL,
    mismatch            BOOLEAN     NOT NULL,
    -- override_text_hash: required when mismatch=true before activation.
    -- SHA-256 of the investor's acknowledgment text stored in sys_document_object.
    override_text_hash  BYTEA,
    assessed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT inv_suitability_pk  PRIMARY KEY (assessment_id),
    CONSTRAINT inv_suitability_investor_fk
        FOREIGN KEY (investor_id) REFERENCES inv_account (investor_id),
    -- Override text is only meaningful when there is a mismatch.
    -- The cross-row constraint (activation blocked until override set when
    -- mismatch=true) is enforced at application layer — see APPLICATION-LAYER
    -- INVARIANTS SA.2 below.
    CONSTRAINT inv_suitability_override_only_on_mismatch_chk
        CHECK (mismatch = true OR override_text_hash IS NULL)
);

COMMENT ON TABLE inv_suitability IS
    'BC7. Suitability assessment questionnaire result for an investor. '
    'Immutable after creation. Refs: C21, G26, DL-050.';

COMMENT ON COLUMN inv_suitability.mismatch IS
    'True when investor answers indicate a product mismatch against their '
    'declared risk profile.';
COMMENT ON COLUMN inv_suitability.override_text_hash IS
    'SHA-256 of investor-acknowledged override text (stored in sys_document_object). '
    'Must be set before inv_account can be activated when mismatch=true. '
    'Application-layer invariant — not expressible as a single-row CHECK.';

-- APPLICATION-LAYER INVARIANTS: inv_suitability
-- SA.1  Row is immutable after insert. No UPDATEs permitted.
-- SA.2  When mismatch=true and override_text_hash IS NULL at activation time,
--       the Activate command MUST be rejected by the application handler.
--       override_text_hash may only be populated by the investor via
--       AcknowledgeSuitabilityOverride command, which sets it in the same
--       assessment row before activation.

CREATE INDEX idx_inv_suitability_investor ON inv_suitability (investor_id);


-- =============================================================================
-- BC8 — SUPPLIER ONBOARDING
-- =============================================================================

-- ---------------------------------------------------------------------------
-- sup_account
-- Aggregate root for a supplier. NO identity_id FK — suppliers have no login
-- in Phase 1. Admin acts on behalf under sup_agency_consent (DL-012, DL-013).
-- ---------------------------------------------------------------------------
CREATE TABLE sup_account (
    supplier_id             UUID                        NOT NULL,
    legal_name              TEXT                        NOT NULL,
    constitution_type       sup_constitution_type       NOT NULL,
    pan                     pan_type                    NOT NULL,
    gstin                   gstin_type,
    cin                     CHAR(21),   -- Corporate Identity Number (MCA).
    -- udyam: Udyam Registration Number. Nullable; Phase 2 hook (DL-001).
    udyam                   VARCHAR(19),
    status                  sup_account_status          NOT NULL DEFAULT 'created',
    -- kyc_approved_by: FK → admin_user; must hold compliance_reviewer role.
    kyc_approved_by         UUID,
    kyc_approved_at         TIMESTAMPTZ,
    -- credit_review_outcome: denormalised snapshot for quick operational reads.
    credit_exposure_cap_paise money_paise,
    credit_risk_rating      TEXT,
    -- maa_agreement_id: FK → BC5 legal_master_agreement (cross-context by identity).
    maa_agreement_id        UUID,
    maa_signed_at           TIMESTAMPTZ,
    activated_at            TIMESTAMPTZ,
    suspended_at            TIMESTAMPTZ,
    suspension_reason       TEXT,
    blacklisted_at          TIMESTAMPTZ,
    blacklist_reason        TEXT,
    voluntarily_exited_at   TIMESTAMPTZ,
    aggregate_version       INT                         NOT NULL DEFAULT 1,

    CONSTRAINT sup_account_pk       PRIMARY KEY (supplier_id)
);

COMMENT ON TABLE sup_account IS
    'BC8. Aggregate root for a supplier. Suppliers have NO login in Phase 1; '
    'all admin actions are performed under sup_agency_consent (DL-012, DL-013). '
    'identity_id FK is intentionally absent. Refs: DL-012, DL-013, DL-014.';

COMMENT ON COLUMN sup_account.udyam IS
    'Udyam Registration Number. Present in schema as a Phase 2 hook (DL-001). '
    'Nullable and unused in Phase 1.';
COMMENT ON COLUMN sup_account.credit_exposure_cap_paise IS
    'Denormalised snapshot from BC3 risk_supplier_profile. money_paise domain (>= 0). Null until credit review completed.';

-- APPLICATION-LAYER INVARIANTS: sup_account
-- SA8.1  Every admin action on a supplier record must reference an active
--        sup_agency_consent row for that supplier (AC.1).  The application handler
--        checks this before any state-changing command executes.
-- SA8.2  Legal-signature commands (e-sign on MAA) are never delegable under
--        agency; the supplier's authorised signatory must sign personally (AC.2).
-- SA8.3  voluntary_exit (status=voluntarily_exited) is only permitted when
--        the supplier has zero live invoices in non-terminal status across BC1.
--        Application performs a read-side BC1 check before issuing Exit command.
-- SA8.4  AgencyAction.Recorded envelope must be emitted for every admin command
--        executed under agency, with the consent_id referenced (G5, DL-013).
-- SA8.5  Annual KYC refresh cadence tracked via comp_refresh_schedule (C17).

CREATE INDEX idx_sup_account_status ON sup_account (status);
CREATE INDEX idx_sup_account_pan    ON sup_account (pan);

-- ---------------------------------------------------------------------------
-- sup_agency_consent
-- Every admin agency action on a supplier must reference an active consent.
-- scope TEXT[] lists the permitted action categories.
-- ---------------------------------------------------------------------------
CREATE TABLE sup_agency_consent (
    consent_id          UUID        NOT NULL,
    supplier_id         UUID        NOT NULL,  -- FK → sup_account
    -- scope: array of permitted action categories granted by the supplier.
    scope               TEXT[]      NOT NULL,
    -- consent_doc_hash: SHA-256 reference into sys_document_object (signed consent document).
    consent_doc_hash    BYTEA       NOT NULL,
    granted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ,
    revoked_by          UUID,       -- FK → admin_user or NULL (supplier self-revoked).
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT sup_agency_consent_pk      PRIMARY KEY (consent_id),
    CONSTRAINT sup_agency_consent_supplier_fk
        FOREIGN KEY (supplier_id) REFERENCES sup_account (supplier_id),
    CONSTRAINT sup_agency_consent_revoke_chk
        CHECK (
            (is_active = TRUE AND revoked_at IS NULL)
            OR (is_active = FALSE AND revoked_at IS NOT NULL)
        )
);

COMMENT ON TABLE sup_agency_consent IS
    'BC8. Records the supplier''s explicit consent for the platform to act on '
    'their behalf (G5). Every admin agency action must reference an active '
    'consent row for the relevant supplier_id. Refs: DL-013, G5.';

COMMENT ON COLUMN sup_agency_consent.scope IS
    'Array of permitted action categories (e.g. kyc_submission, financial_profile, '
    'invoice_submission). Application validates each command against this list.';

-- APPLICATION-LAYER INVARIANTS: sup_agency_consent
-- AC.1  Before any agency command executes, handler queries for an active consent
--       (is_active=TRUE) for the supplier_id and validates that the action falls
--       within scope[].  Missing or inactive consent → command rejected.
-- AC.2  e-sign commands are never covered by agency consent; the supplier's
--       authorised signatory must authenticate directly via BC19.
-- AC.3  AgencyAction.Recorded envelope emitted for every successful agency command.

CREATE INDEX idx_sup_agency_consent_supplier_active
    ON sup_agency_consent (supplier_id) WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- sup_financial_profile
-- One per supplier (UNIQUE constraint on supplier_id).
-- ---------------------------------------------------------------------------
CREATE TABLE sup_financial_profile (
    financial_profile_id        UUID        NOT NULL,
    supplier_id                 UUID        NOT NULL,  -- FK → sup_account
    -- submitted_doc_hashes: array of SHA-256 refs into sys_document_object.
    submitted_doc_hashes        BYTEA[]     NOT NULL DEFAULT '{}',
    -- TTL timestamps set per A2 §1.4 (GST 90d, AA bank statement 90d).
    gst_returns_ttl_until       TIMESTAMPTZ,
    aa_bank_statement_ttl_until TIMESTAMPTZ,
    -- top_buyers: JSONB array of {buyer_name, gstin, annual_turnover_paise}.
    top_buyers                  JSONB       NOT NULL DEFAULT '[]',
    status                      TEXT        NOT NULL DEFAULT 'submitted'
        CHECK (status IN ('submitted', 'reviewed')),
    submitted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_version           INT         NOT NULL DEFAULT 1,

    CONSTRAINT sup_financial_profile_pk          PRIMARY KEY (financial_profile_id),
    CONSTRAINT sup_financial_profile_supplier_uq UNIQUE (supplier_id),
    CONSTRAINT sup_financial_profile_supplier_fk
        FOREIGN KEY (supplier_id) REFERENCES sup_account (supplier_id)
);

COMMENT ON TABLE sup_financial_profile IS
    'BC8. Financial profile for a supplier. Exactly one per supplier (UNIQUE). '
    'GST returns and AA bank statement have TTLs per A2 §1.4. '
    'Refs: DL-014, A2 §1.4, C24.';

COMMENT ON COLUMN sup_financial_profile.gst_returns_ttl_until IS
    'TTL for GST returns data: 90 days from fetch (A2 §1.4). '
    'Re-pull required on expiry before credit review proceeds.';
COMMENT ON COLUMN sup_financial_profile.aa_bank_statement_ttl_until IS
    'TTL for AA bank statement: 90 days from delivery (A2 §1.4).';

-- APPLICATION-LAYER INVARIANTS: sup_financial_profile
-- FP.1  All financial data must be verified via BC17 (Aggregator ACL), not
--       self-attested.  The handler must check BC17 verification status (C24).
-- FP.2  On TTL expiry of gst_returns or aa_bank_statement, BC17 re-pull is
--       required before the credit review command is accepted.

CREATE INDEX idx_sup_financial_profile_supplier ON sup_financial_profile (supplier_id);


-- =============================================================================
-- BC9 — BUYER MANAGEMENT
-- =============================================================================

-- ---------------------------------------------------------------------------
-- buyer_account
-- Aggregate root for a buyer on the platform.
-- ---------------------------------------------------------------------------
CREATE TABLE buyer_account (
    buyer_id                UUID                        NOT NULL,
    legal_name              TEXT                        NOT NULL,
    mca_cin                 CHAR(21),
    gstin                   gstin_type,
    sector                  TEXT,
    -- relationship_tier: Phase 1 locks to acknowledged_buyer only.
    -- anchor and unacknowledged_buyer are Phase 2 values (C25).
    relationship_tier       buyer_relationship_tier     NOT NULL DEFAULT 'acknowledged_buyer'
        CONSTRAINT buyer_account_tier_phase1_chk
            CHECK (relationship_tier = 'acknowledged_buyer'),
    -- acknowledgment_mode: Phase 1 locks to per_invoice only (DL-019).
    -- blanket is a Phase 2 value.
    acknowledgment_mode     buyer_ack_mode              NOT NULL DEFAULT 'per_invoice'
        CONSTRAINT buyer_account_ack_mode_phase1_chk
            CHECK (acknowledgment_mode = 'per_invoice'),
    status                  buyer_account_status        NOT NULL DEFAULT 'nominated',
    -- credit_limit_paise: snapshot from BC3 risk_buyer_profile.
    credit_limit_paise      positive_money_paise,
    pricing_band_id         UUID,   -- cross-context reference to BC3 PricingBand.
    -- noa_doc_hash: SHA-256 ref to signed Notice of Assignment in sys_document_object.
    noa_doc_hash            BYTEA,
    nominated_by            UUID    NOT NULL,  -- FK → admin_user (Credit Reviewer).
    activated_at            TIMESTAMPTZ,
    suspended_at            TIMESTAMPTZ,
    suspension_reason       TEXT,
    aggregate_version       INT     NOT NULL DEFAULT 1,

    CONSTRAINT buyer_account_pk             PRIMARY KEY (buyer_id)
);

COMMENT ON TABLE buyer_account IS
    'BC9. Aggregate root for a buyer. Phase 1 accepts only acknowledged_buyer '
    'tier and per_invoice acknowledgment mode; anchor and unacknowledged_buyer '
    'are Phase 2 enum values present in schema for superset-readiness (DL-001, '
    'DL-019, DL-020). Refs: DL-018, DL-019, DL-020, DL-021.';

COMMENT ON COLUMN buyer_account.relationship_tier IS
    'Phase 1: acknowledged_buyer only. anchor and unacknowledged_buyer are '
    'Phase 2 (DL-020). CHECK constraint enforces the Phase 1 restriction; '
    'remove the constraint when unlocking Phase 2.';
COMMENT ON COLUMN buyer_account.acknowledgment_mode IS
    'Phase 1: per_invoice only (DL-019). blanket is Phase 2. CHECK constraint '
    'enforces Phase 1 restriction.';

-- APPLICATION-LAYER INVARIANTS: buyer_account
-- BA.1  At least one buyer_ack_user with is_active=TRUE must exist for a
--       buyer before status can advance to active (DL-021).
-- BA.2  buyer_account suspension (status=suspended) requires maker-checker:
--       checker must be a different admin user than the initiator (C4).
-- BA.3  All buyer identity data (MCA CIN, GSTIN) must be verified via BC17
--       before status advances beyond identity_verified (C24).

CREATE INDEX idx_buyer_account_status ON buyer_account (status);

-- ---------------------------------------------------------------------------
-- buyer_ack_user
-- Authorised buyer contact for invoice acknowledgment.
-- Carries identity_id FK. Login = OTP only; no auth_credential row created.
-- ---------------------------------------------------------------------------
CREATE TABLE buyer_ack_user (
    ack_user_id         UUID        NOT NULL,
    buyer_id            UUID        NOT NULL,  -- FK → buyer_account
    -- identity_id: FK → auth_identity. One identity per acknowledgment user.
    identity_id         UUID        NOT NULL,
    display_name        TEXT        NOT NULL,
    email               TEXT        NOT NULL,
    phone               TEXT        NOT NULL,
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    designated_by       UUID        NOT NULL,  -- FK → admin_user (Ops Executive).
    designated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deactivated_at      TIMESTAMPTZ,
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT buyer_ack_user_pk             PRIMARY KEY (ack_user_id),
    CONSTRAINT buyer_ack_user_identity_uq    UNIQUE (identity_id),
    CONSTRAINT buyer_ack_user_buyer_fk
        FOREIGN KEY (buyer_id) REFERENCES buyer_account (buyer_id),
    CONSTRAINT buyer_ack_user_deactivate_chk
        CHECK (
            (is_active = TRUE AND deactivated_at IS NULL)
            OR (is_active = FALSE AND deactivated_at IS NOT NULL)
        )
);

COMMENT ON TABLE buyer_ack_user IS
    'BC9. Buyer''s authorised user for invoice acknowledgment. Carries '
    'identity_id FK. Login is OTP-only; no auth_credential row is created for '
    'this identity — application-layer invariant (DL-021). Refs: DL-021.';

COMMENT ON COLUMN buyer_ack_user.identity_id IS
    'FK → auth_identity. Login is OTP-only. The application must never create '
    'an auth_credential row for this identity — application-layer invariant.';

-- APPLICATION-LAYER INVARIANTS: buyer_ack_user
-- AU.1  Login mechanism for buyer_ack_user is OTP-only (SMS/email).
--       The application must NEVER create an auth_credential row for the
--       associated identity_id.
-- AU.2  Deactivation of a buyer_ack_user is blocked if it would leave
--       the buyer with zero active acknowledgment users.  Application performs
--       a count check on (buyer_id, is_active=TRUE) before deactivation.

CREATE INDEX idx_buyer_ack_user_buyer_active
    ON buyer_ack_user (buyer_id) WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- buyer_payment_rule
-- Current payment instruction for a buyer. Only one current per buyer at a
-- time — enforced via partial UNIQUE index on superseded_by IS NULL.
-- ---------------------------------------------------------------------------
CREATE TABLE buyer_payment_rule (
    instruction_id      UUID        NOT NULL,
    buyer_id            UUID        NOT NULL,  -- FK → buyer_account
    -- instruction_doc_hash: SHA-256 reference into sys_document_object for the confirmed doc.
    instruction_doc_hash BYTEA      NOT NULL,
    effective_from      DATE        NOT NULL,
    -- superseded_by: set when a newer buyer_payment_rule is confirmed.
    superseded_by       UUID,       -- FK → buyer_payment_rule (self-referential).
    confirmed_by        UUID        NOT NULL,  -- FK → admin_user (Ops Executive).
    confirmed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT buyer_payment_rule_pk          PRIMARY KEY (instruction_id),
    CONSTRAINT buyer_payment_rule_buyer_fk
        FOREIGN KEY (buyer_id) REFERENCES buyer_account (buyer_id),
    CONSTRAINT buyer_payment_rule_superseded_fk
        FOREIGN KEY (superseded_by) REFERENCES buyer_payment_rule (instruction_id)
);

COMMENT ON TABLE buyer_payment_rule IS
    'BC9. Buyer payment/remittance instruction. Exactly one current (unsuperseded) '
    'instruction per buyer enforced via partial unique index. Supersession pattern '
    'mirrors risk_pricing_policy. Refs: Spec §2.3.';

COMMENT ON COLUMN buyer_payment_rule.superseded_by IS
    'NULL means this is the current instruction. Non-NULL means it has been '
    'replaced; the referenced row is the successor.';

-- Enforces one current buyer_payment_rule per buyer.
CREATE UNIQUE INDEX uidx_buyer_payment_rule_buyer_current
    ON buyer_payment_rule (buyer_id)
    WHERE superseded_by IS NULL;

CREATE INDEX idx_buyer_payment_rule_buyer ON buyer_payment_rule (buyer_id);


-- =============================================================================
-- BC10 — ADMIN IAM
-- =============================================================================

-- ---------------------------------------------------------------------------
-- admin_user
-- Aggregate root for a platform admin user. Carries identity_id FK (UNIQUE).
-- MFA factors live in the auth layer (auth_mfa_factor, keyed on identity_id).
-- At least one active TOTP factor required before status=active (C7, DL-035).
-- Every state-changing command requires mfa_assertion_id from auth_session (C7).
-- ---------------------------------------------------------------------------
CREATE TABLE admin_user (
    admin_user_id       UUID                NOT NULL,
    -- identity_id: FK → auth_identity. One-to-one (UNIQUE).
    identity_id         UUID                NOT NULL,
    email               TEXT                NOT NULL,
    display_name        TEXT                NOT NULL,
    status              admin_user_status   NOT NULL DEFAULT 'invited',
    -- tenant_claims: JSONB array; enforced at repository layer (C16, G19).
    tenant_claims       JSONB               NOT NULL DEFAULT '[]',
    disabled_at         TIMESTAMPTZ,
    disabled_by         UUID,   -- FK → admin_user (Super Admin).
    aggregate_version   INT                 NOT NULL DEFAULT 1,

    CONSTRAINT admin_user_pk              PRIMARY KEY (admin_user_id),
    CONSTRAINT admin_user_identity_uq     UNIQUE (identity_id),
    CONSTRAINT admin_user_email_uq        UNIQUE (email),
    CONSTRAINT admin_user_disabled_by_fk
        FOREIGN KEY (disabled_by) REFERENCES admin_user (admin_user_id)
);

COMMENT ON TABLE admin_user IS
    'BC10. Platform admin user. Carries identity_id FK (one-to-one). '
    'Roles are assigned separately via admin_role_assignment. '
    'At least one TOTP MFA factor must be enrolled before status=active — '
    'application-layer invariant (C7, DL-035). '
    'Every state-changing command in every context requires a valid '
    'mfa_assertion_id from auth_session — application-layer invariant (C7). '
    'Refs: DL-031, DL-032, DL-035, C7, C16.';

COMMENT ON COLUMN admin_user.tenant_claims IS
    'JSONB array of tenant-scoped claims issued at session establishment. '
    'Enforced at repository layer (G19); never UI-only (C16).';

-- APPLICATION-LAYER INVARIANTS: admin_user
-- AU10.1  status cannot advance to active unless auth_mfa_factor contains
--         at least one active TOTP factor for this admin user's identity_id
--         (C7, DL-035). MFA is owned by the auth layer — resolve via:
--         admin_user.identity_id → auth_identity → auth_mfa_factor.
-- AU10.2  Every state-changing command across all bounded contexts must supply
--         a mfa_assertion_id referencing a valid, non-expired
--         auth_session.mfa_assertion_id for this admin_user_id (C7, B2 §2.2).
-- AU10.3  audit_account (BC13) identity must not share identity_id with any
--         admin user who holds an active operational role (C19 — see BC13).

-- admin_user_identity_idx omitted: admin_user_identity_uq UNIQUE already
-- creates an implicit index on identity_id. auth.sql must NOT re-add this index
-- (would collide). auth.sql ALTER TABLE must only ADD the FK constraint.
CREATE INDEX idx_admin_user_email    ON admin_user (email);

-- ---------------------------------------------------------------------------
-- MFA FACTORS — OWNED BY THE AUTH LAYER (auth.sql)
-- auth_mfa_factor dropped from BC10. Admin MFA factors live in
-- auth_mfa_factor, keyed on identity_id (not admin_user_id).
-- Resolution path:
--   admin_user.admin_user_id
--     → admin_user.identity_id
--     → auth_mfa_factor.identity_id
-- auth_mfa_factor has stronger constraints than the dropped table:
--   • EXCLUDE prevents silent TOTP shadow re-enrolment.
--   • totp_has_secret CHECK enforces secret presence for TOTP factors.
--   • secret_ref TEXT stores a vault reference — raw secret never in DB.
-- mfa_factor_type ENUM also removed; auth.sql uses mfa_factor_kind_enum.
-- Refs: C7, DL-035, auth.sql invariants A1 (KIND/CREDENTIAL/FACTOR MATRIX),
--       A2 (ADMIN ACTIVATION GATE).
-- MIGRATION NOTE: auth.sql ALTER TABLE admin_user must only ADD the FK
-- constraint (admin_user_identity_fk). The identity_id column and
-- admin_user_identity_uq UNIQUE constraint are already defined above.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- admin_role_assignment
-- Composite natural PK (admin_user_id, role). One row per user-role pair.
-- Strict SoD: credit_reviewer + treasury_and_settlement cannot coexist for
-- the same user — application invariant (strict system-block per DL-033, C5).
-- ---------------------------------------------------------------------------
CREATE TABLE admin_role_assignment (
    admin_user_id               UUID                            NOT NULL,  -- FK → admin_user
    role                        admin_role                      NOT NULL,
    status                      admin_role_assignment_status    NOT NULL DEFAULT 'active',
    assigned_at                 TIMESTAMPTZ                     NOT NULL DEFAULT now(),
    assigned_by                 UUID                            NOT NULL,  -- FK → admin_user (Super Admin).
    revoked_at                  TIMESTAMPTZ,
    revoked_by                  UUID,   -- FK → admin_user.
    -- sod_warning_acknowledged_at: set when a soft SoD pair is overridden.
    sod_warning_acknowledged_at TIMESTAMPTZ,
    override_reason             TEXT,
    -- deviation_register_entry_id: FK → admin_deviation_log when soft SoD is triggered.
    deviation_register_entry_id UUID,
    aggregate_version           INT                             NOT NULL DEFAULT 1,

    CONSTRAINT admin_role_assignment_pk         PRIMARY KEY (admin_user_id, role),
    CONSTRAINT admin_role_assignment_admin_user_fk
        FOREIGN KEY (admin_user_id) REFERENCES admin_user (admin_user_id),
    CONSTRAINT admin_role_assignment_assigned_by_fk
        FOREIGN KEY (assigned_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT admin_role_assignment_revoked_by_fk
        FOREIGN KEY (revoked_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT admin_role_assignment_revoke_chk
        CHECK (
            (status = 'active' AND revoked_at IS NULL AND revoked_by IS NULL)
            OR (status = 'revoked' AND revoked_at IS NOT NULL)
        ),
    CONSTRAINT admin_role_assignment_soft_sod_override_chk
        CHECK (
            deviation_register_entry_id IS NULL
            OR (sod_warning_acknowledged_at IS NOT NULL AND override_reason IS NOT NULL)
        )
);

COMMENT ON TABLE admin_role_assignment IS
    'BC10. Role assignment for an admin user. Composite natural PK. '
    'STRICT SoD BLOCK (DL-033, C5): credit_reviewer + treasury_and_settlement '
    'cannot coexist as active assignments for the same admin_user_id — '
    'application-layer invariant; the command handler rejects the Assign command '
    'before this row is inserted. '
    'Soft SoD pairs (Super Admin+Compliance Reviewer, Ops Executive+Treasury, '
    'Credit Reviewer+Compliance Reviewer) require override_reason and create a '
    'admin_deviation_log entry — application-layer invariant. '
    'Refs: DL-031, DL-032, DL-033, C5.';

COMMENT ON COLUMN admin_role_assignment.deviation_register_entry_id IS
    'FK → admin_deviation_log. Set when assignment triggers a soft SoD pair. '
    'Must be set alongside override_reason and sod_warning_acknowledged_at.';

-- APPLICATION-LAYER INVARIANTS: admin_role_assignment
-- RA.1  STRICT SoD BLOCK: before inserting a row for credit_reviewer, the
--       handler checks that no active treasury_and_settlement row exists for
--       the same admin_user_id, and vice versa.  Violation → command rejected
--       with SodStrictViolation error (C5, DL-033).
-- RA.2  Soft SoD pairs (super_admin+compliance_reviewer,
--       ops_executive+treasury_and_settlement,
--       credit_reviewer+compliance_reviewer) issue a warning.  The actor must
--       supply override_reason; an admin_deviation_log row is created atomically.
-- RA.3  Exactly one admin_deviation_log entry is created per soft-SoD assignment event;
--       its PK is stored in deviation_register_entry_id.
-- RA.4  audit_account identity (BC13) must never hold any operational role;
--       the handler cross-checks audit_account before assignment.

CREATE INDEX idx_admin_role_assignment_admin_user ON admin_role_assignment (admin_user_id);
CREATE INDEX idx_admin_role_assignment_role_active
    ON admin_role_assignment (role, admin_user_id) WHERE status = 'active';

-- ---------------------------------------------------------------------------
-- admin_deviation_log
-- Managed Deviation Register. Immutable except quarterly_review fields.
-- combo TEXT[2]: the two roles forming the soft SoD pair.
-- ---------------------------------------------------------------------------
CREATE TABLE admin_deviation_log (
    deviation_register_entry_id UUID                        NOT NULL,
    admin_user_id               UUID                        NOT NULL,  -- FK → admin_user
    -- combo: exactly two role values representing the soft SoD pair.
    combo                       TEXT[]                      NOT NULL
        CONSTRAINT admin_deviation_log_combo_len_chk CHECK (array_length(combo, 1) = 2),
    reason                      TEXT                        NOT NULL,
    created_at                  TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    quarterly_review_status     admin_sod_review_status     NOT NULL DEFAULT 'pending',
    -- review_decision: set exactly once at quarterly review (DE.1).
    review_decision             TEXT,
    reviewed_at                 TIMESTAMPTZ,
    reviewed_by                 UUID,   -- FK → admin_user (Super Admin).
    aggregate_version           INT                         NOT NULL DEFAULT 1,

    CONSTRAINT admin_deviation_log_pk             PRIMARY KEY (deviation_register_entry_id),
    CONSTRAINT admin_deviation_log_admin_user_fk
        FOREIGN KEY (admin_user_id) REFERENCES admin_user (admin_user_id),
    CONSTRAINT admin_deviation_log_reviewed_by_fk
        FOREIGN KEY (reviewed_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT admin_deviation_log_review_chk
        CHECK (
            (quarterly_review_status = 'pending'
                AND review_decision IS NULL
                AND reviewed_at IS NULL)
            OR (quarterly_review_status = 'reviewed'
                AND review_decision IS NOT NULL
                AND reviewed_at IS NOT NULL)
        )
);

COMMENT ON TABLE admin_deviation_log IS
    'BC10. Managed Deviation Register entry for soft SoD pair overrides. '
    'Immutable except the quarterly review fields (review_decision, reviewed_at, '
    'reviewed_by), which are set exactly once. Reviewed quarterly by Super Admin. '
    'Refs: DL-033, DL-034, C5.';

COMMENT ON COLUMN admin_deviation_log.combo IS
    'TEXT[2]: the two admin_role values forming the soft SoD pair '
    '(e.g. ARRAY[''super_admin'',''compliance_reviewer'']).';
COMMENT ON COLUMN admin_deviation_log.review_decision IS
    'Set exactly once at quarterly review.  Immutable thereafter — '
    'application-layer invariant.';

-- APPLICATION-LAYER INVARIANTS: admin_deviation_log
-- DE.1  The row is immutable after insert except for the quarterly review
--       fields (quarterly_review_status, review_decision, reviewed_at,
--       reviewed_by), which may be set exactly once.  Any attempt to update
--       non-review fields after insert must be rejected.
-- DE.2  review_decision, reviewed_at, reviewed_by must all be set in a single
--       atomic update; partial updates are rejected.

CREATE INDEX idx_admin_deviation_log_admin_user ON admin_deviation_log (admin_user_id);
CREATE INDEX idx_admin_deviation_log_review_status
    ON admin_deviation_log (quarterly_review_status) WHERE quarterly_review_status = 'pending';

-- ---------------------------------------------------------------------------
-- admin_sod_policy
-- Rules-as-data table. One current active policy at a time (supersession).
-- ---------------------------------------------------------------------------
CREATE TABLE admin_sod_policy (
    sod_policy_id       UUID                        NOT NULL,
    -- strict_pairs: JSONB array of [role, role] pairs that are system-blocked.
    strict_pairs        JSONB                       NOT NULL DEFAULT '[]',
    -- soft_pairs: JSONB array of [role, role] pairs that warn + log deviation.
    soft_pairs          JSONB                       NOT NULL DEFAULT '[]',
    enforcement_tier    admin_sod_enforcement_tier  NOT NULL,
    effective_from      TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    -- superseded_by: NULL means this is the current policy.
    superseded_by       UUID,   -- FK → admin_sod_policy (self-referential).
    published_by        UUID    NOT NULL,  -- FK → admin_user (Super Admin).
    aggregate_version   INT     NOT NULL DEFAULT 1,

    CONSTRAINT admin_sod_policy_pk PRIMARY KEY (sod_policy_id),
    CONSTRAINT admin_sod_policy_superseded_fk
        FOREIGN KEY (superseded_by) REFERENCES admin_sod_policy (sod_policy_id),
    CONSTRAINT admin_sod_policy_published_by_fk
        FOREIGN KEY (published_by) REFERENCES admin_user (admin_user_id)
);

COMMENT ON TABLE admin_sod_policy IS
    'BC10. SoD policy as rules-as-data. Exactly one current policy (superseded_by '
    'IS NULL). Phase 1 fixed policy: strict = {(credit_reviewer, '
    'treasury_and_settlement)}; soft = {(super_admin, compliance_reviewer), '
    '(ops_executive, treasury_and_settlement), (credit_reviewer, '
    'compliance_reviewer)} per DL-033, C5. Refs: DL-033, C5.';

COMMENT ON COLUMN admin_sod_policy.strict_pairs IS
    'JSONB array of [role, role] pairs blocked at system level.';
COMMENT ON COLUMN admin_sod_policy.soft_pairs IS
    'JSONB array of [role, role] pairs that trigger a warning and '
    'mandate an admin_deviation_log entry.';

-- Enforces one current admin_sod_policy at a time.
-- Indexes a constant expression so the UNIQUE constraint means at most one row
-- can satisfy the WHERE clause (the active-policy slot).
CREATE UNIQUE INDEX uidx_admin_sod_policy_one_active
    ON admin_sod_policy ((1))
    WHERE superseded_by IS NULL;


-- =============================================================================
-- BC11 — COMPLIANCE
-- =============================================================================

-- ---------------------------------------------------------------------------
-- comp_aml_screening
-- AML/PEP screening record. One-time at onboarding in Phase 1.
-- subject_type covers investor, supplier, signatory, ubo.
-- ---------------------------------------------------------------------------
CREATE TABLE comp_aml_screening (
    screening_id            UUID                        NOT NULL,
    subject_id              UUID                        NOT NULL,  -- cross-context: investor_id, supplier_id, or signatory/ubo identity.
    subject_type            comp_aml_subject_type       NOT NULL,
    status                  comp_aml_screening_status   NOT NULL DEFAULT 'initiated',
    -- match_score: vendor-returned score (0.0–1.0 range typical).
    match_score             NUMERIC(5,4),
    -- hits: JSONB array of {name, hit_type, score, source} from vendor.
    hits                    JSONB                       NOT NULL DEFAULT '[]',
    -- adjudication_decision: 'clear', 'false_positive', 'true_hit_suspend'.
    adjudication_decision   TEXT,
    adjudicated_by          UUID,   -- FK → admin_user (Compliance Reviewer).
    adjudicated_at          TIMESTAMPTZ,
    -- vendor_payload_hash: SHA-256 ref into sys_document_object for raw vendor response (C24).
    vendor_payload_hash     BYTEA,
    screened_at             TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    aggregate_version       INT                         NOT NULL DEFAULT 1,

    CONSTRAINT comp_aml_screening_pk               PRIMARY KEY (screening_id),
    CONSTRAINT comp_aml_screening_adjudicated_by_fk
        FOREIGN KEY (adjudicated_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT comp_aml_screening_adjudication_chk
        CHECK (
            (status = 'adjudicated'
                AND adjudication_decision IS NOT NULL
                AND adjudicated_by IS NOT NULL
                AND adjudicated_at IS NOT NULL)
            OR status <> 'adjudicated'
        ),
    CONSTRAINT comp_aml_screening_match_score_range
        CHECK (match_score IS NULL OR (match_score >= 0 AND match_score <= 1))
);

COMMENT ON TABLE comp_aml_screening IS
    'BC11. AML/PEP screening record for investor, supplier, signatory, or UBO. '
    'One-time at onboarding in Phase 1; re-screening scheduler dormant (DL-037). '
    'Vendor payload stored verbatim in sys_document_object via vendor_payload_hash (C24). '
    'Refs: DL-037, DL-038, C24.';

COMMENT ON COLUMN comp_aml_screening.subject_type IS
    'investor | supplier | signatory | ubo — identifies which entity type '
    'the screening applies to.';
COMMENT ON COLUMN comp_aml_screening.vendor_payload_hash IS
    'SHA-256 reference into sys_document_object for the raw vendor AML response. '
    'Stored verbatim for evidence per C24.';

CREATE INDEX idx_comp_aml_screening_subject ON comp_aml_screening (subject_id, subject_type);
CREATE INDEX idx_comp_aml_screening_status  ON comp_aml_screening (status);

-- ---------------------------------------------------------------------------
-- comp_sar_case
-- Internal Suspicious Activity Report. Status=internal only in Phase 1.
-- escalated_to_fiu_ind is a Phase 2 enum value.
-- ---------------------------------------------------------------------------
CREATE TABLE comp_sar_case (
    sar_id              UUID                NOT NULL,
    subject_id          UUID                NOT NULL,  -- cross-context: investor_id or supplier_id.
    subject_type        comp_aml_subject_type NOT NULL,
    -- status: 'internal' is the only active value in Phase 1.
    -- 'escalated_to_fiu_ind' is a Phase 2 value blocked by application.
    status              comp_sar_status     NOT NULL DEFAULT 'internal',
    summary_doc_hash    BYTEA               NOT NULL,  -- SHA-256 ref → sys_document_object.
    raised_by           UUID                NOT NULL,  -- FK → admin_user (Compliance Reviewer).
    raised_at           TIMESTAMPTZ         NOT NULL DEFAULT now(),
    -- updates: JSONB append-only log of {doc_hash, updated_by, updated_at}.
    updates             JSONB               NOT NULL DEFAULT '[]',
    aggregate_version   INT                 NOT NULL DEFAULT 1,

    CONSTRAINT comp_sar_case_pk   PRIMARY KEY (sar_id),
    CONSTRAINT comp_sar_case_raised_by_fk
        FOREIGN KEY (raised_by) REFERENCES admin_user (admin_user_id)
);

COMMENT ON TABLE comp_sar_case IS
    'BC11. Internal Suspicious Activity Report. Phase 1 status is always '
    '''internal''. ''escalated_to_fiu_ind'' is a Phase 2 enum value; application '
    'blocks it in Phase 1 (DL-038). Refs: DL-038.';

COMMENT ON COLUMN comp_sar_case.status IS
    'Phase 1: internal only. escalated_to_fiu_ind is Phase 2 — '
    'the application command handler rejects that value until unlocked.';

CREATE INDEX idx_comp_sar_case_subject ON comp_sar_case (subject_id, subject_type);

-- ---------------------------------------------------------------------------
-- comp_refresh_schedule
-- Annual KYC refresh schedule. One per subject (UNIQUE on subject_id).
-- due_at is stored as activated_at + 12 months (C17).
-- ---------------------------------------------------------------------------
CREATE TABLE comp_refresh_schedule (
    schedule_id         UUID                    NOT NULL,
    subject_id          UUID                    NOT NULL,  -- investor_id or supplier_id.
    subject_type        comp_kyc_subject_type   NOT NULL,
    -- due_at: stored computed value = activated_at + INTERVAL '12 months' (C17).
    due_at              TIMESTAMPTZ             NOT NULL,
    status              comp_kyc_refresh_status NOT NULL DEFAULT 'scheduled',
    window_close_at     TIMESTAMPTZ,   -- set when status transitions to due.
    completed_at        TIMESTAMPTZ,
    aggregate_version   INT                     NOT NULL DEFAULT 1,

    CONSTRAINT comp_refresh_schedule_pk         PRIMARY KEY (schedule_id),
    -- Scoped to (subject_id, subject_type) to allow an investor and a supplier
    -- to each have their own schedule (matching comp_kyc_file_subject_uq convention).
    CONSTRAINT comp_refresh_schedule_subject_uq UNIQUE (subject_id, subject_type)
);

COMMENT ON TABLE comp_refresh_schedule IS
    'BC11. Annual KYC refresh schedule. Exactly one per subject (UNIQUE). '
    'due_at = activated_at + 12 months stored as a computed value (C17). '
    'Missed refresh does not auto-suspend in Phase 1; Compliance Reviewer '
    'adjudicates (DL-037). Refs: C17, DL-037.';

COMMENT ON COLUMN comp_refresh_schedule.due_at IS
    'Stored computed value: activated_at + INTERVAL ''12 months'' (C17). '
    'Must be set at Schedule command time; scheduler fires KycRefresh.Due '
    'at this timestamp.';

CREATE INDEX idx_comp_refresh_schedule_due_at
    ON comp_refresh_schedule (due_at) WHERE status IN ('scheduled', 'due');

-- ---------------------------------------------------------------------------
-- comp_kyc_file
-- BC11 approval view. One per (subject_id, subject_type).
-- Approver must be a Compliance Reviewer — application invariant (C21, KF.1).
-- ---------------------------------------------------------------------------
CREATE TABLE comp_kyc_file (
    kyc_file_id         UUID                    NOT NULL,
    subject_id          UUID                    NOT NULL,  -- investor_id or supplier_id.
    subject_type        comp_kyc_subject_type   NOT NULL,
    -- doc_hashes: array of SHA-256 refs into sys_document_object (one per submitted document).
    doc_hashes          BYTEA[]                 NOT NULL DEFAULT '{}',
    status              comp_kyc_file_status    NOT NULL DEFAULT 'in_review',
    -- approver_id: FK → admin_user; must hold compliance_reviewer role.
    approver_id         UUID,
    decided_at          TIMESTAMPTZ,
    rejection_reason    TEXT,
    submitted_at        TIMESTAMPTZ             NOT NULL DEFAULT now(),
    aggregate_version   INT                     NOT NULL DEFAULT 1,

    CONSTRAINT comp_kyc_file_pk            PRIMARY KEY (kyc_file_id),
    CONSTRAINT comp_kyc_file_subject_uq    UNIQUE (subject_id, subject_type),
    CONSTRAINT comp_kyc_file_approver_fk
        FOREIGN KEY (approver_id) REFERENCES admin_user (admin_user_id),
    CONSTRAINT comp_kyc_file_decision_chk
        CHECK (
            (status = 'in_review'
                AND approver_id IS NULL
                AND decided_at IS NULL)
            OR (status IN ('approved', 'rejected')
                AND approver_id IS NOT NULL
                AND decided_at IS NOT NULL)
        ),
    CONSTRAINT comp_kyc_file_rejection_reason_chk
        CHECK (status <> 'rejected' OR rejection_reason IS NOT NULL)
);

COMMENT ON TABLE comp_kyc_file IS
    'BC11. KYC file — approval lens. Exactly one per (subject_id, subject_type). '
    'Approver must hold the compliance_reviewer role — application-layer invariant '
    '(KF.1, C21). The approver cannot be the same individual who submitted on '
    'behalf of the supplier (record-level maker-checker, C4). '
    'Refs: DL-050, C4, C21.';

COMMENT ON COLUMN comp_kyc_file.approver_id IS
    'FK → admin_user. Must hold compliance_reviewer role. '
    'Cannot be the same individual as the submitter — application-layer invariant '
    '(record-level maker-checker, C4).';

-- APPLICATION-LAYER INVARIANTS: comp_kyc_file
-- KF.1  approver_id must reference an admin_user with an active
--       compliance_reviewer admin_role_assignment.
-- KF.2  For supplier KYC files, approver_id must differ from the admin_user
--       who submitted on the supplier's behalf (maker-checker, C4).
-- KF.3  comp_kyc_file.Approved is the only event that allows the corresponding BC7/BC8
--       account to advance to the MAA/MIA signing stage (DL-050).

CREATE INDEX idx_comp_kyc_file_subject ON comp_kyc_file (subject_id, subject_type);
CREATE INDEX idx_comp_kyc_file_status  ON comp_kyc_file (status) WHERE status = 'in_review';

-- ---------------------------------------------------------------------------
-- comp_spot_check
-- Compliance audit-trail spot check record. Immutable after creation (C1).
-- ---------------------------------------------------------------------------
CREATE TABLE comp_spot_check (
    spot_check_id       UUID        NOT NULL,
    period              TEXT        NOT NULL,   -- e.g. '2024-Q1'.
    scope               TEXT        NOT NULL,
    findings_doc_hash   BYTEA       NOT NULL,   -- SHA-256 ref → sys_document_object.
    completed_by        UUID        NOT NULL,   -- FK → admin_user (Compliance Reviewer).
    completed_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT comp_spot_check_pk PRIMARY KEY (spot_check_id),
    CONSTRAINT comp_spot_check_completed_by_fk
        FOREIGN KEY (completed_by) REFERENCES admin_user (admin_user_id)
);

COMMENT ON TABLE comp_spot_check IS
    'BC11. Audit-trail spot-check record. Immutable after creation (C1). '
    'Ref: Spec §7.1.';

CREATE INDEX idx_comp_spot_check_period ON comp_spot_check (period);


-- =============================================================================
-- BC12 — TAX & REPORTING
-- =============================================================================

-- ---------------------------------------------------------------------------
-- tax_year_profile
-- Composite PK (investor_id, fy_code). One per investor per financial year.
-- Stores TDS rate determination inputs and cumulative figures for the FY.
-- ---------------------------------------------------------------------------
CREATE TABLE tax_year_profile (
    investor_id                 UUID        NOT NULL,  -- FK → inv_account.
    -- fy_code: e.g. 'FY2024-25'.
    fy_code                     TEXT        NOT NULL,
    -- tds_rate_bps: applicable TDS rate in basis points for this investor × FY.
    tds_rate_bps                INT         NOT NULL DEFAULT 0
        CONSTRAINT tax_year_profile_tds_rate_nonneg CHECK (tds_rate_bps >= 0),
    -- pan_verified: whether PAN was verified at rate-resolution time.
    pan_verified                BOOLEAN     NOT NULL DEFAULT FALSE,
    -- cumulative_gross_paise: running sum of gross distributions in the FY.
    cumulative_gross_paise      money_paise NOT NULL DEFAULT 0,
    -- cumulative_tds_paise: running sum of TDS deducted in the FY.
    cumulative_tds_paise        money_paise NOT NULL DEFAULT 0,
    form_16a_issued             BOOLEAN     NOT NULL DEFAULT FALSE,
    form_16a_doc_hash           BYTEA,
    form_16a_issued_at          TIMESTAMPTZ,
    fy_closed_at                TIMESTAMPTZ,
    aggregate_version           INT         NOT NULL DEFAULT 1,

    CONSTRAINT tax_year_profile_pk PRIMARY KEY (investor_id, fy_code)
);

COMMENT ON TABLE tax_year_profile IS
    'BC12. TDS tax year profile for an investor. Composite PK (investor_id, fy_code). '
    'One row per investor per financial year. Stores resolved TDS rate and '
    'cumulative totals for Form 16A issuance. Refs: DL-045, G4, G12.';

COMMENT ON COLUMN tax_year_profile.tds_rate_bps IS
    'Resolved TDS rate in basis points (e.g. 1000 = 10%). Zero for exempt investors.';
COMMENT ON COLUMN tax_year_profile.fy_code IS
    'Financial year code, e.g. ''FY2024-25''. Format enforced by application.';

CREATE INDEX idx_tax_year_profile_investor ON tax_year_profile (investor_id);

-- ---------------------------------------------------------------------------
-- tax_tds_deduction
-- Per-investor, per-listing TDS deduction record. Linked to a payout
-- instruction. Invariant: gross - tds_amount - fee_paise = net_paise.
-- ---------------------------------------------------------------------------
CREATE TABLE tax_tds_deduction (
    tds_deduction_id        UUID        NOT NULL,
    investor_id             UUID        NOT NULL,  -- FK → inv_account.
    -- listing_id: cross-context reference to BC1 deal_listing.
    listing_id              UUID        NOT NULL,
    fy_code                 TEXT        NOT NULL,
    -- payout_instruction_id: FK → BC4 cash_payout_instruction (cross-context by identity).
    payout_instruction_id   UUID        NOT NULL,
    -- challan_ref: escrow TDS challan reference once deposited (G12).
    challan_ref             TEXT,
    gross_paise             positive_money_paise NOT NULL,
    tds_amount_paise        money_paise          NOT NULL DEFAULT 0,
    fee_paise               money_paise          NOT NULL DEFAULT 0,
    net_paise               positive_money_paise NOT NULL,
    -- Invariant: gross - tds_amount - fee = net.
    CONSTRAINT tax_tds_deduction_net_formula_chk
        CHECK (net_paise = gross_paise - tds_amount_paise - fee_paise),
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    aggregate_version       INT         NOT NULL DEFAULT 1,

    CONSTRAINT tax_tds_deduction_pk PRIMARY KEY (tds_deduction_id)
);

COMMENT ON TABLE tax_tds_deduction IS
    'BC12. Per-investor per-listing TDS deduction record. '
    'CHECK enforces: gross - tds_amount - fee = net_paise. '
    'payout_instruction_id FK links to BC4 cash_payout_instruction. '
    'challan_ref populated on TdsChallanRecorded event from BC4. '
    'Refs: DL-045, G4, G12.';

COMMENT ON COLUMN tax_tds_deduction.net_paise IS
    'Net payout to investor in paise. Must equal gross - tds_amount - fee '
    '(enforced by CHECK constraint).';
COMMENT ON COLUMN tax_tds_deduction.challan_ref IS
    'Escrow TDS challan reference. Set when BC4 emits TdsChallanRecorded. '
    'Used for Form 16A generation (G12).';

CREATE INDEX idx_tax_tds_deduction_investor_listing ON tax_tds_deduction (investor_id, listing_id);
CREATE INDEX idx_tax_tds_deduction_investor_fy      ON tax_tds_deduction (investor_id, fy_code);
CREATE INDEX idx_tax_tds_deduction_payout_instruction ON tax_tds_deduction (payout_instruction_id);

-- ---------------------------------------------------------------------------
-- tax_investor_statement
-- Composite PK (investor_id, period, kind). One per investor per period/kind.
-- kind: monthly_portfolio | form_16a.
-- ---------------------------------------------------------------------------
CREATE TABLE tax_investor_statement (
    investor_id         UUID                            NOT NULL,  -- FK → inv_account.
    -- period: 'YYYY-MM' for monthly_portfolio; 'FY20XX-XX' for form_16a.
    period              TEXT                            NOT NULL,
    kind                tax_investor_statement_kind     NOT NULL,
    -- doc_hash: SHA-256 ref into sys_document_object for the generated statement document.
    doc_hash            BYTEA                           NOT NULL,
    generated_at        TIMESTAMPTZ                     NOT NULL DEFAULT now(),
    aggregate_version   INT                             NOT NULL DEFAULT 1,

    CONSTRAINT tax_investor_statement_pk PRIMARY KEY (investor_id, period, kind)
);

COMMENT ON TABLE tax_investor_statement IS
    'BC12. Investor statement. Composite PK (investor_id, period, kind). '
    'kind=monthly_portfolio generated on monthly cycle. '
    'kind=form_16a generated annually per DL-045, G12. '
    'doc_hash is a SHA-256 reference into sys_document_object. '
    'Refs: DL-045, Spec §2.4, G12.';

COMMENT ON COLUMN tax_investor_statement.period IS
    'YYYY-MM for monthly_portfolio statements. '
    'FY20XX-XX format for form_16a statements.';

CREATE INDEX idx_tax_investor_statement_investor ON tax_investor_statement (investor_id, kind);


-- =============================================================================
-- BC13 — AUDITOR ACCESS
-- =============================================================================

-- ---------------------------------------------------------------------------
-- audit_scope
-- Defines the scope for an auditor engagement: date range, entity types,
-- sensitivity level. Immutable after first use.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_scope (
    scope_id            UUID        NOT NULL,
    -- date_range: TSTZRANGE covering the audit period (ASC.1).
    date_range          TSTZRANGE   NOT NULL
        CONSTRAINT audit_scope_date_range_valid CHECK (NOT isempty(date_range)),
    -- entity_types: TEXT[] of aggregate/entity type names in scope.
    entity_types        TEXT[]      NOT NULL,
    -- sensitivity_level: highest sensitivity level accessible under this scope.
    sensitivity_level   audit_sensitivity_level NOT NULL,
    defined_by          UUID        NOT NULL,  -- FK → admin_user (Super Admin).
    defined_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- first_used_at: set when first referenced by an audit_account.
    -- Once set, scope is immutable (ASC.2).
    first_used_at       TIMESTAMPTZ,
    aggregate_version   INT         NOT NULL DEFAULT 1,

    CONSTRAINT audit_scope_pk PRIMARY KEY (scope_id),
    CONSTRAINT audit_scope_defined_by_fk
        FOREIGN KEY (defined_by) REFERENCES admin_user (admin_user_id)
);

COMMENT ON TABLE audit_scope IS
    'BC13. Access scope definition for an auditor engagement. Immutable after '
    'first use (first_used_at IS NOT NULL → no further mutations). '
    'date_range is a TSTZRANGE covering the audit period. '
    'Refs: C19, DL-039, DL-041.';

COMMENT ON COLUMN audit_scope.date_range IS
    'TSTZRANGE covering the period the auditor may access data for. '
    'Must satisfy: lower(date_range) ≤ upper(date_range) and within '
    'retention window — application-layer invariant (ASC.1).';
COMMENT ON COLUMN audit_scope.entity_types IS
    'TEXT[] of aggregate/entity type names the auditor may access '
    '(e.g. ARRAY[''inv_account'',''deal_listing'']).';
COMMENT ON COLUMN audit_scope.first_used_at IS
    'Set atomically when an audit_account first references this scope. '
    'Once set, scope is immutable (ASC.2) — application-layer invariant.';

-- APPLICATION-LAYER INVARIANTS: audit_scope
-- ASC.1  lower(date_range) ≤ upper(date_range) and the range must fall within
--        the 10-year retention window (C1).
-- ASC.2  Once first_used_at IS NOT NULL, no UPDATE on any other column is
--        permitted.  Changes require defining a new audit_scope.

CREATE INDEX idx_audit_scope_defined_by ON audit_scope (defined_by);

-- ---------------------------------------------------------------------------
-- audit_account
-- Just-in-time, time-bound, scoped auditor account. Carries identity_id FK.
-- Proposed by Super Admin; approved by Compliance Reviewer (G21).
-- valid_until: account auto-disables at this timestamp (C19).
-- ---------------------------------------------------------------------------
CREATE TABLE audit_account (
    auditor_account_id  UUID                    NOT NULL,
    -- identity_id: FK → auth_identity. One auditor account per identity.
    identity_id         UUID                    NOT NULL,
    email               TEXT                    NOT NULL,
    scope_id            UUID                    NOT NULL,  -- FK → audit_scope.
    -- valid_until: scheduler fires AutoDisable at this timestamp.
    valid_until         TIMESTAMPTZ             NOT NULL,
    status              audit_account_status    NOT NULL DEFAULT 'proposed',
    proposed_by         UUID                    NOT NULL,  -- FK → admin_user (Super Admin).
    proposed_at         TIMESTAMPTZ             NOT NULL DEFAULT now(),
    -- approved_by: FK → admin_user; must hold compliance_reviewer role; ≠ proposed_by.
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,
    activated_at        TIMESTAMPTZ,
    auto_disabled_at    TIMESTAMPTZ,
    aggregate_version   INT                     NOT NULL DEFAULT 1,

    CONSTRAINT audit_account_pk             PRIMARY KEY (auditor_account_id),
    CONSTRAINT audit_account_identity_uq    UNIQUE (identity_id),
    CONSTRAINT audit_account_scope_fk
        FOREIGN KEY (scope_id) REFERENCES audit_scope (scope_id),
    CONSTRAINT audit_account_proposed_by_fk
        FOREIGN KEY (proposed_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT audit_account_approved_by_fk
        FOREIGN KEY (approved_by) REFERENCES admin_user (admin_user_id),
    CONSTRAINT audit_account_approved_chk
        CHECK (
            (status = 'proposed'
                AND approved_by IS NULL AND approved_at IS NULL)
            OR (status IN ('approved', 'activated', 'auto_disabled')
                AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        ),
    CONSTRAINT audit_account_maker_checker_chk
        CHECK (proposed_by <> approved_by OR approved_by IS NULL),
    CONSTRAINT audit_account_valid_until_chk
        CHECK (valid_until > proposed_at)
);

COMMENT ON TABLE audit_account IS
    'BC13. Just-in-time auditor account. Proposed by Super Admin; approved by '
    'Compliance Reviewer (≠ proposer — record-level maker-checker G21, C4). '
    'valid_until: scheduler fires AutoDisable at this timestamp (C19). '
    'audit_account identity CANNOT share identity_id with any operational '
    'role-holder (admin user with active admin_role_assignment) — application-layer '
    'invariant (C19). Refs: G21, C4, C19, DL-039.';

COMMENT ON COLUMN audit_account.valid_until IS
    'Timestamp at which the scheduler deterministically fires AutoDisable. '
    'Status transitions to auto_disabled at this time (C19).';
COMMENT ON COLUMN audit_account.approved_by IS
    'FK → admin_user; must hold compliance_reviewer role. '
    'Must differ from proposed_by (maker-checker G21). '
    'Enforced by CHECK constraint and application handler.';

-- APPLICATION-LAYER INVARIANTS: audit_account
-- AA13.1  proposed_by must hold an active super_admin admin_role_assignment.
-- AA13.2  approved_by must hold an active compliance_reviewer admin_role_assignment
--         and must differ from proposed_by (maker-checker G21).
-- AA13.3  audit_account identity_id must NOT be the same as any
--         admin_user.identity_id that has at least one active admin_role_assignment
--         (account-level SoD, C19).  The handler cross-checks admin_user
--         and admin_role_assignment at Approve command time.
-- AA13.4  AutoDisable fires deterministically at valid_until; status becomes
--         terminal (auto_disabled). No further state transitions allowed.
-- AA13.5  Every auditor read and export must emit AuditorRead.Performed or
--         AuditorExport.Performed envelope before returning data (C3, DL-039).
-- AA13.6  Export volume is bounded; exceeding the rate limit emits
--         AuditorRateLimit.Triggered and blocks the export (C19).

CREATE INDEX idx_audit_account_identity    ON audit_account (identity_id);
CREATE INDEX idx_audit_account_status      ON audit_account (status);
CREATE INDEX idx_audit_account_valid_until ON audit_account (valid_until)
    WHERE status = 'activated';
CREATE INDEX idx_audit_account_scope       ON audit_account (scope_id);

-- =============================================================================
-- DEFERRED FK CONSTRAINTS
-- BC7/BC8/BC9 tables were created before admin_user (BC10). These ALTER TABLE
-- statements add the remaining FKs now that all tables exist.
-- =============================================================================

-- BC7 backward references
ALTER TABLE inv_invite
    ADD CONSTRAINT inv_invite_issued_by_fk
        FOREIGN KEY (issued_by) REFERENCES admin_user (admin_user_id);

ALTER TABLE inv_account
    ADD CONSTRAINT inv_account_kyc_approved_by_fk
        FOREIGN KEY (kyc_approved_by) REFERENCES admin_user (admin_user_id);

-- BC8 backward references
ALTER TABLE sup_account
    ADD CONSTRAINT sup_account_kyc_approved_by_fk
        FOREIGN KEY (kyc_approved_by) REFERENCES admin_user (admin_user_id);

ALTER TABLE sup_agency_consent
    ADD CONSTRAINT sup_agency_consent_revoked_by_fk
        FOREIGN KEY (revoked_by) REFERENCES admin_user (admin_user_id);

-- BC9 backward references
ALTER TABLE buyer_account
    ADD CONSTRAINT buyer_account_nominated_by_fk
        FOREIGN KEY (nominated_by) REFERENCES admin_user (admin_user_id);

ALTER TABLE buyer_ack_user
    ADD CONSTRAINT buyer_ack_user_designated_by_fk
        FOREIGN KEY (designated_by) REFERENCES admin_user (admin_user_id);

ALTER TABLE buyer_payment_rule
    ADD CONSTRAINT buyer_payment_rule_confirmed_by_fk
        FOREIGN KEY (confirmed_by) REFERENCES admin_user (admin_user_id);

-- BC10 intra-context backward reference
-- (admin_role_assignment defined before admin_deviation_log)
ALTER TABLE admin_role_assignment
    ADD CONSTRAINT admin_role_assignment_deviation_entry_fk
        FOREIGN KEY (deviation_register_entry_id)
            REFERENCES admin_deviation_log (deviation_register_entry_id);

-- =============================================================================
-- END OF 02_counterparty_platform.sql
-- =============================================================================
