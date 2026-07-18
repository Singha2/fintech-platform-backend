# PROJECT TRACKER — Backend + UI (single source of truth)

> **This file is the one place to read "where are we and what's next" across *both* repos.**
> Backend: `fintech-platform-backend` · UI: `../fintech-patform-mock`.
> Every other plan doc is **subordinate detail** feeding this tracker (see §1). When a status changes,
> update **here first**, then the detail doc. Do **not** start a new top-level plan — that is what caused
> the drift this file exists to end.
>
> **Last updated:** 2026-07-18 · **Maintained by:** solo dev (Amit).

---

## 0. Current state — the one-glance summary

Three things are each mature on their own; the **bridge between them is not built yet**.

| Half | State | Evidence |
|---|---|---|
| **Backend core** | ✅ Complete — full money lifecycle `listed → … → distributed → closed` over HTTP, five controls enforced | 413 tests green (real Postgres) |
| **Backend read surface (for the UI)** | ✅ **BE-1…BE-12 + BE-14 + BE-17 shipped** (admin reads + dashboard + investor read-only portal). BE-13/15/16 deferred by design | `docs/API_CATALOGUE.md`, `DL-BE-079…084` |
| **UI (mock)** | ✅ All **15 screens** built and working **offline** on a mock store | `../fintech-patform-mock/src/store/PlatformStore.jsx` |
| **🟡 Live wiring (the bridge)** | ⚠️ **Auth + read-side wired** — `src/api/` client + envelope + `ApiError` + full service layer + `AuthContext` + Vite proxy; **S1 login live**; **reads live for S2–S8, S11, S12, S14** (fetch-into-store on mount; unified-listing→split-invoice/listing mapper). Remaining: **write commands** (still mock), S7 (composed read), S13 (needs investor login). | mock `docs/UI_WORKORDER.md` |

**So the critical path is: wire the UI to the now-complete backend read surface, screen by screen.**
The backend needs *nothing new* for screens S1–S8, S12, S14 — those endpoints already exist and are tested.

---

## 1. How the docs fit together (drift-killer)

**Rule of thumb:** *status* lives here; *detail* lives in exactly one doc below; *nothing* has two homes.

| Doc | Repo | Role now | Authority |
|---|---|---|---|
| **`PROJECT_TRACKER.md`** (this) | backend | **Index + status + next actions** | ⭐ top |
| `API_CATALOGUE.md` | backend | Endpoint reference (every route + shape) | ✅ live reference |
| `UI_INTEGRATION_BACKEND_SPEC.md` | backend | BE-1…BE-16 read-surface design detail | ✅ done (reference) |
| `ROADMAP.md` | backend | Backend **remainder** (non-UI: M14/M15/M17) | ✅ live reference |
| `spec/Spec_Driven_Build_Plan.md` | backend | Module bible (build order, gates) | ✅ authoritative for modules |
| `DECISION_LOG.md` (`DL-BE-*`) | backend | Why-we-did-it record | ✅ append-only |
| **`INTEGRATION_PLAN.md`** | mock | **UI wiring — the executable step-by-step** | ⭐ active execution doc |
| `API_ALIGNMENT.md` | mock | Per-screen ↔ endpoint shapes + enum corrections | ✅ reference |
| **`BACKEND_UI_READINESS.md`** | mock | **Backend→UI hand-off** — what the live API actually ships + corrections to stale plan assumptions | ⭐ read before wiring |
| `HARMONIZATION.md`, `Mock_Build_Plan.md`, `TIER2_SHARED_STORE_PLAN.md`, `STEP*_BLUEPRINT.md` | mock | **Historical** — how the mock was built | 🗄️ archive (don't extend) |

Two docs are "plans you execute from": **INTEGRATION_PLAN** (UI side) and **Spec_Driven_Build_Plan** (backend
modules). Everything else is reference or history. This tracker links them; it does not replace them.

---

## 2. Master board — 15 screens × (backend · UI mock · live wiring)

Legend: ✅ done · ⚠️ partial · ❌ not done · ⛔ blocked (deferred milestone).

| Screen | Needs (backend) | Backend | UI mock | **Wired live** | Next action | Blocked by |
|---|---|:---:|:---:|:---:|---|---|
| **S1** Login + MFA | `auth/login/*`, `GET /auth/session` (BE-1) | ✅ | ✅ | ✅ | **Done** — bridge Phase 0–2 (proxy + `src/api` + `AuthContext`); `/auth/session` wiring lands in Phase 3 | — |
| **S2** Admin dashboard | `/admin/work-queues`, `/admin/stats` (BE-12) | ✅ | ✅ | ⚠️ | **Stats + queue reads live** (`useHydrate('dashboard')`); queues are counts-only per BE-12 (mode-aware UI) | — |
| **S3** Supplier onboarding | `GET /suppliers` (BE-4), `…/kyc-file` (BE-2), supplier cmds | ✅ | ✅ | ✅ | **Read + full onboarding chain live** — create → identity → submit-kyc → kyc-approve (COMPLIANCE) → financial + credit-review (CREDIT) → maa → activate. Status-driven wizard; version threaded; SoD roles enforced; no fallback | — |
| **S4** Buyer mgmt + credit | `GET /buyers`, `…/pricing-bands` (BE-5), buyer/credit cmds | ✅ | ✅ | ✅ | **Read + full onboarding chain live** — nominate → identity-verified → credit-assessment → engagement → (ack-user + confirm-PI + activate). Version threaded per step; SoD roles enforced; no fallback. *(pricing-bands read still mock)* | — |
| **S5** Invoice checks + listing | `GET /listings`, `…/ops-checks` (BE-6), listing cmds | ✅ | ✅ | ⚠️ | **Reads + all listing writes wired** — record-ops-check (+buyer-ack), promote (`complete-ops-checks`→ack→`snapshot-and-ready`), go-live (`approve-go-live`). record-ops-check verified persists; **go-live E2E blocked by DOC.3** (needs 2 ops accounts) → dev seed helper | — |
| **S6** Disbursement queue | `/disbursements`, `…/disbursement/detail` (BE-7) | ✅ | ✅ | ⚠️ | **Queue read + approve write wired** (mapped queue shape; `POST …/disbursement/approve`, checker≠maker). E2E not yet verified — needs a **disbursable (fully_funded ∧ all_signed) listing**; suggest a `/dev` helper to seed one | — |
| **S7** Distribution + recon | `…/distribution/investors`, `/reconciliation` (BE-8) | ✅ | ✅ | ⚠️ | **Maturity + distribution writes wired** (`record-maturity`; `distribution/draft`→`approve`, checker≠maker). E2E needs a disbursed listing (dev helper); no distributions-list read → projection; recon local | — |
| **S8** Investor invites | `GET /investor-invites` (BE-9) + issue cmd | ✅ | ✅ | ⚠️ | **List read + Issue write live** (POST /investor-invites/issue → refresh; persists); list omits email/phone PII; revoke has no endpoint (mock) | — |
| **S9** Audit log | `GET /audit/events` (BE-13) | ⛔ | ✅ | ❌ | Await **M17 Auditor** | BE-13 / M17 |
| **S10** Investor onboarding | investor cmds + `…/kyc-file` (BE-2) | ✅ | ✅ | ❌ | Wire cmds | — |
| **S11** Listing marketplace | investor-scoped `GET /listings?status=live` (BE-14) | ✅ | ✅ | ⚠️ | **Marketplace read live** (`useHydrate('marketplace')`, empty until live listings exist); subscribe still mock | BE-14 / BE-17 |
| **S12** Listing detail | `GET /listings/{id}/detail` (BE-10, admin) | ✅ | ✅ | ⚠️ | **Detail read + subscribe write wired** (`useHydrate(['listingDetail',id])`; `POST …/subscriptions/commit`, ops-on-behalf). E2E needs a live listing + a real investor_id (dev helper / investor-login) | — |
| **S13** Investor portfolio | `…/subscriptions` + summary (BE-14) | ✅ | ✅ | ❌ | Backend ready (**M10-D**); own-scoped `{rows,summary}`. Wire UI | BE-14 / BE-17 |
| **S14** Supplier tracker (admin) | `GET /suppliers/{id}/listings` (BE-11) | ✅ | ✅ | ⚠️ | **Tracker read live** (`useHydrate(['supplierListings',id])`; buyer_name blank — not in BE-11); submit still mock | — |
| **S15** Buyer portal | ack-user OTP login + buyer reads + self-ack (BE-15) | ⛔ | ✅ | ❌ | Await **WS-2** (ack-user login) | BE-15 / WS-2 |

**Wire-able now (backend ready): S1–S8, S10, S11, S12, S13, S14.** Blocked on a milestone: S9, S15.
_(S11/S13 unblocked by **M10-D**; investor read-only login uses the dev password today — real investor login is Phase B/BE-18.)_

---

## 3. Backend read surface — BE-1…BE-17 at a glance

| | Endpoints | Status |
|---|---|:---:|
| BE-1…BE-12 | session, kyc-file, suppliers, buyers+bands, listings+ops-checks, disbursement, distribution+recon, invites, listing-detail, supplier-tracker, dashboard | ✅ shipped (413 tests) |
| BE-13 | `GET /audit/events` | ⛔ build in **M17 Auditor** |
| BE-14 | investor marketplace + portfolio (ownership-scoped) | ✅ shipped (**M10-D**, DL-BE-084) |
| BE-15 | buyer ack-user login + buyer reads + self-ack | ⛔ build in **WS-2** |
| BE-16 | CORS | prod-only (dev uses Vite proxy) |
| BE-17 | investor read-only self-login (`/auth/session` +`investor_id`, ownership scoping, KYC download gate); dev password only — real login is Phase B/BE-18 | ✅ shipped (**M10-D**, DL-BE-084) |

Detail: `UI_INTEGRATION_BACKEND_SPEC.md`. All are **additive** — no frozen contract changes.

---

## 4. Backend remainder (non-UI, runs in parallel — does not block wiring)

From `ROADMAP.md` §5. These add capability but the UI wiring above does not wait on them.

| Module | What | Status |
|---|---|:---:|
| **M15** Compliance | sanctions/PEP, reg reporting hooks | ⬜ todo |
| **M14** Collections | delinquency, recovery ladder | ⬜ todo |
| **M17** Auditor | read-only regulator access → **unblocks BE-13 / S9** | ⬜ todo |
| Deferred-control sweep | ≥₹10 Cr senior sign-off, suspension, reminders, sensitive-read gating | ⬜ todo |

---

## 5. Next actions — the single ordered to-do

> One list. Do top-down. Backend read surface is done, so the **critical path is UI wiring**.

**Track A — UI live wiring (critical path)** · execute via mock `INTEGRATION_PLAN.md`; the **current scoped slice** is the mock's `docs/UI_WORKORDER.md` (built by the mock-side session)
1. **Bridge foundation** — build `src/api/` client (bearer + `X-Command-Id`/`X-Aggregate-Version` envelope), flip `DATA_MODE` live/mock switch, wire **S1 login + OTP + `/auth/session`**. Keep offline mock path working.
2. **Read-only admin screens** (fast wins, no writes): **S2 → S3 → S4 → S5 → S6 → S7 → S8 → S12 → S14**.
3. **Command flows** (writes with the envelope): supplier/buyer/listing/investor onboarding cmds on S3/S4/S5/S10.
4. **Investor read-only portal** — **S11 + S13 now backend-ready (M10-D/BE-14/BE-17)**; wire against a real investor bearer (dev password today, Phase B for real login). Details in the mock's `BACKEND_UI_READINESS.md`.
5. **Deferred screens** — S9 (after M17), S15 (after WS-2).

**Track B — backend remainder (parallel, non-blocking)**
6. M17 Auditor (also unblocks S9) → then M15, M14, deferred-control sweep.
7. **BE-18 · Phase B investor login + self-commit** — passwordless invite→email+OTP investor login **+** investor self-commit (the `CommandGateway` non-admin-actor authz change). Unblocks **real** investor login (M10-D shipped dev-password-only) and investor-initiated payments. Slot after M10-D; spec as a new M11-x slice. Rationale + shape in `DL-BE-084` (§Phase B).

**Track C — go-live prep (later)**
8. BE-16 CORS decision, real integration credentials (verification/escrow/e-sign/KYC swap from stubs).

### Deferred fixes — known gaps, fix eventually (don't lose these)
| # | Item | Where it lives | Trigger to fix |
|---|---|---|---|
| **DF-1** | **Real investor login + self-commit** (passwordless). M10-D ships **dev-password login only**; real investors can't self-login/pay yet. | = **BE-18** above (Track B #7) · `DL-BE-084`, `M10-D §DoR-1` | Before any pilot with real investors logging in / paying. |
| **DF-2** | **KYC download gate over-permissive for suspended/exited investors** — `InvestorService.isKycApprovedForDownload`'s `… OR kyc_approved_at IS NOT NULL` lets a once-approved but later `suspended`/`exited` investor still download. Latent (Suspend/Exit not built) but a real over-permission. | `InvestorService.isKycApprovedForDownload`, `InvoiceDocumentService.download` · `DL-BE-084` (§findings) | **With the Suspend/Exit module** (M10 §9 post-active lifecycle) — decide there whether a de-activated investor retains document access, then tighten the clause. |

---

## 6. Update protocol (keep it alive, solo-friendly)

- **When you finish wiring a screen:** flip its **Wired live** cell in §2 to ✅ (or ⚠️), update §5 if the next action changed, bump the "Last updated" date. One-line commit.
- **When you ship a backend item:** update §3/§4 + add a `DL-BE-*` entry; reflect the ✅ here.
- **One status, one home.** If you catch yourself writing a status into another doc, delete it and link here.
- **No new top-level plan.** New detail goes into `INTEGRATION_PLAN.md` (UI) or `Spec_Driven_Build_Plan.md` (backend modules) as a step — never a new competing plan.
- The mock repo carries a 1-line pointer (`../fintech-patform-mock/docs/PROJECT_TRACKER.md`) back to this file so both repos resolve to the same source of truth.
