# Phase-1 Roadmap — Backend → Pilot

> **Status date:** 2026-07-12 *(refreshed; supersedes the 2026-06-27 draft)* · **For founder review.**
> Sequencing roadmap from "backend core complete" to "pilot-ready". The authoritative *module* plan stays
> `docs/spec/Spec_Driven_Build_Plan.md`; this is the forward sequence + the open-risk register. One-line rule:
> **UI and integrations build *against* the API contract, backend remainder is *additive* — so nothing
> downstream conflicts.**
>
> **What changed since 2026-06-27:** the money-path + document + onboarding-doc items shipped — **M16 Tax**
> (spine now reaches `distributed/closed` incl. TDS + Form 16A), **M18 Documents**, **M19 Invoice Artifacts**,
> and **M20 Onboarding Docs (buyer KYB + investor/supplier KYC), now DONE**. Tests **279 → 359**. Remaining
> Phase-D: **M15 Compliance**, **M14 Collections**, **M17 Auditor**, and the deferred-control sweep. See §5.

---

## 1. Where we are

**The full money lifecycle works end-to-end.** A deal goes `listed → priced → live → funded → confirmed →
assigned → disbursed → matured → distributed → closed` over HTTP (incl. TDS withholding + Form 16A issuance),
proven by an automated smoke run; every state change carries the five controls (maker-checker, MFA,
segregation-of-duties, idempotency, immutable audit).

| | |
|---|---|
| **Backend core** | **Complete** — Wave-1 (M6–M13) at full rigor **+ M16 Tax** (distribution/close) |
| **Document surface** | **M18** generic doc service, **M19** invoice PDF, **M20** onboarding docs (buyer KYB + investor/supplier KYC) — all shipped |
| **Tests** | **359 green** (unit + integration on real Postgres) |
| **Bounded contexts live** | Listing, Subscription, Credit, Settlement, Signing, Investor, Supplier, Buyer, Tax, Documents, Compliance (KYC docs); Verification/Escrow/Notifications (stubbed) |
| **Schema** | Flyway-owned, **13 migrations** (V1–V13), DB-enforced invariants |
| **Integrations** | **All stubbed** behind ACL ports (verification, escrow/banking, e-sign, notifications) |
| **Run locally** | `docs/MANUAL_TESTING.md` + `manual-test.http` + `scripts/dev-smoke.sh` (dev profile). API map: `docs/API_CATALOGUE.md` |

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
| **Distribution & Tax** | Distribution/Form16a/TaxQuery (7) | draft→approve payout, TDS snapshot, close→`distributed`; issue + download Form 16A; deduction/statement reads |
| **Documents** | `/documents` (5) | initiate → PUT content → finalize → resolve → download (two-phase upload) |
| **Invoice artifacts** | `/listings/{id}/invoice-documents` (4) | upload PDF → attach → `document_completeness` (recorder ≠ uploader) → download on live listing |
| **Buyer KYB** | `/buyers/{id}/kyb-verification` (2) | ops attests `kyb_verified` (+ optional doc); independent of automated identity check |

**Exit criteria for A:** every flow above behaves as intended; any gap is logged (bug vs accepted-deferral)
and triaged. **This freezes the (now-larger) API contract for Phase B.**

> **Fold into this pass — one known control gap to close (not defer):** M19 invoice **download is not yet
> audit-logged** (DL-BE-071; control #5 requires every download to emit an envelope). Add the
> `InvoiceArtifact.Downloaded` envelope + test before the contract is signed off. Small, but a pilot-blocker
> for the compliance trail.

---

## 4. Phases B & C — summary (start after A sign-off)

**B · UI plugin** — the frontend is the contract (`../fintech-patform-ui`, 15 screens). Wire **one module at
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

**Shipped since the 2026-06-27 draft** (were "not yet built"; now done to DoD):

| Module | BC | What landed |
|---|---|---|
| ✅ **M16 Tax** | BC12 | TDS computation + distribution/close + Form 16A — spine now reaches `distributed/closed`. *(closes the "distribution + close (TDS)" deferred control)* |
| ✅ **M18 Documents** | BC16 | generic two-phase upload API (`/documents`); local DB store. *GCS adapter (M18c) + Form-16A convergence (M18d) still deferred.* |
| ✅ **M19 Invoice Artifacts** | BC1 | invoice PDF upload/attach/download + `document_completeness` wiring. *One gap: download-audit (see §3 fold-in).* |
| ✅ **M20 Onboarding Docs** | BC9+BC11 | buyer KYB (`V12`) + investor/supplier KYC docs (`V13`). **Nothing mandatory — Ops decides completeness**; coverage is advisory. Capture-only. |

**Still to build** (additive; build to DoD, one at a time):

| Module | BC | Why it matters | Blocks / notes |
|---|---|---|---|
| **M15 Compliance (full)** | BC11 | replace the auto-approve KYC stub with the real review engine | **production KYC gate** — M20 is capture-only + advisory; real completeness/adjudication enforcement (and any *mandatory* docs) lives here |
| **M14 Collections** | BC6 | overdue → default classification (incl. the M6-deferred DefaultCase) | the non-happy maturity path |
| **M17 Auditor** | BC13 | read-only audit/regulator access | audit/regulator demo |
| **M18c GCS · M18d Form-16A convergence** | BC16 | blob storage in prod; retire `sys_document_object` | M18c → Production gate; M18d an isolated cleanup |

> **Sequencing note (DL-BE-070/072/073):** the document surface (M18/M19/M20) was pulled forward ahead of
> M14/M15/M17 as a **Phase-B (UI) enabler** — now all shipped. M20 is **capture-only** (non-gating) and
> **nothing is mandatory** (Ops decides), so it does not disturb the current auto-approve onboarding flow;
> real KYC-completeness enforcement (and any mandatory-doc rule) stays with **M15**.

**Deferred controls register** — *flagged during the build; each is a known gap with a trigger* (founder
should note the compliance ones):

| Control | Where deferred | Risk | Trigger to build |
|---|---|---|---|
| ✅ ~~**Distribution + close (TDS)**~~ | ~~M13~~ | **DONE** — built as **M16** | — |
| **Four-eyes / second-approver** (>₹10 Cr) | M6 (DL-BE-059) | **Compliance** — large credit limits/caps can't be set, or would bypass dual-approval | before any >₹10 Cr counterparty |
| **Suspend / Blacklist** | M7, M8, M10 | can't off-board a bad actor mid-life | before live counterparties at scale |
| **M19 download audit envelope** | M19 (DL-BE-071) | **Compliance** — invoice-PDF access has no audit trail | **before pilot** (fold into Phase A — see §3) |
| **Agency-consent enforcement** | M7 (DL-BE-055) | agency actions not yet gated on active consent | when agency/portal actors exist |
| **Pricing-band re-pricing** (supersession) | M6 (DL-BE-060) | a band can be set once, not changed | when re-pricing UX is needed (needs a migration) |
| **Documents: encryption at rest** | M18 (DL-BE-074) | real KYC/Aadhaar PII stored plaintext | **before real KYC docs / Production gate** (Cloud KMS) |
| **Session token hardening** | auth (DL-BE-077) | UUIDv7 token is part-predictable; not hashed at rest | before real PII / Production gate |
| **Schedulers** (window-expiry, KYC-refresh, review cadence) | M9/M10/M6 | time-based steps are manual/ops-triggered | scheduler-era (post-pilot ok) |
| **Async event bus** | platform-wide | cross-context coordination is inline, in-tx | scale-era (post-pilot ok) |

---

## 6. Recommended sequence (the one-slide version)

```
NOW ──► A. Validate every endpoint/flow (dev harness) — now incl. docs/artifacts/KYB/tax   ◄── you are here
          │  + close the M19 download-audit gap in the same pass
          │  exit: contract frozen, gaps triaged
          ▼
        B. UI plugin, module-by-module (against frozen API)
          │   ║ in parallel, non-blocking:
          │   ╚═► D. M15 Compliance · M14 Collections · M17 Auditor + deferred-control sweep
          ▼
        C. Integrations: stub → sandbox → production (behind ACL ports)
          │  order: notifications → verification → e-sign → escrow/banking
          │  + Production-gate prereqs: doc encryption-at-rest, token hardening, M18c GCS
          ▼
        PRODUCTION GATE ──► Pilot (real counterparties, real money)
```

**The critical next step:** finish **Phase A** on the *extended* surface (documents, invoice artifacts,
onboarding KYC/KYB, distribution/tax) and **close the M19 download-audit gap** in the same pass — everything
in B/C consumes that frozen, gap-free contract.

**One call for the founder** *(the M20-KYC checklist question is resolved — nothing is mandatory, Ops decides
completeness, so no per-persona required-docs list is needed until M15):*
- **Deferred-control cut line** — which §5 controls are pilot-blockers vs fast-follows. *Recommendation:*
  four-eyes + suspend/blacklist + the M19 download-audit envelope before real counterparties; doc
  encryption-at-rest + token hardening at the Production gate; schedulers + event-bus post-pilot.
