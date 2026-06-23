# WS-3 · Investor active (BC7) — invite → active

> **Lean sub-slice spec** (Walking Skeleton §4; = M10 min). Light tier. The third onboarding flow — one
> investor driven `signed_up → active` over HTTP, gated by a compliance-issued invite. Mirrors WS-1.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-3 (= M10 Investor Onboarding, min) |
| **Slice** | WS-3 — investor onboarding state machine to `active` (BC7) |
| **Tier** | Light (skeleton-thin — happy path; reject branches are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-23 |

> **DoR decisions (settled at the gate):** **admin-driven signup** — an `ops_executive` command consumes
> the invite + provisions the investor identity + account (fully admin-on-behalf, consistent with WS-1/2);
> the C20 invite-gate is still enforced (signup validates pending + unexpired + email/phone hash-match).
> The investor self-service login portal (real signup, investor session, investor-submitted KYC) is
> deferred to M10-full. Inherited: record outcomes (no inline M5a/M5c), one slice, full-body create id.

---

## 1. Scope
**Owns:** a new `investor` package — `InvestorService` (commands via `CommandGateway`) + `InvestorController`.
Tables: `inv_invite`, `inv_account`, `inv_suitability`; reuses `ComplianceService` (`comp_kyc_file`,
`subject_type='investor'`).

**State machine (linear; DB-enum order):** `signed_up → identity_verified → kyc_submitted →
suitability_assessed → financial_profile_completed → kyc_approved → mia_signed → active`.

**Does NOT own (deferred):** the investor self-service portal + investor login flow; suitability *mismatch*
+ override-ack (skeleton uses `mismatch=false`, keeping IA.4/C21/G26 out); `nri`/`institutional` sub-types
(Phase-1 DB CHECK locks `resident_individual`/`huf`); penny-drop realism (we record `bank_account_last4`);
KYC refresh scheduler; suspension/exit (DB maker-checker, ALTER-added — only bites when `suspended_at` set);
BC3 four-eyes (n/a — investors have no credit cap). No new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-0/1/2** edge + patterns (`CommandGateway`, `RequestBodies`, `AbstractEdgeHttpTest`, `ComplianceService`). Done.
- **M4a–d** roles (`compliance_reviewer`, `ops_executive`). Done.

## 3. Invariants & rules
- **INV-1 — IA.3 activation gate.** `activate` requires `mia_signed` (⇒ kyc_approved ∧ suitability set ∧
  bank_account_last4 set ∧ MIA signed — all earlier in the linear machine). Enforced by the status-guarded
  transitions; activation additionally sets `kyc_refresh_due_at = activated_at + 12 months` (DB CHECK —
  both set via `now()` in one UPDATE, which is stable in-statement). _(ref: IA.3, C17)_
- **INV-2 — Invite-gate (C20/DL-008).** No `inv_account` without consuming a `pending`, unexpired
  `inv_invite` whose `email_hash`/`phone_hash` match the signup's email/phone (SHA-256). The invite is
  issued by a compliance_reviewer; one account per invite (UNIQUE). _(ref: C20, DL-008)_
- **INV-3 — KYC maker-checker.** `comp_kyc_file` (subject_type=investor): submitter (ops at submit-kyc) ≠
  approver (compliance at record-kyc-approved) + approver MFA — DB-enforced, app-guarded for a clean 409
  (reuses `ComplianceService`). _(ref: KF.2/C4)_
- **INV-4 — Set-once PII off the audit log.** pan / aadhaar_last4 / bank_account_last4 are written to
  `inv_account` columns but **never** to a `CommandEvent` payload (envelopes carry only investor_id +
  status). _(ref: C14/C15, ND.2)_
- **INV-5 — Phase-1 sub_type.** `resident_individual` / `huf` only (DB CHECK). _(ref: IA.2/DL-006)_
- **INV-6 — Idempotent + audited.** Idempotent on `X-Command-Id`; one envelope per command; the investor
  identity is a direct `auth_identity` insert (one envelope, no PII in the log; `DuplicateKey`→400). _(ref: G18, X13)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /investor-invites/issue` → 201 | compliance_reviewer | `inv_invite` (email_hash, phone_hash, expiry=+14d) |
| `POST /investors/sign-up` → 201 | ops_executive | consume invite + provision `investor` identity + `inv_account` → `signed_up` |
| `POST /investors/{id}/record-identity-verified` | ops_executive | `signed_up → identity_verified` (pan, aadhaar_last4) |
| `POST /investors/{id}/submit-kyc` | ops_executive | `identity_verified → kyc_submitted` (+ comp_kyc_file submitted) |
| `POST /investors/{id}/assess-suitability` | compliance_reviewer | `kyc_submitted → suitability_assessed` (inv_suitability, mismatch=false) |
| `POST /investors/{id}/complete-financial-profile` | ops_executive | `suitability_assessed → financial_profile_completed` (bank_account_last4) |
| `POST /investors/{id}/record-kyc-approved` | compliance_reviewer | `financial_profile_completed → kyc_approved` |
| `POST /investors/{id}/record-mia-signed` | ops_executive | `kyc_approved → mia_signed` |
| `POST /investors/{id}/activate` | ops_executive | `mia_signed → active` (+ kyc_refresh_due_at) |
| `GET /investors/{id}` | (any authenticated) | aggregate read |

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | yes (KYC) | `comp_kyc_file` submitter ≠ approver + approver MFA (INV-3) |
| 2 | MFA-fresh | yes | session assertion + `isMfaFresh` on every command |
| 3 | SoD-checked | yes | per-command role gate via `CommandGateway` (table §4) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → `sys_command_log` |
| 5 | Audit-logged | yes | one envelope per command, before 2xx (no PII in payload, INV-4) |

## 6. Events
- **Publishes:** `investor.Invite.Issued`; `investor.InvestorAccount.SignedUp` / `.IdentityVerified` /
  `.KycSubmitted` / `.SuitabilityAssessed` / `.FinancialProfileCompleted` / `.KycApproved` / `.MiaSigned`
  / `.Activated`. _(no bus yet)_
- **Subscribes:** none.

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest` (MockMvc, Testcontainers)
- [ ] **Happy path (E2E):** compliance issues an invite; ops + compliance drive sign-up→active over HTTP;
      assert each transition and `inv_account.status='active'` + `kyc_refresh_due_at` set.
- [ ] **Invite-gate:** sign-up with a mismatched email (hash ≠ invite) → rejected; a second sign-up on the
      same (now consumed) invite → rejected.
- [ ] **SoD:** a non-compliance actor on `assess-suitability` → 403 `role_not_held`.
- [ ] **KYC maker-checker:** a dual-role admin submitting + approving KYC → 409 `checker_equals_maker`.
- [ ] **Idempotent replay:** re-issuing `sign-up` (same `X-Command-Id`) → original `emitted_events`,
      exactly one investor row.
- [ ] **GET** `/investors/{id}` returns the aggregate read.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `InvestorOnboardingTest` **8/8**, full suite **169**.
- [x] `/code-review` on the diff; findings fixed — a platform-wide edge format-validation gap (identity
      fields → DB-domain 500s) fixed once in shared `RequestBodies` (`requiredPan`/`requiredGstin`/
      `requiredFourDigits`) and applied to all three onboarding controllers; invite-consume rowcount guard.
      2 regression tests added.
- [x] `DL-BE-033` added (the invite-gate, admin-driven signup, the linear machine, the PII-off-audit rule).
- [x] This spec flipped to **Status: Done**.
