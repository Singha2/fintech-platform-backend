-- V8 (DL-BE-066, M16 Tax) — effective-dated TDS default-rate reference table.
--
-- Founder decision M16-Q2 (docs/M16-tax-founder-decisions.md, 2026-07-12): the TDS rate is 10% with a
-- verified PAN and 20% without (§206AA). These MUST NOT be hardcoded in Java — government rates move with
-- every Budget. This table is the *default source*: one authoritative row per (financial year × PAN status).
-- A new FY, or a mid-scheme rate change, is a new seed row in a later migration — versioned and auditable,
-- never a code change.
--
-- Freezing (the load-bearing part): this table only supplies the DEFAULT. When an investor's FY is first
-- touched, the resolved rate is STAMPED onto tax_year_profile.tds_rate_bps (which already exists, V2) and is
-- immutable thereafter. So changing FY2027-28's default here never disturbs an FY2026-27 Form 16A already
-- issued — the certificate always reconciles to the rate actually deducted (DL-045, G4).
--
-- Future seams kept open, NOT built now: rate_bps is the all-in effective rate, so surcharge/cess is
-- expressible without a schema change; a §197 lower-deduction certificate is a per-investor override on
-- tax_year_profile.tds_rate_bps, not here.
--
-- Hibernate ddl-auto=validate: pure additive DDL + DML; the entity layer maps this table read-only.

CREATE TABLE tax_rate_default (
    -- fy_code: Indian financial year, e.g. 'FY2026-27'. Format enforced by the application.
    fy_code         TEXT        NOT NULL,
    -- pan_verified: the PAN-status band this default applies to (TRUE = valid PAN on file).
    pan_verified    BOOLEAN     NOT NULL,
    -- rate_bps: the all-in effective TDS rate in basis points (1000 = 10%). Absorbs any surcharge/cess.
    rate_bps        bps_type    NOT NULL,
    -- effective_from: the date this default takes effect (documentary; resolution is by fy_code).
    effective_from  DATE        NOT NULL,
    note            TEXT,

    CONSTRAINT tax_rate_default_pk PRIMARY KEY (fy_code, pan_verified)
);

COMMENT ON TABLE tax_rate_default IS
    'M16/BC12. Effective-dated DEFAULT TDS rate per (financial year x PAN status). Source of the rate that '
    'is stamped + frozen onto tax_year_profile.tds_rate_bps at first resolution. One row per (fy_code, '
    'pan_verified). Add a new migration to introduce a new FY or amend a rate (DL-BE-066, founder M16-Q2).';
COMMENT ON COLUMN tax_rate_default.rate_bps IS
    'All-in effective TDS rate in basis points (1000 = 10%, 2000 = 20% under 206AA). Includes surcharge/cess.';
COMMENT ON COLUMN tax_rate_default.pan_verified IS
    'TRUE = the band for investors with a verified PAN (standard rate); FALSE = the 206AA higher-rate band.';

-- Seed FY2026-27 (founder M16-Q2): 10% with PAN, 20% without. effective_from = FY start (2026-04-01).
INSERT INTO tax_rate_default (fy_code, pan_verified, rate_bps, effective_from, note) VALUES
    ('FY2026-27', TRUE,  1000, '2026-04-01', '194A standard rate with verified PAN (founder M16-Q2)'),
    ('FY2026-27', FALSE, 2000, '2026-04-01', '206AA higher rate, PAN absent (founder M16-Q2)');
