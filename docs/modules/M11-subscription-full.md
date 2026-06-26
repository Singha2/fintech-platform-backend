# M11 · Subscription (BC2) — **full rigor** (Milestone 2, widen WS-5)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M11). Takes BC2 from the WS-5
> skeleton (commit → fully_funded → inflow → confirmed, the G10 chain) to the complete pre-disbursement
> subscription lifecycle: **pre-confirmation cancellation + release**, and the **funding-shortfall → refund**
> path. Spec before code; **invariant test before rule**. Umbrella decision: **DL-BE-048**; sub-slices
> claim `DL-BE-049+`.

| | |
|---|---|
| **Module** | M11 — Subscription (BC2), full rigor |
| **Predecessor** | WS-5 ([[DL-BE-035]]) — commit (coordinated, S.5) → `fully_funded` (L.6) → HMAC inflow → `confirmed` (G10/S.3) |
| **Tier** | Full (maximum rigor — Wave 1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (settled at the gate — the three forks + derived)

1. **Admin-on-behalf retained; investor self-service commit + login DEFERRED.** Ops commits/cancels on the
   investor's behalf (as WS-5; consistent with M9/M10). Investor login + self-service
   commit/cancel opens post-pilot. (User decision, 2026-06-26.)
2. **`DeclareFundingShortfall` = an Ops-triggered command; the automatic cron is DEFERRED.** The platform has
   no scheduler yet (M9/M10 deferred all crons). The full shortfall → refund path is built and testable via a
   command guarded on `now() ≥ funding_window_close_at AND committed_total < funding_target`; only the
   automatic `@Scheduled` time-trigger at the window boundary is deferred (L.8/L.9 active side). (User decision.)
3. **Concentration warnings (S.8) DEFERRED** (simpler path now; gap recorded). `concentration_warnings_at_commit`
   stays `[]`; M11-full builds the cancel + refund lifecycle only. Concentration is **soft/non-blocking**, so
   deferring it changes no money outcome. (User decision — keep the simpler option, record the gap.)
4. **(Derived) Refund money-movement runs inline via `EscrowPort.instructRefund`** (`cash_payout_kind='refund'`,
   PI.4), mirroring the WS-7 inline payout. The refund webhook + EoD overlay are deferred.
5. **(Derived) No new migration.** `sub_subscription_status` already has `cancelled_by_investor`, `refunded`,
   `closed`; `deal_listing_status` has `funding_failed_refunded`; `cash_payout_instruction` has the refund
   kind. There is **no `refund_eligible` status** — `RefundEligible` is an audit event, not a state.
6. **(Derived) No new ArchUnit boundary.** M11 reaches other contexts via existing seams (the listing/VA
   tables it already coordinates with under the monolith, and the BC18 `EscrowPort`). The BC1↔BC2 coordinated
   commit/release is the documented in-process G17 coupling (event bus is Phase-2).

---

## 1. Scope

**Owns (BC2 pre-disbursement lifecycle + its alternate branches):** the `subscription` package
(`SubscriptionService` + `SubscriptionController`), the `sub_subscription` aggregate, and the coordinated
effect on `deal_listing.committed_total` / status (commit bumps, cancel/shortfall release). Refund money
movement via the BC18 `EscrowPort` (inline).

**State machine — DB-enum-true (`sub_subscription_status`), M11 span:**

```
committed ──(inflow reconciled, WS-5)──→ confirmed
   │
   ├──(CancelByInvestor: from committed, S.2)──→ cancelled_by_investor   [release listing committed_total]
   │
   └──(funding shortfall, S.2)── committed|confirmed ──(RecordRefund)──→ refunded ──→ closed
                                                       [confirmed: inline escrow refund of the inflow]
```

**Listing coordination:** `CancelByInvestor` releases `deal_listing.committed_total -= amount` and flips
`fully_funded → live` if it drops below target (L.2 inverse of the WS-5 coordinated commit).
`DeclareFundingShortfall` flips the host listing `live → funding_failed_refunded` (guarded on the window +
under-funding).

**Does NOT own (deferred, documented):** investor self-service commit/login; concentration warnings (S.8);
the automatic funding-window-expiry `@Scheduled` cron; the refund webhook + EoD reconciliation overlay; the
post-disbursement subscription lifecycle (`assignment_executed` → M12, `distribution_received` → M13,
`loss_realised` → M14); `funds_pending`/`funds_received` micro-states (WS-5 collapsed inflow into
`committed → confirmed`).

## 2. Upstream dependencies
- **WS-5** subscription/settlement + the coordinated commit. Done.
- **BC18 Escrow ACL** — `EscrowPort.instructRefund(clientInstructionId, inflowRef, amountPaise)` + the
  `cash_payout_instruction` (kind=refund). Done.
- **M4** roles `ops_executive` (commit/cancel/declare-shortfall), `treasury_and_settlement` (record-refund — money out).

## 3. Invariants & rules (BC2, B3 §2.2 — M11 scope)
- **S.1 — `amount ≥ ₹10,000`** at commit. _(DL-007)_ — built WS-5.
- **S.2 — Transitions follow §6.5.** `cancelled_by_investor` only from `committed`/`funds_pending` (pre-funds);
  `refunded` reachable from `{committed, funds_pending, funds_received, confirmed, assignment_executed}` (G3/G13). _(Spec §6.5)_
- **S.3 — `Confirmed` ⟹ `expected_inflow_amount == amount`** reconciled. _(G10, C23)_ — built WS-5.
- **S.4 — At commit, investor active.** _(DL-005)_ — built WS-5.
- **S.5 — At commit, listing live ∧ `committed_total + amount ≤ funding_target`.** _(L.2, L.9, G10)_ — built WS-5.
- **L.2 (inverse) — Release on cancel.** A cancellation decrements `committed_total`; a `fully_funded`
  listing returns to `live`. Atomic single-statement (no stale before-image), mirroring the WS-5 commit. _(L.2, G10)_
- **L.8/L.9 — Funding shortfall.** `DeclareFundingShortfall` valid only when `now() ≥ funding_window_close_at`
  AND `committed_total < funding_target`; flips `live → funding_failed_refunded`. _(DL-017, C12)_
- **PI.4 — `kind=refund`** payout allowed only when the subscription is refund-eligible (shortfall declared). _(DL-017)_

## 4. API / type surface (intent-shaped, B4 §2.1) — new vs WS-5

| Endpoint | Role | Effect |
|---|---|---|
| `POST /subscriptions/{id}/cancel` | ops_executive | S.2: from `committed` → `cancelled_by_investor`; atomic release of listing `committed_total` (+ `fully_funded → live`) |
| `POST /listings/{id}/declare-funding-shortfall` | ops_executive | L.8/L.9 guard → listing `live → funding_failed_refunded`; emits `FundingShortfallDeclared` (+ `RefundEligible` per subscription) |
| `POST /subscriptions/{id}/record-refund` | treasury_and_settlement | host listing `funding_failed_refunded`: `confirmed` → inline `instructRefund` of the inflow → `refunded` → `closed`; `committed` (unfunded) → `refunded` → `closed` (no money) |

(`commit` / the inflow webhook → `confirmed` unchanged from WS-5.)

## 5. Five non-negotiables — applicability
SoD (ops vs treasury per command), idempotent (`X-Command-Id`; refund idempotent on the instruction id),
audit (one envelope per command + the release/shortfall transitions). Cancel/declare-shortfall are
single-actor ops (state changes, not maker-checker pairs); record-refund is treasury (money out). MFA-fresh
on the admin commands via the gateway. No floats — paise throughout; the release decrement is exact.

## 6. Sub-slices (build order — each: red tests → green → /code-review → DoD → DL)

- **M11-A · Pre-confirmation cancellation + release** ([[DL-BE-049]]) — `cancel` (ops): `committed →
  cancelled_by_investor`; atomic `deal_listing.committed_total -= amount` with `fully_funded → live` folded
  into the same statement (L.2 inverse). **Red:** cancel a committed sub on a live listing (committed_total
  drops); cancel a sub that completed a fully_funded listing (listing → live); cancel a `confirmed` sub →
  rejected (S.2, post-funds); SoD. **Build note:** the `(listing_id, investor_id)` UNIQUE is unconditional,
  so a cancelled row keeps the slot — **re-subscription by the same investor is blocked** (DuplicateKey →
  400). A partial UNIQUE (excluding cancelled/refunded) to allow re-subscription is a documented future
  migration, out of M11-full scope (no new migration).
- **M11-B · Funding shortfall → refund** ([[DL-BE-050]]) — `declare-funding-shortfall` (ops, L.8/L.9 guard)
  → `funding_failed_refunded`; `record-refund` (treasury): a `confirmed` (funded) sub → inline escrow refund
  + a `cash_payout_instruction` (kind=refund) → `refunded`; a `committed` (unfunded) sub → `refunded` (no
  money). The terminal `closed` (explicit `Close`) is **folded/deferred** — `refunded` is the money-returned
  terminal here. **Red:** declare before the window → reject; declare when fully-funded → reject; declare
  under-funded past window → funding_failed_refunded; refund a confirmed sub → escrow `instructRefund` called
  + `refunded` + a kind=refund payout row; refund idempotent (command_id replay); refund without a shortfall
  → reject; SoD (record-refund is treasury).

## 7. Test scenarios (write first) — extend `SubscribeFullyFundedTest`'s harness (seed listing→live via the
M9 flow, seed active investor). Each invariant test proves the app guard and the DB CHECK where one exists
(`committed_lte_target`, the payout kind/target CHECK). G10 stays paise-exact after a release.

## 8. Definition of Done (full tier — gate F)
- [x] §7 tests green; whole suite green — **247** (was 236 at M10 close; +11).
- [x] `/code-review` (high recall); 1 fix (deterministic refund-escrow id → no double-refund + PK backstop), 1 documented (DL-BE-050).
- [x] SoD, idempotency, audit — enforced + tested incl. reject paths; release/refund are paise-exact.
- [x] No new migration.
- [x] `DL-BE-048` umbrella + `DL-BE-049..050` per slice; spec flipped to **Status: Done**.

## 9. Remaining gaps after M11-full (documented, with owner)
- **Investor self-service commit + login** → investor-portal slice.
- **Concentration warnings (S.8)** → a later BC2 pass (soft, non-blocking; per-buyer/supplier/invoice).
- **Automatic funding-window-expiry `@Scheduled` cron** → scheduler-era (with the KYC-refresh + SLA schedulers).
- **Refund webhook + EoD reconciliation overlay** → settlement-full (M13).
- **Post-disbursement subscription lifecycle** (`assignment_executed`/`distribution_received`/`loss_realised`)
  → M12 / M13 / M14.
