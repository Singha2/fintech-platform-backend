# M9 · Listing & Invoice (BC1) — **full rigor** (Milestone 2, widen WS-4)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M9). Takes BC1 from the
> WS-4 skeleton-thin happy path to its **complete spec**: every state-machine path, every invariant,
> every reject/alternate branch — to DoD. Spec before code; **invariant test before rule**.
> Umbrella decision: **DL-BE-038**; each sub-slice claims its own subsequent `DL-BE-039+`.

| | |
|---|---|
| **Module** | M9 — Listing & Invoice (BC1), full rigor |
| **Predecessor** | WS-4 ([[DL-BE-034]]) built the happy path `draft → awaiting_acknowledgment → ready_for_review → live` + the go-live maker-checker |
| **Tier** | Full (maximum rigor — Wave 1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-24 |

---

## DoR decisions (settled at the gate — the four scope forks + derived choices)

1. **BC query ports + ArchUnit — IN SCOPE (now).** M9 is the first Milestone-2 module and the consumer
   of the cross-context reads DL-BE-034 deferred. We extract read-only **query-port interfaces** for the
   three cross-BC reads and move the direct `JdbcTemplate` reads behind them, then stand up the **ArchUnit**
   harness that forbids a BC package from reaching another BC's internals. Sets the architectural pattern
   for all of Milestone 2.
   - `credit.PricingQueryPort` (BC3): `activeBand(buyerId, tenorBucket) → Band?`.
   - `buyer.BuyerQueryPort` (BC9): `creditLimitPaise(buyerId)`, `isActive(buyerId)`.
   - `supplier.SupplierQueryPort` (BC8): `exposureCapPaise(supplierId)`, `isActive(supplierId)`.
   - These are **read-only** ports (queries, not commands) — no maker-checker. Event-bus coupling stays out;
     synchronous reads through the ports are the Phase-1 shape (G17 monolith).
2. **Buyer acknowledgment = admin-captured only (DL-019).** Build the full `awaiting_acknowledgment →
   {received | acknowledgment_failed}` branch + SLA, but the ack is **recorded by an Ops admin on the
   buyer's behalf** (DL-019). The ack-user (OTP-only) login + real per-invoice OTP ack stays **deferred** to
   the buyer-portal slice. Ack evidence is stored in `deal_invoice.check_outcomes['buyer_ack']` (JSONB) — no
   new migration.
3. **Funding window = business days (L.8); scheduler deferred.** Build a shared-kernel **`BusinessDate`**
   helper (weekend + holiday-calendar aware) so `funding_window_close_at = GoneLive_at + 5 business days`.
   The window-expiry **scheduler + `DeclareFundingShortfall`** (L.9 → `funding_failed_refunded`) is
   **deferred to M11** (subscription), where the funding lifecycle lives. The *guard* L.9 (reject a commit
   after the window) is already exercised in WS-5 and re-asserted at M11.
4. **C6 four-eyes ≥ ₹1 Cr — DEFERRED.** Go-live stays the single maker-checker pair from WS-4. C6's
   second-approver branch for high-value listings is a documented **remaining gap** (see §10), picked up
   with the M4d four-eyes engine.
5. **(Derived) No new migration.** The schema (V1–V6) already carries every `deal_listing_status` /
   `deal_invoice_status` enum value, the `check_outcomes` JSONB, and the maker-checker columns this module
   needs. Ack + per-check evidence live in `check_outcomes`. If a slice surfaces a genuinely missing column,
   it reserves V7 with its own DL note — not expected.
6. **(Derived) DB-enum-true state names win over the prose state machine.** The Bounded_Contexts_Reference
   prose lists `ops_checks_passed → acknowledged → priced → ready_for_review`; the DB enums have **no**
   `acknowledged`/`priced` listing states. We follow the enums: pricing + snapshot collapse into
   `snapshot-and-ready` (`awaiting_acknowledgment → ready_for_review`), exactly as WS-4 — ack is a
   precondition recorded in `check_outcomes`, not a distinct listing state.

---

## 1. Scope

**Owns (BC1, the intake → go-live span + its alternate branches):** the `listing` package
(`ListingService` + `ListingController` + `FundingMath`), the `deal_invoice` and `deal_listing` aggregates,
and the inline `cash_virtual_account` creation at go-live. Adds the operational-check sub-machine, the
buyer-acknowledgment branch, the pricing/snapshot via ports, and the go-live L.11 hold.

**State machine — DB-enum-true (this module's span):**

```
deal_invoice:  submitted → ops_checks_in_progress → { ops_checks_passed → listed | ops_checks_failed }

deal_listing:  draft
                 └─(start ops checks)→ operational_checks_in_progress
                       ├─(all 7 checks pass)→ awaiting_acknowledgment
                       │                          ├─(buyer ack recorded)→ (snapshot-and-ready)→ ready_for_review
                       │                          └─(ack fails / SLA breach)→ acknowledgment_failed   [terminal-M9]
                       └─(any check fails)→ rejected_operational                                       [terminal-M9]
                 ready_for_review ─(approve-go-live: maker-checker + MFA)→ live
                                  └─(L.11: counterparty inactive at go-live)→ held_for_review
```

**Downstream listing states are NOT owned here** (`fully_funded`, `disbursed`, `matured_payment_received`,
`distributed`, `closed`, the delay/outcome sub-states): they are driven by M11/M12/M13/M14 reacting to
their own events. M9 owns only the transitions up to `live` (+ `held_for_review`).

**Does NOT own (deferred, with the owning module):** the funding-window-expiry scheduler +
`DeclareFundingShortfall` (M11); C6 four-eyes ≥ ₹1 Cr (M4d four-eyes engine); the real ack-user OTP login
(buyer-portal slice); `CancelPreDisbursement` from `fully_funded`/`held_for_review` (needs the post-funding
states — M11/M13); the event-bus subscribers for `HoldForReview` on in-flight BC8/9 suspension (the
**synchronous** go-live re-check is owned here; the asynchronous mid-flight suspension hold is M-bus).

## 2. Upstream dependencies
- **WS-0..7** edge + patterns (`CommandGateway`, `RequestBodies`, `AbstractEdgeHttpTest`, `RoleResolver`). Done.
- **M5a Verification ACL (BC17)** — `VerificationApi.VERIFY_IRN` / `VERIFY_EWAY_BILL` already exposed. Done.
- **M4a–d** roles (`ops_executive`, `treasury_and_settlement`). Done.
- **BC8/BC9/BC3 reads** — buyer/supplier/pricing data; consumed via the **new query ports** (slice A).
  Counterparties + bands are seeded in tests (upstream context state) as in WS-4.
- **M5d Notifications (BC15) stub** — `RequestBuyerAcknowledgment` emits an ack-request notification.

## 3. Invariants & rules (BC1, B3 §2.1 — the full set)

### Invoice (deal_invoice)
- **INV.1 — Duplicate check.** `(supplier_id, irn)` unique when `irn` present; else
  `(supplier_id, buyer_id, invoice_number, face_value, tenor_days)` unique. Surfaces as a clean reject, not a
  DB 500. _(DL-016, DL-027, C24)_
- **INV.2 — `face_value > 0`; `tenor_days ∈ [1,180]`.** Edge-validated + DB CHECK / `positive_money_paise`. _(DL-016)_
- **INV.3 — `due_date == invoice_date + tenor_days`** (calendar; BusinessDate not applied to due_date per DL-016). _(DL-016)_
- **INV.4 — Invoice transitions** `submitted → ops_checks_in_progress → {ops_checks_passed | ops_checks_failed}; ops_checks_passed → listed`. No backward moves. _(DL-027)_
- **INV.5 — `ops_checks_passed` requires every check in the DL-027 set = passed:** IRN validity, e-way bill
  match, buyer-supplier relationship, duplicate, supplier exposure cap, buyer limit headroom, document
  completeness (7 checks). _(DL-027, C24)_
- **INV.7 — IRN, when present, verified via BC17 `verify_irn` before `ops_checks_passed`.** Self-attested IRN
  never accepted. _(C24, A2 §1.3)_

### Listing (deal_listing)
- **L.1 — State transitions follow §1; no skips, no backward moves; terminal states accept no further commands.** _(Spec §6.4)_
- **L.2 — `committed_total ≤ funding_target`** (asserted in WS-5/M11; DB CHECK present). _(DL-017, C12, G10)_
- **L.3 — Snapshot immutability.** After `ready_for_review`, the four snapshot fields (`pricing_snapshot`,
  `buyer_limit_headroom_snapshot`, `supplier_exposure_cap_snapshot`, `funding_target`) are immutable. _(G20)_
- **L.4 — Go-live maker-checker + MFA.** checker ∈ Treasury & Settlement, checker ≠ maker, fresh MFA. _(C4, C7)_
- **L.7 — `funding_target` formula + HALF_EVEN per line item** (frozen at snapshot). _(DL-024, G20)_ — built in WS-4.
- **L.8 — `funding_window_close_at = GoneLive_at + 5 business days`** via `BusinessDate`. _(DL-017, C12)_
- **L.9 — commit rejected after `funding_window_close_at`** (guard exercised WS-5; scheduler deferred to M11). _(C12, DL-017)_
- **L.10 — `rate_bps` within the active band** for `(buyer_id, tenor_bucket)` at snapshot. _(DL-024, BC3)_ — built WS-4, re-routed via port.
- **L.11 — buyer + supplier `active` at GoneLive;** if either inactive → `HoldForReview` (`held_for_review`).
  Synchronous re-check owned here. _(DL-014, DL-018)_
- **L.12 — `va_id` set exactly once at GoneLive, never cleared until terminal Close.** DB UNIQUE(listing_id). _(C8, DL-043)_ — built WS-4.

### Architecture
- **ARCH.1 — No cross-BC table joins / internals access.** The `listing` BC reaches buyer/supplier/credit
  data only through the read-only query ports; enforced by **ArchUnit**. _(B1, C-context-isolation)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /listings` → 201 | ops_executive | `deal_invoice` 'submitted' + `deal_listing` 'draft' (+ INV.1 duplicate reject; optional `irn`) |
| `POST /listings/{id}/start-ops-checks` | ops_executive | listing 'draft'→'operational_checks_in_progress'; invoice 'submitted'→'ops_checks_in_progress' |
| `POST /listings/{id}/record-ops-check` | ops_executive | record one DL-027 check outcome into `check_outcomes` (check_name, outcome, evidence_ref?); IRN/e-way call BC17 |
| `POST /listings/{id}/complete-ops-checks` | ops_executive | RunChecksCompletionCheck: all 7 passed → invoice 'ops_checks_passed'→'listed', listing →'awaiting_acknowledgment'; else → invoice 'ops_checks_failed', listing →'rejected_operational' |
| `POST /listings/{id}/request-buyer-ack` | ops_executive | listing stays 'awaiting_acknowledgment'; stamp `ack_requested_at` + `ack_sla_hours`; emit BC15 notification |
| `POST /listings/{id}/record-buyer-ack` | ops_executive | admin-captured (DL-019): outcome acknowledged → stamp `buyer_ack` in `check_outcomes`; outcome failed / SLA breach → listing →'acknowledgment_failed' |
| `POST /listings/{id}/snapshot-and-ready` | ops_executive (**maker**) | requires `buyer_ack` recorded; freeze snapshot + `funding_target` (rate within band via port); stamp `golive_maker_id`; →'ready_for_review' |
| `POST /listings/{id}/approve-go-live` | treasury_and_settlement (**checker**) | checker≠maker + MFA; L.11 re-check via ports → 'live' (+ VA, business-day window) **or** →'held_for_review' |
| `GET /listings/{id}` | (any authenticated) | aggregate read (status, funding_target, va_id, version, check_outcomes summary) |

- **Types:** `FundingMath` (WS-4); `BusinessDate` (shared kernel, slice B); `OperationalCheck` enum (the 7
  DL-027 checks); `PricingQueryPort` / `BuyerQueryPort` / `SupplierQueryPort` (slice A).

## 5. Five non-negotiables — applicability
| # | Control | Applies | How |
|---|---|---|---|
| 1 | Maker-checker | **yes** | go-live pair (maker @ snapshot-and-ready, checker @ approve-go-live); each ops-check/ack command is single-actor ops (not a money state change) |
| 2 | MFA-fresh | **yes** | gateway `isMfaFresh` on the checker + `golive_mfa_assertion_id` |
| 3 | SoD | yes | per-command role gate (ops vs treasury) |
| 4 | Idempotent | yes | `X-Command-Id` on every command; `record-ops-check` idempotent per (listing, check_name) |
| 5 | Audit-logged | yes | one envelope per command before 2xx; every state change |

## 6. Events
- **Publishes:** `listing.Invoice.Submitted` / `.OperationalChecksPassed` / `.OperationalChecksFailed`;
  `listing.Listing.Created` / `.OpsChecksStarted` / `.AcknowledgmentRequested` / `.AcknowledgmentReceived` /
  `.AcknowledgmentFailed` / `.SnapshotTaken` / `.GoneLive` / `.HeldForReview`; `settlement.VirtualAccount.Created`.
- **Subscribes:** none (synchronous; no bus in Phase 1).

## 7. Sub-slices (build order — each: red tests → implementer green → /code-review → DoD → DL)

- **M9-A · Query ports + ArchUnit** ([[DL-BE-039]]) — extract `PricingQueryPort`/`BuyerQueryPort`/
  `SupplierQueryPort`, refactor `ListingService` onto them, stand up the ArchUnit harness (ARCH.1). No
  behaviour change; `ListingGoLiveTest` stays green. **Red:** ArchUnit rule fails on a deliberate cross-BC
  read, passes after the refactor.
- **M9-B · BusinessDate + business-day funding window** ([[DL-BE-040]]) — shared-kernel `BusinessDate`
  (weekend + seeded holiday calendar); go-live sets `funding_window_close_at = +5 business days` (L.8).
  **Red:** a go-live on a Thursday lands the close 5 business days out (skips the weekend), exact.
- **M9-C · Operational checks sub-machine** ([[DL-BE-041]]) — `operational_checks_in_progress`, the 7
  DL-027 checks in `check_outcomes`, IRN/e-way via BC17 (INV.7), `complete-ops-checks` →
  `awaiting_acknowledgment` (all pass) or `rejected_operational` (any fail). Replaces WS-4's collapsed
  `pass-ops-checks`. **Red:** all-pass path; one-fail → rejected_operational; missing check → blocked;
  IRN absent-but-required reject; duplicate (INV.1).
- **M9-D · Buyer acknowledgment (admin-captured)** ([[DL-BE-042]]) — `request-buyer-ack` (+ BC15 notify,
  SLA) / `record-buyer-ack` (DL-019) → received (stamp) or `acknowledgment_failed`; `snapshot-and-ready`
  now requires ack recorded. **Red:** ack-then-snapshot happy; snapshot-without-ack reject; SLA-breach →
  acknowledgment_failed; ack-fail → acknowledgment_failed.
- **M9-E · Go-live L.11 hold + reconcile** ([[DL-BE-043]]) — go-live re-checks buyer+supplier `active` via
  ports; inactive → `held_for_review` (no VA, no window). Wire the full happy path E2E through the new
  sub-machine. **Red:** suspended buyer at go-live → held_for_review (no VA); both-active → live.

## 8. Test scenarios (write first) — `ListingFullRigorTest extends AbstractEdgeHttpTest`
Happy E2E (create → start-ops → 7×record-ops → complete-ops → request-ack → record-ack → snapshot-and-ready
→ approve-go-live → live + VA + business-day window) **plus** the reject matrix in §7 per slice. Reuse the
WS-4 seed helpers (`seedActiveSupplier/Buyer/PricingBand`, `seedAdminWithRoles`, `bearerFor`). Keep
`face_value < ₹1 Cr` (C6 deferred). Each invariant test proves **both** the app guard and (where present)
the DB CHECK fire.

## 9. Definition of Done (full tier — gate F)
- [x] §8 tests green; whole suite green — **224** (was 198 at Milestone 1; +26 across M9-A..E).
- [x] `/code-review` (high effort, 8 angles) on the full M9 diff; 4 findings fixed, 1 refuted (DL-BE-043).
- [x] Maker-checker, SoD, MFA, idempotency, audit chain — enforced + tested incl. reject paths.
- [x] ArchUnit harness green; `listing` BC has no cross-BC table reads (ARCH.1 — the one remaining direct
      `buyer_ack_user` read was caught in review and routed through `BuyerQueryPort`).
- [x] No new migration (schema V1–V6 already carried every enum value + `check_outcomes` + the columns).
- [x] `DL-BE-038` umbrella + `DL-BE-039..043` per slice added; spec flipped to **Status: Done**.
- [x] Spec updated as-built.

## 10. Remaining gaps after M9-full (documented, with owner)
- **Invoice PDF artifact** — M9's `document_completeness` ops-check is a **checkbox**; the real invoice
  document (upload by Ops Executive, download by KYC'd investors) is **not** stored. → **M19 Invoice
  Artifacts** (consumes **M18 Documents/BC16**); M19 wires this check to a hash-verified artifact.
- **C6 four-eyes ≥ ₹1 Cr go-live** → M4d four-eyes engine.
- **Funding-window-expiry scheduler + `DeclareFundingShortfall`** (L.9 active side) → M11.
- **Real ack-user OTP login + per-invoice OTP ack** → buyer-portal slice.
- **Async `HoldForReview` on in-flight BC8/9 suspension** + `CancelPreDisbursement` → event-bus + M11/M13.
- **Event bus** replacing synchronous port reads → Phase-2 (G17/G27).
