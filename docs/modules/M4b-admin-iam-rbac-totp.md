# M4b · Admin IAM + RBAC + TOTP enrollment

> **Lean module spec** — *foundation-critical* (admin identity lifecycle, the TOTP factor C7 requires,
> and the role-authorization every admin command checks): light ceremony, heavy invariant rigor.
> See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M4 — Admin IAM + Maker-Checker + SoD (BC10, BC16) |
| **Slice** | M4b — admin user lifecycle, TOTP enrollment (AU10.1), composable RBAC + command authorization |
| **Tier** | Foundation-critical (light ceremony · heavy IAM-invariant rigor) |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-019) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> M4 is sliced **M4a → M4b → M4c**: **M4a** (Done) = the command substrate; **M4b** (this) = Admin IAM
> + RBAC + TOTP; **M4c** = SoD + maker-checker. M4b gives the M4a command boundary its **real
> role-authorization** (closing M4a's `disableAdminUser` authz gap) and builds the **TOTP factor that
> AU10.1 gates admin activation on** (C7 "TOTP preferred"). It runs **every command through the M4a
> `CommandGateway`**, inheriting idempotency (#4), MFA-freshness (#2), and audit (#5) for free. SoD
> enforcement on role-assign (#3) and maker-checker (#1) are **M4c** — M4b writes role assignments
> *without* the SoD gate, behind a guardrail.

## 1. Scope
**Owns:**
- **TOTP enrollment (C7, AU10.1)** — `enrollTotp` generates a secret, app-layer-encrypts it into
  `auth_mfa_factor.secret_encrypted` (kind `totp`); `confirmTotp` verifies an RFC-6238 code and stamps
  `last_used_at` (the "confirmed" signal). An admin is MFA-capable once it has ≥1 active
  (`revoked_at IS NULL`) confirmed (`last_used_at IS NOT NULL`) TOTP factor.
- **Admin user lifecycle** (commands through the M4a gateway) — `provisionAdminUser` (creates the
  `auth_identity` + `admin_user` row, `status=invited`), `activateAdminUser` (`invited→active`,
  **gated on AU10.1**), `disableAdminUser` / `enableAdminUser` (extends M4a's disable with authz).
- **Composable RBAC (C18, DL-032)** — `assignRole` / `revokeRole` (`admin_role_assignment`), and
  `activeRoles(adminUserId) → Set<AdminRole>` = the **union** of active assignments. The five roles
  (`ops_executive`, `credit_reviewer`, `compliance_reviewer`, `treasury_and_settlement`, `super_admin`)
  are the Phase-1 permission units.
- **Command authorization at the boundary** — extend the M4a `CommandGateway` with an authz gate: a
  command declares the role(s) that may issue it; the gateway rejects when the actor's `activeRoles`
  don't intersect (`role_not_held`, 403, **no envelope** — G22). `disableAdminUser` now requires
  `super_admin`.
- **`SecretCipher` port** + dev stub (AES-GCM with a config key) for `secret_encrypted`; real KMS at
  Production (mirrors the BC15 ACL-port pattern).
- **Events:** `AdminUser.Created`, `Mfa.Enrolled`, `AdminUser.Activated`, `Role.Assigned`,
  `Role.Revoked`, `AdminUser.Disabled` — each via the gateway envelope (#5).

**Does NOT own (deferred / other slice):**
- **SoD enforcement on role-assign (#3)** — strict block (credit_reviewer ⊕ treasury_and_settlement)
  + soft warn-with-deviation-log (RA.1–RA.4), `admin_sod_policy`, `admin_deviation_log` → **M4c**.
  M4b writes `admin_role_assignment` **without** the SoD gate. **Guardrail:** role-assign must not be
  exposed (and SoD must gate it) before M4c.
- **Maker-checker (#1)** on provisioning / role-assign → **M4c**.
- **TOTP-at-login assertion minting** — M4b enrolls the factor (satisfies AU10.1); minting an
  `mfa_assertion` from a TOTP code at login is a small follow-up. Admins authenticate via the **SMS-OTP
  fallback** (M3a) meanwhile; the gateway is factor-agnostic.
- **Auditor accounts** (BC13, account-level SoD RA.4/C19) → **M17**; investor/ack onboarding → their
  modules.
- **HTTP layer** (header parsing, the cookie filter) → Walking Skeleton. M4b is the service substrate.

## 2. Upstream dependencies
- **M4a command substrate** — Done (every M4b command runs through `CommandGateway`; M4b adds the authz
  gate to it). **M3a/M3b** (identity primitives, sessions, `isMfaFresh`), **M2** (audit), **M1a/b** — Done.
- **M0 schema** — Done: `admin_user`, `admin_role_assignment` (V2), `auth_mfa_factor`,
  `mfa_factor_kind_enum`, `admin_role` enum (V2/V3). **No new migration.**

## 3. Invariants & rules
- **INV-1 — Admin activation requires a confirmed TOTP (AU10.1, C7, DL-035).** `invited→active` is
  rejected unless the identity has ≥1 active, confirmed TOTP factor. _(ref: `auth_mfa_factor`; AU10.1)_
- **INV-2 — The TOTP secret never persists or logs in cleartext.** Only `secret_encrypted` (app-layer
  encrypted) is stored; the DB `auth_mfa_factor_totp_has_secret` CHECK requires it present. _(ref: schema; Spec §7.3)_
- **INV-3 — RBAC is composable; effective roles = union of active assignments (C18, DL-031/032).**
  Revoked assignments don't count; one assignment per `(admin_user_id, role)`. _(ref: `admin_role_assignment` PK + `..._revoke_chk`)_
- **INV-4 — Authorization gates every command (DoD "authz at the boundary").** A command whose required
  roles don't intersect the actor's `activeRoles` is rejected `role_not_held` (403), **no envelope, no
  mutation** (pre-authorisation, G22). _(ref: B4 §4.2, C18)_
- **INV-5 — Admin email is globally unique (DL-031).** _(ref: `admin_user_email_uq`)_
- **INV-6 — Every admin command is idempotent, MFA-fresh, and audited** — inherited by routing through
  the M4a gateway (#2/#4/#5). _(ref: [[M4a-command-substrate]])_

## 4. API / type surface
- **Commands (via `CommandGateway`):** `provisionAdminUser(email, displayName) → adminUserId`,
  `activateAdminUser(adminUserId)`, `disableAdminUser` / `enableAdminUser(adminUserId)`,
  `assignRole(adminUserId, role)`, `revokeRole(adminUserId, role)`,
  `enrollTotp(adminUserId, label) → {factorId, otpauthUri}`, `confirmTotp(factorId, code)`.
- **Queries:** `activeRoles(adminUserId) → Set<AdminRole>`; `getAdminUser(adminUserId)`.
- **Boundary extension:** `CommandRequest` gains `requiredRoles`; `CommandGateway` gains the authz gate.
- **Port:** `SecretCipher.encrypt/decrypt(bytes)` → dev AES-GCM stub; KMS adapter at Production.
- **Types:** `AdminRole` (enum mirroring `admin_role`), `TotpEnrollment`, `Permission`-by-role map.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no (→ M4c) | provisioning / role-assign maker-checker is M4c |
| 2 | MFA-fresh | **yes (inherited)** | every command runs through the M4a gateway |
| 3 | SoD-checked | no (→ M4c) | role-pair SoD on assign is M4c; M4b writes assignments un-gated (guardrail) |
| 4 | Idempotent on `command_id` | **yes (inherited)** | M4a `sys_command_log` |
| 5 | Audit-logged envelope | **yes (inherited)** | gateway emits each command's envelope |
| — | Authorization at boundary | **built here** | role-authz gate added to the gateway (`role_not_held`, C18) |

## 6. Events (audit envelopes via the gateway; no bus yet)
`admin_iam.AdminUser.Created` (DL-031), `admin_iam.Mfa.Enrolled` (DL-035, C7),
`admin_iam.AdminUser.Activated`, `admin_iam.Role.Assigned` / `admin_iam.Role.Revoked` (DL-032),
`admin_iam.AdminUser.Disabled` (from M4a, now authz-gated). _Soft-SoD / deviation events → M4c._

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] TOTP: enroll → secret stored only as `secret_encrypted` (never cleartext); confirm with a valid
      RFC-6238 code stamps `last_used_at`; a wrong code is rejected (INV-1, INV-2).
- [ ] Activation gate: `activateAdminUser` rejected with no confirmed TOTP; succeeds once one exists (INV-1).
- [ ] RBAC union: assign two roles → `activeRoles` = both; revoke one → only the other remains (INV-3).
- [ ] Command authz: a non-`super_admin` actor's `disableAdminUser` → `role_not_held` (403), no
      envelope, no mutation; a `super_admin` actor succeeds (INV-4).
- [ ] Email uniqueness: a second `provisionAdminUser` with the same email → rejected (INV-5).
- [ ] Inherited controls: an admin command replays as a no-op and emits one envelope (INV-6, via M4a).

## 8. Definition of Done (foundation-critical)
- [x] §7 tests green — `AdminIamTest` (9) + `TotpTest` (3, incl. RFC-6238 vectors); 95 total green.
- [x] `/code-review` on the diff; findings fixed (disabled-admin deauthorization, one-time TOTP
      confirm, AES-GCM length guard, duplicate-key translation, soft-SoD column clear on reactivate).
- [x] `DL-BE-019` entry (TOTP impl + secret cipher, last_used_at-as-confirmed, role-based authz model,
      bootstrap-first-super_admin, the SoD/maker-checker deferral with its M4c guardrail).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED:** M4b owns IAM lifecycle + TOTP + RBAC + command authz; **SoD on role-assign
   (#3) and maker-checker (#1) are M4c**, with a guardrail that role-assign is not exposed before then.
2. **Migration — RESOLVED: none.** `admin_user`, `admin_role_assignment`, `auth_mfa_factor` exist (V2/V3).
3. **TOTP secret — RESOLVED:** a `SecretCipher` port (dev AES-GCM with a config key) writes
   `secret_encrypted`; real KMS at Production (ACL-port pattern). `last_used_at IS NOT NULL` is the
   "confirmed" signal for AU10.1 — no schema column needed.
4. **TOTP algorithm — RESOLVED:** RFC-6238 via JDK `javax.crypto.Mac` (HMAC-SHA1, 6-digit, 30s step,
   ±1 window); Base32 for the `otpauth://` secret. Add `commons-codec` for Base32 if not transitively
   present (confirm at build). No heavyweight dependency.
5. **Permission model — RESOLVED: role-based.** The five roles are the Phase-1 permission units (the
   corpus defines no fine-grained catalog); a command declares required roles and authz =
   `activeRoles ∩ required ≠ ∅` (C18 union). Avoids inventing a permission enum the spec lacks.
6. **Bootstrap — RESOLVED:** the first `super_admin` is **seeded** (a one-time bootstrap path outside
   the authz'd command flow), since no Super Admin exists to provision the first one.
7. **Authz home — RESOLVED:** wired into the M4a `CommandGateway` (the boundary gains an authz step
   after the MFA gate, before claim); `CommandRequest` carries `requiredRoles`. Closes M4a's
   `disableAdminUser` authz gap.
8. **TOTP-at-login — RESOLVED: deferred** (small follow-up); admins use the SMS-OTP fallback (M3a) to
   mint assertions meanwhile; the gateway is factor-agnostic.

## 10. Watch-for (carry forward)
- **Last-factor revocation downgrade** — revoking an admin's last active factor must downgrade
  `status` from `active` (schema comment on `auth_mfa_factor.revoked_at`); handle on revoke or note for
  a factor-management follow-up.
- **SoD guardrail** — M4c must gate role-assign (strict block + soft-warn/deviation) before any
  role-assign surface is exposed; un-gated assignment can create the credit⊕treasury strict pair.
- **KMS + secret rotation** at Production; the dev AES-GCM key is not production-grade.
- **AI-agent actor model (G31)** — agents inherit an admin's role assignments + a cert-bound assertion;
  the role-authz model here is the one they reuse. Out of scope for M4b; keep the authz gate
  factor-agnostic so it composes later.
