# WS-2 · Buyer + acknowledgment user (BC9)

> **Lean sub-slice spec** (Walking Skeleton §4). Light tier. The second counterparty — one buyer driven
> `nominated → active` over HTTP, all admin-on-behalf through the WS-0 edge, plus its OTP-only
> acknowledgment user. Mirrors WS-1. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-2 |
| **Slice** | WS-2 — buyer onboarding state machine to `active` + ack user (BC9) |
| **Tier** | Light (skeleton-thin — happy path; reject branches are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-23 |

> **Inherited decisions (from WS-1, no new forks):** record outcomes (no inline M5a/M5c); one slice;
> commands through `CommandGateway`; creating-command id from the full body. **New this slice:** the
> acknowledgment user is a login principal — `designate-ack-user` provisions an `acknowledgment_user`
> `auth_identity` (OTP-only: no password, no MFA — AU.1/DL-021) + a `buyer_ack_user` row.

---

## 1. Scope
**Owns:** a new `buyer` package — `BuyerService` (commands via `CommandGateway`) + `BuyerController`.
Tables: `buyer_account`, `buyer_ack_user`, `buyer_payment_rule`.

**State machine (linear; each step gated on the prior status):**
`nominated → identity_verified → credit_assessed → engagement_started → active`.

**Does NOT own (deferred):** buyer suspension (DB-enforced maker-checker via the ALTER-added
`suspend_maker_id`/`suspend_checker_id`/`suspend_checker_mfa_assertion_id` — Milestone 2); blanket ack /
anchor tier (Phase-1 DB CHECK locks `acknowledged_buyer` + `per_invoice`); payment-rule supersession; the
ack user's actual OTP login flow (WS-2 just provisions the identity); BC3 four-eyes (cap < ₹10 Cr keeps C6
out). No new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-0** edge + **WS-1** patterns (`SupplierService`/`CommandGateway` shape, `RequestBodies`,
  `AbstractEdgeHttpTest`). Done.
- **M4a–d** `CommandGateway` + roles (`ops_executive`, `credit_reviewer`). Done.
- **M3a** `AuthService.provisionIdentity` (for the ack user's `acknowledgment_user` identity). Done.

## 3. Invariants & rules
- **INV-1 — BA.3 activation gate.** `activate` requires `status = engagement_started` (⇒ credit assessed)
  ∧ ≥1 active `buyer_ack_user` ∧ a current `buyer_payment_rule`. The status machine covers the credit
  precondition; `activate` additionally app-checks the ack-user + payment-rule existence. _(ref: BA.3)_
- **INV-2 — Admin-on-behalf, SoD per command.** Every command is an `admin_user` action; the gateway
  gates each on the required role (table §4). _(ref: DL-012, C18)_
- **INV-3 — Phase-1 lock.** `relationship_tier = acknowledged_buyer`, `acknowledgment_mode = per_invoice`
  (DB CHECK). _(ref: C25, DL-019)_
- **INV-4 — Ack user is OTP-only.** `designate-ack-user` provisions an `acknowledgment_user` identity with
  **no password / no MFA factor** (DL-021) and a `buyer_ack_user` row (one identity per ack user — UNIQUE).
  _(ref: AU.1/DL-021)_
- **INV-5 — No four-eyes (C6 out).** `credit_limit_paise < ₹10 Cr`. _(ref: C6)_
- **INV-6 — Idempotent + audited.** Idempotent on `X-Command-Id`, one envelope per command; create id from
  the full body (gstin + mca_cin are UNIQUE on `buyer_account`). _(ref: G18, X13)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /buyers/nominate` → 201 | credit_reviewer | mint buyer → `nominated` (legal_name, mca_cin, gstin, sector) |
| `POST /buyers/{id}/record-identity-verified` | ops_executive | `nominated → identity_verified` |
| `POST /buyers/{id}/record-credit-assessment` | credit_reviewer | `identity_verified → credit_assessed` (credit_limit_paise) |
| `POST /buyers/{id}/start-engagement` | ops_executive | `credit_assessed → engagement_started` |
| `POST /buyers/{id}/designate-ack-user` | ops_executive | provision `acknowledgment_user` identity + `buyer_ack_user` row (email, phone, display_name) |
| `POST /buyers/{id}/confirm-payment-instruction` | ops_executive | `buyer_payment_rule` row (effective_from) |
| `POST /buyers/{id}/activate` | ops_executive | `engagement_started → active` — **BA.3 gate** |
| `GET /buyers/{id}` | (any authenticated) | aggregate read (status, version) |

- **Types:** commands return `CommandResponse` (WS-0). Bodies via `RequestBodies` (shared validation).

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | onboarding is single-actor advances; buyer suspension (M2) is the maker-checker path |
| 2 | MFA-fresh | yes | session assertion + `isMfaFresh` on every command (admin actor) |
| 3 | SoD-checked | yes | per-command role gate via `CommandGateway` (table §4) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → `sys_command_log` |
| 5 | Audit-logged | yes | one envelope per command, appended before 2xx |

## 6. Events
- **Publishes:** `buyer.Buyer.Nominated` / `.IdentityVerified` / `.CreditAssessed` / `.EngagementStarted` /
  `.Activated`; `buyer.AckUser.Designated`; `buyer.PaymentInstruction.Confirmed`. _(no bus yet)_
- **Subscribes:** none.

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest` (MockMvc, Testcontainers)
- [ ] **Happy path (E2E):** ops + credit_reviewer drive nominate→active over HTTP; assert each transition
      and `buyer_account.status='active'`.
- [ ] **BA.3 gate:** `activate` at `engagement_started` **without** an ack user (or without a payment rule)
      → rejected (status unchanged).
- [ ] **SoD:** a non-credit_reviewer on `record-credit-assessment` → 403 `role_not_held`.
- [ ] **Ack user OTP-only:** after `designate-ack-user`, the `buyer_ack_user` row + its
      `acknowledgment_user` identity exist, with **no password credential / no MFA factor**.
- [ ] **Idempotent replay:** re-issuing `nominate` (same `X-Command-Id`, same body) → original
      `emitted_events`, exactly one buyer row.
- [ ] **GET** `/buyers/{id}` returns the aggregate read.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `BuyerOnboardingTest` **8/8**, full suite **161**.
- [x] `/code-review` on the diff; findings fixed — 4 findings (ack-user dup-email→500 + PII-leaking second
      envelope, non-positive credit-limit→500, BA.3/state error precedence) all resolved + 2 regression tests.
- [x] `DL-BE-032` added (the buyer state machine, the BA.3 app-gate, the OTP-only ack-user provisioning,
      the shared-helper extraction, the deferred suspension maker-checker).
- [x] This spec flipped to **Status: Done**.
