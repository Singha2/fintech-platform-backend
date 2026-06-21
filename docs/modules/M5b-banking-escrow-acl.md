# M5b · Banking / Escrow ACL (BC18) — stubbed escrow behind a real port

> **Lean module spec** — *foundation* (the money-movement ACL: virtual accounts, payouts, inflow
> observations). Low rigor (stub), but money-adjacent — the **idempotency + webhook-dedup** invariants
> are the headline. See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test first.

| | |
|---|---|
| **Module** | M5 — Integration ACLs, stubbed (BC15/17/18/19) |
| **Slice** | M5b — Banking/Escrow ACL (BC18): real `EscrowPort`, fake deterministic in-process adapter |
| **Tier** | Foundation (low rigor — stub; idempotency/dedup invariants matter) |
| **Status** | Done (impl test-first + tests green; `/code-review` findings fixed; DL-BE-025) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> Second M5 slice (after [[M5a-verification-acl]]). It **reuses M5a's ACL shape** — a fixed service
> (persistence, idempotency, audit) + a swappable vendor client (`Stub…` now, real escrow later, DL-046)
> — and adds the **inbound-webhook substrate**: deterministic webhooks carrying **fake UTRs**, dedup on
> `vendor_event_id` (VI.3). The **sole Phase-1 consumer is BC4 Settlement (M13, not built)**; M5b ships
> the port BC4 will call. **Heaviest M5 slice** — see §9.1 for the split option.

## 1. Scope
**Owns:**
- **`EscrowPort`** (outbound, BC4 calls): `createVa`, `closeVa`, `instructPayoutSingle` (supplier
  disbursement), `instructPayoutMultiLeg` (investor distribution, N legs / one instruction),
  `instructRefund`. Each is keyed by a caller-supplied **`client_instruction_id`** (= the
  `vendor_instruction_id` PK, the idempotency key, VI.1).
- **`gate_vendor_instruction` lifecycle** — `pending → sent → executed | failed`; `vendor_event_id`
  (UNIQUE) set when the confirming webhook arrives; `vendor_payload_hash` (hash only, VI… /BC16 later).
- **Inbound webhook handlers** — `processPayoutWebhook` / `processRefundWebhook` / `processInflowWebhook`:
  **dedup on `vendor_event_id`** (first-write-wins) → a duplicate emits `Webhook.DuplicateDropped` and
  changes no state (VI.3, C22, G1); the first applies the outcome (UTR, executed) + audits.
- **`gate_inflow_observation`** — inbound funds at a VA recorded **provisional** (amount > 0,
  `positive_money_paise`); deduped on `vendor_event_id` **and** `utr` (UNIQUE). Status stays
  `provisional` — the `reconciled`/`unmatched` transition is **BC4 reconciliation (M13)**.
- **`StubEscrowVendorClient`** (the swappable seam) — deterministic IFSC + account number on `createVa`;
  payouts/refunds **auto-succeed** and fire a deterministic webhook with a **fake UTR** in-process.
- **Audit envelopes** — `banking.Va.LifecycleObserved`, `banking.PayoutLegWebhookProcessed`,
  `banking.RefundWebhookProcessed`, `banking.InflowWebhookProcessed`, `banking.Webhook.DuplicateDropped`.

**Does NOT own (deferred / other slice):**
- **Reconciliation** — `provisional → reconciled | unmatched`, the EoD **master-statement** parser, the
  authoritative ledger, corrective overlays (`corrects`, X15) → **BC4 Settlement (M13)**. M5b leaves
  inflows provisional; `fetch_master_statement` (stub) returns a deterministic statement only.
- **Multi-leg partial-failure remediation** (VI.5, G11 — Manual Remediation queue under T&S) → **BC4/ops**;
  the stub auto-succeeds **all** legs.
- **TDS challan** webhook + per-investor TDS snapshot (VI.6, DL-045) → **BC12/M16 + BC4**.
- **HTTP webhook ingress** — `/webhooks/banking/{vendor}/{event}` routes, HMAC over `(timestamp||body)`
  (C10), the **5-min replay window** (A2 §1.2), the dead-letter queue → **the real adapter** (the stub
  fires `processXWebhook` in-process; the real HTTP handler calls the same method after HMAC).
- **The payout-approval maker-checker + MFA** — that is **BC4** `PayoutInstruction` (PI.5, T&S
  maker-checker, M4d gate); the ACL only **executes an already-approved instruction**.
- **Verbatim payload archival** → BC16 Documents (hash only here).
- **The other ACLs** (Verification done, Signing BC19, Notifications-full BC15) → M5a/c/d.

## 2. Upstream dependencies
- **M2 Audit Log**, **M1a/b** (`Ids`, `Money`/paise, errors) — Done. **M5a** — Done (the ACL pattern this reuses).
- **M0 schema** — Done: `gate_vendor_instruction`, `gate_inflow_observation` (+ enums, `positive_money_paise`) — V4. **No new migration.**
- **BC4 Settlement** (the consumer) — **not built (M13)**; M5b ships the port + stub. Tests drive it directly with synthetic VA/payout refs.

## 3. Invariants & rules
- **INV-1 — `client_instruction_id` idempotency (VI.1, C9).** `vendor_instruction_id =
  client_instruction_id`; re-issuing the same id is a **no-op that returns the original outcome**,
  never a double-execution. _(ref: DB PK; A2 §2.3)_
- **INV-2 — `vendor_event_id` webhook dedup (VI.3, C22, G1).** A duplicate webhook → `Webhook.DuplicateDropped`,
  **no state change**; first-write-wins. _(ref: DB UNIQUE `vendor_event_id`)_
- **INV-3 — Inflows are provisional + deduped (IO.2).** Recorded `provisional`; `reconciled`/`unmatched`
  is BC4's (deferred). Deduped on `vendor_event_id` **and** `utr`. _(ref: DB UNIQUE on both; C23/G6)_
- **INV-4 — Inflow amount > 0.** A zero/negative inflow is a vendor anomaly, rejected. _(ref: DB `positive_money_paise`)_
- **INV-5 — HMAC before state mutation (VI.2).** `hmac_verified_at` set before the row is mutated — for
  the stub, stamped at the in-process webhook; the real HMAC ingress is the real adapter (§9.4). _(ref: C10)_
- **INV-6 — The vendor model never leaks (ACL rule).** BC4 calls domain ops; no vendor type crosses
  `EscrowPort`. _(ref: A1/B1)_
- **INV-7 — Every instruction + webhook is audited.** _(ref: non-negotiable #5; C1)_

## 4. API / type surface
- **Port (outbound):** `EscrowPort` — `createVa(clientInstructionId, vaRef) → VaResult{ifsc, accountNo}`;
  `instructPayoutSingle(clientInstructionId, payoutRef, amountPaise, beneficiary) → PayoutResult{utr,status}`;
  `instructPayoutMultiLeg(clientInstructionId, payoutRef, legs) → MultiLegResult`;
  `instructRefund(clientInstructionId, inflowRef, amountPaise) → RefundResult{utr}`; `closeVa(…)`.
- **Inbound (the ACL's webhook entry; real HTTP handler calls these post-HMAC):**
  `processPayoutWebhook(vendorEvent)`, `processRefundWebhook(vendorEvent)`,
  `processInflowWebhook(vaRef, amountPaise, utr, vendorEventId)` → dedup + apply.
- **Swappable seam:** `EscrowVendorClient` → `StubEscrowVendorClient` (deterministic).
- **Types:** `VendorInstructionType`, `VendorInstructionStatus`, `InflowStatus`, the result records,
  `WebhookOutcome { APPLIED, DUPLICATE_DROPPED }`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | the payout **approval** four-eyes is BC4 (PI.5 / M4d); the ACL executes an approved instruction |
| 2 | MFA-fresh | no | carried by the BC4 approval command, not the ACL call |
| 3 | SoD-checked | no | — |
| 4 | Idempotent | **yes (ACL keys)** | `client_instruction_id` (VI.1) + `vendor_event_id` webhook dedup (VI.3) — not the M4a `command_id` store |
| 5 | Audit-logged envelope | **yes** | instruction + every webhook (incl. `DuplicateDropped`) |

## 6. Events (audit envelopes; no bus yet)
`banking.Va.LifecycleObserved`, `banking.PayoutLegWebhookProcessed`, `banking.RefundWebhookProcessed`,
`banking.InflowWebhookProcessed`, `banking.Webhook.DuplicateDropped`. Subscriber (BC4 Settlement) wires
up at M13. _`MasterStatement.Fetched`, `TdsChallanWebhookProcessed`, `WebhookSignature.Invalid` → with
reconciliation / the real adapter._

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] `instructPayoutSingle` → a `gate_vendor_instruction` (executed) with a deterministic fake UTR, a
      `PayoutLegWebhookProcessed` envelope (INV-1, INV-7).
- [ ] Idempotent retry: same `client_instruction_id` again → no second instruction/UTR, original
      outcome returned (INV-1).
- [ ] Webhook dedup: `processPayoutWebhook` twice with the same `vendor_event_id` → second is
      `DUPLICATE_DROPPED`, a `Webhook.DuplicateDropped` envelope, no state change (INV-2).
- [ ] Inflow: `processInflowWebhook` → a `provisional` `gate_inflow_observation` (amount > 0, UTR set);
      a duplicate `vendor_event_id` **or** `utr` → dropped (INV-3, INV-4).
- [ ] `createVa` → deterministic IFSC + account number, a `Va.LifecycleObserved` envelope; no vendor
      type crosses the port (INV-5, INV-6).
- [ ] Zero-amount inflow → rejected by the `positive_money_paise` domain (INV-4).

## 8. Definition of Done (foundation, low rigor)
- [x] §7 tests green — `BankingAclTest` (6, written test-first: red → green); 122 total green.
- [x] `/code-review` on the diff; findings fixed (atomic `ON CONFLICT` idempotency claim — no
      exists()-TOCTOU; multi-leg retry re-reads UTRs in `leg_index` order — no misattribution;
      DuplicateDropped filed under the original inflow id).
- [x] `DL-BE-025` entry (the escrow stub pattern, client_instruction_id + vendor_event_id idempotency,
      provisional inflows, the BC4-reconciliation / TDS / remediation / HMAC-ingress deferrals).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED: one slice, with a split option.** M5b is the heaviest M5 slice; if it balloons,
   split **outbound** (VA + payouts/refund + their webhooks) from **inflow** (observations + dedup). Held
   together for now because they share `gate_vendor_instruction`'s dedup substrate.
2. **Migration — RESOLVED: none.** Both tables + enums + `positive_money_paise` exist (V4).
3. **Stub shape — RESOLVED: deterministic.** Fixed `EscrowAclService` (persistence/idempotency/audit) +
   swappable `EscrowVendorClient`; `StubEscrowVendorClient` returns a deterministic IFSC/account and a
   fake UTR, firing the webhook in-process. Only the client is swapped at Production.
4. **Webhook ingress — RESOLVED: deferred.** The `processXWebhook` methods are the entry; the real
   adapter adds the `/webhooks/banking/...` routes + HMAC + 5-min replay (A2 §1.2) + dead-letter in front.
5. **Reconciliation — RESOLVED: BC4 (M13).** Inflows stay `provisional`; the master-statement parser,
   `reconciled`/`unmatched`, and corrective overlays (`corrects`, X15) are Settlement's.
6. **Maker-checker/MFA — RESOLVED: BC4.** The ACL executes an **approved** payout instruction; the
   four-eyes + MFA are on the BC4 `PayoutInstruction.approve` command (PI.5, M4d), upstream of the ACL.
7. **Multi-leg partial failure (VI.5/G11), TDS (VI.6) — RESOLVED: deferred** to BC4/M16; the stub
   auto-succeeds all legs and emits no TDS challan.

## 10. Watch-for (carry forward)
- **Reconciliation (BC4/M13)** is what makes inflows authoritative — provisional rows here are
  meaningless until Settlement reconciles them against the EoD master statement (C9/C23/G6).
- **Multi-leg remediation (G11)** — when the real adapter can partially fail, failed legs must route to
  the Manual Remediation queue (never silently dropped, VI.5).
- **Failed path** (like M5a) — `instruction → failed` + a failure envelope has no code while the stub
  can't fail; build it with the real adapter.
- **Real-adapter webhook stack** — HMAC, 5-min replay, dead-letter, the HTTP routes; **TDS challan**; **BC16** payload archival.
- **Shared ACL substrate** — M5b adds the webhook-dedup pattern to M5a's JSON/Instant helpers; after M5b
  (the second consumer) extract a small shared base (idempotent-instruction + `vendor_event_id` dedup).
