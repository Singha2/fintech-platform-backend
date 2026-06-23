# WS · Walking Skeleton — one invoice, `listed → disbursed`, **over HTTP**

> **Milestone spec** (not a single light slice). The thin vertical cut that takes **one** invoice
> end-to-end through the money-flow **and the HTTP delivery mechanism**, on top of the finished
> foundation (M0–M5). See `docs/spec/Spec_Driven_Build_Plan.md` §H Milestone 1 and
> `docs/spec/10_B4_API_Conventions.md`. Spec before code; invariant test before rule.
> Each sub-slice (WS-0…WS-7) runs the per-module loop (§D) at the *light* tier and lands to its own
> mini-DoD; the capstone is one green end-to-end test **driven over HTTP through real controllers**.

| | |
|---|---|
| **Milestone** | M1 — Walking Skeleton (highest-value first build) |
| **Touches** | the **HTTP edge** (B4) · BC1/2/3/4/5 (money-flow) · BC7/8/9 (counterparties) · BC11 (compliance, auto-approve stub) — minimal cut of M6–M13 |
| **Tier** | Light (skeleton-thin — happy path only; Milestone 2 widens each to full rigor) |
| **Status** | Draft |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-22 |

> **Why this exists.** A true walking skeleton (Cockburn / GOOS) is a thin slice through **every layer
> including the delivery mechanism** — a request enters over HTTP, walks the spine, returns a response —
> precisely to prove the thing is deployable, reachable, and wireable by the frontend contract. M1–M5
> built the hexagonal **core** (domain + application services + command/audit/maker-checker/SoD substrate
> + ACL ports), proven headless by integration tests calling services directly. **There is no HTTP
> surface yet** — zero business endpoints, only `GlobalExceptionHandler` + Actuator. This milestone adds
> the skin: it stands up the B4 command + webhook edges (WS-0) and drives one invoice
> `Listing.GoneLive → Listing.Disbursed` (the B2 §4.1 Trace A spine) **through real controllers**, under
> a single `correlation_id`. It proves the five hardest invariants early — **maker-checker**, **MFA
> freshness**, **funding equality (G10)**, **idempotency**, **audit chaining** — *at the wire boundary*,
> where the frontend will actually hit them. **No new migration** (money-flow schema is V1–V5); the only
> new persistence is whatever the edge itself needs (e.g. a webhook dedup index, if not already present).

---

## 0. Naming — how `WS-n` maps to the module register (M0–M17)

The plan has **two axes**: the **module register** (`M0–M17`, plan §C — units of functionality, each built
to full rigor *eventually*) and the **build sequence** (plan §H — the *order*). §H is explicit that after
**Wave 0 (M0–M5)** you do **not** build M6–M13 to full rigor next; you first build **Milestone 1, the
Walking Skeleton**: *"the thin vertical slice … touching the foundation (M0–M5) plus **just enough** of
M6–M13."*

So a Walking-Skeleton slice is a **thin cut across one (or several) Wave-1 modules**, not a full module.
The plan refers to these cuts as "M7 min", "M8 min", etc.; we label them `WS-0…WS-7` because a single slice
(e.g. WS-4) spans several modules and no one `M`-number fits, and to keep skeleton-thin work visibly
distinct from the later full-module work. The mapping is 1:1:

| Slice | Plan's name (§H) | Module (built *full* at Milestone 2) | BC | Status |
|---|---|---|---|---|
| **WS-0** · HTTP edge | *(added — B4 API conventions, [[DL-BE-030]])* | — (cross-cutting infra) | — | ✅ Done |
| **WS-1** · Supplier active | M7 min | M7 Supplier Onboarding | BC8 | ✅ Done |
| **WS-2** · Buyer + ack user | M8 min | M8 Buyer Management | BC9 | ✅ Done |
| **WS-3** · Investor active | M10 min | M10 Investor Onboarding | BC7 | ◻ next |
| **WS-4** · Listing priced + gone-live | M9 min (+ M6 pricing, + M13 VA) | M9 Listing & Invoice | BC1/3/4 | ◻ |
| **WS-5** · Subscribe to 100% | M11 min | M11 Subscription | BC2 | ◻ |
| **WS-6** · Assignment single-leg signed | M12 min | M12 Assignment & Signing | BC5 | ◻ |
| **WS-7** · Disbursement | M13 min | M13 Settlement | BC4 | ◻ |

**Two things to keep straight:**
- **`WS-0` is an addition, not a module-min.** It stands up the B4 HTTP edge (request envelope, error
  taxonomy, bearer auth) — there is no `M`-numbered "API module"; B4 was meant to be applied per-module.
  We front-loaded it because the foundation (M0–M5) was built headless. ([[DL-BE-030]].)
- **The `M`-numbers return at Milestone 2.** "Widen Wave 1 to full rigor" builds `M6`, `M7-full`,
  `M8-full`, … so each money-flow module is touched **twice**: once skeleton-thin here (e.g. `WS-1`), then
  to its complete spec later (`M7`). The decision log cross-references the two (e.g. [[DL-BE-031]] ↔ M7).

---

## 1. Goal — what "done" looks like

One green integration test (`WalkingSkeletonE2ETest`, **MockMvc / `TestRestTemplate`**) drives this golden
path **over HTTP**, asserting the response envelope at each hop **and** the chained `sys_audit_event`
sequence:

```
WS-0  Edge stands up: admin logs in → bearer; one command + one query round-trip the full B4 envelope
WS-1  Supplier onboarded → sup_account.status = active            (BC8, admin-on-behalf, X-Agency-Consent-Id)
WS-2  Buyer + ack user set up → buyer_account.status = active     (BC9)
WS-3  Investor onboarded → inv_account.status = active            (BC7, invite→active; KYC auto-approved)
WS-4  Invoice listed, priced (snapshot), gone live  ┐            (BC1 + BC3 pricing + BC4 VA)
        deal_listing: draft → … → ready_for_review → live        ← maker-checker + MFA #1 (go-live, 2 endpoints)
        cash_virtual_account.status = created
WS-5  Single investor subscribes 100% → fully_funded             (BC2 + stubbed escrow inflow via webhook)
        Σ confirmed sub.amount = listing.committed_total
                               = VA.observed_inflow_total = funding_target   ← G10
        deal_listing.status = fully_funded
WS-6  Assignment requested, single leg signed → all_signed       (BC5 + stubbed signing via webhook)
        legal_assignment_set.status = all_signed; deal_listing.all_signed = true
WS-7  Disbursement drafted → approved → instructed → executed    (BC4 + stubbed escrow payout via webhook)
        cash_payout_instruction: drafted → approved → … → executed  ← maker-checker + MFA #2 (payout, 2 endpoints)
        deal_listing.status = disbursed
```

**The slice is "walking" when** a real HTTP client drives it to `deal_listing.status = 'disbursed'`: every
command went through a controller carrying the B4 request envelope, **no 2xx returned before its audit
envelope was appended** (X13), the two maker-checker+MFA gates fired with proposer ≠ approver, G10 held to
the paise, and the audit chain links unbroken end-to-end under one `correlation_id`.

> The maturity → distribution → closed tail is the *same* maker-checker shape (WS-7 repeated for
> `kind = distribution`). The skeleton **stops at `disbursed`**; the tail is an optional WS-8 (deferred,
> §8) — disbursement is the load-bearing proof.

---

## 2. Scope & the minimal config

**In scope — the happy path only,** wiring the smallest application service per BC **plus a thin HTTP
controller** that maps the B3 command to a B4 endpoint. **Out of scope — every reject / shortfall /
timeout / cancellation branch** (deferred to Milestone 2, §8) *except* the handful of edge-level rejects
WS-0 must prove (bad headers, missing bearer, stale MFA, idempotency replay). The config is the smallest
that still exercises every cross-cutting invariant:

- **One supplier, one buyer + one acknowledgment user, one investor** (`sub_type = resident_individual`).
- **One invoice, face value < ₹1 Cr** — keeps `risk_*_profile` four-eyes (C6, threshold ₹10 Cr / `1e10`
  paise) **out** of the skeleton (co-approver columns stay null; their CHECKs pass).
- **A single subscriber funding 100%** — so `legal_assignment_set.total_count = 1`, collapsing BC5 to
  **one** signature leg and dodging the 24 h time-box (G13, AS.2), while still proving the
  `AllSigned → disbursement` gate (C27).
- **Compliance auto-approved** — the WS compliance step writes `comp_kyc_file.status = approved`
  immediately, **behind the same `record-kyc-approved` command the real BC11 engine will call at M15**
  (one-place swap, not scattered `if (stub)`). No AML/PEP.
- **All vendors stubbed** — verification (M5a), escrow inflow + payout (M5b), e-sign (M5c), notifications
  (M5d) are the in-process ACL stubs already built. In WS, the stubs' inbound results arrive **through the
  B4 webhook ingress** (the test POSTs an HMAC-signed event to `/webhooks/…`), so the inbound vendor seam
  is exercised for real. Real sandboxes swap in at the Production gate only.
- **Suitability has no mismatch** — keeps the override-ack path (IA.4 / C21 / G26) out of WS-3.

**Owns (new code):**
- `infrastructure/web` — the **HTTP edge**: a request-envelope resolver (B4 §2.2 headers), a thin
  command-controller pattern (intent-shaped routes, B4 §2.1), the success-response shape (B4 §2.3
  `emitted_events`), the full **error taxonomy** (B4 §4 — extending the existing `GlobalExceptionHandler`),
  a security filter chain (bearer → `actor` + tenant claims), and the **webhook ingress** (B4 §5 — HMAC,
  dedup, correlation re-establish), introduced at WS-5.
- Thin command services + handlers for BC8/9/7/1/3/2/5/4 happy paths, plus a `compliance/` auto-approve
  stub — all under `com.arthvritt.platform.<context>`, each fronted by a controller.

**Does not own:** any new money-flow migration (schema is V1–V5, frozen); the full state machines, reject
paths, per-invoice stamping, real vendor sandboxes, AI-agent auth (G31) — Milestone 2 / Production.

---

## 3. Upstream dependencies

| Dependency | State | Used for |
|---|---|---|
| **B4 API Conventions** (`docs/spec/10_B4_API_Conventions.md`) | spec, unimplemented | the contract WS-0 implements: envelope headers, endpoint shapes, error taxonomy, webhook ingress, maker-checker-as-two-endpoints, audit-before-2xx |
| `spring-boot-starter-web` | on classpath (M0) | the HTTP transport (embedded Tomcat) |
| **M2** Audit Log (`AuditLog`, chained `sys_audit_event`) | Done | every command's envelope (#5); the same-tx append **is** the Phase-1 realization of audit-before-2xx (X13/§7 — no separate outbox table; M2's DL-BE decision) |
| **M3a/b** Auth + sessions + MFA freshness | Done | the login endpoint (mints the bearer); the `mfa_assertion_id` the two checker steps consume; ack-user & investor OTP |
| **M4a** Command substrate (`sys_command_log`, `command_id`) | Done | `X-Command-Id` idempotency on every command (B4 §2.4) |
| **M4b/c/d** Admin IAM + RBAC + SoD + **maker-checker engine** | Done | bearer→role authz; the propose→approve pairs behind go-live (WS-4) and payout (WS-7); SoD at the command boundary |
| **M5a/b/c/d** Integration ACLs (stubs) | Done | verification facts; escrow inflow+payout & signing **delivered through the webhook ingress**; OTP/notifications |

If a sub-slice needs a substrate not yet extracted (e.g. a generic *domain* maker-checker helper — M4d's
engine is admin-IAM-shaped), that extraction is its own decision-logged step **before** the sub-slice,
mirroring the M4c disable-cascade and the M5 `AbstractAclService` moves.

---

## 4. Sub-slices (build in this order; each is test-first, light tier)

Each names what it advances, the key **B4 endpoint(s)** (intent-shaped, kebab-cased B3 command name; path
locates the aggregate — §2.1), the resulting transition (**DB-enum-true** names, quoted from the V1/V2
migrations — *not* the spec's idealized labels), and what it defers.

### WS-0 · The HTTP edge (command + query round-trip)
The reusable skin every later slice plugs into. Prove the B4 envelope end-to-end against **already-built**
services — no new domain logic.
- **Endpoints:**
  - `POST /auth/login` (+ the OTP/TOTP step per M3a/b) → returns a **bearer**. The only command-class call
    that takes no bearer (it mints the session).
  - One existing **M4 admin command** exposed through the full envelope — e.g.
    `POST /admin-users/{admin_user_id}/assign-role` — to exercise `X-Command-Id`, `X-Aggregate-Version`,
    `X-Mfa-Assertion-Id`, `X-Correlation-Id`, bearer→actor, the `emitted_events` response, and
    audit-before-2xx.
  - One **query**: `GET /admin-users/{admin_user_id}` (B4 §3.1 aggregate read).
- **Proves (edge invariants):** missing/malformed header → 400, **no envelope** (B4 §2.2/§4); bearer
  missing/expired → 401, no envelope; stale/missing `X-Mfa-Assertion-Id` on an admin command → 401
  `mfa_assertion_*`; **2xx only after the audit envelope is appended** (X13/§7); idempotent replay (same
  `X-Command-Id`, same body) → original `emitted_events`, **no second envelope** (§2.4); same `command_id`
  + different body → 409 `idempotency_conflict`; stale `X-Aggregate-Version` → 409 `version_conflict`.
- **Defers:** the webhook ingress (introduced at WS-5), AI-agent auth (G31), rate limiting, projections.

### WS-1 · Supplier active (BC8) — admin-on-behalf
- **Advances:** `sup_account.status` `created → identity_verified → kyc_submitted → kyc_approved →
  credit_reviewed → maa_signed → active`.
- **Endpoints (admin actor; on-behalf calls carry `X-Agency-Consent-Id` — B4 §2.2, DL-013/AC.1):**
  `POST /suppliers/create` (creating → 201, minted id) · `…/{id}/grant-agency-consent` ·
  `…/record-identity-verified` (← M5a) · `…/submit-kyc` → **compliance auto-approve** →
  `…/record-kyc-approved` · `…/submit-financial-profile` · `…/record-credit-review` (exposure < ₹10 Cr ⇒
  no four-eyes) · `…/record-maa-signed` (← M5c; MAA signed personally, never delegated — AC.2) ·
  `…/activate` · `GET /suppliers/{id}`.
- **Gate to active (SA8.2):** kyc approved ∧ credit review set ∧ MAA signed.
- **Defers:** suspension/blacklist/exit, consent revocation, real KYC.

### WS-2 · Buyer + acknowledgment user (BC9)
- **Advances:** `buyer_account.status` `nominated → identity_verified → credit_assessed →
  engagement_started → active` (`relationship_tier=acknowledged_buyer`, `acknowledgment_mode=per_invoice`).
- **Endpoints:** `POST /buyers/nominate` · `…/{id}/record-identity-verified` (← M5a) ·
  `…/record-credit-assessment` (< ₹10 Cr ⇒ no four-eyes) · `…/start-engagement` · `…/designate-ack-user`
  (OTP-only login, **no password, no MFA** — AU.1/DL-021) · `…/confirm-payment-instruction` · `…/activate`
  · `GET /buyers/{id}`.
- **Gate to active (BA.3):** credit assessment ∧ ≥1 active ack user ∧ payment instruction confirmed.
- **Defers:** blanket ack, anchor tier, payment-rule supersession.

### WS-3 · Investor active (BC7) — invite → active
- **Advances:** `inv_account.status` `signed_up → identity_verified → kyc_submitted →
  suitability_assessed → financial_profile_completed → kyc_approved → mia_signed → active`.
- **Endpoints:** `POST /investor-invites/issue` (compliance reviewer; 14-day expiry — DL-008/C20) ·
  `POST /investors/sign-up` (consumes invite) · `…/{id}/record-identity-verified` (PAN+Aadhaar ← M5a) ·
  `…/submit-kyc` → **auto-approve** → `…/record-kyc-approved` · `…/assess-suitability` (`mismatch=false`) ·
  `…/complete-financial-profile` · `…/record-mia-signed` (← M5c) · `…/activate` · `GET /investors/{id}`.
- **Gate to active (IA.3):** kyc approved ∧ suitability set ∧ bank_account_last4 set ∧ MIA signed.
- **Defers:** suitability mismatch + override-ack, NRI/institutional, penny-drop realism, KYC refresh.

### WS-4 · Invoice listed, priced, **gone live** (BC1 + BC3 + BC4) — maker-checker + MFA gate #1
- **Pre-req (BC3, minimal):** one active `risk_pricing_policy` for `(buyer_id, tenor_bucket)` plus a buyer
  credit limit and supplier exposure cap — the three values BC1 snapshots
  (`POST /pricing-bands/set`, `POST /buyers/{id}/credit-limit/set` per B4 §2.1).
- **Advances Invoice:** `deal_invoice.status` `submitted → ops_checks_in_progress → ops_checks_passed →
  listed` (`POST /invoices/submit` → `…/{id}/record-ops-checks-passed` → `…/list`).
- **Advances Listing:** `deal_listing.status` `draft → operational_checks_in_progress →
  awaiting_acknowledgment → ready_for_review → live`.
  - **Acknowledgment:** the ack user acknowledges the invoice via OTP (`POST /listings/{id}/acknowledge`).
  - **Maker — snapshot + ready (`POST /listings/{id}/snapshot-and-ready`, B4 §6.1):** at `ready_for_review`
    freeze (immutably, G20/L.3) `pricing_snapshot{pricing_band_id, rate_bps, fee_bps}`,
    `buyer_limit_headroom_snapshot`, `supplier_exposure_cap_snapshot`, and **`funding_target`** =
    `face_value − face_value·rate_bps/10000·tenor_days/365 − face_value·fee_bps/10000` (L.7/DL-024,
    integer paise — pin the rounding rule in the WS-4 plan + a DL-BE entry).
  - **Checker — go-live (`POST /listings/{id}/approve-go-live`, B4 §6 / worked example A):** carries
    `X-Mfa-Assertion-Id`; handler reads the `Listing.ReadyForReview` envelope for the maker id, requires
    **checker ∈ Treasury & Settlement, `golive_checker_id ≠ golive_maker_id`, fresh MFA** — enforced
    in-app *and* by `deal_listing_golive_maker_ne_checker` + `deal_listing_golive_assertion_chk`. Emits
    `admin_iam.MakerChecker.Approved` + `Listing.GoneLive`.
- **Advances Settlement (BC4, subscriber to `Listing.GoneLive`):** `cash_virtual_account.status = created`,
  `expected_inflow_total = funding_target`, one VA per listing (`cash_virtual_account_listing_uq`).
  Observed via `GET /listings/{id}` (`va_id` set) or `GET /virtual-accounts/{va_id}`.
- **Defers:** ops-check *failure*, acknowledgment failure, held-for-review, funding-window expiry, the
  4-eyes >₹1 Cr go-live variant.

### WS-5 · Subscription to 100% → **fully funded** (BC2 + M5b escrow inflow via webhook) — G10
- **Advances:** `sub_subscription.status` `committed → funds_pending → funds_received → confirmed`;
  `deal_listing.status` `live → fully_funded`.
- **Endpoints / flow:** `POST /listings/{id}/subscriptions/commit` (B4 §2.1) — `amount = funding_target`,
  `amount ≥ ₹10 000` (`1 000 000` paise, S.1), a **coordinated commit** that atomically bumps
  `deal_listing.committed_total`, blocked at commit by `deal_listing_committed_lte_target` so
  over-subscription is impossible (X1/L.2/S.5). The stubbed escrow then delivers the inflow **through the
  webhook ingress** — `POST /webhooks/banking/{vendor}/inflow.received` (B4 §5: HMAC verify → dedup on
  `vendor_event_id` → `banking.InflowWebhookProcessed`, correlation re-established from the VA mapping,
  G24). BC4 subscriber → `InflowObserved` (provisional) → `InflowReconciled` (only reconciled advances
  `funds_received`, V.4) → BC2 → `Subscription.FundsReceived` → `…Confirmed` (`expected_inflow_amount ==
  amount`, S.3). At `committed_total == funding_target` (strict paise, L.6) → `Listing.FullyFunded`.
- **Edge work introduced here:** the **webhook ingress** (B4 §5) — HMAC over `(timestamp‖body)` before any
  parse/read (C10), 5-min replay window, `(vendor, vendor_event_id)` dedup, `WebhookSignature.Invalid` /
  `Webhook.DuplicateDropped` envelopes. Reused by WS-6/WS-7.
- **G10 to assert:** `Σ (confirmed sub.amount) = deal_listing.committed_total =
  cash_virtual_account.observed_inflow_total = funding_target`, paise-exact (X3/V.2).
- **Defers:** multi-investor allocation, partial fills, funding shortfall + refund, pre-confirmation
  cancellation, concentration warnings, the EoD overlay correction.

### WS-6 · Assignment requested, single leg signed → **all signed** (BC5 + M5c via webhook) — gate to disbursement
- **Advances:** `legal_assignment_set.status` `requested → in_progress → all_signed`;
  `deal_listing.all_signed = true`.
- **Endpoints / flow:** on `Listing.FullyFunded` → `POST /listings/{id}/assignment-set/request`
  (`total_count = 1`; `sign_deadline = now + 24h`; `legal_assignment_set_listing_uq`) →
  `POST /assignment-sets/{id}/legs/{investor_id}/initiate` (per-investor MIA `legal_master_agreement` must
  exist) → stubbed signing delivers via `POST /webhooks/signing/{vendor}/session.completed` (B4 §5) →
  `RecordLegSigned`. At `signed_count == total_count` → `AssignmentSet.AllSigned` → flip
  `deal_listing.all_signed = true` (the C27 gate: every investor signs before disbursement).
- **Defers:** multi-leg coordination, the 24 h time-box abandonment (Trace C), per-invoice stamping
  (AS.7/G2).

### WS-7 · Disbursement drafted → approved → executed → **disbursed** (BC4 + M5b via webhook) — maker-checker + MFA gate #2
- **Advances:** `cash_payout_instruction.status` `drafted → approved → sent → executed`;
  `deal_listing.status` `fully_funded → disbursed`.
- **Endpoints / flow (B4 §6 two-endpoint pair):**
  `POST /payout-instructions/draft-disbursement` — T&S **maker**; allowed only when
  `deal_listing.status = fully_funded ∧ all_signed = true` (PI.2); `kind=disbursement`, `listing_id` set,
  `subscription_id` null (`cash_payout_instruction_kind_target_chk`). The `payout_instruction_id` PK **is**
  the bank vendor idempotency key (PI.1/C9), reused verbatim on retry →
  `POST /payout-instructions/{id}/approve` — T&S **checker ≠ maker, fresh MFA** (PI.5;
  `cash_payout_instruction_maker_ne_checker` + `…_checker_mfa_chk`) → `DisbursementInstructed` → stubbed
  escrow payout leg delivers via `POST /webhooks/banking/{vendor}/payout.executed` →
  `RecordPayoutExecuted` → `Listing.Disbursed`. Observed via `GET /payout-instructions/{id}` /
  `GET /listings/{id}`.
- **Defers:** partial/failed legs + remediation case, the EoD master-statement reconciliation overlay
  (PI.7/RL.3), the distribution leg (WS-8).

---

## 5. Cross-cutting invariants this skeleton must prove (at the wire boundary)

| Invariant | Where it bites | Asserted in |
|---|---|---|
| **Maker-checker** (proposer ≠ approver, on the same originating aggregate — B4 §6.2/X11) | go-live (WS-4) · payout approve (WS-7) — two endpoints each | reject test: checker = maker ⇒ 409 `checker_equals_maker` + `MakerChecker.Blocked` envelope (app + DB CHECK) |
| **MFA-fresh** (`X-Mfa-Assertion-Id` on the checker — B4 §6.4) | same two steps | reject test: stale/missing assertion ⇒ 401 `mfa_assertion_expired/missing`, no envelope |
| **Funding equality G10** | WS-5 | `Σ confirmed = committed_total = observed_inflow_total = funding_target`, paise-exact; over-subscribing commit ⇒ 422 invariant violation at commit (DB CHECK fires) |
| **Idempotency** (B4 §2.4/§5.2) | every command (`X-Command-Id`) · inflow/payout webhooks (`vendor_event_id`) · payout (`payout_instruction_id`) | replay each ⇒ original `emitted_events`, no double effect, no second envelope |
| **Audit chaining + audit-before-2xx** (X13/§7) | every state change | E2E test re-reads `sys_audit_event` by the run's `correlation_id`, asserts an unbroken `previous_envelope_hash` chain across all hops; WS-0 asserts no 2xx precedes the append |

---

## 6. Five non-negotiables — applicability (milestone level)

Every **state-changing** command across WS-1…WS-7 is all five, now carried on the **B4 request envelope**.
The two genuine maker-checker+MFA gates are go-live and payout-approve (two endpoints each); the onboarding
`record-*`/`activate` commands are single-actor state-machine advances that are still idempotent,
SoD/role-checked, and audited (command-boundary authz, not a propose→approve pair).

| # | Control | Applies | How / where |
|---|---|---|---|
| 1 | Maker-checker | yes | two-endpoint pair on go-live (WS-4) & payout (WS-7); command-boundary authz on the rest (B4 §6) |
| 2 | MFA-fresh | yes | `X-Mfa-Assertion-Id` header on the two checker endpoints; freshness window at the handler (B4 §6.4) |
| 3 | SoD-checked | yes | M4c policy at every command boundary (T&S vs Ops vs Credit vs Compliance roles) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → M4a `sys_command_log` claim on every command (B4 §2.4) |
| 5 | Audit-logged | yes | M2 envelope per command, appended **before 2xx** (X13/§7); the E2E chain assert is the headline |

---

## 7. Test scenarios (write these first)

- [ ] **WS-0 edge tests (MockMvc):** missing/bad header → 400 no-envelope; bearer missing/expired → 401
      no-envelope; stale MFA → 401; happy command → 2xx with `emitted_events` **and** envelope present;
      idempotent replay (same `X-Command-Id`) → original `emitted_events`, no second envelope; divergent
      body → 409 `idempotency_conflict`; stale `X-Aggregate-Version` → 409 `version_conflict`.
- [ ] **Capstone — `WalkingSkeletonE2ETest` (HTTP):** drive WS-1→WS-7 through controllers under one
      `correlation_id`; assert each hop's state, the response envelopes, **and** the unbroken audit chain
      ending at `deal_listing.status='disbursed'`.
- [ ] **WS-4 go-live maker-checker:** `golive_checker_id == golive_maker_id` ⇒ 409 + `MakerChecker.Blocked`.
- [ ] **WS-4 go-live MFA:** missing/stale `X-Mfa-Assertion-Id` ⇒ 401, no state change.
- [ ] **WS-4 pricing snapshot immutability:** a later BC3 pricing change does **not** alter the listing's
      frozen `funding_target` / `pricing_snapshot`.
- [ ] **WS-5 webhook ingress:** bad HMAC → 401 + `WebhookSignature.Invalid`; duplicate `vendor_event_id` →
      200 + `Webhook.DuplicateDropped`, no double inflow.
- [ ] **WS-5 G10 equality:** after confirm, the four-way paise equality holds; an over-subscribing commit ⇒
      422 at commit (DB CHECK fires).
- [ ] **WS-6 gate:** disbursement draft while `all_signed=false` ⇒ rejected (PI.2).
- [ ] **WS-7 payout maker-checker + MFA:** approver = maker, or stale MFA ⇒ rejected.

---

## 8. Explicitly deferred to Milestone 2 (widen each module to full rigor)

All reject/alternate paths: ops-check failure, acknowledgment failure, held-for-review, funding shortfall
+ refund, pre-confirmation cancellation, the 24 h assignment time-box abandonment, partial/failed payout
legs + remediation, the EoD master-statement reconciliation overlay, four-eyes (>₹1 Cr / >₹10 Cr),
suitability mismatch + override-ack, multi-investor allocation & concentration, per-invoice stamping, real
vendor sandboxes, the maturity → distribution → closed tail (**WS-8**, same maker-checker shape as WS-7
with `kind=distribution` + TDS snapshot, G4), the real BC11 compliance engine (replaces the WS auto-approve
stub), supplier/buyer/investor suspension/exit lifecycles. **Edge-layer deferrals:** projections &
maker-checker queue (B4 §3/§6.5), cursor pagination, sensitive-read envelopes (B4 §3.6), AI-agent auth +
MFA-equivalent (G31), `*Outage.Declared` threshold banners (B4 §5.5), rate limiting.

---

## 9. Definition of Done (milestone)

- [ ] WS-0…WS-7 each: §7 per-slice tests green; `/code-review` on the diff; findings fixed; a `DL-BE-xxx`
      entry per non-obvious decision (the edge plumbing & audit-before-2xx realization, funding-target
      rounding, the coordinated-commit mechanism, the go-live/payout two-endpoint wiring, the webhook
      ingress HMAC/dedup, the compliance auto-approve stub boundary).
- [ ] **Capstone `WalkingSkeletonE2ETest` green** — `listed → disbursed` **over HTTP**, audit chain
      unbroken.
- [ ] Whole suite green (no regression to the foundation's 133).
- [ ] `DL-BE-029` finalized as-built (the skeleton strategy + the HTTP-edge + the cross-cutting proofs).
- [ ] This spec flipped to **Status: Done**. Milestone 1 complete → Milestone 2 (widen Wave 1).

## 10. Watch-for (carry forward)

- **Audit-before-2xx realization:** M2 appends in the caller's transaction, which satisfies X13/§7 in the
  monolith *without* a separate outbox table — but WS-0 must assert the contract at the HTTP boundary (no
  2xx before the append) so it survives the Phase-2 broker swap (G27). Pin this in the WS-0 DL-BE entry.
- **`funding_target` rounding** is real money — pick the rule (truncate vs round-half-even on the discount
  and fee terms) deliberately and pin it; a paise drift breaks G10's strict equality downstream.
- **Coordinated commit (X1/G27):** the `Subscription.Committed` + `Listing.committed_total` bump is one
  local DB transaction — keep it atomic; over-subscription must be impossible by construction, not
  check-then-act.
- **Generic domain maker-checker:** M4d's engine is admin-IAM-shaped; WS-4/WS-7 are the first *domain*
  two-endpoint pairs. If clean reuse needs an extraction, do it (decision-logged) before WS-4 — mirror the
  `AbstractAclService` move.
- **`correlation_id` threading:** minted at the edge for a fresh transaction, propagated via
  `X-Correlation-Id` for chained commands (B4 §2.2); the E2E audit-chain assert only holds if every command
  on the run carries the same id. Establish the convention in WS-0 and hold it throughout.
- **Webhook correlation re-establishment (G24):** the stub vendors never echo the platform `correlation_id`;
  the ingress re-links via the stored `client_instruction_id` / `va_id` / `signature_request_id` mapping —
  get this mapping right in WS-5 or the inflow chain won't join the listing's chain.
- **DB-enum-true state names:** `operational_checks_in_progress`, `awaiting_acknowledgment`; the
  disbursement gate is the `deal_listing.all_signed` boolean, **not** a separate state.
- **Compliance stub boundary:** the auto-approve sits behind the *same* `record-kyc-approved` command the
  real BC11 engine will call at M15 — a one-place swap, never scattered conditionals.
```
