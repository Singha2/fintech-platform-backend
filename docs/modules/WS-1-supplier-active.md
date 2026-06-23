# WS-1 · Supplier active (BC8) — admin-on-behalf onboarding

> **Lean sub-slice spec** (Walking Skeleton §4). Light tier. The first *business* slice through the WS-0
> edge: it drives one supplier from `created → active` over HTTP, all commands admin-on-behalf (no supplier
> login — DL-012), each routed through the `CommandGateway` so it inherits the non-negotiables. Spec
> before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-1 |
| **Slice** | WS-1 — supplier onboarding state machine to `active` (BC8) |
| **Tier** | Light (skeleton-thin — happy path; reject branches are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-23 |

> **DoR decisions (settled at the gate):** **(A) record outcomes** — WS-1 records the verified-identity
> (pan/gstin/cin) and signed-MAA (`maa_agreement_id`) outcomes on the aggregate; the M5a verify / M5c sign
> round-trips are already proven in their own tests, and wiring the event-bus consumption is a Milestone-2
> concern. **(B) one slice** — nine mechanically-uniform state-machine commands, built test-first with one
> end-to-end supplier test + per-transition + gate-reject asserts. **(C) compliance seam** — a thin
> `compliance` package (`ComplianceService`) is the one-place swap-point for the real BC11 engine at M15.

---

## 1. Scope
**Owns:** a new `supplier` package — `SupplierService` (commands via `CommandGateway`) + `SupplierController`;
a thin `compliance` package — `ComplianceService` (KYC submit/approve, the M15 swap-point). Tables:
`sup_account`, `sup_agency_consent`, `sup_financial_profile`, `comp_kyc_file`.

**State machine (linear; each step gated on the prior status — this IS the SA8.2 gate):**
`created → identity_verified → kyc_submitted → kyc_approved → credit_reviewed → maa_signed → active`.

**Does NOT own (deferred):** inline M5a/M5c ACL round-trips + event-bus consumption (decision A); agency-consent
*enforcement* (the header → gateway → reject-if-no-active-consent path — WS-1 establishes the consent row the
happy path needs, AC.1 *rejection* is a Milestone-2 reject branch); the BC3 `risk_supplier_profile` four-eyes
(exposure < ₹10 Cr keeps C6 out — WS-1 snapshots `credit_exposure_cap_paise` on the aggregate only); the real
BC11 compliance/AML engine (replaces the `ComplianceService` auto-approve at M15); suspension / blacklist /
exit; consent revocation. No new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-0** the HTTP edge (request envelope, `CommandResponse`, error taxonomy, bearer auth). Done.
- **M4a–d** `CommandGateway` + roles (`ops_executive`, `compliance_reviewer`, `credit_reviewer`). Done.
- **M5a/M5c** verification/signing stubs — *outcomes recorded* per decision A (not invoked inline). Done.
- **M2** `AuditLog` — one envelope per command. Done.

## 3. Invariants & rules
- **INV-1 — SA8.2 activation gate.** `activate` requires `kyc_approved` ∧ credit review set
  (`credit_exposure_cap_paise`) ∧ MAA signed (`maa_agreement_id`). Enforced by the linear state machine:
  each command's optimistic UPDATE is guarded `WHERE status = <prior> AND aggregate_version = ?`, so a
  step cannot run before its predecessor. _(ref: SA8.2)_
- **INV-2 — Admin-on-behalf, SoD per command.** Every command is an `admin_user` action (no supplier login,
  DL-012); the `CommandGateway` gates each on the required role (table §4). _(ref: DL-012, C18)_
- **INV-3 — Agency consent established (AC.1, happy path).** `grant-agency-consent` writes an active
  `sup_agency_consent` row before the on-behalf steps; AC.1 *rejection* (no active consent ⇒ block) is a
  deferred reject branch. MAA is the supplier's own signatory (AC.2) — recorded, never delegated. _(ref: AC.1/AC.2, G5)_
- **INV-4 — No four-eyes (C6 stays out).** `credit_exposure_cap_paise < ₹10 Cr` (`1e10` paise) ⇒ the BC3
  four-eyes never triggers; WS-1 keeps exposure under threshold. _(ref: C6)_
- **INV-5 — Idempotent + audited.** Every command is idempotent on `X-Command-Id` and emits exactly one
  envelope (via the gateway). A creating command's `supplier_id` is derived from `(command_id, payload)` so
  a replay is stable and a divergent body conflicts (the WS-0 pattern). _(ref: G18, X13)_

## 4. API / type surface (intent-shaped, B4 §2.1; on-behalf calls carry `X-Agency-Consent-Id` advisory)

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /suppliers/create` → 201 | ops_executive | mint supplier → `created` (legal_name, constitution_type, pan, gstin, cin) |
| `POST /suppliers/{id}/grant-agency-consent` | ops_executive | active `sup_agency_consent` row (scope, consent_doc_hash) |
| `POST /suppliers/{id}/record-identity-verified` | ops_executive | `created → identity_verified` (record pan/gstin/cin) |
| `POST /suppliers/{id}/submit-kyc` | ops_executive | `identity_verified → kyc_submitted` (+ `comp_kyc_file` submitted) |
| `POST /suppliers/{id}/record-kyc-approved` | compliance_reviewer | `kyc_submitted → kyc_approved` (`ComplianceService.approveKyc`; kyc_approved_by/at) |
| `POST /suppliers/{id}/submit-financial-profile` | ops_executive | `sup_financial_profile` row (top_buyers) |
| `POST /suppliers/{id}/record-credit-review` | credit_reviewer | `kyc_approved → credit_reviewed` (exposure_cap, risk_rating) |
| `POST /suppliers/{id}/record-maa-signed` | ops_executive | `credit_reviewed → maa_signed` (maa_agreement_id, maa_signed_at) |
| `POST /suppliers/{id}/activate` | ops_executive | `maa_signed → active` (activated_at) |
| `GET /suppliers/{id}` | (any authenticated) | aggregate read (status, version) |

- **Types:** `ConstitutionType` (the `sup_constitution_type` enum). Commands return `CommandResponse` (WS-0).

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | onboarding is single-actor state advances; first maker-checker pair is WS-4 |
| 2 | MFA-fresh | yes | session assertion + `isMfaFresh` on every command (admin actor) |
| 3 | SoD-checked | yes | per-command role gate via `CommandGateway` (table §4) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → `sys_command_log` |
| 5 | Audit-logged | yes | one envelope per command, appended before 2xx |

## 6. Events
- **Publishes:** `supplier.Supplier.Created` / `.IdentityVerified` / `.KycSubmitted` / `.KycApproved` /
  `.FinancialProfileSubmitted` / `.CreditReviewed` / `.MaaSigned` / `.Activated`; `supplier.AgencyConsent.Granted`;
  `compliance.KycFile.Approved`. _(no bus subscriptions yet)_
- **Subscribes:** none.

## 7. Test scenarios (write these first) — MockMvc, Testcontainers
- [ ] **Happy path (E2E):** an ops_executive (+ compliance_reviewer, credit_reviewer) drive
      create→active over HTTP; assert each transition and `sup_account.status='active'` at the end.
- [ ] **SA8.2 gate:** `activate` while `status != maa_signed` → rejected (status unchanged).
- [ ] **SoD:** a wrong-role actor on `record-credit-review` (not credit_reviewer) → 403 `role_not_held`.
- [ ] **Idempotent replay:** re-issuing `create` (same `X-Command-Id`, same body) → original
      `emitted_events`, exactly one supplier row.
- [ ] **Agency consent:** after `grant-agency-consent`, an active `sup_agency_consent` row exists.
- [ ] **GET** `/suppliers/{id}` returns the aggregate read.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `SupplierOnboardingTest` **9/9**, full suite **153**.
- [x] `/code-review` on the diff; findings fixed — 6 findings (array-literal corruption, money-float
      truncation, maker=checker→500, create idempotency gap, financial re-submit→500, duplicate consents)
      all resolved + 3 regression tests added.
- [x] `DL-BE-031` added (the supplier state machine, the record-outcomes decision, the compliance seam,
      the **DB-enforced KYC maker-checker** discovery, the deferred agency-consent enforcement).
- [x] This spec flipped to **Status: Done**.

> **As-built note:** KYC approval turned out to be **DB-enforced maker-checker** (`comp_kyc_file`:
> submitter ≠ approver + approver MFA — surfaced via ALTER-added columns the inline DDL hid), so WS-1
> exercises a real maker-checker control. The app-layer guard (`ComplianceService`) returns a clean
> 409 `checker_equals_maker` with the DB CHECK as backstop.
