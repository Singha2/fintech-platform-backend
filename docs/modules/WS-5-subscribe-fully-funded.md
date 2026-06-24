# WS-5 · Subscribe to 100% → fully-funded (BC2/1/18/4) — funding equality (G10) + the first inbound webhook

> **Lean sub-slice spec** (Walking Skeleton §4; = M11 min). Light tier, but the **funding-equality (G10)**
> slice *and* the platform's first **inbound webhook** (B4 §5). Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-5 (= M11 Subscription, min) |
| **Slice** | WS-5 — subscription commit → fully-funded → HMAC inflow webhook → confirmed (BC2 + BC1 + BC18 + BC4) |
| **Tier** | Light (skeleton-thin — happy path + the gate/HMAC reject paths; alternates are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-24 |

> **The G10 chain this slice proves end-to-end:**
> ```
> commit (amount = funding_target) → committed_total = funding_target → listing live→fully_funded (L.6)
> HMAC webhook inflow → EscrowAclService (dedup/audit) → settlement reconcile → VA.observed += amount
>                                                       → subscription → confirmed (S.3: expected == amount)
> G10 end-state:  Σ confirmed.amount = committed_total = VA.observed_inflow_total = funding_target  (paise-exact)
> ```
>
> **DoR decisions (settled at the gate):**
> 1. **One slice** (the full G10 chain — the end-state equality needs commit + inflow + confirm together).
> 2. **Commit actor = `ops_executive` on-behalf** (investor-initiated commit needs investor login → M11-full;
>    subscription commit has no maker-checker — it's the investor's unilateral decision).
> 3. **Over-subscription blocked at commit** (S.5): app-guard `committed_total + amount ≤ funding_target` →
>    clean 422, with the DB CHECK `deal_listing_committed_lte_target` as the backstop.
> 4. **FullyFunded fires at commit** when `committed_total == funding_target` (L.6) — "fully funded" = fully
>    *subscribed*; the money then settles via the inflow.
> 5. **First inbound webhook (B4 §5):** HMAC over `(timestamp‖body)` against a per-vendor **config secret**
>    (`platform.webhook.banking.secret`); 5-min replay window; invalid → 401 + `WebhookSignature.Invalid`.
>    Reuses the already-built `EscrowAclService.processInflowWebhook` (dedup on `vendor_event_id`/`utr`, audit).
> 6. **Provisional→reconciled collapsed** (skeleton reconciles immediately — no EoD overlay, deferred);
>    inflow auto-advances the subscription to `confirmed` (no separate confirm command).
> 7. **Cross-BC coordination via inline method calls** (webhook → EscrowAcl → settlement → subscription),
>    documented shortcut pending the event bus (Milestone 2).

---

## 1. Scope
**Owns:** `subscription` (BC2: `SubscriptionService.commit` via gateway + `confirmFromInflow`;
`SubscriptionController`); `settlement` (BC4: `SettlementService.recordReconciledInflow` — reconcile the VA +
advance the subscription); a **banking webhook ingress** (`BankingWebhookController` + `HmacVerifier`). Tables:
`sub_subscription`, plus updates to `deal_listing` (committed_total, status), `cash_virtual_account`
(observed_inflow_total), `gate_inflow_observation` (status). Reuses M5b `EscrowAclService`.

**Does NOT own (deferred):** investor self-service commit + login (M11-full); multi-investor allocation /
partial fills / concentration warnings (S.8); funding shortfall + refund; pre-confirmation cancellation; the
EoD master-statement reconciliation overlay (provisional→reconciled is collapsed); funding-window-expiry
rejection (L.9); inflow↔subscription matching beyond the single-investor case; correlation re-establishment
beyond the `va_id`→listing lookup (G24 full mapping is M2). No new migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-0/1/2/3/4** edge + patterns; **WS-4** produces the `live` listing + its VA (seeded/driven in tests).
- **M5b** `EscrowAclService.processInflowWebhook` (the inbound ACL entry — dedup/audit). Done.
- **M4a–d** roles (`ops_executive`); **M2** `AuditLog`.

## 3. Invariants & rules
- **INV-1 — Funding equality G10 (the headline).** After confirm: `Σ (confirmed sub.amount) =
  deal_listing.committed_total = cash_virtual_account.observed_inflow_total = funding_target`, paise-exact.
  _(ref: G10, X3, V.2)_
- **INV-2 — Over-subscription blocked at commit (S.5/L.2).** `committed_total + amount ≤ funding_target`
  app-guarded → 422; DB CHECK `committed_lte_target` backstops. _(ref: S.5, L.2, C12)_
- **INV-3 — Coordinated commit.** `Subscription.Committed` + the `deal_listing.committed_total` bump are one
  local transaction (the gateway tx) — over-subscription is impossible by construction, not check-then-act.
  _(ref: X1, G27)_
- **INV-4 — Min ticket (S.1).** `amount ≥ ₹10,000` (`1_000_000` paise) — app-guarded + DB CHECK. _(ref: S.1, DL-007)_
- **INV-5 — FullyFunded at exact equality (L.6).** `committed_total == funding_target` ⇒ listing
  `live → fully_funded`. _(ref: L.6)_
- **INV-6 — Confirm requires `expected_inflow_amount == amount` (S.3).** Only a *reconciled* inflow advances
  the subscription to `confirmed` (V.4); `actual_inflow_txn_ref = utr`. _(ref: S.3, V.4)_
- **INV-7 — Webhook HMAC + dedup (C10/VI.3/G1).** HMAC verified before any state mutation; invalid → 401 +
  `WebhookSignature.Invalid`. Re-delivery (same `vendor_event_id`/`utr`) → `Webhook.DuplicateDropped`, 200,
  no double count. _(ref: C10, VI.3, G1, B4 §5)_

## 4. API / type surface
- **Commands (state-changing — gateway):** `POST /listings/{id}/subscriptions/commit` (ops_executive) `{investor_id, amount_paise}` → 201 `CommandResponse`.
- **Webhook (vendor, no bearer — HMAC-authenticated, B4 §5):** `POST /webhooks/banking/{vendor}/inflow.received`
  headers `X-Timestamp`, `X-Signature`; body `{va_id, amount_paise, utr, event_id}` → 200 (even on dup).
- **Queries:** `GET /listings/{id}/subscriptions/{subscription_id}` → subscription read (status, amount).
- **Types:** `HmacVerifier`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | subscription commit is unilateral (no propose→approve); the webhook is vendor-driven |
| 2 | MFA-fresh | yes (commit) | gateway `isMfaFresh` on the ops actor. (Webhook actor = vendor, MFA N/A — HMAC instead) |
| 3 | SoD-checked | yes (commit) | `ops_executive` role gate on commit |
| 4 | Idempotent | yes | commit on `X-Command-Id`; **webhook on `vendor_event_id`** (the ACL dedup) |
| 5 | Audit-logged | yes | commit envelope (gateway) + `Listing.FullyFunded` + `InflowWebhookProcessed`/`Reconciled`/`Confirmed` |

## 6. Events
- **Publishes:** `subscription.Subscription.Committed` / `.Confirmed`; `listing.Listing.FullyFunded`;
  `settlement.VirtualAccount.InflowReconciled`; (`banking.InflowWebhookProcessed` / `.WebhookSignature.Invalid`
  / `.Webhook.DuplicateDropped` from the existing ACL).
- **Subscribes:** the webhook handler orchestrates EscrowAcl → settlement → subscription inline (no bus).

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest` (MockMvc, Testcontainers)
- [ ] **Happy path G10 (E2E):** seed a live listing + VA + active investor; commit `amount = funding_target`
      → listing `fully_funded`; POST a signed inflow webhook → subscription `confirmed`; assert
      `Σ confirmed = committed_total = observed_inflow_total = funding_target`.
- [ ] **Over-subscription:** commit `funding_target`, then a second investor commit → 422; `committed_total`
      unchanged. (Or a single commit of `funding_target + 1` → 422.)
- [ ] **Min ticket:** commit `< ₹10,000` → rejected.
- [ ] **HMAC invalid:** bad signature → 401 `signature_invalid` + `banking.WebhookSignature.Invalid` envelope;
      no inflow recorded.
- [ ] **Dedup:** POST the same `event_id` twice → 200 both; `observed_inflow_total` counted once.
- [ ] **SoD:** a non-ops actor on commit → 403 `role_not_held`.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `SubscribeFullyFundedTest` **8/8**, full suite **187**.
- [x] `/code-review` on the diff (G10 equality, HMAC, idempotency, cross-BC coordination focus); fixed —
      3 findings (stale-before-image `fully_funded` miss → atomic bump+CASE+RETURNING; float `amount_paise`
      truncation → reject; reconcile-before-VA-check orphan → reorder) + 2 regression tests.
- [x] `DL-BE-035` added (the G10 chain, the coordinated commit, the HMAC scheme + secret, the inline
      cross-BC orchestration, the deferred reconciliation overlay / multi-investor).
- [x] This spec flipped to **Status: Done**.
