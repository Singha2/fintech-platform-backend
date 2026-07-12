-- V7 (DL-BE-064) — genesis SoD policy as static metadata, plus the reserved SYSTEM principal that authors it.
--
-- The Phase-1 SoD policy (which admin-role pairs are strict-blocked vs soft-warned, per DL-033/C5) is FIXED,
-- static reference data — the admin_sod_policy table comment already spells it out. Yet nothing seeded it in
-- any real environment: SodPolicyService.seedDefaultPolicy is called only from tests, and DevDataSeeder writes
-- admin_role_assignment rows directly (bypassing assignRole), so it never needed a policy. Consequence: on a
-- freshly-migrated, non-dev database, RbacService.assignRole FAILS CLOSED ("no active SoD policy — role
-- assignment refused"), making it impossible to grant any admin a role after break-glass bootstrap. Seeding the
-- policy here makes the invariant hold from schema genesis in every environment, deterministically and versioned,
-- rather than depending on an operator action. Later real supersessions still go through the super_admin-gated,
-- audited SodPolicyService.publishSodPolicy, which correctly supersedes this genesis row.
--
-- admin_sod_policy.published_by is NOT NULL → FK admin_user(admin_user_id), and admin_user.identity_id is
-- NOT NULL → FK auth_identity: a migration runs before any human admin exists, so we seed a reserved, well-known
-- SYSTEM principal to author genesis records (reusable as the actor for other system-authored seed data). It is
-- deliberately NON-INTERACTIVE: status 'disabled' and NO auth_credential/auth_mfa_factor rows, so it can never
-- complete the password → OTP login and is never a live actor — it exists only to satisfy accountability FKs.
--
-- Fixed, documented UUIDs (low, ordered) mark these as reserved genesis rows:
--   00000000-0000-0000-0000-000000000001  SYSTEM auth_identity
--   00000000-0000-0000-0000-000000000002  SYSTEM admin_user
--   00000000-0000-0000-0000-000000000003  genesis admin_sod_policy
--
-- Hibernate ddl-auto=validate is unaffected (pure DML; no schema change).

-- The reserved SYSTEM identity — non-interactive (no credentials are ever seeded for it).
INSERT INTO auth_identity (identity_id, kind, email, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'admin_user', 'system@platform.internal',
        'System (genesis)', 'disabled');

-- The reserved SYSTEM admin_user — authors genesis records; 'disabled' so it is never treated as a live admin.
INSERT INTO admin_user (admin_user_id, identity_id, email, display_name, status)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        'system@platform.internal', 'System (genesis)', 'disabled');

-- The Phase-1 fixed SoD policy (DL-033, C5). superseded_by NULL = the current active policy (the partial
-- UNIQUE index uidx_admin_sod_policy_one_active guarantees at most one such row). strict/soft pair-sets mirror
-- SodPolicyService.STRICT_DEFAULT / SOFT_DEFAULT exactly; enforcement_tier is descriptive-only in Phase 1.
INSERT INTO admin_sod_policy (sod_policy_id, strict_pairs, soft_pairs, enforcement_tier, published_by, superseded_by)
VALUES ('00000000-0000-0000-0000-000000000003',
        '[["credit_reviewer","treasury_and_settlement"]]'::jsonb,
        '[["super_admin","compliance_reviewer"],["ops_executive","treasury_and_settlement"],["credit_reviewer","compliance_reviewer"]]'::jsonb,
        'soft_warn_with_log'::admin_sod_enforcement_tier,
        '00000000-0000-0000-0000-000000000002',
        NULL);

COMMENT ON COLUMN admin_sod_policy.published_by IS
    'FK admin_user. The genesis policy (seeded in V7) is authored by the reserved SYSTEM principal '
    '00000000-0000-0000-0000-000000000002; human supersessions name the acting super_admin (DL-BE-064).';
