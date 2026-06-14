# Fintech Platform — SQL Schema Bundle Manifest

> Canonical schema source of truth for the invoice-discounting platform (see `CLAUDE.md`).
> 4 files · **52 tables** · ported to Flyway migrations under `src/main/resources/db/migration`.
>
> **Audited 2026-06-13** — see [`PRE_MIGRATION_AUDIT.md`](./PRE_MIGRATION_AUDIT.md) for the full
> findings and the fixes applied before migration. This manifest was rewritten as part of that
> audit (the previous version described generic "Account management / Transaction processing" and
> a fabricated FK-ordering rationale — both were factually wrong).

## Bundle contents

### 1. `01_core.sql` — Core deal lifecycle (BC1–BC6) · 17 tables
Shared value-object **domains** (`pan_type`, `gstin_type`, `ifsc_type`, `irn_type`,
`aadhaar_last4_type`, `money_paise`, `positive_money_paise`, `bps_type`) + the `citext` extension,
then the core financial-domain tables:
- **Deals & listings:** `deal_invoice`, `deal_listing`
- **Subscriptions:** `sub_subscription`
- **Risk & pricing:** `risk_buyer_profile`, `risk_supplier_profile`, `risk_pricing_policy`, `risk_default_case`
- **Cash & settlement:** `cash_virtual_account`, `cash_payout_instruction`, `cash_recon_ledger`, `cash_remediation_case`
- **Legal & assignment:** `legal_master_agreement`, `legal_assignment_set`, `legal_signature_request`
- **Collections:** `col_maturity_case`, `col_action_log`, `col_claim_case`

### 2. `02_counterparty_platform.sql` — Counterparties, admin, compliance, tax (BC7–BC13) · 23 tables
- **Investor:** `inv_invite`, `inv_account`, `inv_suitability`
- **Supplier:** `sup_account`, `sup_agency_consent`, `sup_financial_profile`
- **Buyer:** `buyer_account`, `buyer_ack_user`, `buyer_payment_rule`
- **Admin & segregation-of-duties:** `admin_user`, `admin_role_assignment`, `admin_sod_policy`, `admin_deviation_log`
- **Compliance (KYC/AML/SAR):** `comp_kyc_file`, `comp_aml_screening`, `comp_sar_case`, `comp_spot_check`, `comp_refresh_schedule`
- **Tax (TDS):** `tax_year_profile`, `tax_tds_deduction`, `tax_investor_statement`
- **Audit accounts:** `audit_account`, `audit_scope`

### 3. `03_auth.sql` — Identity & MFA · 5 tables
`auth_identity`, `auth_credential`, `auth_mfa_factor`, `auth_otp_challenge`, `auth_session`.
All FKs are internal to `auth_identity`. `auth_otp_challenge.assertion_id` is the
`mfa_assertion_id` that the rest of the platform's audit envelopes reference (non-negotiable #2,
MFA-fresh).

### 4. `04_generic_acl.sql` — Cross-cutting platform services + integration ACLs (BC15–BC19) · 7 tables
- **Audit chain:** `sys_audit_event` (immutable, hash-chained event envelope)
- **Documents (BC16):** `sys_document_object`
- **Notifications (BC15):** `sys_notification_dispatch`
- **Integration ACL ports:** `gate_verification` (BC17), `gate_vendor_instruction` (BC18),
  `gate_inflow_observation`, `gate_signature_session` (BC19)

## Load order (verified)

```
01_core.sql  →  02_counterparty_platform.sql  →  03_auth.sql  →  04_generic_acl.sql
```

**Rationale (corrected — the old rationale was fabricated):** There are **zero hard inter-file
foreign-key constraints** in the bundle. Every FK is intra-file (e.g. `03_auth` FKs all target its
own `auth_identity`; `04`'s only cross-row FK is the `sys_audit_event.corrects` self-reference).
Tables in `02` reference other contexts (`auth_identity`, `deal_listing`, …) via **soft
`identity_id` / `*_id` columns with no `REFERENCES` clause** — the bounded-context isolation rule.

The **one real cross-file dependency is type/extension availability, not FK ordering**:
`01_core.sql` defines the shared `DOMAIN` value-object types and the `citext` extension that
`02`, `03`, and `04` consume in column definitions. Running any of them before `01` fails with
`type "…" does not exist`. Hence `01` must run first; `02`/`03`/`04` have no hard ordering among
themselves, but the canonical `01→02→03→04` sequence (baked into `04`'s own header) is kept.

**Flyway mapping:** `V1__core` · `V2__counterparty_platform` · `V3__auth` · `V4__generic_acl`.
Domains/enums stay co-located in `V1` (single producer; every consumer follows). `ddl-auto=validate`
only — Hibernate never alters the schema.

## Notes
- Money is paise (`BIGINT` via `money_paise`/`positive_money_paise`). Rates are bps (`bps_type`).
  Time is `TIMESTAMPTZ`. Never floats for money.
- **No procedural triggers.** Invariants are enforced declaratively (CHECK/FK/NOT NULL/UNIQUE/
  domains/enums) + GRANT/REVOKE. `updated_at` is app-owned. See `DECISION_LOG.md` (`DL-BE`).
- For deployment, run via Flyway, not raw `psql`. The raw-`psql` instructions in the old manifest
  are superseded by the migration pipeline.

---
Rewritten: 2026-06-13 (pre-migration audit) · supersedes the 2026-05-24 auto-generated manifest.
