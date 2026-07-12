# Phase-1 Roadmap — Backend → Pilot

> **Status date:** 2026-06-27 · **For founder review.** Sequencing roadmap from "backend core complete" to
> "pilot-ready". The authoritative *module* plan stays `docs/spec/Spec_Driven_Build_Plan.md`; this is the
> forward sequence + the open-risk register. One-line rule: **the API contract is now frozen — UI and
> integrations build *against* it, backend remainder is *additive*, so nothing downstream conflicts.**

---

## 1. Where we are

**The money spine works end-to-end.** A deal goes `listed → priced → live → funded → confirmed → assigned →
disbursed → matured` over HTTP, proven by an automated smoke run; every state change carries the five controls
(maker-checker, MFA, segregation-of-duties, idempotency, immutable audit).

| | |
|---|---|
| **Backend core** | **Complete** — all Wave-1 modules (M6–M13) at full rigor |
| **Tests** | **279 green** (unit + integration on real Postgres) |
| **Bounded contexts live** | Listing, Subscription, Credit, Settlement, Signing, Investor, Supplier, Buyer, Verification/Escrow/Notifications (stubbed) |
| **Schema** | Flyway-owned, 6 migrations, DB-enforced invariants |
| **Integrations** | **All stubbed** behind ACL ports (verification, escrow/banking, e-sign, notifications) |
| **Run locally** | `docs/MANUAL_TESTING.md` + `manual-test.http` + `scripts/dev-smoke.sh` (dev profile) |

---

## 2. The four phases (in order; non-overlapping ownership)

| Phase | What | Depends on | Conflict risk |
|---|---|---|---|
| **A — Validate** *(now)* | Manually exercise every endpoint + every flow; confirm behaviour vs intent | nothing | none |
| **B — UI plugin** | Wire the 15 frontend screens to the live APIs, module by module | A signed-off | none — reads the frozen contract |
| **C — Integrations** | Swap each ACL stub → real sandbox → production credentials | B per-module | none — swap is behind the port, API unchanged |
| **D — Backend remainder** | Remaining modules + deferred controls (§5) | runs in parallel; gated only where noted | additive endpoints only |

**Why they don't conflict:** B consumes A's frozen request/response contract; C changes only what's *behind*
the ACL ports (no endpoint signatures move); D *adds* endpoints, never alters the ones B/C depend on. The one
hard ordering: **a few flows need Phase-D modules before they fully work end-to-end** (see §5 "blocks").

---

## 3. Phase A — Validation checklist (do this now)

Run each controller's flow via `manual-test.http` / `dev-smoke.sh`. Tick when behaviour matches intent.

| Area | Endpoints | Flow to confirm |
|---|---|---|
| **Auth** | `/auth/login` (2) | password → OTP → bearer |
| **Admin users** | `/admin-users` (3) | provision admin, assign roles |
| **Supplier** | `/suppliers` (12) | create → verify identity → KYC submit/approve **+ reject→resubmit** → credit review → MAA → active |
| **Buyer** | `/buyers` (8) | nominate → verify identity → credit-assess → ack user → payment → active |
| **Investor** | InvestorController (13) | onboard → verify PAN/penny-drop → suitability **+ override** → KYC **+ reject** → active |
| **Credit** | `/credit` (3) | set pricing band; set buyer/supplier profile; **>₹10 Cr → rejected** |
| **Listing** | `/listings` (10) | create → ops-checks (+reject) → buyer-ack → price → go-live (+held-for-review) |
| **Subscription** | SubscriptionController (4) | subscribe → fully-funded; **cancel/release**; **shortfall→refund** |
| **Assignment** | AssignmentController (6) | multi-leg sign → all-signed (C27 gate); incomplete; leg-fail→retry |
| **Disbursement** | DisbursementController (3) | maker→checker → disbursed |
| **Maturity** | MaturityController (1) | record full repayment → matured |
| **Banking webhook** | BankingWebhookController (1) | HMAC-signed inflow → funds confirmed |

**Exit criteria for A:** every flow above behaves as intended; any gap is logged (bug vs accepted-deferral)
and triaged. **This freezes the API contract for Phase B.**

---

## 4. Phases B & C — summary (start after A sign-off)

**B · UI plugin** — the frontend is the contract (`../fintech-patform-mock`, 15 screens). Wire **one module at
a time**, screen → its backend endpoints, reusing the dev-profile login. Deliverable per module: the screen
drives the real API (no mock data). Order mirrors the deal lifecycle: onboarding screens → listing → funding →
assignment → settlement → admin/ops consoles.

**C · Integrations** — replace each in-process stub with the real adapter, **behind the existing ACL port**
(no API change). Order = by external dependency readiness:
1. **Notifications** (BC15) — email/SMS provider.
2. **Verification** (BC17) — PAN/GSTIN/MCA21/penny-drop/IRN vendor.
3. **E-sign** (BC5) — signing provider.
4. **Escrow / Banking** (BC18) — the real escrow + payout + inflow webhook. *(highest-stakes — real money)*

Each integration ships **sandbox-validated first**, then production credentials only at the **Production
gate**.

---

## 5. Phase D — Backend remainder + deferred controls

**Modules not yet built** (additive; build to DoD, one at a time):

| Module | BC | Why it matters | Blocks |
|---|---|---|---|
| **M16 Tax** | BC12 | TDS computation | **Unblocks M13 distribution + close** — *investors cannot be paid their returns until this lands* |
| **M14 Collections** | BC6 | overdue → default classification (incl. the M6-deferred DefaultCase) | the non-happy maturity path |
| **M15 Compliance (full)** | BC11 | replace the auto-approve KYC stub with the real review engine | production KYC |
| **M17 Auditor** | BC13 | read-only audit/regulator access | audit/regulator demo |
| **M18 Documents** | BC16 | generic doc service: two-phase upload API + backend DB↔GCS (`document_id`) | **enables M19 + M20; unblocks any real file upload/download** |
| **M19 Invoice Artifacts** | BC1 | invoice **PDF** upload (Ops) + **investor download**; wires `document_completeness` | **UI-blocking** — supplier upload + investor due-diligence screens need it (Phase B). Needs M18. |
| **M20 Onboarding Docs** | BC11+BC9 | typed KYC (investor/supplier) + KYB (buyer) documents; shared checklist; capture-only | **UI-blocking** — onboarding screens' document upload need it (Phase B). Needs M18. |

> **Sequencing note (DL-BE-070/072/073):** M18 → {M19, M20} is arguably **not** pure "remainder" — the
> document upload/download surface is a Phase-B (UI) enabler for the onboarding, supplier, and investor
> screens, so it likely wants to land alongside those screens rather than waiting behind M14/M15/M17.
> M20 is **capture-only** (non-gating) so it does not disturb the current auto-approve onboarding flow;
> real KYC-completeness enforcement stays with **M15**. Founder call on where M18–M20 slot.

**Deferred controls register** — *flagged during the build; each is a known gap with a trigger* (founder
should note the compliance ones):

| Control | Where deferred | Risk | Trigger to build |
|---|---|---|---|
| **Four-eyes / second-approver** (>₹10 Cr) | M6 (DL-BE-059) | **Compliance** — large credit limits/caps can't be set, or would bypass dual-approval | before any >₹10 Cr counterparty |
| **Suspend / Blacklist** | M7, M8, M10 | can't off-board a bad actor mid-life | before live counterparties at scale |
| **Agency-consent enforcement** | M7 (DL-BE-055) | agency actions not yet gated on active consent | when agency/portal actors exist |
| **Pricing-band re-pricing** (supersession) | M6 (DL-BE-060) | a band can be set once, not changed | when re-pricing UX is needed (needs a V7 migration) |
| **Distribution + close (TDS)** | M13 (DL-BE-054) | deal can't pay investor returns / close | = build M16 |
| **Schedulers** (window-expiry, KYC-refresh, review cadence) | M9/M10/M6 | time-based steps are manual/ops-triggered | scheduler-era (post-pilot ok) |
| **Async event bus** | platform-wide | cross-context coordination is inline, in-tx | scale-era (post-pilot ok) |

---

## 6. Recommended sequence (the one-slide version)

```
NOW ──► A. Validate every endpoint/flow (dev harness)         ◄── you are here
          │  exit: contract frozen, gaps triaged
          ▼
        B. UI plugin, module-by-module (against frozen API)
          │   ║ in parallel, non-blocking:
          │   ╚═► D. M16 Tax → unblocks distribution/close      (do early — it's on the money path)
          │       D. M14/M15/M17 + deferred-control sweep
          ▼
        C. Integrations: stub → sandbox → production (behind ACL ports)
          │  order: notifications → verification → e-sign → escrow/banking
          ▼
        PRODUCTION GATE ──► Pilot (real counterparties, real money)
```

**Two calls for the founder:**
1. **M16 Tax priority** — recommend building it early in Phase D; without it a funded deal can't pay returns
   or close (the spine ends at `matured`, not `distributed`).
2. **Deferred-control cut line** — which §5 controls are pilot-blockers vs fast-follows. *Recommendation:*
   four-eyes + suspend/blacklist before real counterparties; schedulers + event-bus as post-pilot.
