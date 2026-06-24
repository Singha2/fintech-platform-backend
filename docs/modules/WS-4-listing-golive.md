# WS-4 · Listing priced + gone-live (BC1/3/4) — the first money-flow + maker-checker gate

> **Lean sub-slice spec** (Walking Skeleton §4; = M9 min, + M6 pricing + M13 VA). Light tier, but the
> **inflection slice**: the first genuine money math (the `funding_target` snapshot) and the first
> **two-endpoint maker-checker + MFA gate** (go-live). Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-4 (= M9 Listing & Invoice, min) |
| **Slice** | WS-4 — invoice → ops checks → pricing snapshot → go-live → VA (BC1 + BC3 + BC4) |
| **Tier** | Light (skeleton-thin — happy path + the gate's reject paths; alternate branches are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-24 |

> **DoR decisions (settled at the gate):**
> 1. **`funding_target` rounding = HALF_EVEN per line item.** discount and fee each rounded to the nearest
>    paise (banker's rounding), then subtracted from face_value. Unbiased; pinned + frozen at snapshot.
> 2. **Maker-checker = column-based on `deal_listing`** (`golive_maker_id`/`golive_checker_id`/
>    `golive_mfa_assertion_id` + DB CHECKs) — **no M4d extraction**. Same shape as `comp_kyc_file` (WS-1/3):
>    app-guard checker≠maker → clean 409, DB CHECK as backstop. M4d's envelope-stream gate is for
>    aggregates *without* dedicated columns; the listing has them.
> 3. **Inline VA creation** in `approve-go-live` (real BC4 subscribes to the GoneLive event — no bus yet).
> 4. **OTP per-invoice acknowledgment deferred** (needs the buyer ack-user login flow, itself deferred in
>    WS-2); the skeleton enters/exits `awaiting_acknowledgment` via ops.
> 5. **Cross-context reads** (buyer credit limit, supplier exposure cap, active pricing band) are done as
>    **documented pragmatic direct reads** in the listing service for the skeleton — to be replaced by
>    BC3/8/9 query ports + the event bus at Milestone 2 (when ArchUnit is also wired).

---

## 1. Scope
**Owns:** a new `listing` package — `ListingService` (commands via `CommandGateway`) + `ListingController`
+ a `FundingMath` helper (the rounding). Tables: `deal_invoice`, `deal_listing`; creates the
`cash_virtual_account` (BC4) inline at go-live.

**State machine (DB-enum-true):** invoice `submitted → listed`; listing `draft → awaiting_acknowledgment →
ready_for_review → live`. (The transient `operational_checks_in_progress` and the OTP `awaiting_
acknowledgment` *gate* are collapsed/deferred — see decisions 4.)

**Does NOT own (deferred):** ops-check *failure* / rejected_operational; the real per-invoice OTP
acknowledgment; the BC3 pricing-band *command* (seeded in tests; the band is created by Credit at M6-full)
and the BC8/9 credit caps (set in WS-1/WS-2); business-day funding-window math (L.8 — skeleton uses 5
calendar days); the funding-window-expiry scheduler (L.9); L.11 counterparty-active re-check at go-live
(counterparties are active by construction in the skeleton); four-eyes >₹1 Cr go-live variant (C6 — kept
under ₹1 Cr). No new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-0/1/2/3** edge + patterns (`CommandGateway`, `RequestBodies`, `AbstractEdgeHttpTest`). Done.
- **M4a–d** roles (`ops_executive`, `treasury_and_settlement`, `credit_reviewer`). Done.
- **WS-1/WS-2** supplier/buyer (active, with caps) — seeded directly in WS-4 tests.

## 3. Invariants & rules
- **INV-1 — Pricing snapshot frozen at `ready_for_review` (L.3/G20).** `snapshot-and-ready` freezes
  `pricing_snapshot{pricing_band_id, rate_bps, fee_bps, snapshot_at}`, `buyer_limit_headroom_snapshot`,
  `supplier_exposure_cap_snapshot`, and **`funding_target`**. A later pricing-band change never alters a
  frozen listing. _(ref: L.3, G20)_
- **INV-2 — `funding_target` formula + rounding (L.7/DL-024).** `funding_target = face_value −
  round(face_value·rate_bps/10000·tenor_days/365) − round(face_value·fee_bps/10000)`, each `round` =
  HALF_EVEN to integer paise (`FundingMath`, `BigDecimal`). Guarded `> 0` at the edge (not a DB 500);
  `≤ face_value` by construction. _(ref: L.7, DL-024)_
- **INV-3 — Rate within band (L.10).** `snapshot-and-ready`'s `rate_bps` must lie within the active band's
  `[rate_range_min_bps, rate_range_max_bps]` for `(buyer_id, tenor_bucket)`; `fee_bps` is the band's. _(ref: L.10)_
- **INV-4 — Go-live maker-checker + MFA (L.4/C4/C7).** `approve-go-live`: checker ∈ treasury_and_settlement
  (gateway role gate), `golive_checker_id ≠ golive_maker_id` (app 409 `checker_equals_maker` + DB CHECK),
  fresh MFA (gateway + `golive_mfa_assertion_id` column). _(ref: L.4, C4, C7)_
- **INV-5 — One VA per listing, set once (V.1/L.12).** Go-live creates `cash_virtual_account` ('created',
  `expected_inflow_total = funding_target`); `deal_listing.va_id` set once. DB UNIQUE(listing_id). _(ref: V.1, L.12)_
- **INV-6 — Idempotent + audited.** Idempotent on `X-Command-Id`; one envelope per command; create-listing
  id from the full body; no PII/raw money beyond paise in payloads. _(ref: G18, X13)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /listings` → 201 | ops_executive | `deal_invoice` 'submitted' + `deal_listing` 'draft' (supplier_id, buyer_id, invoice_number, face_value_paise, invoice_date, tenor_days) |
| `POST /listings/{id}/pass-ops-checks` | ops_executive | invoice 'submitted'→'listed'; listing 'draft'→'awaiting_acknowledgment' |
| `POST /listings/{id}/snapshot-and-ready` | ops_executive (**maker**) | freeze snapshot + `funding_target` (rate_bps in body, within band); stamp `golive_maker_id`; 'awaiting_acknowledgment'→'ready_for_review' |
| `POST /listings/{id}/approve-go-live` | treasury_and_settlement (**checker**) | checker≠maker + MFA; 'ready_for_review'→'live'; create VA; set `va_id`, `funding_window_close_at` |
| `GET /listings/{id}` | (any authenticated) | aggregate read (status, funding_target, va_id, version) |

- **Types:** `FundingMath` (the rounding). Bodies via `RequestBodies` (+ `requiredPositivePaise`).

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **yes** | go-live two-endpoint pair: maker at snapshot-and-ready, checker at approve-go-live (INV-4) |
| 2 | MFA-fresh | **yes** | gateway `isMfaFresh` on the checker + `golive_mfa_assertion_id` column |
| 3 | SoD-checked | yes | per-command role gate (ops vs treasury_and_settlement vs credit) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → `sys_command_log` |
| 5 | Audit-logged | yes | one envelope per command, before 2xx |

## 6. Events
- **Publishes:** `listing.Invoice.Submitted`; `listing.Listing.Created` / `.OpsChecksPassed` /
  `.SnapshotTaken` / `.GoneLive`; `settlement.VirtualAccount.Created`. _(no bus — VA inline)_
- **Subscribes:** none (real BC4 subscribes to `Listing.GoneLive`).

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest` (MockMvc, Testcontainers; seed supplier+buyer+band)
- [ ] **Happy path (E2E):** create → pass-ops-checks → snapshot-and-ready (ops) → approve-go-live (T&S);
      assert listing 'live', `funding_target` frozen, VA 'created' with `expected_inflow_total =
      funding_target`, `va_id` set.
- [ ] **Rounding exactness:** for a known (face_value, rate_bps, tenor_days, fee_bps), assert
      `funding_target` equals the hand-computed HALF_EVEN value to the paise.
- [ ] **Snapshot immutability:** supersede the pricing band after `ready_for_review`; `funding_target` is
      unchanged.
- [ ] **Maker-checker:** the maker (same actor) calling `approve-go-live` → 409 `checker_equals_maker`,
      listing stays 'ready_for_review'.
- [ ] **MFA on checker:** stale assertion → 401 `mfa_assertion_expired`.
- [ ] **SoD:** an ops actor (not T&S) calling `approve-go-live` → 403 `role_not_held`; a T&S actor calling
      `snapshot-and-ready` → 403.
- [ ] **Rate outside band:** `snapshot-and-ready` with `rate_bps` outside `[min,max]` → 400 (L.10).

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `ListingGoLiveTest` **10/10**, full suite **179**.
- [x] `/code-review` on the diff (money-math + maker-checker focus); findings fixed — 3 findings (int-cast
      wrap on tenor/rate → `requiredPositiveInt`; concurrent-VA 500 → reorder; `longValueExact` 500 →
      BigDecimal-to-the-end) all resolved + 2 regression tests.
- [x] `DL-BE-034` added (the rounding rule + worked example, the column-based maker-checker decision, the
      inline-VA + cross-context-read shortcuts, the deferred OTP-ack).
- [x] This spec flipped to **Status: Done**.
