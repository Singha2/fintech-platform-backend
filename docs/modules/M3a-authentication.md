# M3a · Authentication — password + SMS-OTP MFA → mfa_assertion

> **Lean module spec** — *foundation-critical* (security-sensitive: hashing, OTP): light ceremony,
> heavy invariant rigor. See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M3 — Auth & Identity (BC: auth shared kernel) |
| **Slice** | M3a — identity primitives, password auth, SMS-OTP MFA → `mfa_assertion_id` |
| **Tier** | Foundation-critical (light ceremony · heavy security-invariant rigor) |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-016) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> M3a is where **MFA-freshness (non-negotiable #2) is *minted*** — the `mfa_assertion_id` every later
> command checks (the freshness *check* + sessions are **M3b**). It is also **M2's first audit
> producer** (#5). SMS delivery of the OTP is **stubbed behind a thin BC15 NotificationPort**
> (real interface, fake in-process adapter) — Constitution-compliant.

## 1. Scope
**Owns:**
- **Identity primitives** — provision an `auth_identity`; set a `password` credential. Reused by M4
  admin-IAM and onboarding; M3a owns the *table operations*, not the per-actor *workflow*.
- **Password authentication** — verify a `password` credential with **argon2id**, constant-time.
- **SMS-OTP MFA** — issue an `auth_otp_challenge` (`login_mfa`), deliver the code via the
  NotificationPort stub, verify it (attempts / expiry / single-use), and on success **mint
  `mfa_assertion_id`** (set only when the challenge is `consumed`).
- **Thin BC15 `NotificationPort`** + lean in-process stub (records sends for test/dev; no real SMS,
  no `sys_notification_dispatch` write).
- **Audit wiring** — emit envelopes for every auth event via `AuditLog.append()` (M2).

**Does NOT own (deferred):**
- **Sessions, MFA-freshness check, tenant claims → M3b** (next slice: `auth_session` lifecycle,
  `isMfaFresh`, `tenant_claims`).
- **Admin-user management workflow** (roles, maker-checker provisioning) → **M4**.
- **Investor / auditor / acknowledgment-user onboarding** → M10 / M17 / M8 (they reuse this engine).
- **TOTP** (`mfa_factor_kind=totp`, DL-035) and **OAuth-Google** credential → later (this slice ships
  `sms_otp` + `password`).
- **OTP purposes other than `login_mfa`** (`phone_verify`, `email_verify`, `password_reset`) → later.
- **Full BC15 Notifications** (templates, retries, dispatch lifecycle, multi-channel) → **M5**.

## 2. Upstream dependencies
- **M2 Audit Log** — Done (auth events append here). **M1a** (errors), **M1b** (`Ids`) — Done.
- **M0 schema** — Done: `auth_identity / auth_credential / auth_otp_challenge` (+ enums).
- **SMS provider** — **stubbed** (agreed; no real service). DoR-satisfying per §D.

## 3. Invariants & rules
- **INV-1 — Passwords are only ever a strong hash.** `password` credentials store an argon2id
  `secret_hash`, never plaintext; verification is constant-time. _(ref: DB
  `auth_credential_password_shape`)_
- **INV-2 — OTP plaintext never persists.** Only `code_hash` is stored; the code transits the
  NotificationPort once and is never written to the DB or (prod) logs. _(ref: `auth_otp_challenge`)_
- **INV-3 — Assertion minted only on consume.** `assertion_id` is set iff the challenge is
  `consumed`; verifying marks `consumed_at`/`status='consumed'` and mints the assertion.
  _(ref: DB `auth_otp_challenge_assertion_only_when_consumed`, `..._consumed_implies_status`)_
- **INV-4 — OTP is single-use, bounded, expiring.** Wrong codes increment `attempts` up to
  `max_attempts`; `expires_at` and exhaustion invalidate; a new `active` challenge for the same
  `(identity, purpose)` supersedes the prior. _(ref: DB CHECKs + app)_
- **INV-5 — Identity status/validity gates auth.** `disabled`/`auto_disabled` cannot authenticate;
  an `auditor` must be within `[valid_from, valid_until]`. _(ref: DB identity CHECKs; B3 AA13.4)_
- **INV-6 — Every auth event is audited.** Password verified/rejected, OTP issued/consumed/failed,
  identity provisioned — each emits an `AuditEventEnvelope`. _(ref: non-negotiable #5, C1–C3)_

## 4. API / type surface
- **Commands:**
  - `provisionIdentity(kind, email, phoneE164, …) → identityId` (primitive; M4/onboarding wrap it)
  - `setPassword(identityId, rawPassword)` (argon2id hash; never stores plaintext)
  - `authenticatePassword(email, rawPassword) → PasswordResult` (ok ⇒ MFA required | rejected)
  - `issueLoginOtp(identityId) → challengeId` (creates challenge, sends via NotificationPort)
  - `verifyOtp(challengeId, code) → MfaAssertion(assertionId)`
- **Queries:** `getIdentity(identityId)`.
- **Port (thin BC15):** `NotificationPort.send(NotificationRequest)` → `StubNotifier` (in-memory,
  test-retrievable; dev-log only). Real SMS adapter later (M5 / Production).
- **Types:** `MfaAssertion`, `PasswordResult`, `NotificationRequest`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | admin provisioning maker-checker is **M4** |
| 2 | MFA-fresh | **minted here** | `verifyOtp` mints `mfa_assertion_id`; the freshness *check* + sessions are **M3b** |
| 3 | SoD-checked | no | M4 |
| 4 | Idempotent on `command_id` | no | auth ops; money-command idempotency store deferred ([[DL-BE-013]]) |
| 5 | Audit-logged envelope | **yes** | every auth event → `AuditLog.append()` (M3a is M2's first producer) |

## 6. Events (audit envelopes; no bus yet)
Appended via `AuditLog.append()` ([[DL-BE-014]]): `IdentityProvisioned`, `PasswordVerified`,
`PasswordRejected`, `OtpIssued`, `OtpConsumed`, `OtpFailed`.

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] Password: correct → ok; wrong / unknown email / `disabled` identity → rejected (INV-1, INV-5).
- [ ] OTP happy path: issue → stub captured a code → verify → assertion minted, challenge `consumed`,
      `assertion_id` set only then (INV-3).
- [ ] OTP wrong code increments `attempts`; exceeding `max_attempts` blocks; expired challenge
      rejected; a new challenge supersedes the prior `active` one (INV-4).
- [ ] Audit: a successful login (password+OTP) produces the expected envelopes; shard verifies intact (INV-6).
- [ ] Stub never persists/returns the code via any prod path; plaintext only via the port (INV-2).
- [ ] Password is stored as an argon2id hash, not plaintext; verify rejects a wrong password (INV-1).

## 8. Definition of Done (foundation-critical)
- [ ] §7 tests green (security invariants are the headline).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-016` entry (argon2id, OTP parameters, NotificationPort shape, stub, provisioning ownership).
- [ ] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Password hashing — RESOLVED: argon2id** (Spring Security `Argon2PasswordEncoder`).
2. **OTP parameters — RESOLVED: 6-digit, 5-min TTL, `max_attempts=5`** (matches schema default).
3. **Identity provisioning — RESOLVED:** M3a owns the table primitives (`provisionIdentity` /
   `setPassword`); M4 builds the admin workflow on top (avoids M3↔M4 circularity).
4. **Slice — RESOLVED: split.** This is **M3a** (authenticate → assertion); **M3b** = sessions +
   `isMfaFresh` + tenant claims, drafted after M3a is Done.
5. **Stub — RESOLVED: lean** (in-memory + dev-log, **no** `sys_notification_dispatch` write); the
   dispatch lifecycle lands in M5.
6. **Tenant claims — RESOLVED: deferred to M3b** (with sessions); roles arrive with M4.

## 10. Watch-for (carry forward)
- **OTP rate-limiting / resend throttling** is minimal while stubbed; revisit when the real SMS
  provider lands (cost + abuse) — note for M5/Production.
- **MFA-freshness window** value (C7) is decided in **M3b** when `isMfaFresh` is built.
