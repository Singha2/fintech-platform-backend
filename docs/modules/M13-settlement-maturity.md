# M13 · Settlement (BC4) — **maturity recording** (Milestone 2, widen WS-7)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M13). WS-7 built the disbursement
> (`fully_funded → disbursed`). This slice adds the next spine step that is buildable **without BC12/Tax**:
> **maturity recording** — the buyer's full repayment → `disbursed → matured_payment_received`. The
> TDS-bearing investor **distribution**, the deal **close**, and the **reconciliation/remediation engine**
> are deferred (see §9). Decision: **DL-BE-054**.

| | |
|---|---|
| **Module** | M13 — Settlement (BC4), maturity-recording slice |
| **Predecessor** | WS-7 ([[DL-BE-037]]) — disbursement `fully_funded → disbursed` (Treasury maker-checker, inline escrow payout) |
| **Tier** | Full-rigor slice (narrow scope, see DoR) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (settled at the gate — the three forks + the schema coupling)

1. **Scope = the distribution/maturity spine** (not the reconciliation/remediation engine). (User, 2026-06-26.)
2. **TDS investor distribution DEFERRED entirely** — no investor payout until BC12 (Tax & Reporting, M16)
   exists. (User.) → **Consequence:** the only TDS-free part of the spine is **maturity recording**.
3. **Inline + ops-triggered** — `RecordMaturity` is an ops command (the buyer's repayment recorded
   on-behalf); no maturity inflow webhook, no EoD overlay, no payout webhook. (User; consistent with M11/M12.)
4. **(Schema coupling — why close defers too)** `deal_listing.terminal_outcome` has only
   `{distributed, funding_failed_refunded, cancelled_pre_disbursement, defaulted}`, and the
   `terminal_outcome_shape_chk` requires `status='closed' ⟺ terminal_outcome IS NOT NULL`. The only terminal
   outcome for a matured deal is `distributed` — so a listing **cannot be closed until the distribution has
   happened**. Close is therefore coupled to the (deferred) distribution and defers with it. M13-maturity
   stops at `matured_payment_received`.
5. **(Derived) No new migration.** `deal_listing_status` already has `matured_payment_received`.
6. **(Derived) Maturity = full repayment only.** A maturity **shortfall** (buyer pays < face value) routes
   to BC6 Collections (`col_maturity_case`, the delay/default states) — M14, deferred. M13 records the
   **full** repayment path and rejects under-payment with a clear "shortfall → collections (M14)" message.

---

## 1. Scope

**Owns:** a new `MaturityService` + `MaturityController` (settlement package, BC4). The single command
`RecordMaturity` records the buyer's full maturity repayment and transitions the listing
`disbursed → matured_payment_received` (BC1 `Listing.Matured`, driven by the BC4 maturity inflow).

**State machine (DB-enum-true):** `deal_listing`: `disbursed → matured_payment_received`.

**Does NOT own (deferred, documented):** the TDS investor **distribution** (DraftDistribution / ApprovePayout
/ per-leg execution / `Distribution.Completed`, S.9/PI.3 — needs BC12/Tax, M16) and the deal **Close**
(coupled to distribution via `terminal_outcome='distributed'`); the **reconciliation/remediation engine**
(ReconciliationLedger RL.1-3, RemediationCase RC.1-3, discrepancy detection V.2, partial/failed legs PI.6);
the **payout/maturity webhooks** + **EoD overlay** (PI.7) + **T+1 SLA** (PI.8); the **maturity shortfall →
Collections** branch (BC6/M14, `col_maturity_case`, L.13); the subscription `assignment_executed` /
`distribution_received` advances (gated on the deferred distribution); **VirtualAccount.Close** (V.3 — needs
the listing terminal).

## 2. Upstream dependencies
- **WS-7** disbursement (`disbursed`). Done.
- **M4** roles `ops_executive`. The gateway (idempotency / MFA / SoD / audit).

## 3. Invariants & rules (BC4/BC1 — M13 scope)
- **MAT.1 — `RecordMaturity` valid only from `disbursed`.** Idempotent on `X-Command-Id`. _(Spec §6.4)_
- **MAT.2 — Full repayment.** The recorded amount must equal the invoice `face_value` (the buyer repays the
  full invoice at maturity; the discount was the investors' return). Under-payment → reject (shortfall is
  M14 Collections). _(L.13)_
- **L.1 — `disbursed → matured_payment_received`** is a forward listing transition; rowcount-asserted. _(Spec §6.4)_
- **Audit** — one `listing.Listing.Matured` envelope per command, carrying the repayment amount + UTR (the
  durable record of the buyer's repayment; the VA maturity-inflow ledger + reconciliation are deferred).

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Effect |
|---|---|---|
| `POST /listings/{id}/record-maturity` (body `amount_paise`, `utr`) | ops_executive | MAT.1/MAT.2: listing `disbursed → matured_payment_received`; emits `Listing.Matured` |

## 5. Five non-negotiables — applicability
SoD (ops), idempotent (`X-Command-Id`), audit (one `Listing.Matured` envelope). `RecordMaturity` is a
single-actor recorded inflow (money **in** from the buyer), not a maker-checker money-out pair. MFA-fresh via
the gateway. No floats — the repayment is paise-exact and asserted `== face_value`.

## 6. Sub-slices (single slice)

- **M13-A · Maturity recording** ([[DL-BE-054]]) — `record-maturity` (ops): `disbursed →
  matured_payment_received` on full repayment (`amount == face_value`), `Listing.Matured` envelope. **Red:**
  full repayment → matured_payment_received; record-maturity on a non-disbursed listing → reject; an
  under-payment (< face_value) → reject (shortfall → M14); idempotent replay; SoD.

## 7. Test scenarios (write first) — `ListingMaturityTest` (seed a `disbursed` listing with a known
`face_value`). Each invariant test proves the app guard; the transition is rowcount-asserted.

## 8. Definition of Done
- [x] §7 tests green; whole suite green — **260** (was 255 at M12 close; +5).
- [x] `/code-review` on the diff — **clean** (no findings).
- [x] SoD, idempotency, audit — enforced + tested incl. reject paths; the transition rowcount-asserted.
- [x] No new migration.
- [x] `DL-BE-054` added; spec flipped to **Status: Done**.

## 9. Remaining BC4 work after this slice (the large deferred surface — a future "M13-distribution" + "M13-reconciliation")
- **TDS investor distribution** (DraftDistribution + ApprovePayout maker-checker, per-investor legs, the
  TDS snapshot S.9/PI.3, inline execution → `distributed`) — **blocked on BC12 (Tax, M16)**.
- **Deal Close** (listing → `closed` w/ `terminal_outcome='distributed'`; VA → `closed`, V.3) — coupled to
  distribution.
- **Reconciliation/remediation engine** — ReconciliationLedger (RL.1-3), RemediationCase (RC.1-3),
  discrepancy detection (V.2), partial/failed legs (PI.6), payout webhook + EoD overlay (PI.7), T+1 SLA (PI.8).
- **Maturity shortfall → Collections** (BC6/M14, `col_maturity_case`, the delay/default states, L.13).
- **Subscription post-assignment lifecycle** (`assignment_executed` → `distribution_received` → `closed`).
- **VirtualAccount maturity-inflow ledger** (V.2 buyer-maturity-expected) + VA close.
