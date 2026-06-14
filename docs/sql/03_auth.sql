-- =============================================================================
-- 03_auth.sql
-- Authentication layer for the Fintech Invoice Discounting Platform (Phase 1 MVP)
--
-- Scope: five tables only — auth_identity, auth_credential, auth_mfa_factor,
--        auth_otp_challenge, auth_session.
--
-- Decision provenance (do not re-litigate in code review):
--   DL-031 RBAC composable; DL-032 five admin roles; DL-035 MFA mandatory for admins;
--   DL-039 auditor accounts are time-bound; DL-021 buyer ack users are email+OTP;
--   B3 §2.10 AdminUser aggregate; B3 §2.13 AuditorAccount aggregate;
--   B4 §3.5 tenant isolation via session claims; G19 session claims drive C16;
--   Spec §2.4 investor onboarding (email/mobile OTP + PAN/Aadhaar/KYC).
--
-- Design rules baked in:
--   - Auth is a separate layer; identity is the join key into the domain.
--   - One auth_identity row per human/principal; multiple credentials per identity
--     are allowed for investors (password + Google on the same identity).
--   - Buyer acknowledgment users have NO auth_credential row — login is OTP only.
--   - Admins MUST have email+password auth_credential AND TOTP auth_mfa_factor.
--   - Auditors get a provisioned password credential bounded by auth_identity.
--     valid_from/valid_until (time-bound per DL-039 / B3 AA13.4).
--   - One active OTP challenge per (identity_id, purpose) at a time.
--   - Session cookie carries session_id only; mfa_assertion_id on the session row
--     is consulted by every admin state-changing command handler (AU10.3, C7).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. ENUM TYPES
-- -----------------------------------------------------------------------------

CREATE TYPE identity_kind_enum AS ENUM (
    'admin_user',           -- BC10 AdminUser (DL-032 five roles)
    'investor',             -- Spec §2.4
    'acknowledgment_user',  -- BC9 buyer-side ack user (DL-021)
    'auditor'               -- BC13 AuditorAccount (DL-039)
);

CREATE TYPE identity_status_enum AS ENUM (
    'invited',              -- account row created, not yet self-activated
    'active',               -- credential/factor enrolled per kind's rules
    'disabled',             -- soft-off, reversible
    'auto_disabled'         -- auditor time-bound expiry (B3 AA13.4)
);

CREATE TYPE credential_kind_enum AS ENUM (
    'password',             -- bcrypt/argon2 hash held in secret_hash
    'oauth_google'          -- Google OIDC; identity-provider subject in subject_id
);

CREATE TYPE mfa_factor_kind_enum AS ENUM (
    'totp',                 -- DL-035 preferred
    'sms_otp'               -- DL-035 fallback
);

CREATE TYPE otp_purpose_enum AS ENUM (
    'login_mfa',            -- admin MFA at sign-in / sensitive-action; ack-user login
    'phone_verify',         -- onboarding phone proof
    'email_verify',         -- onboarding email proof
    'password_reset'        -- forgot-password flow
);

CREATE TYPE otp_status_enum AS ENUM (
    'active',               -- issued, not yet consumed/expired
    'consumed',             -- successfully verified
    'expired',              -- ttl elapsed without verification
    'superseded'            -- a new active challenge for the same (identity, purpose) replaced it
);

CREATE TYPE session_status_enum AS ENUM (
    'active',
    'revoked',              -- explicit logout / admin kill
    'expired'               -- idle or absolute ttl elapsed
);


-- -----------------------------------------------------------------------------
-- 2. TABLES
-- -----------------------------------------------------------------------------

-- ============================================================================
-- auth_identity — one row per authenticable principal
-- ============================================================================
CREATE TABLE auth_identity (
    identity_id       UUID         PRIMARY KEY,
    kind              identity_kind_enum NOT NULL,
    email             CITEXT       NOT NULL,
    phone_e164        TEXT         NULL,
    display_name      TEXT         NULL,
    status            identity_status_enum NOT NULL DEFAULT 'invited',
    valid_from        TIMESTAMPTZ  NULL,
    valid_until       TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT auth_identity_email_uq UNIQUE (email),

    CONSTRAINT auth_identity_validity_window_ordered
        CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_from <= valid_until),

    CONSTRAINT auth_identity_auditor_must_be_time_bound
        CHECK (kind <> 'auditor' OR (valid_from IS NOT NULL AND valid_until IS NOT NULL)),

    CONSTRAINT auth_identity_phone_required_for_ack_user
        CHECK (kind <> 'acknowledgment_user' OR phone_e164 IS NOT NULL)
);

COMMENT ON TABLE auth_identity IS
    'Authentication-layer identity. One row per authenticable principal regardless of kind. Domain tables (admin_user, inv_account, buyer_ack_user) reference identity_id; sup_account does not (no supplier login in Phase 1).';

COMMENT ON COLUMN auth_identity.identity_id IS
    'Primary identity key. UUIDv7 generated at the application layer (B3 root-identity convention).';
COMMENT ON COLUMN auth_identity.kind IS
    'Discriminator. Determines which domain table holds the FK and which credential/factor rules apply (admin: pwd+TOTP; investor: pwd or Google; ack_user: OTP-only; auditor: provisioned password + time-bound).';
COMMENT ON COLUMN auth_identity.email IS
    'Globally unique login identifier across all kinds (DL-031, AU10.1). CITEXT for case-insensitive uniqueness; storage normalised at write time.';
COMMENT ON COLUMN auth_identity.phone_e164 IS
    'E.164-formatted phone, required for acknowledgment_user (DL-021 email+OTP-only login uses both channels for OTP delivery resilience). Optional for other kinds.';
COMMENT ON COLUMN auth_identity.valid_from IS
    'Activation lower bound; for auditors this is the AccessScope window start (B3 AA13.4). NULL for non-time-bound kinds.';
COMMENT ON COLUMN auth_identity.valid_until IS
    'Activation upper bound; for auditors this is the AccessScope window end after which the scheduler sets status=auto_disabled (B3 AA13.4, DL-039). NULL for non-time-bound kinds.';
COMMENT ON COLUMN auth_identity.status IS
    'Lifecycle state of the identity itself. Distinct from the domain account''s onboarding state machine — an investor may be identity.status=active but inv_account.status=kyc_in_review.';

COMMENT ON CONSTRAINT auth_identity_auditor_must_be_time_bound ON auth_identity IS
    'Auditor identities MUST be time-bound (DL-039, B3 AA13.4). The scheduler-driven AutoDisable command depends on valid_until being present.';
COMMENT ON CONSTRAINT auth_identity_phone_required_for_ack_user ON auth_identity IS
    'Acknowledgment users authenticate via email+OTP only (DL-021). Phone is captured at designation (BC9 AcknowledgmentUser.Designate) to enable SMS-OTP fallback if email delivery fails.';


-- ============================================================================
-- auth_credential — long-lived authentication material
-- ============================================================================
CREATE TABLE auth_credential (
    credential_id     UUID         PRIMARY KEY,
    identity_id       UUID         NOT NULL REFERENCES auth_identity(identity_id) ON DELETE RESTRICT,
    kind              credential_kind_enum NOT NULL,
    secret_hash       TEXT         NULL,
    subject_id        TEXT         NULL,
    last_used_at      TIMESTAMPTZ  NULL,
    revoked_at        TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT auth_credential_password_shape
        CHECK (kind <> 'password' OR (secret_hash IS NOT NULL AND subject_id IS NULL)),

    CONSTRAINT auth_credential_oauth_google_shape
        CHECK (kind <> 'oauth_google' OR (subject_id IS NOT NULL AND secret_hash IS NULL)),

    CONSTRAINT auth_credential_oauth_subject_uq
        UNIQUE (kind, subject_id)
);

COMMENT ON TABLE auth_credential IS
    'Long-lived authentication material. One identity_id may carry multiple credentials (e.g. an investor with both a password and Google OAuth — same identity_id, two rows). Buyer acknowledgment users have ZERO rows here (DL-021: email+OTP only). Admins have exactly one password row; their MFA factor lives in auth_mfa_factor.';

COMMENT ON COLUMN auth_credential.credential_id IS 'Surrogate key. UUIDv7.';
COMMENT ON COLUMN auth_credential.identity_id IS 'Identity owning this credential. ON DELETE RESTRICT — identities are archived, never hard-deleted, to preserve audit-log references (C1, DL-040).';
COMMENT ON COLUMN auth_credential.kind IS 'Credential mechanism. Drives which of secret_hash / subject_id is populated (enforced by CHECK).';
COMMENT ON COLUMN auth_credential.secret_hash IS 'Argon2id / bcrypt hash for password credentials. Application is responsible for the algorithm, parameters, and pepper. NULL for oauth_google.';
COMMENT ON COLUMN auth_credential.subject_id IS 'Identity-provider subject (Google ''sub'' claim) for oauth_google. Unique per (kind, subject_id) so the same Google account cannot be linked to two platform identities. NULL for password.';
COMMENT ON COLUMN auth_credential.last_used_at IS 'Stamped on every successful authentication via this credential. Used by anomaly detection and credential-rotation hygiene reports.';
COMMENT ON COLUMN auth_credential.revoked_at IS 'Soft-revocation timestamp. A revoked row is retained for audit; the partial-unique constraints above only apply to non-revoked rows so a user may rotate credentials.';

COMMENT ON CONSTRAINT auth_credential_password_shape ON auth_credential IS
    'Password credentials carry secret_hash and never subject_id.';
COMMENT ON CONSTRAINT auth_credential_oauth_google_shape ON auth_credential IS
    'OAuth Google credentials carry the provider subject_id and never a secret_hash.';
COMMENT ON CONSTRAINT auth_credential_oauth_subject_uq ON auth_credential IS
    'A single Google subject cannot map to two platform identities. Across revoked + active rows this also blocks "transferring" a Google linkage between identities.';


-- ============================================================================
-- auth_mfa_factor — second factors registered against an identity
-- ============================================================================
CREATE TABLE auth_mfa_factor (
    factor_id         UUID         PRIMARY KEY,
    identity_id       UUID         NOT NULL REFERENCES auth_identity(identity_id) ON DELETE RESTRICT,
    kind              mfa_factor_kind_enum NOT NULL,
    secret_encrypted  BYTEA        NULL,
    label             TEXT         NULL,
    enrolled_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at      TIMESTAMPTZ  NULL,
    revoked_at        TIMESTAMPTZ  NULL,

    CONSTRAINT auth_mfa_factor_totp_has_secret
        CHECK (kind <> 'totp' OR secret_encrypted IS NOT NULL)
);

COMMENT ON TABLE auth_mfa_factor IS
    'Registered MFA factors. Admin identities MUST have at least one active row here before identity.status moves to ''active'' (DL-035, AU10.2 — enforced in application). Investors and ack users may optionally enrol MFA; auditors are not required to.';

COMMENT ON COLUMN auth_mfa_factor.kind IS 'TOTP preferred for admins (DL-035); SMS-OTP is fallback. SMS-OTP factor row exists to record enrolment intent; the actual one-time codes flow through auth_otp_challenge.';
COMMENT ON COLUMN auth_mfa_factor.secret_encrypted IS 'TOTP shared secret, encrypted at the application layer with a KMS-managed key (Spec §7.3). NULL for sms_otp (the phone number lives on auth_identity).';
COMMENT ON COLUMN auth_mfa_factor.label IS 'User-friendly factor label (e.g. "Authenticator on Pixel 7"). Surfaced in the admin self-service factor list.';
COMMENT ON COLUMN auth_mfa_factor.revoked_at IS 'Soft-removal timestamp. Revoking the last active factor for an admin must trigger application-layer status downgrade — see APPLICATION-LAYER INVARIANTS.';


-- ============================================================================
-- auth_otp_challenge — short-lived one-time codes for four purposes
-- ============================================================================
CREATE TABLE auth_otp_challenge (
    challenge_id      UUID         PRIMARY KEY,
    identity_id       UUID         NOT NULL REFERENCES auth_identity(identity_id) ON DELETE RESTRICT,
    purpose           otp_purpose_enum NOT NULL,
    code_hash         TEXT         NOT NULL,
    delivery_channel  TEXT         NOT NULL,
    attempts          SMALLINT     NOT NULL DEFAULT 0,
    max_attempts      SMALLINT     NOT NULL DEFAULT 5,
    issued_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ  NOT NULL,
    consumed_at       TIMESTAMPTZ  NULL,
    status            otp_status_enum NOT NULL DEFAULT 'active',
    assertion_id      UUID         NULL,

    CONSTRAINT auth_otp_challenge_expiry_after_issue
        CHECK (expires_at > issued_at),

    CONSTRAINT auth_otp_challenge_attempts_nonneg
        CHECK (attempts >= 0 AND attempts <= max_attempts),

    CONSTRAINT auth_otp_challenge_consumed_implies_status
        CHECK ((consumed_at IS NULL) = (status <> 'consumed')),

    CONSTRAINT auth_otp_challenge_assertion_only_when_consumed
        CHECK (assertion_id IS NULL OR status = 'consumed')
);

COMMENT ON TABLE auth_otp_challenge IS
    'One-time codes spanning four purposes: login_mfa, phone_verify, email_verify, password_reset. Exactly one active challenge per (identity_id, purpose) — issuing a new one supersedes the prior (application transitions the prior to status=superseded before insert). The assertion_id minted on consumption is what auth_session.mfa_assertion_id references for login_mfa.';

COMMENT ON COLUMN auth_otp_challenge.purpose IS 'Why this OTP exists. The four purposes are the complete Phase 1 set; new purposes require enum-extension and a migration.';
COMMENT ON COLUMN auth_otp_challenge.code_hash IS 'Hash of the OTP code (never the cleartext). The plaintext is delivered to the user once and never persisted.';
COMMENT ON COLUMN auth_otp_challenge.delivery_channel IS 'Which channel the code was dispatched to: email | sms. Free-text rather than enum since BC15 owns channel semantics and may extend.';
COMMENT ON COLUMN auth_otp_challenge.attempts IS 'Wrong-code verification attempts to date. On reaching max_attempts the application transitions status to expired and forces re-issuance.';
COMMENT ON COLUMN auth_otp_challenge.expires_at IS 'Hard TTL. Typical values: login_mfa 5m; phone_verify 10m; email_verify 30m; password_reset 30m — final values owned by BC10 policy.';
COMMENT ON COLUMN auth_otp_challenge.assertion_id IS 'For purpose=login_mfa: the assertion identifier minted when this challenge is consumed. Referenced by auth_session.mfa_assertion_id and by every admin envelope''s actor.mfa_assertion_id (B2 §2.2, AU10.3). NULL for non-login_mfa purposes.';

COMMENT ON CONSTRAINT auth_otp_challenge_consumed_implies_status ON auth_otp_challenge IS
    'consumed_at and status=''consumed'' are tied together — neither can be set without the other.';


-- ============================================================================
-- auth_session — authenticated session state
-- ============================================================================
CREATE TABLE auth_session (
    session_id        UUID         PRIMARY KEY,
    identity_id       UUID         NOT NULL REFERENCES auth_identity(identity_id) ON DELETE RESTRICT,
    issued_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    idle_expires_at  TIMESTAMPTZ  NOT NULL,
    absolute_expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ  NULL,
    status            session_status_enum NOT NULL DEFAULT 'active',
    mfa_assertion_id  UUID         NULL,
    tenant_claims     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    client_ip         INET         NULL,
    user_agent        TEXT         NULL,

    CONSTRAINT auth_session_absolute_after_issue
        CHECK (absolute_expires_at > issued_at),

    CONSTRAINT auth_session_idle_within_absolute
        CHECK (idle_expires_at <= absolute_expires_at),

    CONSTRAINT auth_session_revoked_implies_status
        CHECK ((revoked_at IS NULL) OR (status = 'revoked'))
);

COMMENT ON TABLE auth_session IS
    'Authenticated session. The cookie carries session_id ONLY — every other field (identity, claims, mfa assertion) is loaded server-side from this row. tenant_claims is the C16 enforcement input consumed by the repository layer (B4 §3.5, G19). mfa_assertion_id is consulted by every admin state-changing command handler (AU10.3, C7).';

COMMENT ON COLUMN auth_session.session_id IS 'Opaque session identifier. The sole value carried in the session cookie; cookie is HttpOnly, Secure, SameSite=Lax (Spec §7.3).';
COMMENT ON COLUMN auth_session.idle_expires_at IS 'Rolling idle timeout. Refreshed on each successful request; if now() > idle_expires_at the application transitions status to expired.';
COMMENT ON COLUMN auth_session.absolute_expires_at IS 'Hard ceiling. The session cannot be extended past this point regardless of activity; re-authentication is required.';
COMMENT ON COLUMN auth_session.mfa_assertion_id IS 'For admin sessions: references the consumed auth_otp_challenge.assertion_id from the most-recent successful TOTP/SMS verification. Admin command handlers reject when this is NULL or when the underlying challenge''s consumed_at is older than the policy freshness window (B4 §6.4 — 5 minutes for sensitive actions, 30 minutes for normal).';
COMMENT ON COLUMN auth_session.tenant_claims IS 'IAM-issued claims serialised at session establishment (B2 §3.10 TenantClaim.Issued, G19). For investor: {investor_id}; supplier_user: {supplier_id} (dormant in Phase 1); ack_user: {buyer_id}; admin: {roles:[…]}; auditor: {scope_id}. Repository injects these as query predicates — never trusted from client input.';
COMMENT ON COLUMN auth_session.client_ip IS 'IP at session issuance, captured for audit (C1) and anomaly detection. Not used for binding — sessions survive IP changes.';

COMMENT ON CONSTRAINT auth_session_idle_within_absolute ON auth_session IS
    'Idle expiry cannot exceed the absolute expiry — the absolute is the hard ceiling.';


-- -----------------------------------------------------------------------------
-- 3. ALTER TABLE — adds identity_id FOREIGN KEY to domain tables
--    The identity_id column and UNIQUE constraint are already defined in
--    each domain table's CREATE TABLE in 02_counterparty_platform.sql.
--    This section ONLY adds the FK to auth_identity — do NOT re-add the
--    column or UNIQUE (those ALTERs removed per schema-fix session).
--    (admin_user, inv_account, buyer_ack_user only — DL-012 keeps
--     sup_account login-less in Phase 1, so no FK there.)
--    FLYWAY ORDERING: this file must run AFTER 01_core.sql and
--    02_counterparty_platform.sql (FK targets must exist first).
-- -----------------------------------------------------------------------------

-- identity_id column + admin_user_identity_uq UNIQUE already defined in
-- 02_counterparty_platform.sql CREATE TABLE admin_user. FK only added here.
ALTER TABLE admin_user
    ADD CONSTRAINT admin_user_identity_fk
        FOREIGN KEY (identity_id) REFERENCES auth_identity(identity_id) ON DELETE RESTRICT;

COMMENT ON COLUMN admin_user.identity_id IS
    'Auth-layer join key. The admin''s auth_identity row carries kind=admin_user. Domain row carries roles, SoD acknowledgements, deviation register links (B3 §2.10).';


-- identity_id column + inv_account_identity_uq UNIQUE already defined in
-- 02_counterparty_platform.sql CREATE TABLE inv_account. FK only added here.
ALTER TABLE inv_account
    ADD CONSTRAINT inv_account_identity_fk
        FOREIGN KEY (identity_id) REFERENCES auth_identity(identity_id) ON DELETE RESTRICT;

COMMENT ON COLUMN inv_account.identity_id IS
    'Auth-layer join key. The investor''s auth_identity row carries kind=investor. Domain row carries the onboarding state machine (Spec §6.3) and the suitability/financial-profile chain).';


-- identity_id column + buyer_ack_user_identity_uq UNIQUE already defined in
-- 02_counterparty_platform.sql CREATE TABLE buyer_ack_user. FK only added here.
ALTER TABLE buyer_ack_user
    ADD CONSTRAINT buyer_ack_user_identity_fk
        FOREIGN KEY (identity_id) REFERENCES auth_identity(identity_id) ON DELETE RESTRICT;

COMMENT ON COLUMN buyer_ack_user.identity_id IS
    'Auth-layer join key. The ack user''s auth_identity row carries kind=acknowledgment_user; no auth_credential row exists for it (DL-021: email+OTP login only). The buyer relationship lives on the domain row (BC9 AU.1: belongs to exactly one buyer).';


-- -----------------------------------------------------------------------------
-- 4. INDEXES
-- -----------------------------------------------------------------------------

-- Identity lookup by email (login path for every kind)
-- The UNIQUE constraint on auth_identity.email already provides this index; no
-- duplicate index is created. Documented here for review traceability.

-- Identity lookup by kind (admin/auditor listing screens, scheduled jobs)
CREATE INDEX idx_auth_identity_kind_status
    ON auth_identity (kind, status);

-- Auditor auto-disable scheduler scan: identities expiring soon
CREATE INDEX idx_auth_identity_valid_until
    ON auth_identity (valid_until)
    WHERE valid_until IS NOT NULL AND status = 'active';

-- At most one active password credential per identity (replaces EXCLUDE USING btree)
CREATE UNIQUE INDEX uidx_auth_credential_one_password_per_identity
    ON auth_credential (identity_id)
    WHERE kind = 'password' AND revoked_at IS NULL;
COMMENT ON INDEX uidx_auth_credential_one_password_per_identity IS
    'Implements the "an investor may have password OR Google OR both" rule (the brief): at most one *active* password per identity. Rotation works by inserting a new row after revoking the old.';

-- At most one active Google OAuth linkage per identity
CREATE UNIQUE INDEX uidx_auth_credential_one_google_per_identity
    ON auth_credential (identity_id)
    WHERE kind = 'oauth_google' AND revoked_at IS NULL;
COMMENT ON INDEX uidx_auth_credential_one_google_per_identity IS
    'Symmetric to the password rule: at most one active Google linkage per identity. Re-linking to a different Google account requires revoking the old row first.';

-- At most one active TOTP factor per identity
CREATE UNIQUE INDEX uidx_auth_mfa_factor_one_active_totp_per_identity
    ON auth_mfa_factor (identity_id)
    WHERE kind = 'totp' AND revoked_at IS NULL;
COMMENT ON INDEX uidx_auth_mfa_factor_one_active_totp_per_identity IS
    'One active TOTP secret per identity. Re-enrolment requires revoking the previous secret first; this prevents quiet-shadow-enrolment attacks.';

-- At most one active OTP challenge per (identity, purpose)
CREATE UNIQUE INDEX uidx_auth_otp_challenge_one_active_per_identity_purpose
    ON auth_otp_challenge (identity_id, purpose)
    WHERE status = 'active';
COMMENT ON INDEX uidx_auth_otp_challenge_one_active_per_identity_purpose IS
    'The brief: one active challenge per (identity_id, purpose). Re-issuing for the same purpose requires the application to first move the prior row to status=superseded so the partial index permits the insert.';

-- Credential lookups
CREATE INDEX idx_auth_credential_identity
    ON auth_credential (identity_id)
    WHERE revoked_at IS NULL;

-- OAuth subject lookup at OAuth callback time:
-- find which platform identity (if any) is linked to the Google sub.
-- The UNIQUE (kind, subject_id) constraint above provides this index; this
-- partial index narrows the hot path to non-revoked rows.
CREATE INDEX idx_auth_credential_oauth_subject_active
    ON auth_credential (subject_id)
    WHERE kind = 'oauth_google' AND revoked_at IS NULL;

-- MFA factor lookup at challenge issuance
CREATE INDEX idx_auth_mfa_factor_identity_active
    ON auth_mfa_factor (identity_id, kind)
    WHERE revoked_at IS NULL;

-- OTP challenge lookup: verify code by (identity, purpose, active)
CREATE INDEX idx_auth_otp_challenge_lookup
    ON auth_otp_challenge (identity_id, purpose)
    WHERE status = 'active';

-- OTP expiry sweep (background job moves expired actives to status=expired)
CREATE INDEX idx_auth_otp_challenge_expiry_sweep
    ON auth_otp_challenge (expires_at)
    WHERE status = 'active';

-- Session lookup by id is via PK. Index for the "all active sessions for an
-- identity" admin screen (revoke-all, anomaly review).
CREATE INDEX idx_auth_session_identity_active
    ON auth_session (identity_id)
    WHERE status = 'active';

-- Session idle/absolute expiry sweep
CREATE INDEX idx_auth_session_expiry_sweep
    ON auth_session (idle_expires_at)
    WHERE status = 'active';


-- -----------------------------------------------------------------------------
-- APPLICATION-LAYER INVARIANTS
-- -----------------------------------------------------------------------------
-- The following rules are not expressible in SQL (or are expressible only at
-- a cost that distorts the schema) and must be enforced in code. Each is
-- cross-referenced to the design artefact that owns it.
--
-- A1. KIND/CREDENTIAL/FACTOR MATRIX
--     The combinations of auth_identity.kind, auth_credential rows, and
--     auth_mfa_factor rows MUST satisfy:
--
--       admin_user           : exactly 1 active password auth_credential
--                              AND at least 1 active TOTP auth_mfa_factor
--                              (DL-035, AU10.2).
--       investor             : at least 1 active auth_credential row, kind in
--                              {password, oauth_google}; both allowed on
--                              the same identity_id.
--       acknowledgment_user  : ZERO auth_credential rows; ZERO auth_mfa_factor rows.
--                              Login is OTP-only via auth_otp_challenge
--                              purpose=login_mfa (DL-021).
--       auditor              : exactly 1 active password auth_credential
--                              provisioned by Super Admin; the identity row
--                              MUST carry valid_from/valid_until and the
--                              scheduler MUST transition status to
--                              auto_disabled at valid_until (DL-039, AA13.4).
--
--     A CHECK constraint cannot reach across tables; this matrix is enforced
--     by command handlers in BC10 (admin), BC7 (investor), BC9 (ack user),
--     BC13 (auditor).
--
-- A2. ADMIN ACTIVATION GATE
--     identity.status MAY transition to 'active' for kind=admin_user ONLY
--     when at least one active auth_mfa_factor row exists for that identity_id
--     (AU10.2, DL-035). If the last active MFA factor is revoked, the
--     application MUST downgrade the identity status (or block the revoke).
--
-- A3. MFA-ASSERTION FRESHNESS
--     Every admin state-changing command handler MUST verify that
--     session.mfa_assertion_id IS NOT NULL AND points to an auth_otp_challenge
--     row with purpose=login_mfa, status=consumed, and consumed_at within
--     the policy freshness window (B4 §6.4: 5 minutes for sensitive actions,
--     30 minutes for ordinary admin actions). Stale or missing → reject with
--     401 mfa_assertion_expired / mfa_assertion_missing (B4 §4.2, AU10.3).
--
-- A4. OTP CHALLENGE SUPERSESSION
--     Before INSERTing a new auth_otp_challenge for an existing
--     (identity_id, purpose) that already has an 'active' row, the handler
--     MUST UPDATE the prior row to status='superseded'. The partial-unique
--     index will otherwise reject the insert (which is the safety net, not
--     the happy path).
--
-- A5. OAUTH LINKING DECISION
--     At Google OAuth callback, if the Google sub matches an existing
--     auth_credential row, log in as that identity. If the sub is unknown but
--     the email matches an existing auth_identity, the application — NOT the
--     database — decides whether to auto-link (per investor onboarding
--     policy, Spec §2.4) or require explicit linking via password.
--     Auto-link is permitted ONLY for kind=investor; never for admin_user
--     or auditor.
--
-- A6. AUDITOR AUTO-DISABLE
--     A scheduler MUST scan auth_identity for kind='auditor' AND
--     status='active' AND valid_until <= now() and transition to
--     status='auto_disabled', revoking all active auth_session rows for that
--     identity_id in the same transaction (DL-039, AA13.4). The DB-level
--     CHECK only guarantees the time-bound window is present; deterministic
--     expiry is the scheduler''s job.
--
-- A7. TENANT-CLAIM ISSUANCE
--     auth_session.tenant_claims MUST be populated by the IAM layer at session
--     establishment from the canonical aggregate state (admin RoleAssignments,
--     investor_id, buyer_id, scope_id). The claim set is a serialised
--     snapshot — role changes mid-session do NOT mutate the session row;
--     they take effect at next session establishment (G19).
--
-- A8. CREDENTIAL-REVOCATION CASCADE
--     Revoking any auth_credential row or auth_mfa_factor row for an identity
--     SHOULD trigger revocation of that identity''s active auth_session rows.
--     This is application policy, not DB enforcement, because some flows
--     (password rotation by the user themselves) intentionally preserve the
--     current session.
--
-- A9. SUPPLIER HAS NO IDENTITY (PHASE 1)
--     There is intentionally no identity_id column on sup_account
--     (DL-012, DL-013: supplier work in Phase 1 is admin-on-behalf via
--     AgencyConsent). Adding supplier login is a Phase 2 schema change,
--     not an application toggle.
--
-- A10. RATE LIMITING
--     OTP issuance, password attempts, and OAuth callback rate limits are
--     enforced in code/middleware against a separate rate-limit store. Not
--     modelled here.
-- -----------------------------------------------------------------------------
