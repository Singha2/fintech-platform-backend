# M6 · Credit & Underwriting (BC3) — **write side** (Milestone 2)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M6). BC3 was read-port-only (the
> M9-A query ports read `risk_pricing_policy` / `buyer_account` / `sup_account`; the bands + limits were only
> *test-seeded*). This builds the **BC3 write side** — the Credit Reviewer's policy commands: **SetPricingBand**
> (the genuine gap M9 depends on) and the **buyer/supplier credit profiles**. **Four-eyes (BCP.2/SCP.2) is
> deferred** at the user's request (recorded — see §9). Umbrella decision: **DL-BE-059**.

| | |
|---|---|
| **Module** | M6 — Credit & Underwriting (BC3), write side |
| **Predecessor** | M9-A ([[DL-BE-039]]) — BC3 read ports (`PricingQueryPort` etc.); WS-1/WS-2 onboarding recorded credit values directly |
| **Tier** | Full-rigor (four-eyes + DefaultCase deferred — see DoR) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions

1. **Four-eyes (BCP.2 / SCP.2 / DC.2) DEFERRED — recorded (user request).** The ₹10 Cr threshold is
   **DB-enforced** (`risk_{buyer,supplier}_profile_four_eyes_required_chk`: a limit/cap > 10,000,000,000 paise
   requires `four_eyes_approval_ref` + `second_approver_id`). So M6 supports limits/caps **≤ ₹10 Cr**; a
   request **> ₹10 Cr is rejected** (422 — "four-eyes approval required, not yet built"), with the DB CHECK as
   the backstop. **This is a real compliance-control gap** — large limits cannot be set until the four-eyes /
   FourEyesApproval workflow (C6/DL-023) is built. Recorded in §9 + the DL.
2. **Pricing-band re-pricing / supersession (PB.3) DEFERRED.** `risk_pricing_policy` has a partial UNIQUE on
   `(buyer_id, tenor_bucket) WHERE superseded_by IS NULL` plus a self-FK `superseded_by`; superseding the old
   band requires it to reference the not-yet-inserted new band (a chicken-and-egg the non-deferrable index
   can't satisfy without a **V7 deferrable-unique migration**). M6 **creates** a band; a re-price (an active
   band already exists for that `(buyer, bucket)`) is rejected. Recorded in §9.
3. **DefaultCase (DC.1-4) DEFERRED** — the default-classification aggregate is the BC6 Collections /
   adjudication side (M14); out of M6 scope.
4. **Periodic-review schedulers (BCP.4 / SCP.3) DEFERRED** (non-blocking review cadence; scheduler-era).
5. **(Derived) No new migration.** `risk_pricing_policy` / `risk_buyer_profile` / `risk_supplier_profile`
   exist with the CHECKs that back PB.1 / BCP.1 / SCP.1 / the four-eyes gate.

---

## 1. Scope

**Owns:** a new write side in the `credit` package — `CreditService` (commands via `CommandGateway`) +
`CreditController` — over `risk_pricing_policy` / `risk_buyer_profile` / `risk_supplier_profile`. Credit
Reviewer role. The buyer/supplier credit profile is the **authoritative BC3 record**; setting it also
snapshots the value to `buyer_account.credit_limit_paise` / `sup_account.credit_exposure_cap_paise` (the
columns the M9-A query ports read — the inline "BC3 sets → BC8/BC9 snapshot", no bus). _(Relationship to
WS-1/WS-2: onboarding's `record-credit-assessment` / `record-credit-review` seed the snapshot at the
onboarding stage; M6's commands are the standalone authoritative credit-policy setters that also write the
`risk_*_profile` and can adjust the value any time.)_

## 2. Invariants & rules (BC3, B3 §2.3 — M6 scope)
- **PB.1 — `rate_range_min_bps ≤ max`, `min > 0`, `fee_bps ≥ 0`.** _(DL-024)_ — DB CHECKs + `bps_type` back it.
- **PB.2 — One active band per `(buyer_id, tenor_bucket)`** (partial UNIQUE `WHERE superseded_by IS NULL`). _(DL-024)_
- **BCP.1 — `credit_limit > 0`; `tenor_cap_days ∈ [1,180]`.** _(DL-022)_ — `positive_money_paise` + CHECK.
- **SCP.1 — `exposure_cap ≥ 0`.** _(DL-022)_ — `money_paise` domain.
- **BCP.2 / SCP.2 — four-eyes for > ₹10 Cr.** _(C6, DL-023)_ — **DEFERRED**: M6 rejects > ₹10 Cr.

## 3. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Effect |
|---|---|---|
| `POST /credit/pricing-bands` | credit_reviewer | SetPricingBand — insert a band (PB.1/PB.2); a re-price (active band exists) → reject (PB.3 deferred) |
| `POST /credit/buyers/{id}/profile` | credit_reviewer | upsert `risk_buyer_profile` (sector/rating/limit/tenor_cap) + snapshot `buyer_account.credit_limit_paise`; > ₹10 Cr → reject (four-eyes deferred) |
| `POST /credit/suppliers/{id}/profile` | credit_reviewer | upsert `risk_supplier_profile` (risk_rating/exposure_cap) + snapshot `sup_account.credit_exposure_cap_paise`; > ₹10 Cr → reject |

## 4. Five non-negotiables — applicability
SoD (credit_reviewer per command), idempotent (`X-Command-Id`), audit (one envelope per command), MFA via
the gateway. Money is paise (bps for rates); limits/caps asserted positive/non-negative. No four-eyes
maker-checker pair in M6 (deferred) — these are single-actor Credit Reviewer policy commands ≤ ₹10 Cr.

## 5. Sub-slices (build order)

- **M6-A · SetPricingBand** ([[DL-BE-060]]) — `POST /credit/pricing-bands` creates a `risk_pricing_policy`
  band (PB.1/PB.2). The genuine gap: M9 listing reads the active band via `PricingQueryPort`, but no command
  created it. **Red:** create a band → M9 can price against it (snapshot-and-ready succeeds); an invalid
  rate range (min>max) → reject; a re-price for an existing `(buyer,bucket)` → reject (supersession deferred);
  SoD.
- **M6-B · Buyer + Supplier credit profile** ([[DL-BE-061]]) — `set-buyer-credit-profile` /
  `set-supplier-credit-profile` upsert the `risk_*_profile` + snapshot the `buyer_account`/`sup_account`
  column the query ports read. **Red:** set a buyer limit ≤ ₹10 Cr → snapshot updated, M9 reads it; a limit
  > ₹10 Cr → 422 (four-eyes deferred); supplier cap likewise; SoD.

## 6. Test scenarios — `CreditPolicyTest extends AbstractEdgeHttpTest`. M6-A asserts a created band is
usable by the M9 listing flow (end-to-end, not just a row). Each invariant proves the app guard + the DB
CHECK where present (rate range, four-eyes).

## 7. Definition of Done (gate F)
- [x] §6 tests green; whole suite green — **279** (was 267 at M8 close; +12).
- [x] `/code-review` (high recall, 2 finders) — 4 findings fixed (date→500, money truncation, bps ceiling,
  snapshot rowcount), 1 documented (profile `aggregate_version` not bumped — endpoints aren't version-guarded).
- [x] SoD, idempotency, audit — enforced + tested incl. reject paths (SoD 403, four-eyes 422).
- [x] No new migration.
- [x] `DL-BE-059` umbrella + `DL-BE-060..061` per slice; spec flipped to **Status: Done**.

## 8. (removed)

## 9. Remaining BC3 gaps after M6 (documented, with owner)
- **Four-eyes / FourEyesApproval (BCP.2 / SCP.2 / DC.2, C6/DL-023)** — *the deferred control the user
  flagged.* M6 rejects > ₹10 Cr limits/caps; the second-approver workflow (and lifting the ceiling) is a
  follow-on (ties into the M4d maker-checker engine). **A real compliance gap until built.**
- **Pricing-band re-pricing / supersession (PB.3)** — needs a **V7** migration making the
  `(buyer_id, tenor_bucket) WHERE superseded_by IS NULL` index DEFERRABLE (so the old band can be superseded
  in the same tx as the new insert).
- **DefaultCase (DC.1-4)** — default classification / adjudication → BC6 Collections (M14).
- **Periodic credit-review cadence (BCP.4 / SCP.3)** → scheduler-era.
- **Reconcile onboarding `record-credit-assessment`/`record-credit-review`** to flow *through* BC3 (currently
  they write the snapshot directly; M6's commands are the authoritative parallel path).
